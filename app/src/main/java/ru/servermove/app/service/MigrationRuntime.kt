package ru.servermove.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.servermove.app.model.MigrationPhase
import ru.servermove.app.model.MigrationRequest
import ru.servermove.app.model.MigrationStatus
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object MigrationRuntime {
    private val _status = MutableStateFlow(MigrationStatus())
    val status = _status.asStateFlow()

    @Volatile
    private var pending: MigrationRequest? = null

    private var transferStartedNanos: Long? = null

    fun submit(request: MigrationRequest) {
        check(pending == null && !_status.value.running) { "Миграция уже запущена" }
        pending = request
        transferStartedNanos = null
        _status.value = MigrationStatus(
            phase = MigrationPhase.CONNECTING,
            running = true,
            logs = listOf(stamp("Задание принято.")),
        )
    }

    fun takePending(): MigrationRequest? = pending.also { pending = null }

    fun phase(value: MigrationPhase) {
        val resetTransfer = value == MigrationPhase.FILES || value == MigrationPhase.DATABASE
        if (resetTransfer) transferStartedNanos = System.nanoTime()
        _status.value = _status.value.copy(
            phase = value,
            progress = if (resetTransfer) null else _status.value.progress,
            bytesTransferred = if (resetTransfer) 0L else _status.value.bytesTransferred,
            expectedBytes = if (resetTransfer) null else _status.value.expectedBytes,
            bytesPerSecond = if (resetTransfer) null else _status.value.bytesPerSecond,
            etaSeconds = if (resetTransfer) null else _status.value.etaSeconds,
        )
    }

    fun log(message: String) {
        val next = (_status.value.logs + stamp(message)).takeLast(MAX_LOG_LINES)
        _status.value = _status.value.copy(logs = next)
    }

    fun progress(bytes: Long, expected: Long?) {
        val now = System.nanoTime()
        val started = transferStartedNanos ?: now.also { transferStartedNanos = it }
        val elapsedSeconds = ((now - started).coerceAtLeast(1L) / 1_000_000_000.0).coerceAtLeast(0.001)
        val rate = (bytes / elapsedSeconds).toLong().takeIf { it > 0L }
        val progress = expected?.takeIf { it > 0L }?.let {
            (bytes.toDouble() / it).coerceIn(0.0, 1.0).toFloat()
        }
        val eta = if (expected != null && rate != null && expected > bytes) {
            ((expected - bytes) / rate).coerceAtLeast(0L)
        } else {
            null
        }
        _status.value = _status.value.copy(
            bytesTransferred = bytes,
            expectedBytes = expected,
            progress = progress,
            bytesPerSecond = rate,
            etaSeconds = eta,
        )
    }

    fun complete() {
        pending = null
        _status.value = _status.value.copy(
            phase = MigrationPhase.COMPLETED,
            running = false,
            progress = 1f,
            etaSeconds = 0L,
        )
    }

    fun cancelled() {
        pending = null
        _status.value = _status.value.copy(
            phase = MigrationPhase.CANCELLED,
            running = false,
            etaSeconds = null,
        )
    }

    fun fail(message: String) {
        pending = null
        _status.value = _status.value.copy(
            phase = MigrationPhase.FAILED,
            running = false,
            error = message,
            etaSeconds = null,
            logs = (_status.value.logs + stamp("ОШИБКА: $message")).takeLast(MAX_LOG_LINES),
        )
    }

    private fun stamp(message: String): String = "${LocalTime.now().format(TIME_FORMAT)}  $message"

    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private const val MAX_LOG_LINES = 300
}
