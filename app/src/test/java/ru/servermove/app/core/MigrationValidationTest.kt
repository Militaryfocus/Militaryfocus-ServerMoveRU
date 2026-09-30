package ru.servermove.app.core

import org.junit.Assert.assertThrows
import org.junit.Test
import ru.servermove.app.model.DatabaseEngine
import ru.servermove.app.model.DatabaseSpec
import ru.servermove.app.model.MigrationPlan
import ru.servermove.app.model.MigrationRequest
import ru.servermove.app.model.ServerEndpoint

class MigrationValidationTest {
    private val validFingerprint = "SHA256:" + "A".repeat(43)

    private fun endpoint(title: String, host: String = "server.example") = ServerEndpoint(
        title = title,
        host = host,
        port = 22,
        username = "migration",
        password = "secret",
        hostKeySha256 = validFingerprint,
    )

    private fun request(
        source: ServerEndpoint = endpoint("Источник", "source.example"),
        target: ServerEndpoint = endpoint("Назначение", "target.example"),
        plan: MigrationPlan = MigrationPlan("/var/www/site", "/var/www/site"),
    ) = MigrationRequest(source, target, plan)

    @Test
    fun invalidPortIsRejectedBeforeServiceStarts() {
        val source = endpoint("Источник").copy(port = 0)
        assertThrows(IllegalArgumentException::class.java) {
            MigrationEngine().validateRequest(request(source = source))
        }
    }

    @Test
    fun malformedFingerprintIsRejected() {
        val source = endpoint("Источник").copy(hostKeySha256 = "SHA256:broken")
        assertThrows(IllegalArgumentException::class.java) {
            MigrationEngine().validateRequest(request(source = source))
        }
    }

    @Test
    fun obviousSelfCopyIsRejected() {
        val source = endpoint("Источник", "same.example")
        val target = endpoint("Назначение", "same.example")
        assertThrows(IllegalArgumentException::class.java) {
            MigrationEngine().validateRequest(request(source = source, target = target))
        }
    }

    @Test
    fun emptyPlanIsRejected() {
        val plan = MigrationPlan(
            sourcePath = "/var/www/site",
            targetPath = "/var/www/site-new",
            migrateFiles = false,
            database = DatabaseSpec(DatabaseEngine.NONE),
        )
        assertThrows(IllegalArgumentException::class.java) {
            MigrationEngine().validateRequest(request(plan = plan))
        }
    }
}
