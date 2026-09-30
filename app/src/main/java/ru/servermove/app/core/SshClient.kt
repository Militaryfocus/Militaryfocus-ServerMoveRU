package ru.servermove.app.core

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ru.servermove.app.model.ServerEndpoint
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

class SshClient {
    suspend fun connect(endpoint: ServerEndpoint): Session = withContext(Dispatchers.IO) {
        require(endpoint.host.isNotBlank()) { "Не указан адрес сервера ${endpoint.title}" }
        require(endpoint.username.isNotBlank()) { "Не указан SSH-пользователь ${endpoint.title}" }
        require(endpoint.password.isNotEmpty()) { "Не указан SSH-пароль ${endpoint.title}" }
        require(endpoint.port in 1..65535) { "Некорректный SSH-порт ${endpoint.title}" }

        val jsch = JSch().apply {
            hostKeyRepository = HostKeyPinRepository(endpoint.hostKeySha256)
        }
        val session = jsch.getSession(endpoint.username, endpoint.host, endpoint.port)
        session.setPassword(endpoint.password)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        session.connect(CONNECT_TIMEOUT_MS)
        session
    }

    suspend fun exec(session: Session, command: String, timeoutMs: Long = 60_000L): CommandResult =
        withContext(Dispatchers.IO) {
            val channel = session.openChannel("exec") as ChannelExec
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            channel.setCommand(command)
            channel.setInputStream(null)
            channel.setOutputStream(stdout)
            channel.setErrStream(stderr)
            channel.connect(CHANNEL_TIMEOUT_MS)

            val started = System.currentTimeMillis()
            try {
                while (!channel.isClosed) {
                    coroutineContext.ensureActive()
                    if (System.currentTimeMillis() - started > timeoutMs) {
                        error("Команда превысила таймаут ${timeoutMs / 1000} с")
                    }
                    Thread.sleep(50)
                }
                CommandResult(
                    exitCode = channel.exitStatus,
                    stdout = stdout.toString(Charsets.UTF_8.name()).trim(),
                    stderr = stderr.toString(Charsets.UTF_8.name()).trim(),
                )
            } finally {
                channel.disconnect()
            }
        }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val CHANNEL_TIMEOUT_MS = 15_000
    }
}
