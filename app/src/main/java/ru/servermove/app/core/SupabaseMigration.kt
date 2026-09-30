package ru.servermove.app.core

import com.jcraft.jsch.Session
import ru.servermove.app.model.SupabaseSpec

class SupabaseMigration(
    private val ssh: SshClient,
    private val bridge: StreamBridge,
) {
    suspend fun preflight(
        source: Session,
        target: Session,
        spec: SupabaseSpec,
        onLog: (String) -> Unit,
    ): Long {
        val sourceRoot = Shell.requireAbsolutePath(spec.sourceRoot, "Корень Supabase на источнике")
        val targetRoot = Shell.requireAbsolutePath(spec.targetRoot, "Корень Supabase на назначении")
        val sourceRootQ = Shell.quote(sourceRoot)
        val targetRootQ = Shell.quote(targetRoot)

        val sourceCheck = ssh.exec(
            source,
            "set -eu; " +
                "command -v docker >/dev/null; command -v tar >/dev/null; command -v sha256sum >/dev/null; " +
                "sudo -n true; " +
                "test -f ${Shell.quote("$sourceRoot/docker-compose.yml")}; " +
                "test -f ${Shell.quote("$sourceRoot/.env")}; " +
                "sudo -n test -d ${Shell.quote("$sourceRoot/volumes/db/data")}; " +
                "sudo -n test -d ${Shell.quote("$sourceRoot/volumes/storage")}; " +
                "cd $sourceRootQ; " +
                "sudo -n docker compose version >/dev/null; " +
                "sudo -n docker compose config >/dev/null; " +
                "running=\$(sudo -n docker compose ps --status running -q | wc -l); " +
                "test \"\$running\" -eq 0 || { echo 'SUPABASE_RUNNING' >&2; exit 43; }; " +
                "image=\$(sudo -n docker compose config --images | grep -m1 'supabase/postgres' || true); " +
                "test -n \"\$image\" || { echo 'NOT_SUPABASE' >&2; exit 44; }; " +
                "sudo -n docker compose config | grep -Eq 'STORAGE_BACKEND:[[:space:]]+file' || { echo 'EXTERNAL_STORAGE' >&2; exit 45; }; " +
                "bytes=\$(sudo -n du -sb -- $sourceRootQ | awk '{print \$1}'); " +
                "version=\$(test -f .supabase-version && cat .supabase-version || printf 'unknown'); " +
                "printf 'IMAGE=%s\\nVERSION=%s\\nBYTES=%s\\n' \"\$image\" \"\$version\" \"\$bytes\"",
            timeoutMs = PREFLIGHT_TIMEOUT_MS,
        )
        require(sourceCheck.exitCode == 0) {
            when {
                "SUPABASE_RUNNING" in sourceCheck.stderr ->
                    "Supabase на источнике запущен. Для согласованного cold snapshot сначала вручную остановите stack."
                "NOT_SUPABASE" in sourceCheck.stderr ->
                    "В указанном каталоге не обнаружен официальный self-hosted Supabase Docker stack."
                "EXTERNAL_STORAGE" in sourceCheck.stderr ->
                    "Storage использует не локальный file backend. Внешний S3/object storage в этом контуре пока не переносится."
                else -> "Supabase source preflight не пройден: ${sourceCheck.stderr.ifBlank { sourceCheck.stdout }}"
            }
        }

        val bytes = sourceCheck.value("BYTES=")?.toLongOrNull()
            ?: error("Не удалось определить объём Supabase snapshot")
        val image = sourceCheck.value("IMAGE=") ?: "unknown"
        val version = sourceCheck.value("VERSION=") ?: "unknown"
        onLog("Supabase обнаружен: $image; версия конфигурации: $version.")
        onLog("Source stack остановлен; snapshot ≈ ${humanBytes(bytes)}.")

        val targetCheck = ssh.exec(
            target,
            "set -eu; " +
                "command -v docker >/dev/null; command -v tar >/dev/null; command -v sha256sum >/dev/null; " +
                "sudo -n true; sudo -n docker compose version >/dev/null; " +
                "existing=\$(sudo -n docker ps -a --format '{{.Names}}' | grep -E '^supabase-' | head -n1 || true); " +
                "test -z \"\$existing\" || { echo 'TARGET_SUPABASE_EXISTS' >&2; exit 46; }; " +
                "p=\$(dirname -- $targetRootQ); sudo -n test -d \"\$p\"; " +
                "if sudo -n test -e $targetRootQ; then " +
                "sudo -n test -d $targetRootQ; " +
                "test -z \"\$(sudo -n find $targetRootQ -mindepth 1 -maxdepth 1 -print -quit)\" || { echo 'TARGET_NOT_EMPTY' >&2; exit 42; }; " +
                "fi; " +
                "sudo -n df -Pk \"\$p\" | tail -1 | awk '{print \$4}'",
            timeoutMs = PREFLIGHT_TIMEOUT_MS,
        )
        require(targetCheck.exitCode == 0) {
            when {
                "TARGET_NOT_EMPTY" in targetCheck.stderr ->
                    "Целевой Supabase-каталог не пуст. Автоматическая очистка запрещена."
                "TARGET_SUPABASE_EXISTS" in targetCheck.stderr ->
                    "На сервере назначения уже обнаружены контейнеры supabase-*. Используйте чистый target или остановите и удалите старый stack вручную после отдельной проверки."
                else -> "Supabase target preflight не пройден: ${targetCheck.stderr.ifBlank { targetCheck.stdout }}"
            }
        }
        val freeKb = targetCheck.stdout.lineSequence().lastOrNull()?.trim()?.toLongOrNull()
            ?: error("Не удалось определить свободное место на target")
        require(freeKb * 1024L > bytes) {
            "На target недостаточно места для Supabase snapshot (${humanBytes(bytes)})."
        }
        onLog("Target чистый, Docker Compose доступен, свободное место проверено.")
        return bytes
    }

    suspend fun transfer(
        source: Session,
        target: Session,
        spec: SupabaseSpec,
        expectedBytes: Long,
        onLog: (String) -> Unit,
        onProgress: (Long, Long?) -> Unit,
    ) {
        val sourceRootQ = Shell.quote(spec.sourceRoot)
        val targetRootQ = Shell.quote(spec.targetRoot)
        val sourceCommand =
            "set -eu; sudo -n tar --numeric-owner --xattrs --acls -C $sourceRootQ -cf - ."
        val targetCommand =
            "set -eu; sudo -n mkdir -p -- $targetRootQ; " +
                "sudo -n tar --numeric-owner --xattrs --acls -C $targetRootQ -xf -"

        onLog("Передаю полный Supabase snapshot: конфигурация, Postgres data, Storage и Functions…")
        val transferred = bridge.pipe(
            sourceSession = source,
            sourceCommand = sourceCommand,
            targetSession = target,
            targetCommand = targetCommand,
            completionTimeoutMs = TRANSFER_COMPLETION_TIMEOUT_MS,
        ) { count -> onProgress(count, expectedBytes) }
        onLog("Supabase snapshot передан: ${humanBytes(transferred)} в tar-потоке.")
    }

    suspend fun verify(
        source: Session,
        target: Session,
        spec: SupabaseSpec,
        onLog: (String) -> Unit,
    ) {
        onLog("Сверяю SHA-256 всех обычных файлов cold snapshot…")
        val sourceHash = ssh.exec(
            source,
            checksumCommand(spec.sourceRoot),
            timeoutMs = VERIFY_TIMEOUT_MS,
        )
        val targetHash = ssh.exec(
            target,
            checksumCommand(spec.targetRoot),
            timeoutMs = VERIFY_TIMEOUT_MS,
        )
        require(sourceHash.exitCode == 0) {
            "Не удалось посчитать Supabase checksum источника: ${sourceHash.stderr}"
        }
        require(targetHash.exitCode == 0) {
            "Не удалось посчитать Supabase checksum target: ${targetHash.stderr}"
        }
        require(sourceHash.stdout.isNotBlank() && sourceHash.stdout == targetHash.stdout) {
            "Supabase checksum не совпал. Target запускать и переключать трафик нельзя."
        }
        onLog("Supabase checksum совпал: ${sourceHash.stdout.trim().take(16)}…")
    }

    suspend fun startTarget(
        target: Session,
        spec: SupabaseSpec,
        onLog: (String) -> Unit,
    ) {
        val targetRootQ = Shell.quote(spec.targetRoot)
        onLog("Запускаю перенесённый Supabase stack на target и жду health checks…")
        val result = ssh.exec(
            target,
            "set -eu; cd $targetRootQ; sudo -n docker compose up -d --wait",
            timeoutMs = START_TIMEOUT_MS,
        )
        require(result.exitCode == 0) {
            "Target Supabase не прошёл docker compose up --wait: ${result.stderr.ifBlank { result.stdout }}"
        }
        onLog("Target Supabase запущен; docker compose health checks завершились успешно.")
    }

    private fun checksumCommand(root: String): String {
        val inner =
            "cd ${Shell.quote(root)}; " +
                "find . -type f -print0 | LC_ALL=C sort -z | xargs -0 -r sha256sum | sha256sum | awk '{print \$1}'"
        return "set -eu; sudo -n sh -c ${Shell.quote(inner)}"
    }

    private fun String.value(prefix: String): String? =
        lineSequence().firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.trim()

    private fun humanBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes Б"
        val units = arrayOf("КиБ", "МиБ", "ГиБ", "ТиБ")
        var value = bytes.toDouble()
        var index = -1
        do {
            value /= 1024.0
            index++
        } while (value >= 1024.0 && index < units.lastIndex)
        return "%.1f %s".format(value, units[index])
    }

    companion object {
        private const val PREFLIGHT_TIMEOUT_MS = 30 * 60_000L
        private const val TRANSFER_COMPLETION_TIMEOUT_MS = 30 * 60_000L
        private const val VERIFY_TIMEOUT_MS = 60 * 60_000L
        private const val START_TIMEOUT_MS = 15 * 60_000L
    }
}
