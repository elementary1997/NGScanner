@file:OptIn(ExperimentalLayoutApi::class)

package ru.ngscanner.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.ngscanner.obd.ObdPid
import ru.ngscanner.ui.MetricSample
import ru.ngscanner.ui.theme.GaugeRed

/** Одна наложенная серия на графике: параметр, его точки, цвет линии. */
private data class OverlaySeries(val pid: ObdPid, val samples: List<MetricSample>, val color: Color)

/** Палитра для наложенных параметров (отличимые от основной линии цвета). */
private val OVERLAY_COLORS = listOf(
    Color(0xFFF2A65A), Color(0xFF57C4E5), Color(0xFFB388FF),
    Color(0xFF80CBC4), Color(0xFFFF8A80), Color(0xFFAED581),
)

/**
 * Полноэкранный график параметра: линия с осью времени, пунктирными линиями
 * порогов (warn/crit), статистикой окна (мин/сред/макс/разброс) и возможностью
 * наложить другие параметры на ту же ось времени — чтобы видеть взаимосвязь
 * (например, скачет ли топливная коррекция именно на разгоне).
 */
@Composable
internal fun ParameterGraphScreen(
    pid: ObdPid,
    value: Double?,
    history: Map<ObdPid, List<MetricSample>>,
    onBack: () -> Unit,
    modelNorm: String?,
    loading: Boolean,
    onRequestNorm: (ObdPid) -> Unit,
    activeCarTitle: String?,
) {
    val cs = MaterialTheme.colorScheme
    val samples = history[pid].orEmpty()
    val status = metricStatus(pid, value)
    val primaryColor = if (status == MetricStatus.NORMAL) cs.primary else statusColor(status)

    // Параметры, доступные для наложения: те, у кого есть история, кроме основного.
    val available = remember(history) {
        history.filter { it.key != pid && it.value.size >= 2 }.keys.sortedBy { it.ordinal }
    }
    var selected by rememberSaveable { mutableStateOf(setOf<String>()) }
    val overlays = available
        .filter { it.name in selected }
        .map { OverlaySeries(it, history[it].orEmpty(), OVERLAY_COLORS[available.indexOf(it) % OVERLAY_COLORS.size]) }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(pid.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "PID ${pid.cmd}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = cs.onSurfaceVariant,
                )
            }
            Text(
                value?.let { "${formatMetric(it)} ${pid.unit}" } ?: "нет данных",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = if (value == null || status == MetricStatus.NORMAL) cs.onSurface else primaryColor,
            )
        }

        if (samples.size >= 2) {
            val yRange = yRangeFor(pid, samples)
            val durationSec = ((samples.last().tMs - samples.first().tMs) / 1000L).toInt()
            Row(Modifier.fillMaxWidth().height(220.dp)) {
                // Ось значений: max сверху, min снизу.
                Column(
                    Modifier.fillMaxSize().width(44.dp).padding(vertical = 2.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    AxisLabel(formatMetric(yRange.second))
                    AxisLabel(formatMetric((yRange.first + yRange.second) / 2))
                    AxisLabel(formatMetric(yRange.first))
                }
                Spacer(Modifier.width(6.dp))
                TimeSeriesChart(pid, samples, primaryColor, overlays, yRange, Modifier.weight(1f).fillMaxSize())
            }
            // Ось времени.
            Row(Modifier.fillMaxWidth().padding(start = 50.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                AxisLabel(if (durationSec >= 120) "−${durationSec / 60} мин" else "−$durationSec с")
                AxisLabel("сейчас")
            }

            StatsRow(samples, pid)

            if (available.isNotEmpty()) {
                Text(
                    "НАЛОЖИТЬ ПАРАМЕТР",
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.sp,
                    color = cs.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    available.forEach { p ->
                        val on = p.name in selected
                        val dot = OVERLAY_COLORS[available.indexOf(p) % OVERLAY_COLORS.size]
                        FilterChip(
                            selected = on,
                            onClick = { selected = if (on) selected - p.name else selected + p.name },
                            label = { Text(p.label) },
                            leadingIcon = if (on) {
                                { Box(Modifier.size(10.dp).clip(CircleShape).background(dot)) }
                            } else {
                                null
                            },
                            colors = FilterChipDefaults.filterChipColors(),
                        )
                    }
                }
            }
        } else {
            Surface(shape = RoundedCornerShape(16.dp), color = cs.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Недостаточно данных для графика — подождите несколько секунд опроса.",
                    Modifier.padding(20.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            }
        }

        Text(pid.about, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, lineHeight = 21.sp)

        NormBox("Норма (общая)", pid.norm, cs.onSurface, Modifier.fillMaxWidth())
        when {
            modelNorm != null -> NormBox("Для вашей машины ★", modelNorm, cs.primary, Modifier.fillMaxWidth())
            loading -> Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Узнаю норму для вашей машины…", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
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
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun AxisLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

@Composable
private fun StatsRow(samples: List<MetricSample>, pid: ObdPid) {
    val vals = samples.map { it.value }
    val min = vals.min()
    val max = vals.max()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Stat("мин", "${formatMetric(min)} ${pid.unit}")
        Stat("сред", "${formatMetric(vals.average())} ${pid.unit}")
        Stat("макс", "${formatMetric(max)} ${pid.unit}")
        Stat("разброс", "${formatMetric(max - min)} ${pid.unit}")
    }
}

@Composable
private fun Stat(caption: String, value: String) {
    val cs = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(caption.uppercase(), style = MaterialTheme.typography.labelSmall, letterSpacing = 0.8.sp, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(3.dp))
        Text(value, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    }
}

/** Диапазон оси значений: данные, расширенные до порогов, с небольшим запасом. */
private fun yRangeFor(pid: ObdPid, samples: List<MetricSample>): Pair<Double, Double> {
    var lo = samples.minOf { it.value }
    var hi = samples.maxOf { it.value }
    listOfNotNull(pid.warnLow, pid.critLow).forEach { lo = minOf(lo, it) }
    listOfNotNull(pid.warnHigh, pid.critHigh).forEach { hi = maxOf(hi, it) }
    if (hi - lo < 1e-6) hi = lo + 1.0
    val pad = (hi - lo) * 0.08
    return (lo - pad) to (hi + pad)
}

/**
 * Линейный график по времени: сетка, пунктирные пороги, наложенные серии
 * (нормируются к своему диапазону — сравнение по форме) и основная линия с
 * заливкой. Ось X — реальное время из [MetricSample.tMs].
 */
@Composable
private fun TimeSeriesChart(
    primaryPid: ObdPid,
    primary: List<MetricSample>,
    primaryColor: Color,
    overlays: List<OverlaySeries>,
    yRange: Pair<Double, Double>,
    modifier: Modifier,
) {
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
    val allSeries = listOf(primary) + overlays.map { it.samples }
    val tMin = allSeries.minOf { it.first().tMs }
    val tMax = allSeries.maxOf { it.last().tMs }
    val tSpan = (tMax - tMin).coerceAtLeast(1L).toDouble()
    val (yMin, yMax) = yRange
    val ySpan = (yMax - yMin).coerceAtLeast(1e-6)

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        fun xAt(t: Long) = (((t - tMin).toDouble()) / tSpan).toFloat() * w
        fun yPrimary(v: Double) = h - (((v - yMin) / ySpan).toFloat()) * h

        // Сетка.
        for (i in 0..4) {
            val y = h * i / 4f
            drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        // Пороги пунктиром.
        val dash = PathEffect.dashPathEffect(floatArrayOf(9f, 9f))
        fun threshold(v: Double?, color: Color) {
            if (v == null || v < yMin || v > yMax) return
            val y = yPrimary(v)
            drawLine(color.copy(alpha = 0.6f), Offset(0f, y), Offset(w, y), strokeWidth = 1.5.dp.toPx(), pathEffect = dash)
        }
        threshold(primaryPid.critHigh, GaugeRed)
        threshold(primaryPid.warnHigh, Color(0xFFF2A65A))
        threshold(primaryPid.warnLow, Color(0xFFF2A65A))
        threshold(primaryPid.critLow, GaugeRed)

        // Наложенные серии — нормируются к своему диапазону (форма, без единиц).
        overlays.forEach { ov ->
            val vLo = ov.samples.minOf { it.value }
            val vHi = ov.samples.maxOf { it.value }
            val span = (vHi - vLo).takeIf { it > 1e-6 } ?: 1.0
            val path = Path()
            ov.samples.forEachIndexed { i, s ->
                val x = xAt(s.tMs)
                val norm = ((s.value - vLo) / span).toFloat()
                val y = h - (norm * h * 0.9f) - h * 0.05f // отступ 5% сверху/снизу
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, ov.color.copy(alpha = 0.85f), style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round))
        }

        // Основная линия с заливкой.
        val line = Path()
        val area = Path().apply { moveTo(xAt(primary.first().tMs), h) }
        primary.forEachIndexed { i, s ->
            val x = xAt(s.tMs)
            val y = yPrimary(s.value)
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
            area.lineTo(x, y)
        }
        area.lineTo(xAt(primary.last().tMs), h)
        area.close()
        drawPath(area, primaryColor.copy(alpha = 0.12f))
        drawPath(line, primaryColor, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(primaryColor, 3.5.dp.toPx(), Offset(xAt(primary.last().tMs), yPrimary(primary.last().value)))
    }
}
