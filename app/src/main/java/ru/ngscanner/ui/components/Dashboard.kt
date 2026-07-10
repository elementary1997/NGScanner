@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package ru.ngscanner.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.drawscope.Stroke
import ru.ngscanner.obd.ObdPid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.ngscanner.ui.MetricSample

internal enum class MetricStatus { NORMAL, WARNING, CRITICAL }

@Composable
internal fun Dashboard(
    metrics: Map<ObdPid, Double>,
    history: Map<ObdPid, List<MetricSample>>,
    supportedPids: Set<String>,
    dashboardPids: List<String>,
    onDisconnect: () -> Unit,
    onOpenGraph: (ObdPid) -> Unit,
    onOpenDtc: () -> Unit,
    onSetDashboardPids: (List<String>) -> Unit,
) {
    // Тап по прибору открывает полноэкранный график — навигация живёт в DevicesTab
    // (график заменяет весь экран, а не вкладывается в скролл дашборда).
    val onTap: (ObdPid) -> Unit = onOpenGraph
    // Показываем только параметры, которые ЭБУ реально поддерживает (по 0100/0120/0140);
    // если определить не удалось (пусто) — показываем все, как раньше.
    fun shown(pid: ObdPid) = supportedPids.isEmpty() || pid.cmd in supportedPids
    var showConfig by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { showConfig = true }) {
                Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Настроить")
            }
        }
        if (dashboardPids.isEmpty()) {
            // Дефолтная раскладка: круговые гейджи + сгруппированные приборы.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                CircularGauge(ObdPid.RPM, metrics[ObdPid.RPM], onTap, Modifier.weight(1f))
                CircularGauge(ObdPid.COOLANT, metrics[ObdPid.COOLANT], onTap, Modifier.weight(1f))
            }
            MetricsSection("Двигатель", listOf(ObdPid.ENGINE_LOAD, ObdPid.TIMING, ObdPid.SPEED).filter(::shown), metrics, history, onTap)
            MetricsSection(
                "Впуск / Топливо",
                listOf(
                    ObdPid.THROTTLE, ObdPid.MAF, ObdPid.MAP, ObdPid.INTAKE_TEMP,
                    ObdPid.STFT, ObdPid.LTFT, ObdPid.O2_LAMBDA, ObdPid.FUEL_LEVEL,
                ).filter(::shown),
                metrics,
                history,
                onTap,
            )
            MetricsSection("Электрика", listOf(ObdPid.VOLTAGE).filter(::shown), metrics, history, onTap)
        } else {
            // Кастомная раскладка: выбранные PID сеткой в заданном пользователем порядке.
            val ordered = DashboardLayout.resolve(dashboardPids, supportedPids)
            if (ordered.isEmpty()) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Выбранные параметры не поддерживаются этим ЭБУ. Измените набор кнопкой «Настроить».",
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    ordered.chunked(2).forEach { rowPids ->
                        Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                            rowPids.forEach { pid ->
                                MetricCard(pid, metrics[pid], history[pid].orEmpty(), onTap, Modifier.weight(1f))
                            }
                            if (rowPids.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        FilledTonalButton(
            onClick = onOpenDtc,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(Icons.Rounded.Warning, null)
            Spacer(Modifier.width(8.dp))
            Text("Коды неисправностей")
        }
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
    if (showConfig) {
        DashboardPidsDialog(
            current = dashboardPids,
            onDismiss = { showConfig = false },
            onSave = { showConfig = false; onSetDashboardPids(it) },
        )
    }
}

/**
 * Диалог настройки дашборда: выбор приборов (Checkbox) и их порядок (стрелки вверх/вниз).
 * «Сбросить» → пустой список (дефолтная раскладка). «Выбраны все» НЕ схлопываем в пустой —
 * иначе потерялся бы заданный порядок.
 */
@Composable
private fun DashboardPidsDialog(
    current: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val selected = remember { mutableStateListOf<String>().apply { addAll(current) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSave(selected.toList()) }) { Text("Готово") } },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave(emptyList()) }) { Text("Сбросить") }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
        title = { Text("Приборы дашборда") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                if (selected.isNotEmpty()) {
                    Text("Показывать (порядок):", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    selected.toList().forEachIndexed { index, name ->
                        val pid = ObdPid.entries.firstOrNull { it.name == name }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = true, onCheckedChange = { selected.remove(name) })
                            Text(pid?.label ?: name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            IconButton(
                                onClick = { if (index > 0) { val t = selected[index - 1]; selected[index - 1] = selected[index]; selected[index] = t } },
                                enabled = index > 0,
                            ) { Icon(Icons.Rounded.KeyboardArrowUp, "выше") }
                            IconButton(
                                onClick = { if (index < selected.size - 1) { val t = selected[index + 1]; selected[index + 1] = selected[index]; selected[index] = t } },
                                enabled = index < selected.size - 1,
                            ) { Icon(Icons.Rounded.KeyboardArrowDown, "ниже") }
                        }
                    }
                    HorizontalDivider(color = cs.outlineVariant, modifier = Modifier.padding(vertical = 8.dp))
                }
                Text("Добавить:", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                ObdPid.entries.filter { it.name !in selected }.forEach { pid ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = false, onCheckedChange = { if (it) selected.add(pid.name) })
                        Text(pid.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
    )
}

@Composable
private fun MetricsSection(
    title: String,
    pids: List<ObdPid>,
    metrics: Map<ObdPid, Double>,
    history: Map<ObdPid, List<MetricSample>>,
    onTap: (ObdPid) -> Unit,
) {
    if (pids.isEmpty()) return // все параметры секции не поддерживаются — не показываем заголовок
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
                rowPids.forEach { pid ->
                    MetricCard(pid, metrics[pid], history[pid].orEmpty(), onTap, Modifier.weight(1f))
                }
                if (rowPids.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MetricCard(
    pid: ObdPid,
    value: Double?,
    samples: List<MetricSample>,
    onTap: (ObdPid) -> Unit,
    modifier: Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val status = metricStatus(pid, value)
    val statusColor = statusColor(status)
    val hasValue = value != null
    val fraction = ((value ?: 0.0) / pid.gaugeMax).toFloat().coerceIn(0f, 1f)
    ElevatedCard(
        onClick = { onTap(pid) },
        modifier = modifier
            .heightIn(min = 104.dp)
            .semantics { stateDescription = if (hasValue) statusText(status) else "нет данных" },
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
                    Icon(
                        statusIcon(status),
                        null,
                        Modifier.size(14.dp),
                        tint = if (hasValue && status != MetricStatus.NORMAL) statusColor else cs.outline,
                    )
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
                // Мини-статистика окна: min/среднее/max (та же логика, что у monitor_pid).
                if (samples.size >= 3) {
                    Spacer(Modifier.height(8.dp))
                    val vals = samples.map { it.value }
                    Text(
                        "мин ${formatMetric(vals.min())} · сред ${formatMetric(vals.average())} · " +
                            "макс ${formatMetric(vals.max())}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
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

    ElevatedCard(
        onClick = { onTap(pid) },
        modifier = modifier.semantics {
            stateDescription = value?.let { "${pid.label} ${formatMetric(it)} ${pid.unit}, ${statusText(status)}" }
                ?: "${pid.label}: нет данных"
        },
        shape = RoundedCornerShape(20.dp),
    ) {
        Box(
            Modifier.padding(14.dp).fillMaxWidth().aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize().padding(6.dp)) {
                val strokeW = 13.dp.toPx()
                val stroke = Stroke(width = strokeW, cap = StrokeCap.Round)
                // Инсетим овал на половину толщины: центральная линия дуги ложится на
                // радиус (minDimension − strokeW)/2 и не обрезается краем — тогда
                // светящаяся точка на конце ровно по ней, без съезда наружу/внутрь.
                val inset = strokeW / 2f
                val arcTopLeft = Offset(inset, inset)
                val arcSize = Size(size.width - strokeW, size.height - strokeW)
                drawArc(trackColor, 135f, 270f, useCenter = false, style = stroke, topLeft = arcTopLeft, size = arcSize)
                if (value != null) {
                    drawArc(arcBrush, 135f, 270f * fraction, useCenter = false, style = stroke, topLeft = arcTopLeft, size = arcSize)
                    // Светящаяся точка на конце заполненной дуги — по центральной линии.
                    val angle = Math.toRadians((135f + 270f * fraction).toDouble())
                    val radius = (size.minDimension - strokeW) / 2f
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
internal fun metricStatus(pid: ObdPid, v: Double?): MetricStatus {
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
internal fun statusColor(status: MetricStatus): Color = when (status) {
    MetricStatus.NORMAL -> StatusGood
    MetricStatus.WARNING -> MaterialTheme.colorScheme.tertiary
    MetricStatus.CRITICAL -> MaterialTheme.colorScheme.error
}

/** Текст состояния — для скринридера и как не-цветовой признак (WCAG 1.4.1). */
internal fun statusText(status: MetricStatus): String = when (status) {
    MetricStatus.NORMAL -> "норма"
    MetricStatus.WARNING -> "внимание"
    MetricStatus.CRITICAL -> "критично"
}

/** Иконка состояния — форма отличает статус независимо от цвета. */
private fun statusIcon(status: MetricStatus): ImageVector = when (status) {
    MetricStatus.NORMAL -> Icons.Rounded.Info
    MetricStatus.WARNING -> Icons.Rounded.Warning
    MetricStatus.CRITICAL -> Icons.Rounded.Error
}

@Composable
internal fun NormBox(caption: String, value: String, valueColor: Color, modifier: Modifier) {
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

internal fun metricIcon(pid: ObdPid): ImageVector = when (pid) {
    ObdPid.RPM, ObdPid.SPEED -> Icons.Rounded.Speed
    ObdPid.COOLANT -> Icons.Rounded.Thermostat
    ObdPid.INTAKE_TEMP -> Icons.Rounded.DeviceThermostat
    ObdPid.ENGINE_LOAD -> Icons.Rounded.DataUsage
    ObdPid.MAF -> Icons.Rounded.Air
    ObdPid.MAP -> Icons.Rounded.Compress
    ObdPid.THROTTLE -> Icons.Rounded.Tune
    ObdPid.STFT, ObdPid.LTFT, ObdPid.FUEL_LEVEL -> Icons.Rounded.LocalGasStation
    ObdPid.O2_LAMBDA -> Icons.Rounded.Science
    ObdPid.TIMING -> Icons.Rounded.Timer
    ObdPid.VOLTAGE -> Icons.Rounded.Bolt
}

internal fun formatMetric(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)
