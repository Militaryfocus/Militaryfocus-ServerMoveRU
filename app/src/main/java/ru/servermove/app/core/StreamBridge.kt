package ru.servermove.app.core

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext

class StreamBridge {
    suspend fun pipe(
        sourceSession: Session,
        sourceCommand: String,
        targetSession: Session,
        targetCommand: String,
        completionTimeoutMs: Long = DEFAULT_COMPLETION_TIMEOUT_MS,
        onBytes: (Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        require(completionTimeoutMs > 0L) { "Таймаут завершения SSH-потока должен быть положительным" }

        val source = sourceSession.openChannel("exec") as ChannelExec
        val target = targetSession.openChannel("exec") as ChannelExec
        val sourceErr = ByteArrayOutputStream()
        val targetErr = ByteArrayOutputStream()

        source.setCommand(sourceCommand)
        source.setInputStream(null)
        source.setErrStream(sourceErr)

        target.setCommand(targetCommand)
        target.setInputStream(null)
        target.setErrStream(targetErr)

        val sourceOut = source.inputStream
        val targetIn = target.outputStream
        var total = 0L
        var lastReportedBytes = -1L
        var lastReportNanos = System.nanoTime()

        fun reportProgress(force: Boolean = false) {
            val now = System.nanoTime()
            val elapsed = now - lastReportNanos
            if (force || elapsed >= PROGRESS_INTERVAL_NANOS) {
                if (total != lastReportedBytes) {
                    onBytes(total)
                    lastReportedBytes = total
                }
                lastReportNanos = now
            }
        }

        try {
            target.connect(CHANNEL_CONNECT_TIMEOUT_MS)
            source.connect(CHANNEL_CONNECT_TIMEOUT_MS)

            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                coroutineContext.ensureActive()
                val read = sourceOut.read(buffer)
                if (read < 0) break
                targetIn.write(buffer, 0, read)
                total += read
                reportProgress()
            }
            targetIn.flush()
            targetIn.close()
            reportProgress(force = true)

            waitForClose(source, completionTimeoutMs, "источника")
            waitForClose(target, completionTimeoutMs, "назначения")

            if (source.exitStatus != 0) {
                error("Источник завершил поток с кодом ${source.exitStatus}: ${sourceErr.toString(Charsets.UTF_8.name()).trim()}")
            }
            if (target.exitStatus != 0) {
                error("Приёмник завершил поток с кодом ${target.exitStatus}: ${targetErr.toString(Charsets.UTF_8.name()).trim()}")
            }
            total
        } finally {
            runCatching { targetIn.close() }
            source.disconnect()
            target.disconnect()
        }
    }

    private suspend fun waitForClose(
        channel: ChannelExec,
        timeoutMs: Long,
        side: String,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!channel.isClosed) {
            coroutineContext.ensureActive()
            if (System.currentTimeMillis() >= deadline) {
                error("SSH-канал $side не завершился за ${timeoutMs / 1000} секунд")
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    companion object {
        private const val BUFFER_SIZE = 128 * 1024
        private const val CHANNEL_CONNECT_TIMEOUT_MS = 15_000
        private const val DEFAULT_COMPLETION_TIMEOUT_MS = 30 * 60_000L
        private const val POLL_INTERVAL_MS = 50L
        private const val PROGRESS_INTERVAL_NANOS = 250_000_000L
    }
}
