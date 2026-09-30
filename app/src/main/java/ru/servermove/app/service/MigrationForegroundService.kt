package ru.servermove.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.servermove.app.MainActivity
import ru.servermove.app.core.MigrationEngine
import ru.servermove.app.model.MigrationPhase

class MigrationForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engine = MigrationEngine()
    private var job: Job? = null

    @Volatile
    private var timedOut = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                val activeJob = job
                if (activeJob?.isActive == true) {
                    activeJob.cancel()
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_START -> startMigration()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        timedOut = true
        MigrationRuntime.fail("Android остановил длительную dataSync-службу по системному лимиту времени")
        job?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun startMigration() {
        if (job?.isActive == true) return
        timedOut = false
        val request = MigrationRuntime.takePending() ?: run {
            stopSelf()
            return
        }

        startForeground(
            NOTIFICATION_ID,
            notification(
                text = "Подготовка миграции…",
                progressPercent = null,
                ongoing = true,
                showCancel = true,
            ),
        )

        job = scope.launch {
            var finalText = "Миграция завершена"
            try {
                engine.run(
                    request = request,
                    onPhase = { phase ->
                        MigrationRuntime.phase(phase)
                        notifyActive(phase.title, progressPercent = null)
                    },
                    onLog = MigrationRuntime::log,
                    onProgress = { bytes, expected ->
                        MigrationRuntime.progress(bytes, expected)
                        val text = if (expected != null && expected > 0) {
                            "Передано ${(bytes * 100L / expected).coerceIn(0L, 100L)}%"
                        } else {
                            "Передано ${bytes / 1024 / 1024} МиБ"
                        }
                        notifyActive(text, progressPercent = expected?.takeIf { it > 0L }?.let { total -> ((bytes * 100L / total).coerceIn(0L, 100L)).toInt() })
                    },
                )
                MigrationRuntime.complete()
            } catch (_: CancellationException) {
                if (timedOut) {
                    finalText = "Ошибка миграции"
                } else {
                    finalText = "Миграция отменена"
                    MigrationRuntime.cancelled()
                }
            } catch (t: Throwable) {
                finalText = "Ошибка миграции"
                MigrationRuntime.fail(t.message ?: t::class.java.simpleName)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                notifyFinal(finalText)
                stopSelf()
            }
        }
    }

    private fun notification(
        text: String,
        progressPercent: Int?,
        ongoing: Boolean,
        showCancel: Boolean,
    ): android.app.Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("ServerMove RU")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setProgress(
                if (ongoing) 100 else 0,
                progressPercent ?: 0,
                ongoing && progressPercent == null,
            )
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )

        if (showCancel) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Отменить",
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, MigrationForegroundService::class.java).setAction(ACTION_CANCEL),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        return builder.build()
    }

    private fun notifyActive(text: String, progressPercent: Int?) {
        notificationManager().notify(
            NOTIFICATION_ID,
            notification(text, progressPercent, ongoing = true, showCancel = true),
        )
    }

    private fun notifyFinal(text: String) {
        notificationManager().notify(
            NOTIFICATION_ID,
            notification(text, progressPercent = null, ongoing = false, showCancel = false),
        )
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createNotificationChannel() {
        notificationManager().createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Миграции серверов", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val ACTION_START = "ru.servermove.app.START"
        const val ACTION_CANCEL = "ru.servermove.app.CANCEL"
        private const val CHANNEL_ID = "server_migrations"
        private const val NOTIFICATION_ID = 7001
    }
}
