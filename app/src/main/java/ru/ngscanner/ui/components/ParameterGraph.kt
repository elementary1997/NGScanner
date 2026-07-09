@file:OptIn(ExperimentalLayoutApi::class)

package ru.ngscanner.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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
 * Полноэкранный график параметра: крупная линия с осями времени и значений,
 * пунктирными порогами, статистикой окна и возможностью наложить другие
 * параметры на ту же ось времени. Открывается по тапу с приборов; для комбо-
 * связки приходит предвыбранный набор [initialOverlay] — тогда это «редактор»
 * графика, где серии сразу наложены и их можно менять.
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
    initialOverlay: Set<String> = emptySet(),
) {
    val cs = MaterialTheme.colorScheme
    val samples = history[pid].orEmpty()
    val status = metricStatus(pid, value)
    val primaryColor = if (status == MetricStatus.NORMAL) cs.primary else statusColor(status)
    val isCombo = initialOverlay.isNotEmpty()

    // Параметры, доступные для наложения: те, у кого есть история, кроме основного.
    val available = remember(history) {
        history.filter { it.key != pid && it.value.size >= 2 }.keys.sortedBy { it.ordinal }
    }
    var selected by remember { mutableStateOf(initialOverlay) }
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
                Text(
                    if (isCombo) "Связка параметров" else pid.label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (isCombo) "основной: ${pid.label}" else "PID ${pid.cmd}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = if (isCombo) FontFamily.Default else FontFamily.Monospace,
                    color = cs.onSurfaceVariant,
                )
            }
            if (!isCombo) {
                Text(
                    value?.let { "${formatMetric(it)} ${pid.unit}" } ?: "нет данных",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = if (value == null || status == MetricStatus.NORMAL) cs.onSurface else primaryColor,
                )
            }
        }

        if (samples.size >= 2) {
            val yRange = yRangeFor(pid, samples)
            val durationSec = ((samples.last().tMs - samples.first().tMs) / 1000L).toInt()

            // График в карточке: ось значений слева, поле графика, ось времени снизу.
            ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(Modifier.fillMaxWidth().height(230.dp)) {
                        Column(
                            Modifier.fillMaxSize().width(46.dp).padding(vertical = 2.dp),
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            AxisLabel(formatMetric(yRange.second))
                            AxisLabel(formatMetric((yRange.first * 0.25 + yRange.second * 0.75)))
                            AxisLabel(formatMetric((yRange.first + yRange.second) / 2))
                            AxisLabel(formatMetric((yRange.first * 0.75 + yRange.second * 0.25)))
                            AxisLabel(formatMetric(yRange.first))
                        }
                        Spacer(Modifier.width(8.dp))
                        TimeSeriesChart(pid, samples, primaryColor, overlays, yRange, Modifier.weight(1f).fillMaxSize())
                    }
                    Row(Modifier.fillMaxWidth().padding(start = 54.dp, top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        AxisLabel(if (durationSec >= 120) "−${durationSec / 60} мин" else "−$durationSec с")
                        AxisLabel("сейчас")
                    }
                }
            }

            // Легенда серий с цветами и текущими значениями — понятно, что где.
            LegendRow(pid, value, primaryColor, overlays)

            // Подпись порогов, если заданы (чтобы пунктир читался).
            ThresholdLegend(pid)

            StatsRow(samples, pid)

            if (available.isNotEmpty()) {
                Text(
                    if (isCombo) "ПАРАМЕТРЫ СВЯЗКИ" else "НАЛОЖИТЬ ПАРАМЕТР",
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
                if (overlays.isNotEmpty()) {
                    Text(
                        "Наложенные линии нормированы по форме (свой масштаб) — сравнивайте " +
                            "поведение во времени, а не абсолютные значения.",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                    )
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

/** Легенда: основная серия и наложенные — цвет, имя, текущее значение. */
@Composable
private fun LegendRow(pid: ObdPid, value: Double?, primaryColor: Color, overlays: List<OverlaySeries>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LegendItem(primaryColor, pid.label, value?.let { "${formatMetric(it)} ${pid.unit}" } ?: "—")
        overlays.forEach { ov ->
            val last = ov.samples.lastOrNull()?.value
            LegendItem(ov.color, ov.pid.label, last?.let { "${formatMetric(it)} ${ov.pid.unit}" } ?: "—")
        }
    }
}

@Composable
private fun LegendItem(color: Color, name: String, value: String) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(name, style = MaterialTheme.typography.labelMedium, color = cs.onSurface)
        Spacer(Modifier.width(5.dp))
        Text(value, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant)
    }
}

/** Подпись пороговых линий (пунктир на графике) — чтобы было ясно, что это. */
@Composable
private fun ThresholdLegend(pid: ObdPid) {
    val cs = MaterialTheme.colorScheme
    val parts = buildList {
        if (pid.critHigh != null || pid.critLow != null) add(GaugeRed to "критично")
        if (pid.warnHigh != null || pid.warnLow != null) add(Color(0xFFF2A65A) to "внимание")
    }
    if (parts.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        parts.forEach { (c, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 14.dp, height = 2.dp).background(c.copy(alpha = 0.7f)))
                Spacer(Modifier.width(6.dp))
                Text("порог: $label", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatPill("мин", "${formatMetric(min)} ${pid.unit}", Modifier.weight(1f))
        StatPill("сред", "${formatMetric(vals.average())} ${pid.unit}", Modifier.weight(1f))
        StatPill("макс", "${formatMetric(max)} ${pid.unit}", Modifier.weight(1f))
        StatPill("разброс", "${formatMetric(max - min)} ${pid.unit}", Modifier.weight(1f))
    }
}

@Composable
private fun StatPill(caption: String, value: String, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(12.dp), color = cs.surfaceVariant.copy(alpha = 0.5f), modifier = modifier) {
        Column(Modifier.padding(vertical = 10.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(caption.uppercase(), style = MaterialTheme.typography.labelSmall, letterSpacing = 0.6.sp, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(3.dp))
            Text(
                value,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

/** Диапазон оси значений: данные, расширенные до порогов, с небольшим запасом. */
internal fun yRangeFor(pid: ObdPid, samples: List<MetricSample>): Pair<Double, Double> {
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
 * градиентной заливкой. Ось X — реальное время из [MetricSample.tMs].
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
    val fill = Brush.verticalGradient(listOf(primaryColor.copy(alpha = 0.22f), primaryColor.copy(alpha = 0.02f)))

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        fun xAt(t: Long) = (((t - tMin).toDouble()) / tSpan).toFloat() * w
        fun yPrimary(v: Double) = h - (((v - yMin) / ySpan).toFloat()) * h

        // Сетка: горизонтали и несколько вертикалей.
        for (i in 0..4) {
            val y = h * i / 4f
            drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        for (i in 1..3) {
            val x = w * i / 4f
            drawLine(grid.copy(alpha = 0.12f), Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
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
                val y = h - (norm * h * 0.9f) - h * 0.05f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, ov.color.copy(alpha = 0.9f), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }

        // Основная линия с градиентной заливкой.
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
        drawPath(area, fill)
        drawPath(line, primaryColor, style = Stroke(width = 2.8.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(primaryColor, 4.dp.toPx(), Offset(xAt(primary.last().tMs), yPrimary(primary.last().value)))
        drawCircle(Color(0xFFEAFFFB), 1.6.dp.toPx(), Offset(xAt(primary.last().tMs), yPrimary(primary.last().value)))
    }
}
