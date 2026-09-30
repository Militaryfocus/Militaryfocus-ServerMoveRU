package ru.servermove.app.model

data class ServerEndpoint(
    val title: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String,
    val hostKeySha256: String,
)

enum class DatabaseEngine(val title: String) {
    NONE("Без базы"),
    POSTGRESQL("PostgreSQL"),
    MYSQL("MySQL / MariaDB"),
}

data class DatabaseSpec(
    val engine: DatabaseEngine = DatabaseEngine.NONE,
    val sourceName: String = "",
    val targetName: String = "",
    val createTarget: Boolean = false,
)

data class MigrationPlan(
    val sourcePath: String,
    val targetPath: String,
    val migrateFiles: Boolean = true,
    val database: DatabaseSpec = DatabaseSpec(),
    val verifyChecksums: Boolean = true,
)

data class MigrationRequest(
    val source: ServerEndpoint,
    val target: ServerEndpoint,
    val plan: MigrationPlan,
)

enum class MigrationPhase(val title: String) {
    IDLE("Готово к запуску"),
    CONNECTING("Подключение"),
    PREFLIGHT("Предварительная проверка"),
    FILES("Перенос файлов"),
    DATABASE("Перенос базы данных"),
    VERIFY("Проверка результата"),
    COMPLETED("Миграция завершена"),
    CANCELLED("Отменено"),
    FAILED("Ошибка"),
}

data class MigrationStatus(
    val phase: MigrationPhase = MigrationPhase.IDLE,
    val progress: Float? = null,
    val bytesTransferred: Long = 0L,
    val expectedBytes: Long? = null,
    val bytesPerSecond: Long? = null,
    val etaSeconds: Long? = null,
    val running: Boolean = false,
    val logs: List<String> = emptyList(),
    val error: String? = null,
)
