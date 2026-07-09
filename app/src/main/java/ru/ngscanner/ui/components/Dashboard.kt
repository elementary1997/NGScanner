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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.draw.clip
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private enum class MetricStatus { NORMAL, WARNING, CRITICAL }

@Composable
internal fun Dashboard(
    metrics: Map<ObdPid, Double>,
    history: Map<ObdPid, List<Double>>,
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
            listOf(
                ObdPid.THROTTLE, ObdPid.MAF, ObdPid.MAP, ObdPid.INTAKE_TEMP,
                ObdPid.STFT, ObdPid.LTFT, ObdPid.O2_LAMBDA, ObdPid.FUEL_LEVEL,
            ),
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
            history = history[pid].orEmpty(),
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

/** Текст состояния — для скринридера и как не-цветовой признак (WCAG 1.4.1). */
private fun statusText(status: MetricStatus): String = when (status) {
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

/** Шторка с описанием параметра и нормами (открывается по тапу на прибор/карточку). */
@Composable
private fun MetricInfoSheet(
    pid: ObdPid,
    value: Double?,
    history: List<Double>,
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
            if (history.size >= 2) {
                Spacer(Modifier.height(18.dp))
                Text(
                    "ТРЕНД · ПОСЛЕДНИЕ ${history.size}",
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.sp,
                    color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Sparkline(
                    values = history,
                    color = if (status == MetricStatus.NORMAL) cs.primary else statusColor,
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                )
            }
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

/** Мини-график тренда (sparkline) по истории значений параметра. */
@Composable
private fun Sparkline(values: List<Double>, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val min = values.min()
        val max = values.max()
        val range = (max - min).takeIf { it > 0.0001 } ?: 1.0
        val stepX = size.width / (values.size - 1)
        fun yAt(v: Double) = size.height - ((v - min) / range).toFloat() * size.height
        val line = Path()
        val area = Path().apply { moveTo(0f, size.height) }
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = yAt(v)
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
            area.lineTo(x, y)
        }
        area.lineTo(size.width, size.height)
        area.close()
        drawPath(area, color.copy(alpha = 0.15f))
        drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(color, 3.dp.toPx(), Offset((values.size - 1) * stepX, yAt(values.last())))
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
    ObdPid.STFT, ObdPid.LTFT, ObdPid.FUEL_LEVEL -> Icons.Rounded.LocalGasStation
    ObdPid.O2_LAMBDA -> Icons.Rounded.Science
    ObdPid.TIMING -> Icons.Rounded.Timer
    ObdPid.VOLTAGE -> Icons.Rounded.Bolt
}

private fun formatMetric(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)
