package ru.servermove.app.core

import com.jcraft.jsch.Session
import kotlinx.coroutines.CancellationException
import ru.servermove.app.model.DatabaseEngine
import ru.servermove.app.model.MigrationPhase
import ru.servermove.app.model.MigrationRequest
import ru.servermove.app.model.ServerEndpoint

class MigrationEngine(
    private val ssh: SshClient = SshClient(),
    private val bridge: StreamBridge = StreamBridge(),
) {
    private val supabase = SupabaseMigration(ssh, bridge)

    suspend fun run(
        request: MigrationRequest,
        onPhase: (MigrationPhase) -> Unit,
        onLog: (String) -> Unit,
        onProgress: (Long, Long?) -> Unit,
    ) {
        validateRequest(request)
        var source: Session? = null
        var target: Session? = null
        try {
            onPhase(MigrationPhase.CONNECTING)
            onLog("Подключаюсь к серверу-источнику…")
            source = ssh.connect(request.source)
            onLog("Источник: SSH-хост подтверждён по SHA-256 fingerprint.")

            onLog("Подключаюсь к серверу назначения…")
            target = ssh.connect(request.target)
            onLog("Назначение: SSH-хост подтверждён по SHA-256 fingerprint.")

            onPhase(MigrationPhase.PREFLIGHT)
            val expectedBytes = if (request.plan.supabase.enabled) {
                supabase.preflight(source, target, request.plan.supabase, onLog)
            } else {
                preflight(source, target, request, onLog)
            }

            if (request.plan.supabase.enabled) {
                onPhase(MigrationPhase.SUPABASE)
                supabase.transfer(
                    source = source,
                    target = target,
                    spec = request.plan.supabase,
                    expectedBytes = requireNotNull(expectedBytes),
                    onLog = onLog,
                    onProgress = onProgress,
                )
                if (request.plan.verifyChecksums) {
                    onPhase(MigrationPhase.VERIFY)
                    supabase.verify(source, target, request.plan.supabase, onLog)
                }
                if (request.plan.supabase.startTargetAfterCopy) {
                    supabase.startTarget(target, request.plan.supabase, onLog)
                }
            } else {
                if (request.plan.migrateFiles) {
                    onPhase(MigrationPhase.FILES)
                    transferFiles(source, target, request, expectedBytes, onLog, onProgress)
                }

                if (request.plan.database.engine != DatabaseEngine.NONE) {
                    onPhase(MigrationPhase.DATABASE)
                    transferDatabase(source, target, request, onLog, onProgress)
                }

                if (request.plan.verifyChecksums && request.plan.migrateFiles) {
                    onPhase(MigrationPhase.VERIFY)
                    verifyFiles(source, target, request, onLog)
                }
            }

            onPhase(MigrationPhase.COMPLETED)
            onLog("Готово. Источник не изменялся и не удалялся.")
        } catch (cancelled: CancellationException) {
            onPhase(MigrationPhase.CANCELLED)
            onLog("Миграция отменена пользователем. Уже переданные данные на назначении не удалялись.")
            throw cancelled
        } finally {
            source?.disconnect()
            target?.disconnect()
        }
    }

    suspend fun testConnection(endpoint: ServerEndpoint): String {
        val session = ssh.connect(endpoint)
        return try {
            val result = ssh.exec(session, "printf 'ok'; uname -sr")
            require(result.exitCode == 0) { result.stderr.ifBlank { "SSH-команда завершилась с ошибкой" } }
            result.stdout
        } finally {
            session.disconnect()
        }
    }

    fun validateRequest(request: MigrationRequest) {
        validateEndpoint(request.source)
        validateEndpoint(request.target)
        val plan = request.plan
        val sameEndpoint = request.source.host.equals(request.target.host, ignoreCase = true) &&
            request.source.port == request.target.port &&
            request.source.username == request.target.username

        if (plan.supabase.enabled) {
            require(!plan.migrateFiles && plan.database.engine == DatabaseEngine.NONE) {
                "Supabase cold migration — отдельный режим. Отключите обычный перенос файлов и базы."
            }
            val sourceRoot = Shell.requireAbsolutePath(plan.supabase.sourceRoot, "Корень Supabase на источнике")
            val targetRoot = Shell.requireAbsolutePath(plan.supabase.targetRoot, "Корень Supabase на назначении")
            require(sourceRoot != "/") { "Перенос корня / как Supabase запрещён" }
            require(targetRoot != "/") { "Запись Supabase прямо в корень / запрещена" }
            require(!(sameEndpoint && sourceRoot == targetRoot)) {
                "Источник и назначение Supabase указывают на один и тот же каталог"
            }
        } else {
            if (plan.migrateFiles) {
                Shell.requireAbsolutePath(plan.sourcePath, "Путь источника")
                Shell.requireAbsolutePath(plan.targetPath, "Путь назначения")
                require(plan.sourcePath != "/") { "Перенос корня / целиком запрещён в этой версии" }
                require(plan.targetPath != "/") { "Запись прямо в корень / запрещена" }
                require(!(sameEndpoint && plan.sourcePath == plan.targetPath)) {
                    "Источник и назначение указывают на один и тот же каталог"
                }
            }
            if (plan.database.engine != DatabaseEngine.NONE) {
                Shell.requireDatabaseName(plan.database.sourceName, "Исходная база")
                Shell.requireDatabaseName(plan.database.targetName, "Целевая база")
            }
        }

        require(plan.supabase.enabled || plan.migrateFiles || plan.database.engine != DatabaseEngine.NONE) {
            "Выберите перенос файлов, базы данных или Supabase"
        }
    }

    private fun validateEndpoint(endpoint: ServerEndpoint) {
        require(endpoint.host.isNotBlank()) { "Не указан адрес сервера ${endpoint.title}" }
        require(endpoint.port in 1..65535) { "Некорректный SSH-порт ${endpoint.title}" }
        require(endpoint.username.isNotBlank()) { "Не указан SSH-пользователь ${endpoint.title}" }
        require(endpoint.password.isNotEmpty()) { "Не указан SSH-пароль ${endpoint.title}" }
        HostKeyPinRepository.normalize(endpoint.hostKeySha256)
    }

    private suspend fun preflight(
        source: Session,
        target: Session,
        request: MigrationRequest,
        onLog: (String) -> Unit,
    ): Long? {
        var bytes: Long? = null
        if (request.plan.migrateFiles) {
            val src = Shell.quote(request.plan.sourcePath)
            val dst = Shell.quote(request.plan.targetPath)
            val srcCheck = ssh.exec(
                source,
                "set -eu; command -v tar >/dev/null; test -d $src; test -r $src; du -sb -- $src | awk '{print $1}'",
                timeoutMs = FILE_SCAN_TIMEOUT_MS,
            )
            require(srcCheck.exitCode == 0) { "Источник файлов не прошёл проверку: ${srcCheck.stderr}" }
            bytes = srcCheck.stdout.lineSequence().lastOrNull()?.trim()?.toLongOrNull()
            onLog("Источник файлов доступен${bytes?.let { ", объём ≈ ${humanBytes(it)}" } ?: ""}.")

            val targetCheck = ssh.exec(
                target,
                "set -eu; command -v tar >/dev/null; p=$(dirname -- $dst); test -d \"\$p\"; test -w \"\$p\"; " +
                    "if test -e $dst; then test -d $dst; test -z \"$(find $dst -mindepth 1 -maxdepth 1 -print -quit)\" || { echo 'TARGET_NOT_EMPTY' >&2; exit 42; }; fi; " +
                    "df -Pk \"\$p\" | tail -1 | awk '{print $4}'",
            )
            require(targetCheck.exitCode == 0) {
                if ("TARGET_NOT_EMPTY" in targetCheck.stderr) {
                    "Целевой каталог уже содержит файлы. Для безопасной миграции укажите новый или пустой каталог."
                } else {
                    "Назначение файлов не прошло проверку: ${targetCheck.stderr}"
                }
            }
            val freeKb = targetCheck.stdout.lineSequence().lastOrNull()?.trim()?.toLongOrNull()
            if (bytes != null && freeKb != null) {
                require(freeKb * 1024L > bytes) { "На сервере назначения недостаточно свободного места" }
            }
            onLog("Назначение доступно для записи, свободное место проверено.")
        }

        when (request.plan.database.engine) {
            DatabaseEngine.NONE -> Unit
            DatabaseEngine.POSTGRESQL -> preflightPostgres(source, target, request, onLog)
            DatabaseEngine.MYSQL -> preflightMysql(source, target, request, onLog)
        }
        return bytes
    }

    private suspend fun transferFiles(
        source: Session,
        target: Session,
        request: MigrationRequest,
        expectedBytes: Long?,
        onLog: (String) -> Unit,
        onProgress: (Long, Long?) -> Unit,
    ) {
        val src = Shell.quote(request.plan.sourcePath)
        val dst = Shell.quote(request.plan.targetPath)
        val sourceCommand = "set -eu; tar -C $src -cf - ."
        val targetCommand = "set -eu; mkdir -p -- $dst; tar -C $dst -xf -"
        onLog("Передаю файлы потоково, без сохранения архива на телефоне…")
        val transferred = bridge.pipe(
            sourceSession = source,
            sourceCommand = sourceCommand,
            targetSession = target,
            targetCommand = targetCommand,
            completionTimeoutMs = FILE_COMPLETION_TIMEOUT_MS,
        ) { count ->
            onProgress(count, expectedBytes)
        }
        onLog("Поток файлов завершён: передано ${humanBytes(transferred)}.")
    }

    private suspend fun preflightPostgres(
        source: Session,
        target: Session,
        request: MigrationRequest,
        onLog: (String) -> Unit,
    ) {
        val srcDb = Shell.requireDatabaseName(request.plan.database.sourceName, "Исходная база")
        val dstDb = Shell.requireDatabaseName(request.plan.database.targetName, "Целевая база")
        val sourceCheck = ssh.exec(
            source,
            "set -eu; command -v pg_dump >/dev/null; sudo -n -u postgres psql -d ${Shell.quote(srcDb)} -Atqc 'select 1'",
        )
        require(sourceCheck.exitCode == 0) {
            "PostgreSQL на источнике недоступен. Нужен passwordless sudo для пользователя postgres: ${sourceCheck.stderr}"
        }

        val exists = ssh.exec(
            target,
            "set -eu; command -v pg_restore >/dev/null; command -v psql >/dev/null; sudo -n -u postgres psql -Atqc \"select 1 from pg_database where datname='${dstDb}'\"",
        )
        require(exists.exitCode == 0) { "PostgreSQL на назначении недоступен: ${exists.stderr}" }

        if (exists.stdout.trim() != "1") {
            require(request.plan.database.createTarget) {
                "Целевая PostgreSQL-база '$dstDb' не существует. Включите «создать целевую БД»."
            }
            val created = ssh.exec(target, "sudo -n -u postgres createdb ${Shell.quote(dstDb)}")
            require(created.exitCode == 0) { "Не удалось создать PostgreSQL-базу: ${created.stderr}" }
            onLog("Создана пустая PostgreSQL-база '$dstDb'.")
        } else {
            val count = ssh.exec(
                target,
                "sudo -n -u postgres psql -d ${Shell.quote(dstDb)} -Atqc \"" +
                    "select (" +
                    "(select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname not in ('pg_catalog','information_schema') and n.nspname !~ '^pg_toast') + " +
                    "(select count(*) from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname not in ('pg_catalog','information_schema') and n.nspname !~ '^pg_toast') + " +
                    "(select count(*) from pg_type t join pg_namespace n on n.oid=t.typnamespace where t.typtype in ('e','d') and n.nspname not in ('pg_catalog','information_schema') and n.nspname !~ '^pg_toast') + " +
                    "(select count(*) from pg_namespace where nspname not in ('pg_catalog','information_schema','public') and nspname !~ '^pg_toast')" +
                    ")\"",
            )
            require(count.exitCode == 0) { "Не удалось проверить целевую PostgreSQL-базу: ${count.stderr}" }
            require(count.stdout.trim().toLongOrNull() == 0L) {
                "Целевая PostgreSQL-база '$dstDb' содержит пользовательские объекты. Автоматическая очистка намеренно запрещена."
            }
        }
        onLog("PostgreSQL preflight пройден; целевая база пуста.")
    }

    private suspend fun transferDatabase(
        source: Session,
        target: Session,
        request: MigrationRequest,
        onLog: (String) -> Unit,
        onProgress: (Long, Long?) -> Unit,
    ) {
        val db = request.plan.database
        val sourceCommand: String
        val targetCommand: String
        when (db.engine) {
            DatabaseEngine.NONE -> return
            DatabaseEngine.POSTGRESQL -> {
                sourceCommand = "sudo -n -u postgres pg_dump --format=custom --no-owner --no-acl --dbname=${Shell.quote(db.sourceName)}"
                targetCommand = "sudo -n -u postgres pg_restore --exit-on-error --no-owner --no-acl --dbname=${Shell.quote(db.targetName)}"
            }
            DatabaseEngine.MYSQL -> {
                sourceCommand = "sudo -n mysqldump --single-transaction --quick --routines --events --triggers --hex-blob ${Shell.quote(db.sourceName)}"
                targetCommand = "sudo -n mysql ${Shell.quote(db.targetName)}"
            }
        }
        onLog("Передаю базу ${db.engine.title} потоково…")
        val bytes = bridge.pipe(
            sourceSession = source,
            sourceCommand = sourceCommand,
            targetSession = target,
            targetCommand = targetCommand,
            completionTimeoutMs = DATABASE_COMPLETION_TIMEOUT_MS,
        ) { count ->
            onProgress(count, null)
        }
        onLog("База передана: ${humanBytes(bytes)} в дамп-потоке.")
    }

    private suspend fun preflightMysql(
        source: Session,
        target: Session,
        request: MigrationRequest,
        onLog: (String) -> Unit,
    ) {
        val srcDb = Shell.requireDatabaseName(request.plan.database.sourceName, "Исходная база")
        val dstDb = Shell.requireDatabaseName(request.plan.database.targetName, "Целевая база")
        val sourceCheck = ssh.exec(
            source,
            "set -eu; command -v mysqldump >/dev/null; sudo -n mysql -Nse \"select schema_name from information_schema.schemata where schema_name='${srcDb}'\"",
        )
        require(sourceCheck.exitCode == 0 && sourceCheck.stdout.trim() == srcDb) {
            "MySQL/MariaDB на источнике или база '$srcDb' недоступна: ${sourceCheck.stderr}"
        }
        val targetCheck = ssh.exec(target, "set -eu; command -v mysql >/dev/null; sudo -n mysql -Nse 'select 1'")
        require(targetCheck.exitCode == 0) { "MySQL/MariaDB на назначении недоступна: ${targetCheck.stderr}" }

        val exists = ssh.exec(
            target,
            "sudo -n mysql -Nse \"select schema_name from information_schema.schemata where schema_name='${dstDb}'\"",
        )
        if (exists.stdout.trim() != dstDb) {
            require(request.plan.database.createTarget) {
                "Целевая MySQL-база '$dstDb' не существует. Включите «создать целевую БД»."
            }
            val created = ssh.exec(target, "sudo -n mysql -e \"create database \\`${dstDb}\\` character set utf8mb4\"")
            require(created.exitCode == 0) { "Не удалось создать MySQL-базу: ${created.stderr}" }
            onLog("Создана пустая MySQL-база '$dstDb'.")
        } else {
            val count = ssh.exec(
                target,
                "sudo -n mysql -Nse \"select " +
                    "(select count(*) from information_schema.tables where table_schema='${dstDb}') + " +
                    "(select count(*) from information_schema.routines where routine_schema='${dstDb}') + " +
                    "(select count(*) from information_schema.events where event_schema='${dstDb}') + " +
                    "(select count(*) from information_schema.triggers where trigger_schema='${dstDb}')\"",
            )
            require(count.exitCode == 0) { "Не удалось проверить целевую MySQL-базу: ${count.stderr}" }
            require(count.stdout.trim().toLongOrNull() == 0L) {
                "Целевая MySQL-база '$dstDb' содержит объекты. Автоматическая очистка намеренно запрещена."
            }
        }
        onLog("MySQL/MariaDB preflight пройден; целевая база пуста.")
    }

    private suspend fun verifyFiles(
        source: Session,
        target: Session,
        request: MigrationRequest,
        onLog: (String) -> Unit,
    ) {
        val src = Shell.quote(request.plan.sourcePath)
        val dst = Shell.quote(request.plan.targetPath)
        val hashCommand: (String) -> String = { path ->
            "set -eu; cd $path; find . -type f -print0 | LC_ALL=C sort -z | xargs -0 -r sha256sum | sha256sum | awk '{print $1}'"
        }
        onLog("Считаю агрегированный SHA-256 списка файлов на обоих серверах…")
        val srcHash = ssh.exec(source, hashCommand(src), timeoutMs = 30 * 60_000L)
        val dstHash = ssh.exec(target, hashCommand(dst), timeoutMs = 30 * 60_000L)
        require(srcHash.exitCode == 0) { "Не удалось посчитать checksum источника: ${srcHash.stderr}" }
        require(dstHash.exitCode == 0) { "Не удалось посчитать checksum назначения: ${dstHash.stderr}" }
        require(srcHash.stdout.isNotBlank() && srcHash.stdout == dstHash.stdout) {
            "Checksum файлов не совпал. Переключать сайт на новый сервер нельзя."
        }
        onLog("Checksum совпал: ${srcHash.stdout.take(16)}…")
    }

    companion object {
        private const val FILE_SCAN_TIMEOUT_MS = 30 * 60_000L
        private const val FILE_COMPLETION_TIMEOUT_MS = 30 * 60_000L
        private const val DATABASE_COMPLETION_TIMEOUT_MS = 4 * 60 * 60_000L
    }

    private fun humanBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes Б"
        val units = arrayOf("КиБ", "МиБ", "ГиБ", "ТиБ")
        var value = bytes.toDouble()
        var index = -1
        do {
            value /= 1024.0
            index++
        } while (value >= 1024.0 && index < units.lastIndex)
        return "%.1f %s".format(value, units[index])
    }
}
