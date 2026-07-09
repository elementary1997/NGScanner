@file:OptIn(ExperimentalMaterial3Api::class)

package ru.ngscanner.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import ru.ngscanner.llm.LlmImage
import ru.ngscanner.util.ImageEncoder
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.DeviceThermostat
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import ru.ngscanner.obd.ObdPid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.BluetoothSearching
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import ru.ngscanner.llm.LlmModel
import ru.ngscanner.llm.ProviderId

private enum class Tab(val label: String, val icon: ImageVector) {
    Devices("Приборы", Icons.Rounded.Speed),
    Chat("Диагностика", Icons.AutoMirrored.Rounded.Chat),
    Settings("Настройки", Icons.Rounded.Settings),
}

@Composable
fun MainScreen(vm: MainViewModel) {
    val ui by vm.ui.collectAsState()
    var tab by remember { mutableStateOf(Tab.Devices) }

    val permissions = bluetoothPermissions()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { vm.refreshDevices() }
    LaunchedEffect(Unit) { launcher.launch(permissions) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.DirectionsCar, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("NG Scanner", fontWeight = FontWeight.Bold)
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.Devices -> DevicesTab(
                    ui = ui,
                    onRefresh = { launcher.launch(permissions); vm.refreshDevices() },
                    onConnect = vm::connect,
                    onDisconnect = vm::disconnect,
                )
                Tab.Chat -> ChatTab(ui, onSend = vm::sendMessage, onClear = vm::clearChat)
                Tab.Settings -> SettingsTab(ui, vm)
            }
        }
    }
}

@Composable
private fun bluetoothPermissions(): Array<String> = remember {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

// ---------- Вкладка «Приборы» ----------

@Composable
private fun DevicesTab(
    ui: UiState,
    onRefresh: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        StatusCard(ui.connection, ui.connectedName)
        when (ui.connection) {
            ConnectionState.Connected -> Dashboard(ui.metrics, onDisconnect)
            ConnectionState.Connecting -> ConnectingCard()
            ConnectionState.Disconnected -> DevicePicker(ui, onRefresh, onConnect)
        }
        ui.error?.let { ErrorCard(it) }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun StatusCard(state: ConnectionState, name: String?) {
    val cs = MaterialTheme.colorScheme
    val icon: ImageVector
    val text: String
    val bg: Color
    val fg: Color
    when (state) {
        ConnectionState.Connected -> {
            icon = Icons.Rounded.BluetoothConnected
            text = "Подключено" + (name?.let { " · $it" } ?: "")
            bg = cs.primaryContainer
            fg = cs.onPrimaryContainer
        }
        ConnectionState.Connecting -> {
            icon = Icons.AutoMirrored.Rounded.BluetoothSearching
            text = "Подключение…"
            bg = cs.tertiaryContainer
            fg = cs.onTertiaryContainer
        }
        ConnectionState.Disconnected -> {
            icon = Icons.Rounded.Bluetooth
            text = "Не подключено"
            bg = cs.surfaceVariant
            fg = cs.onSurfaceVariant
        }
    }
    Surface(shape = RoundedCornerShape(20.dp), color = bg, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = fg)
            Spacer(Modifier.width(16.dp))
            Text(text, color = fg, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun DevicePicker(ui: UiState, onRefresh: () -> Unit, onConnect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Сопряжённые адаптеры", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            FilledTonalIconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, "Обновить список") }
        }
        when {
            !ui.btEnabled -> InfoCard(
                Icons.Rounded.BluetoothDisabled,
                "Включите Bluetooth и сопрягите адаптер ELM327 в настройках телефона.",
            )
            ui.devices.isEmpty() -> InfoCard(
                Icons.Rounded.Search,
                "Нет сопряжённых устройств. Сопрягите ELM327 в настройках Bluetooth " +
                    "(PIN 1234 или 0000) и нажмите обновить.",
            )
            else -> ui.devices.forEach { device -> DeviceRow(device) { onConnect(device.address) } }
        }
    }
}

@Composable
private fun DeviceRow(device: DeviceUi, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Bluetooth, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium)
                Text(device.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private enum class MetricStatus { NORMAL, WARNING, CRITICAL }

@Composable
private fun Dashboard(metrics: Map<ObdPid, Double>, onDisconnect: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularGauge(metrics[ObdPid.RPM], 7000f, 5500f, "Обороты", "об/мин", Modifier.weight(1f))
            CircularGauge(metrics[ObdPid.COOLANT], 130f, 105f, "Темп. ОЖ", "°C", Modifier.weight(1f))
        }
        MetricsSection("Двигатель", listOf(ObdPid.ENGINE_LOAD, ObdPid.TIMING, ObdPid.SPEED), metrics)
        MetricsSection(
            "Впуск / Топливо",
            listOf(ObdPid.THROTTLE, ObdPid.MAF, ObdPid.MAP, ObdPid.INTAKE_TEMP, ObdPid.STFT, ObdPid.FUEL_LEVEL),
            metrics,
        )
        MetricsSection("Электрика", listOf(ObdPid.VOLTAGE), metrics)
        OutlinedButton(
            onClick = onDisconnect,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(Icons.Rounded.LinkOff, null)
            Spacer(Modifier.width(8.dp))
            Text("Отключить")
        }
    }
}

@Composable
private fun MetricsSection(title: String, pids: List<ObdPid>, metrics: Map<ObdPid, Double>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        pids.chunked(2).forEach { rowPids ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowPids.forEach { pid -> MetricCard(pid, metrics[pid], Modifier.weight(1f)) }
                if (rowPids.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MetricCard(pid: ObdPid, value: Double?, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val status = metricStatus(pid, value)
    val accent = when (status) {
        MetricStatus.WARNING -> cs.tertiary
        MetricStatus.CRITICAL -> cs.error
        MetricStatus.NORMAL -> cs.primary
    }
    ElevatedCard(modifier = modifier.heightIn(min = 96.dp), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(metricIcon(pid), null, Modifier.size(20.dp), tint = accent)
                Spacer(Modifier.width(8.dp))
                Text(pid.label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value?.let { formatMetric(it) } ?: "—",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (status == MetricStatus.NORMAL) cs.onSurface else accent,
                )
                Spacer(Modifier.width(4.dp))
                Text(pid.unit, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

@Composable
private fun CircularGauge(
    value: Double?,
    maxValue: Float,
    warnAt: Float,
    label: String,
    unit: String,
    modifier: Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val target = (value ?: 0.0).toFloat().coerceIn(0f, maxValue)
    val animated by animateFloatAsState(target, tween(600, easing = FastOutSlowInEasing), label = "gauge_$label")
    val fraction = (animated / maxValue).coerceIn(0f, 1f)
    val warning = animated >= warnAt
    val arcColor = if (warning) cs.error else cs.primary
    val trackColor = cs.surfaceVariant.copy(alpha = 0.4f)
    ElevatedCard(modifier, shape = RoundedCornerShape(24.dp)) {
        Box(
            Modifier.padding(16.dp).fillMaxWidth().aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize().padding(7.dp)) {
                val stroke = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round)
                drawArc(trackColor, 135f, 270f, useCenter = false, style = stroke)
                drawArc(arcColor, 135f, 270f * fraction, useCenter = false, style = stroke)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    value?.let { formatMetric(it) } ?: "—",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (warning) cs.error else cs.onSurface,
                )
                Text(unit, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                Text(label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }
    }
}

private fun metricStatus(pid: ObdPid, v: Double?): MetricStatus {
    if (v == null) return MetricStatus.NORMAL
    return when (pid) {
        ObdPid.COOLANT -> if (v >= 110) MetricStatus.CRITICAL else if (v >= 105) MetricStatus.WARNING else MetricStatus.NORMAL
        ObdPid.RPM -> if (v >= 6000) MetricStatus.WARNING else MetricStatus.NORMAL
        ObdPid.VOLTAGE -> if (v < 12.0 || v > 15.0) MetricStatus.WARNING else MetricStatus.NORMAL
        else -> MetricStatus.NORMAL
    }
}

private fun metricIcon(pid: ObdPid): ImageVector = when (pid) {
    ObdPid.RPM, ObdPid.SPEED -> Icons.Rounded.Speed
    ObdPid.COOLANT -> Icons.Rounded.Thermostat
    ObdPid.INTAKE_TEMP -> Icons.Rounded.DeviceThermostat
    ObdPid.ENGINE_LOAD -> Icons.Rounded.DataUsage
    ObdPid.MAF -> Icons.Rounded.Air
    ObdPid.MAP -> Icons.Rounded.Compress
    ObdPid.THROTTLE -> Icons.Rounded.Tune
    ObdPid.STFT, ObdPid.FUEL_LEVEL -> Icons.Rounded.LocalGasStation
    ObdPid.TIMING -> Icons.Rounded.Timer
    ObdPid.VOLTAGE -> Icons.Rounded.Bolt
}

private fun formatMetric(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)

@Composable
private fun ConnectingCard() {
    ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            Spacer(Modifier.width(16.dp))
            Text("Подключение и инициализация ELM327…")
        }
    }
}

@Composable
private fun InfoCard(icon: ImageVector, text: String) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(16.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

// ---------- Вкладка «Диагностика» (чат с LLM) ----------

@Composable
private fun ChatTab(ui: UiState, onSend: (String, List<LlmImage>) -> Unit, onClear: () -> Unit) {
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (ui.chat.isEmpty()) {
                item { EmptyChatHint { onSend(QUICK_DIAGNOSE_PROMPT, emptyList()) } }
            }
            items(ui.chat) { msg -> ChatBubble(msg) }
            if (ui.diagnosing) {
                item {
                    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Диагностирую…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        ChatInput(
            enabled = !ui.diagnosing,
            visionEnabled = isVisionModel(ui.provider, ui.model),
            onSend = onSend,
            onClear = onClear,
            hasChat = ui.chat.isNotEmpty(),
        )
    }
}

@Composable
private fun EmptyChatHint(onDiagnose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 56.dp, start = 8.dp, end = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.HealthAndSafety, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Диагностика через LLM", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Опишите симптом или запустите быструю диагностику — модель прочитает коды и " +
                "параметры с адаптера и даст понятный вердикт.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onDiagnose, shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Rounded.Bolt, null)
            Spacer(Modifier.width(8.dp))
            Text("Быстрая диагностика")
        }
    }
}

@Composable
private fun ChatBubble(msg: ChatMessage) {
    val cs = MaterialTheme.colorScheme

    // Служебный статус инструмента — компактная строка, как «думаю…» у агента.
    if (msg.role == ChatRole.TOOL) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Sync, null, Modifier.size(16.dp), tint = cs.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(
                msg.text,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = cs.onSurfaceVariant,
            )
        }
        return
    }

    val bg: Color
    val fg: Color
    when (msg.role) {
        ChatRole.USER -> { bg = cs.primary; fg = cs.onPrimary }
        ChatRole.SYSTEM -> { bg = cs.errorContainer; fg = cs.onErrorContainer }
        else -> { bg = cs.surfaceVariant; fg = cs.onSurfaceVariant } // ASSISTANT
    }
    val alignment = if (msg.role == ChatRole.USER) Alignment.End else Alignment.Start
    Column(Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.align(alignment).fillMaxWidth(0.9f),
            shape = RoundedCornerShape(16.dp),
            color = bg,
        ) {
            if (msg.role == ChatRole.ASSISTANT) {
                // Markdown-ответ модели (жирный, списки, таблицы, код) рендерится, а не сырой текст.
                Markdown(content = msg.text, modifier = Modifier.padding(14.dp))
            } else {
                Text(msg.text, Modifier.padding(14.dp), color = fg, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ChatInput(
    enabled: Boolean,
    visionEnabled: Boolean,
    onSend: (String, List<LlmImage>) -> Unit,
    onClear: () -> Unit,
    hasChat: Boolean,
) {
    var text by remember { mutableStateOf("") }
    var image by remember { mutableStateOf<LlmImage?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { image = ImageEncoder.encode(context, uri) }
    }

    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            if (image != null) {
                Row(
                    modifier = Modifier.padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Image, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Фото прикреплено",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { image = null }) {
                        Icon(Icons.Rounded.Close, "Убрать фото", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (hasChat) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Rounded.DeleteSweep, "Очистить чат", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (visionEnabled) {
                    IconButton(onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) {
                        Icon(Icons.Rounded.AddPhotoAlternate, "Прикрепить фото", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Опишите проблему…") },
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = {
                        onSend(text, image?.let { listOf(it) } ?: emptyList())
                        text = ""
                        image = null
                    },
                    enabled = enabled && (text.isNotBlank() || image != null),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, "Отправить")
                }
            }
        }
    }
}

// ---------- Вкладка «Настройки» ----------

@Composable
private fun SettingsTab(ui: UiState, vm: MainViewModel) {
    var keyText by remember(ui.provider, ui.apiKey) { mutableStateOf(ui.apiKey) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Провайдер LLM", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = ui.provider == ProviderId.CLAUDE,
                onClick = { vm.setProvider(ProviderId.CLAUDE) },
                label = { Text("Anthropic Claude") },
            )
            FilterChip(
                selected = ui.provider == ProviderId.CLOUD_RU,
                onClick = { vm.setProvider(ProviderId.CLOUD_RU) },
                label = { Text("Cloud.ru") },
            )
        }

        OutlinedTextField(
            value = keyText,
            onValueChange = { keyText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API-ключ") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )

        Button(
            onClick = { vm.testConnection(keyText) },
            enabled = !ui.testing && keyText.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            if (ui.testing) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Icon(Icons.Rounded.Bolt, null)
                Spacer(Modifier.width(8.dp))
                Text("Тест подключения")
            }
        }

        when (val st = ui.testStatus) {
            is TestStatus.Success -> Text(
                "✓ Подключение успешно — выберите модель ниже",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            is TestStatus.Error -> Text(
                "✗ ${st.message}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            null -> if (ui.hasKey) {
                Text(
                    "Ключ сохранён. Нажмите «Тест», чтобы загрузить список моделей.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (ui.availableModels.isNotEmpty()) {
            ModelDropdown(ui.availableModels, ui.model, vm::setModel)
        }

        InfoCard(
            Icons.Rounded.ErrorOutline,
            "Ключ хранится только на устройстве и не попадает в код приложения. " +
                "Claude — console.anthropic.com; Cloud.ru — личный кабинет (сервисный аккаунт).",
        )
    }
}

@Composable
private fun ModelDropdown(models: List<LlmModel>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = models.firstOrNull { it.id == selected }?.label ?: selected
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Модель") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.forEach { m ->
                DropdownMenuItem(text = { Text(m.label) }, onClick = { onSelect(m.id); expanded = false })
            }
        }
    }
}

/** Показывать ли кнопку прикрепления фото — Claude всегда vision, Cloud.ru — по имени модели. */
private fun isVisionModel(provider: ProviderId, model: String): Boolean =
    provider == ProviderId.CLAUDE ||
        Regex("(?i)(vl|vision|4v|4\\.5v|gpt-4o|gpt-4\\.1|gpt-5|gemini|omni)").containsMatchIn(model)

private const val QUICK_DIAGNOSE_PROMPT =
    "Проведи диагностику автомобиля: прочитай активные коды неисправностей и текущие " +
        "параметры двигателя, затем дай понятный вердикт — что вероятно неисправно и что делать."
