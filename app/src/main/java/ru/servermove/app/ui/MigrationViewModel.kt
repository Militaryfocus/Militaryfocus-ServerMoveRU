package ru.servermove.app.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.servermove.app.core.MigrationEngine
import ru.servermove.app.model.MigrationRequest
import ru.servermove.app.model.ServerEndpoint
import ru.servermove.app.service.MigrationForegroundService
import ru.servermove.app.service.MigrationRuntime

class MigrationViewModel(application: Application) : AndroidViewModel(application) {
    val status = MigrationRuntime.status.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = MigrationRuntime.status.value,
    )

    fun start(request: MigrationRequest) {
        runCatching {
            MigrationEngine().validateRequest(request)
            MigrationRuntime.submit(request)
            ContextCompat.startForegroundService(
                getApplication(),
                Intent(getApplication(), MigrationForegroundService::class.java)
                    .setAction(MigrationForegroundService.ACTION_START),
            )
        }.onFailure { MigrationRuntime.fail(it.message ?: "Не удалось запустить службу") }
    }

    fun cancel() {
        getApplication<Application>().startService(
            Intent(getApplication(), MigrationForegroundService::class.java)
                .setAction(MigrationForegroundService.ACTION_CANCEL),
        )
    }

    fun testBoth(source: ServerEndpoint, target: ServerEndpoint) {
        viewModelScope.launch {
            MigrationRuntime.log("Проверяю SSH-подключения…")
            runCatching {
                withContext(Dispatchers.IO) {
                    val engine = MigrationEngine()
                    val src = engine.testConnection(source)
                    MigrationRuntime.log("Источник OK: $src")
                    val dst = engine.testConnection(target)
                    MigrationRuntime.log("Назначение OK: $dst")
                }
            }.onFailure { MigrationRuntime.fail(it.message ?: "Ошибка SSH-проверки") }
        }
    }
}
