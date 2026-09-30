package ru.servermove.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.servermove.app.model.DatabaseEngine
import ru.servermove.app.model.DatabaseSpec
import ru.servermove.app.model.MigrationPhase
import ru.servermove.app.model.MigrationPlan
import ru.servermove.app.model.MigrationRequest
import ru.servermove.app.model.MigrationStatus
import ru.servermove.app.model.ServerEndpoint
import ru.servermove.app.model.SupabaseSpec

@Composable
fun ServerMoveApp(vm: MigrationViewModel = viewModel()) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            MigrationScreen(vm)
        }
    }
}

@Composable
private fun MigrationScreen(vm: MigrationViewModel) {
    val status by vm.status.collectAsStateWithLifecycle()

    var srcHost by remember { mutableStateOf("") }
    var srcPort by remember { mutableStateOf("22") }
    var srcUser by remember { mutableStateOf("root") }
    var srcPass by remember { mutableStateOf("") }
    var srcFp by remember { mutableStateOf("") }

    var dstHost by remember { mutableStateOf("") }
    var dstPort by remember { mutableStateOf("22") }
    var dstUser by remember { mutableStateOf("root") }
    var dstPass by remember { mutableStateOf("") }
    var dstFp by remember { mutableStateOf("") }

    var sourcePath by remember { mutableStateOf("/var/www/site") }
    var targetPath by remember { mutableStateOf("/var/www/site") }
    var migrateFiles by remember { mutableStateOf(true) }
    var engine by remember { mutableStateOf(DatabaseEngine.NONE) }
    var sourceDb by remember { mutableStateOf("") }
    var targetDb by remember { mutableStateOf("") }
    var createDb by remember { mutableStateOf(false) }
    var verify by remember { mutableStateOf(true) }

    var supabaseEnabled by remember { mutableStateOf(false) }
    var supabaseSourceRoot by remember { mutableStateOf("/opt/supabase") }
    var supabaseTargetRoot by remember { mutableStateOf("/opt/supabase") }
    var startSupabaseTarget by remember { mutableStateOf(false) }

    fun source() = ServerEndpoint("Источник", srcHost, srcPort.toIntOrNull() ?: 0, srcUser, srcPass, srcFp)
    fun target() = ServerEndpoint("Назначение", dstHost, dstPort.toIntOrNull() ?: 0, dstUser, dstPass, dstFp)
    fun request() = MigrationRequest(
        source = source(),
        target = target(),
        plan = MigrationPlan(
            sourcePath = sourcePath,
            targetPath = targetPath,
            migrateFiles = migrateFiles,
            database = DatabaseSpec(engine, sourceDb, targetDb.ifBlank { sourceDb }, createDb),
            verifyChecksums = verify,
            supabase = SupabaseSpec(
                enabled = supabaseEnabled,
                sourceRoot = supabaseSourceRoot,
                targetRoot = supabaseTargetRoot,
                startTargetAfterCopy = startSupabaseTarget,
            ),
        ),
    )

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("ServerMove RU", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Миграция сайтов, баз и self-hosted Supabase между Linux-серверами по SSH. Источник не удаляется.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ServerCard(
                title = "1. Сервер-источник",
                host = srcHost,
                onHost = { srcHost = it },
                port = srcPort,
                onPort = { srcPort = it },
                user = srcUser,
                onUser = { srcUser = it },
                password = srcPass,
                onPassword = { srcPass = it },
                fingerprint = srcFp,
                onFingerprint = { srcFp = it },
                enabled = !status.running,
            )

            ServerCard(
                title = "2. Сервер назначения",
                host = dstHost,
                onHost = { dstHost = it },
                port = dstPort,
                onPort = { dstPort = it },
                user = dstUser,
                onUser = { dstUser = it },
                password = dstPass,
                onPassword = { dstPass = it },
                fingerprint = dstFp,
                onFingerprint = { dstFp = it },
                enabled = !status.running,
            )

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("3. Что переносим", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = supabaseEnabled,
                            onCheckedChange = { enabled ->
                                supabaseEnabled = enabled
                                if (enabled) {
                                    migrateFiles = false
                                    engine = DatabaseEngine.NONE
                                }
                            },
                            enabled = !status.running,
                        )
                        Text("Полный self-hosted Supabase (cold snapshot)")
                    }

                    if (supabaseEnabled) {
                        Text(
                            "Source stack должен быть заранее остановлен вручную. Приложение не останавливает и не удаляет источник. Поддерживается локальный file Storage; внешний S3 пока блокируется.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = supabaseSourceRoot,
                            onValueChange = { supabaseSourceRoot = it },
                            label = { Text("Корень Supabase на источнике") },
                            supportingText = { Text("Каталог с docker-compose.yml, .env и volumes/") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !status.running,
                        )
                        OutlinedTextField(
                            value = supabaseTargetRoot,
                            onValueChange = { supabaseTargetRoot = it },
                            label = { Text("Корень Supabase на назначении") },
                            supportingText = { Text("Каталог должен отсутствовать или быть пустым") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !status.running,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = startSupabaseTarget,
                                onCheckedChange = { startSupabaseTarget = it },
                                enabled = !status.running,
                            )
                            Text("После checksum запустить target через docker compose up --wait")
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = migrateFiles, onCheckedChange = { migrateFiles = it }, enabled = !status.running)
                            Text("Файлы сайта / каталога")
                        }
                        OutlinedTextField(
                            value = sourcePath,
                            onValueChange = { sourcePath = it },
                            label = { Text("Путь на источнике") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = migrateFiles && !status.running,
                        )
                        OutlinedTextField(
                            value = targetPath,
                            onValueChange = { targetPath = it },
                            label = { Text("Путь на назначении") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = migrateFiles && !status.running,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DatabaseEngine.entries.forEach { item ->
                                FilterChip(
                                    selected = engine == item,
                                    onClick = { engine = item },
                                    label = { Text(item.title) },
                                    enabled = !status.running,
                                )
                            }
                        }
                        if (engine != DatabaseEngine.NONE) {
                            OutlinedTextField(
                                value = sourceDb,
                                onValueChange = { sourceDb = it },
                                label = { Text("Имя исходной БД") },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !status.running,
                            )
                            OutlinedTextField(
                                value = targetDb,
                                onValueChange = { targetDb = it },
                                label = { Text("Имя целевой БД (пусто = такое же)") },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !status.running,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = createDb, onCheckedChange = { createDb = it }, enabled = !status.running)
                                Text("Создать целевую БД, если её нет")
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = verify, onCheckedChange = { verify = it }, enabled = !status.running)
                        Text(
                            if (supabaseEnabled) {
                                "После переноса сверить SHA-256 обычных файлов Supabase snapshot"
                            } else {
                                "После файлов сверить агрегированный SHA-256"
                            },
                        )
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("4. Запуск", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Fingerprint берётся на сервере командой: ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub -E sha256",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { vm.testBoth(source(), target()) },
                            enabled = !status.running,
                        ) {
                            androidx.compose.material3.Icon(Icons.Default.Cloud, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Проверить SSH")
                        }
                        if (!status.running) {
                            Button(onClick = { vm.start(request()) }) {
                                androidx.compose.material3.Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Начать перенос")
                            }
                        } else {
                            OutlinedButton(onClick = vm::cancel) {
                                androidx.compose.material3.Icon(Icons.Default.Stop, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Отменить")
                            }
                        }
                    }
                }
            }

            StatusCard(status)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ServerCard(
    title: String,
    host: String,
    onHost: (String) -> Unit,
    port: String,
    onPort: (String) -> Unit,
    user: String,
    onUser: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    fingerprint: String,
    onFingerprint: (String) -> Unit,
    enabled: Boolean,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(host, onHost, label = { Text("IP / домен") }, modifier = Modifier.weight(1f), enabled = enabled)
                OutlinedTextField(
                    port,
                    onPort,
                    label = { Text("Порт") },
                    modifier = Modifier.width(100.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = enabled,
                )
            }
            OutlinedTextField(user, onUser, label = { Text("SSH-пользователь") }, modifier = Modifier.fillMaxWidth(), enabled = enabled)
            OutlinedTextField(
                password,
                onPassword,
                label = { Text("SSH-пароль (не сохраняется)") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                enabled = enabled,
            )
            OutlinedTextField(
                fingerprint,
                onFingerprint,
                label = { Text("SSH host key SHA256 fingerprint") },
                placeholder = { Text("SHA256:…") },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun StatusCard(status: MigrationStatus) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    status.running -> CircularProgressIndicator(modifier = Modifier.width(20.dp), strokeWidth = 2.dp)
                    status.phase == MigrationPhase.COMPLETED -> androidx.compose.material3.Icon(Icons.Default.CheckCircle, contentDescription = null)
                    status.phase == MigrationPhase.FAILED -> androidx.compose.material3.Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    status.phase == MigrationPhase.CANCELLED -> androidx.compose.material3.Icon(Icons.Default.Cancel, contentDescription = null)
                    else -> androidx.compose.material3.Icon(Icons.Default.Cloud, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text(status.phase.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            if (status.progress != null) {
                LinearProgressIndicator(progress = { status.progress }, modifier = Modifier.fillMaxWidth())
            } else if (status.running) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (status.bytesTransferred > 0L) {
                val details = buildString {
                    append("Передано ")
                    append(formatBytes(status.bytesTransferred))
                    status.bytesPerSecond?.let {
                        append(" • ")
                        append(formatBytes(it))
                        append("/с")
                    }
                    status.etaSeconds?.let {
                        append(" • осталось ≈ ")
                        append(formatDuration(it))
                    }
                }
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            HorizontalDivider()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(10.dp),
            ) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (status.logs.isEmpty()) {
                        Text("Здесь появится журнал миграции.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        status.logs.forEach { line ->
                            Text("› $line", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
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

private fun formatDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    val hours = safe / 3600L
    val minutes = (safe % 3600L) / 60L
    val secs = safe % 60L
    return when {
        hours > 0L -> "%d ч %02d мин".format(hours, minutes)
        minutes > 0L -> "%d мин %02d с".format(minutes, secs)
        else -> "$secs с"
    }
}
