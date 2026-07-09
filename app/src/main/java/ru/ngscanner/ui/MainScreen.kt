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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Garage
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Numbers
import ru.ngscanner.garage.Car
import ru.ngscanner.garage.GarageRepository
import ru.ngscanner.garage.LogEntry
import ru.ngscanner.garage.VehicleSuggestion
import ru.ngscanner.garage.VinInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin
import ru.ngscanner.ui.theme.GaugeAmber
import ru.ngscanner.ui.theme.GaugeCyan
import ru.ngscanner.ui.theme.GaugeRed
import ru.ngscanner.ui.theme.StatusGood
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
import androidx.compose.material3.FilledTonalButton
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
    Garage("Гараж", Icons.Rounded.Garage),
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
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("NG Scanner", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            ui.garage.activeCar?.let { car ->
                                Text(
                                    car.title,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
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
                    onScan = { launcher.launch(permissions); vm.refreshDevices(); vm.startScan() },
                    onStopScan = vm::stopScan,
                    onConnect = vm::connect,
                    onDisconnect = vm::disconnect,
                    onRequestNorm = vm::requestNorm,
                )
                Tab.Chat -> ChatTab(ui, onSend = vm::sendMessage, onClear = vm::clearChat)
                Tab.Garage -> GarageTab(ui, vm)
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
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onRequestNorm: (ObdPid) -> Unit,
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
            ConnectionState.Connected -> Dashboard(
                metrics = ui.metrics,
                onDisconnect = onDisconnect,
                modelNorms = ui.modelNorms,
                normLoadingPid = ui.normLoadingPid,
                onRequestNorm = onRequestNorm,
                activeCarTitle = ui.garage.activeCar?.title,
            )
            ConnectionState.Connecting -> ConnectingCard()
            ConnectionState.Disconnected -> DevicePicker(ui, onScan, onStopScan, onConnect)
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
private fun DevicePicker(ui: UiState, onScan: () -> Unit, onStopScan: () -> Unit, onConnect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Поиск адаптера", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (ui.scanning) {
                FilledTonalButton(onClick = onStopScan, shape = RoundedCornerShape(12.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Стоп")
                }
            } else {
                FilledTonalButton(onClick = onScan, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Rounded.Search, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Искать")
                }
            }
        }

        if (!ui.btEnabled) {
            InfoCard(
                Icons.Rounded.BluetoothDisabled,
                "Включите Bluetooth, чтобы найти и подключить адаптер ELM327.",
            )
        }

        // Найденные рядом (ещё не сопряжённые) устройства.
        if (ui.scanning || ui.discovered.isNotEmpty()) {
            SectionLabelSmall("Найденные рядом")
            ui.discovered.forEach { device -> DeviceRow(device) { onConnect(device.address) } }
            if (ui.scanning && ui.discovered.isEmpty()) {
                Text(
                    "Идёт поиск устройств… Убедитесь, что адаптер включён (горит светодиод).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Сопряжённые — свёрнуты по умолчанию.
        ExpandableSection("Сопряжённые (${ui.devices.size})") {
            if (ui.devices.isEmpty()) {
                InfoCard(
                    Icons.Rounded.Search,
                    "Нет сопряжённых устройств. Сопрягите ELM327 в настройках Bluetooth " +
                        "(PIN 1234 или 0000) или найдите его поиском выше.",
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ui.devices.forEach { device -> DeviceRow(device) { onConnect(device.address) } }
                }
            }
        }
    }
}

@Composable
private fun SectionLabelSmall(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.4.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ExpandableSection(title: String, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabelSmall(title)
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                if (expanded) "Свернуть" else "Развернуть",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) content()
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
private fun Dashboard(
    metrics: Map<ObdPid, Double>,
    onDisconnect: () -> Unit,
    modelNorms: Map<String, String>,
    normLoadingPid: String?,
    onRequestNorm: (ObdPid) -> Unit,
    activeCarTitle: String?,
) {
    var infoPid by remember { mutableStateOf<ObdPid?>(null) }
    val onTap: (ObdPid) -> Unit = { infoPid = it }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            CircularGauge(ObdPid.RPM, metrics[ObdPid.RPM], onTap, Modifier.weight(1f))
            CircularGauge(ObdPid.COOLANT, metrics[ObdPid.COOLANT], onTap, Modifier.weight(1f))
        }
        MetricsSection("Двигатель", listOf(ObdPid.ENGINE_LOAD, ObdPid.TIMING, ObdPid.SPEED), metrics, onTap)
        MetricsSection(
            "Впуск / Топливо",
            listOf(ObdPid.THROTTLE, ObdPid.MAF, ObdPid.MAP, ObdPid.INTAKE_TEMP, ObdPid.STFT, ObdPid.FUEL_LEVEL),
            metrics,
            onTap,
        )
        MetricsSection("Электрика", listOf(ObdPid.VOLTAGE), metrics, onTap)
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

    infoPid?.let { pid ->
        MetricInfoSheet(
            pid = pid,
            value = metrics[pid],
            onDismiss = { infoPid = null },
            modelNorm = modelNorms[pid.cmd],
            loading = normLoadingPid == pid.cmd,
            onRequestNorm = onRequestNorm,
            activeCarTitle = activeCarTitle,
        )
    }
}

@Composable
private fun MetricsSection(
    title: String,
    pids: List<ObdPid>,
    metrics: Map<ObdPid, Double>,
    onTap: (ObdPid) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.6.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        pids.chunked(2).forEach { rowPids ->
            Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                rowPids.forEach { pid -> MetricCard(pid, metrics[pid], onTap, Modifier.weight(1f)) }
                if (rowPids.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MetricCard(pid: ObdPid, value: Double?, onTap: (ObdPid) -> Unit, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val status = metricStatus(pid, value)
    val statusColor = statusColor(status)
    val hasValue = value != null
    val fraction = ((value ?: 0.0) / pid.gaugeMax).toFloat().coerceIn(0f, 1f)
    ElevatedCard(
        onClick = { onTap(pid) },
        modifier = modifier.heightIn(min = 104.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            // Цветовая полоса-индикатор состояния параметра.
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(if (hasValue) statusColor else cs.outline),
            )
            Column(Modifier.padding(14.dp).fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(metricIcon(pid), null, Modifier.size(17.dp), tint = if (hasValue) statusColor else cs.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        pid.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Rounded.Info, null, Modifier.size(14.dp), tint = cs.outline)
                }
                Spacer(Modifier.height(11.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        value?.let { formatMetric(it) } ?: "—",
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = if (!hasValue || status == MetricStatus.NORMAL) cs.onSurface else statusColor,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        pid.unit,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                // Мини-бар заполнения шкалы (цвет — по состоянию).
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(cs.surfaceVariant),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(if (hasValue) fraction.coerceAtLeast(0.02f) else 0f)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(statusColor),
                    )
                }
            }
        }
    }
}

@Composable
private fun CircularGauge(pid: ObdPid, value: Double?, onTap: (ObdPid) -> Unit, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val maxValue = pid.gaugeMax.toFloat()
    val target = (value ?: 0.0).toFloat().coerceIn(0f, maxValue)
    val animated by animateFloatAsState(target, tween(700, easing = FastOutSlowInEasing), label = "gauge_${pid.name}")
    val fraction = (animated / maxValue).coerceIn(0f, 1f)
    val status = metricStatus(pid, value)
    val numberColor = if (value == null || status == MetricStatus.NORMAL) cs.onSurface else statusColor(status)

    // Градиент вдоль дуги: спокойная бирюза на низах → янтарь → красная зона у верха шкалы.
    val arcBrush = remember {
        Brush.sweepGradient(
            0.00f to GaugeRed,
            0.125f to GaugeRed,
            0.375f to GaugeCyan,
            0.85f to GaugeCyan,
            0.97f to GaugeAmber,
            1.00f to GaugeRed,
        )
    }
    val trackColor = cs.surfaceVariant.copy(alpha = 0.5f)

    ElevatedCard(onClick = { onTap(pid) }, modifier = modifier, shape = RoundedCornerShape(20.dp)) {
        Box(
            Modifier.padding(14.dp).fillMaxWidth().aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize().padding(6.dp)) {
                val stroke = Stroke(width = 13.dp.toPx(), cap = StrokeCap.Round)
                drawArc(trackColor, 135f, 270f, useCenter = false, style = stroke)
                if (value != null) {
                    drawArc(arcBrush, 135f, 270f * fraction, useCenter = false, style = stroke)
                    // Светящаяся точка на конце заполненной дуги.
                    val angle = Math.toRadians((135f + 270f * fraction).toDouble())
                    val radius = (size.minDimension - stroke.width) / 2f
                    val dot = Offset(
                        center.x + radius * cos(angle).toFloat(),
                        center.y + radius * sin(angle).toFloat(),
                    )
                    drawCircle(GaugeCyan.copy(alpha = 0.25f), 11.dp.toPx(), dot)
                    drawCircle(Color(0xFFEAFFFB), 4.5.dp.toPx(), dot)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    value?.let { formatMetric(it) } ?: "—",
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = numberColor,
                )
                Text(pid.unit, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(2.dp))
                Text(
                    pid.label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.sp,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

/** Состояние параметра по порогам из [ObdPid]: отклонение вверх или вниз от нормы. */
private fun metricStatus(pid: ObdPid, v: Double?): MetricStatus {
    if (v == null) return MetricStatus.NORMAL
    val crit = (pid.critHigh != null && v >= pid.critHigh) || (pid.critLow != null && v <= pid.critLow)
    val warn = (pid.warnHigh != null && v >= pid.warnHigh) || (pid.warnLow != null && v <= pid.warnLow)
    return when {
        crit -> MetricStatus.CRITICAL
        warn -> MetricStatus.WARNING
        else -> MetricStatus.NORMAL
    }
}

@Composable
private fun statusColor(status: MetricStatus): Color = when (status) {
    MetricStatus.NORMAL -> StatusGood
    MetricStatus.WARNING -> MaterialTheme.colorScheme.tertiary
    MetricStatus.CRITICAL -> MaterialTheme.colorScheme.error
}

/** Шторка с описанием параметра и нормами (открывается по тапу на прибор/карточку). */
@Composable
private fun MetricInfoSheet(
    pid: ObdPid,
    value: Double?,
    onDismiss: () -> Unit,
    modelNorm: String?,
    loading: Boolean,
    onRequestNorm: (ObdPid) -> Unit,
    activeCarTitle: String?,
) {
    val cs = MaterialTheme.colorScheme
    val status = metricStatus(pid, value)
    val statusColor = statusColor(status)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
            Text(pid.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "PID ${pid.cmd}",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    value?.let { "${formatMetric(it)} ${pid.unit}" } ?: "нет данных",
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = if (value == null || status == MetricStatus.NORMAL) cs.onSurface else statusColor,
                )
                Spacer(Modifier.weight(1f))
                if (value != null) {
                    val (label, container, ink) = when (status) {
                        MetricStatus.NORMAL -> Triple("норма", StatusGood.copy(alpha = 0.15f), StatusGood)
                        MetricStatus.WARNING -> Triple("внимание", cs.tertiary.copy(alpha = 0.15f), cs.tertiary)
                        MetricStatus.CRITICAL -> Triple("критично", cs.error.copy(alpha = 0.15f), cs.error)
                    }
                    Surface(shape = RoundedCornerShape(999.dp), color = container) {
                        Text(
                            label.uppercase(),
                            Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = ink,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(pid.about, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, lineHeight = 21.sp)
            Spacer(Modifier.height(18.dp))
            NormBox("Норма (общая)", pid.norm, cs.onSurface, Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            when {
                modelNorm != null -> NormBox("Для вашей машины ★", modelNorm, cs.primary, Modifier.fillMaxWidth())
                loading -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Узнаю норму для вашей машины…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                    )
                }
                activeCarTitle == null -> Text(
                    "Добавьте машину в «Гараж», чтобы узнать норму именно для неё.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
                else -> OutlinedButton(
                    onClick = { onRequestNorm(pid) },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Узнать норму для «$activeCarTitle»", maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun NormBox(caption: String, value: String, valueColor: Color, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = cs.surface,
        border = BorderStroke(1.dp, cs.outline),
    ) {
        Column(Modifier.padding(13.dp)) {
            Text(
                caption.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 0.8.sp,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(5.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = valueColor)
        }
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
    Column(Modifier.fillMaxSize()) {
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
    // Ответ модели занимает всю ширину — таблицы и код помещаются целиком.
    val widthFraction = if (msg.role == ChatRole.ASSISTANT) 1f else 0.9f
    Column(Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.align(alignment).fillMaxWidth(widthFraction),
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
        } else {
            // Список ещё не загружен, но выбранная модель сохранена — показываем её.
            OutlinedTextField(
                value = ui.model,
                onValueChange = {},
                readOnly = true,
                label = { Text("Текущая модель") },
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Нажмите «Тест», чтобы выбрать другую модель") },
            )
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

// ---------- Вкладка «Гараж» ----------

private sealed interface GarageNav {
    data object List : GarageNav
    data class Detail(val carId: String) : GarageNav
    data object AddSearch : GarageNav
    data class AddForm(val suggestion: VehicleSuggestion) : GarageNav
    data object AddVin : GarageNav
}

@Composable
private fun GarageTab(ui: UiState, vm: MainViewModel) {
    var nav by remember { mutableStateOf<GarageNav>(GarageNav.List) }
    when (val n = nav) {
        is GarageNav.List -> CarListScreen(
            garage = ui.garage,
            onOpen = { nav = GarageNav.Detail(it) },
            onAddSearch = { vm.clearSuggestions(); nav = GarageNav.AddSearch },
            onAddVin = { vm.clearVin(); nav = GarageNav.AddVin },
        )
        is GarageNav.Detail -> {
            val car = ui.garage.cars.firstOrNull { it.id == n.carId }
            if (car == null) {
                LaunchedEffect(Unit) { nav = GarageNav.List }
            } else {
                CarDetailScreen(
                    car = car,
                    isActive = ui.garage.activeCarId == car.id,
                    onBack = { nav = GarageNav.List },
                    onMakeActive = { vm.setActiveCar(car.id) },
                    onDelete = { vm.deleteCar(car.id); nav = GarageNav.List },
                    onAddEntry = { text, km -> vm.addLogEntry(text, km) },
                )
            }
        }
        is GarageNav.AddSearch -> AddBySearchScreen(
            suggestions = ui.carSuggestions,
            onQuery = { vm.searchCars(it) },
            onBack = { vm.clearSuggestions(); nav = GarageNav.List },
            onPick = { nav = GarageNav.AddForm(it) },
        )
        is GarageNav.AddForm -> AddCarForm(
            suggestion = n.suggestion,
            onBack = { nav = GarageNav.AddSearch },
            onSave = { car -> vm.addCar(car); nav = GarageNav.Detail(car.id) },
        )
        is GarageNav.AddVin -> AddByVinScreen(
            ui = ui,
            onDecode = { vm.decodeVin(it) },
            onBack = { vm.clearVin(); nav = GarageNav.List },
            onSave = { car -> vm.addCar(car); nav = GarageNav.Detail(car.id) },
        )
    }
}

@Composable
private fun CarListScreen(
    garage: ru.ngscanner.garage.Garage,
    onOpen: (String) -> Unit,
    onAddSearch: () -> Unit,
    onAddVin: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        if (garage.cars.isEmpty()) {
            EmptyGarageHint()
        } else {
            GarageSectionLabel("Мои машины")
            garage.cars.forEach { car ->
                CarCard(car, isActive = garage.activeCarId == car.id) { onOpen(car.id) }
            }
        }
        Spacer(Modifier.height(4.dp))
        GarageSectionLabel("Добавить машину")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onAddSearch,
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Rounded.Search, null, Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text("Справочник")
            }
            OutlinedButton(
                onClick = onAddVin,
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Rounded.Numbers, null, Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text("По VIN")
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun CarCard(car: Car, isActive: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    ElevatedCard(onClick = onClick, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)).background(cs.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.DirectionsCar, null, Modifier.size(25.dp), tint = cs.primary)
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(car.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (isActive) {
                        Spacer(Modifier.width(8.dp))
                        ActiveBadge()
                    }
                }
                if (car.spec.isNotBlank()) {
                    Text(
                        car.spec,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = cs.onSurfaceVariant,
                    )
                }
                if (car.log.isNotEmpty()) {
                    Text(
                        "${car.log.size} ${plural(car.log.size, "запись", "записи", "записей")} в журнале",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun ActiveBadge() {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(999.dp), color = cs.primary.copy(alpha = 0.14f)) {
        Text(
            "АКТИВНА",
            Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = cs.primary,
        )
    }
}

@Composable
private fun GarageTopBar(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", tint = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.width(4.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun CarDetailScreen(
    car: Car,
    isActive: Boolean,
    onBack: () -> Unit,
    onMakeActive: () -> Unit,
    onDelete: () -> Unit,
    onAddEntry: (String, Int?) -> Unit,
) {
    var showEntry by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        GarageTopBar(car.title, onBack)
        CarSpecCard(car, isActive, onMakeActive, onDelete)
        CtxNote()
        LogbookSection(car.log) { showEntry = true }
        Spacer(Modifier.height(16.dp))
    }
    if (showEntry) {
        AddEntryDialog(
            onDismiss = { showEntry = false },
            onSave = { text, km -> onAddEntry(text, km); showEntry = false },
        )
    }
}

@Composable
private fun CarSpecCard(car: Car, isActive: Boolean, onMakeActive: () -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            SpecRow("Марка / модель", car.title)
            car.generation?.takeIf { it.isNotBlank() }?.let { SpecRow("Поколение", it) }
            SpecRow("Год выпуска", car.year?.toString() ?: "—")
            SpecRow("Двигатель", car.engine ?: "—")
            SpecRow("Пробег", car.mileageKm?.let { "$it км" } ?: "—")
            car.vin?.takeIf { it.isNotBlank() }?.let { SpecRow("VIN", it) }
            car.fuel?.takeIf { it.isNotBlank() }?.let { SpecRow("Топливо", it) }
            HorizontalDivider(color = cs.outline.copy(alpha = 0.6f), modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth()) {
                if (!isActive) {
                    TextButton(onClick = onMakeActive, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Сделать активной")
                    }
                } else {
                    Row(
                        Modifier.weight(1f).padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(16.dp), tint = cs.primary)
                        Spacer(Modifier.width(7.dp))
                        Text("Активная машина", color = cs.primary, style = MaterialTheme.typography.labelLarge)
                    }
                }
                TextButton(onClick = onDelete) {
                    Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text("Убрать", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun SpecRow(label: String, value: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = cs.onSurface,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.padding(vertical = 12.dp, horizontal = 14.dp)) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 0.8.sp,
            color = cs.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = cs.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun CtxNote() {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cs.primary.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, cs.primary.copy(alpha = 0.16f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Info, null, Modifier.size(18.dp), tint = cs.primary)
            Spacer(Modifier.width(11.dp))
            Text(
                "Паспорт машины и последние записи бортжурнала передаются агенту — он не станет " +
                    "предлагать то, что вы уже сделали, и сам дописывает сюда свои выводы.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurface,
                lineHeight = 18.sp,
            )
        }
    }
}

@Composable
private fun LogbookSection(log: List<LogEntry>, onAdd: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "БОРТЖУРНАЛ",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.6.sp,
                color = cs.onSurfaceVariant,
            )
            TextButton(onClick = onAdd) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Запись")
            }
        }
        if (log.isEmpty()) {
            Text(
                "Записей пока нет. Пишите, что сделали руками (заменил ДМРВ, почистил дроссель) — " +
                    "агент учтёт это при диагностике.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
        } else {
            log.forEach { LogEntryRow(it) }
        }
    }
}

@Composable
private fun LogEntryRow(e: LogEntry) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth()) {
        Box(
            Modifier.padding(top = 5.dp).size(9.dp).clip(CircleShape)
                .background(if (e.bySystem) cs.tertiary else cs.primary),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    e.dateIso,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = cs.onSurfaceVariant,
                )
                e.mileageKm?.let {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "$it км",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(e.text, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
            if (e.bySystem) {
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(12.dp), tint = cs.tertiary)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "запись ассистента",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = cs.tertiary,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun AddBySearchScreen(
    suggestions: List<VehicleSuggestion>,
    onQuery: (String) -> Unit,
    onBack: () -> Unit,
    onPick: (VehicleSuggestion) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GarageTopBar("Из справочника", onBack)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; onQuery(it) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Марка, модель… (напр. Лада Веста)") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
        )
        if (query.isNotBlank() && suggestions.isEmpty()) {
            Text(
                "Ничего не найдено. Попробуйте иначе — например «Solaris» или «Тойота Королла».",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
        }
        suggestions.forEach { s ->
            ElevatedCard(onClick = { onPick(s) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            s.years,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = cs.primary)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun AddCarForm(suggestion: VehicleSuggestion, onBack: () -> Unit, onSave: (Car) -> Unit) {
    val currentYear = remember { java.time.LocalDate.now().year }
    val yearTo = suggestion.yearTo ?: currentYear
    val years = remember(suggestion) { (yearTo downTo suggestion.yearFrom).map { it.toString() } }
    var year by remember { mutableStateOf(years.firstOrNull() ?: yearTo.toString()) }
    var engine by remember { mutableStateOf(suggestion.engines.firstOrNull() ?: "") }
    var mileage by remember { mutableStateOf("") }
    var vin by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GarageTopBar("Новая машина", onBack)
        Text(suggestion.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        SimpleDropdown("Год выпуска", years, year) { year = it }
        if (suggestion.engines.isNotEmpty()) {
            SimpleDropdown("Двигатель", suggestion.engines, engine) { engine = it }
        } else {
            OutlinedTextField(
                value = engine,
                onValueChange = { engine = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Двигатель") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
        }
        OutlinedTextField(
            value = mileage,
            onValueChange = { v -> mileage = v.filter { it.isDigit() } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Пробег, км") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(14.dp),
        )
        OutlinedTextField(
            value = vin,
            onValueChange = { vin = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(17) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("VIN (необязательно)") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
        )
        Button(
            onClick = {
                onSave(
                    Car(
                        id = GarageRepository.newCarId(),
                        make = suggestion.makeRu ?: suggestion.make,
                        model = suggestion.modelRu ?: suggestion.model,
                        generation = suggestion.generation,
                        engine = engine.ifBlank { null },
                        year = year.toIntOrNull(),
                        vin = vin.ifBlank { null },
                        mileageKm = mileage.toIntOrNull(),
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Rounded.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("Добавить в гараж")
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun AddByVinScreen(
    ui: UiState,
    onDecode: (String) -> Unit,
    onBack: () -> Unit,
    onSave: (Car) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var vin by remember { mutableStateOf("") }
    var mileage by remember { mutableStateOf("") }
    val info = ui.vinResult

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GarageTopBar("Добавить по VIN", onBack)
        Text(
            "Введите VIN — определим марку, модель, год и двигатель автоматически (нужен интернет).",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
        )
        OutlinedTextField(
            value = vin,
            onValueChange = { vin = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(17) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("VIN") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
        )
        Button(
            onClick = { onDecode(vin) },
            enabled = vin.length in 11..17 && !ui.vinDecoding,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            if (ui.vinDecoding) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = cs.onPrimary)
            } else {
                Icon(Icons.Rounded.Search, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Распознать")
            }
        }
        ui.vinError?.let {
            Text("✗ $it", style = MaterialTheme.typography.bodyMedium, color = cs.error)
        }
        if (info != null) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = cs.surface,
                border = BorderStroke(1.dp, cs.outline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    SpecRow("Марка", info.make)
                    SpecRow("Модель", info.model)
                    info.year?.let { SpecRow("Год", it.toString()) }
                    info.engine?.let { SpecRow("Двигатель", it) }
                    info.fuel?.let { SpecRow("Топливо", it) }
                }
            }
            OutlinedTextField(
                value = mileage,
                onValueChange = { v -> mileage = v.filter { it.isDigit() } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Пробег, км (необязательно)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(14.dp),
            )
            Button(
                onClick = {
                    onSave(
                        Car(
                            id = GarageRepository.newCarId(),
                            make = info.make,
                            model = info.model,
                            engine = info.engine,
                            year = info.year,
                            vin = vin.ifBlank { null },
                            fuel = info.fuel,
                            mileageKm = mileage.toIntOrNull(),
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Rounded.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Добавить в гараж")
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SimpleDropdown(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(text = { Text(opt) }, onClick = { onSelect(opt); expanded = false })
            }
        }
    }
}

/** Русское склонение существительного после числа: 1 запись, 2 записи, 5 записей. */
private fun plural(n: Int, one: String, few: String, many: String): String {
    val mod10 = n % 10
    val mod100 = n % 100
    return when {
        mod10 == 1 && mod100 != 11 -> one
        mod10 in 2..4 && mod100 !in 12..14 -> few
        else -> many
    }
}

@Composable
private fun EmptyGarageHint() {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Garage, null, Modifier.size(52.dp), tint = cs.primary)
        Text("Гараж пуст", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Добавьте автомобиль ниже — агент будет диагностировать именно вашу машину " +
                "с учётом её паспорта и истории работ.",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun GarageSectionLabel(title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        Spacer(Modifier.width(10.dp))
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.6.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AddEntryDialog(onDismiss: () -> Unit, onSave: (String, Int?) -> Unit) {
    var text by remember { mutableStateOf("") }
    var km by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Запись в бортжурнал") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Что сделали или заметили") },
                    minLines = 2,
                )
                OutlinedTextField(
                    value = km,
                    onValueChange = { v -> km = v.filter { it.isDigit() } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Пробег, км (необязательно)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text, km.toIntOrNull()) }, enabled = text.isNotBlank()) {
                Text("Сохранить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
