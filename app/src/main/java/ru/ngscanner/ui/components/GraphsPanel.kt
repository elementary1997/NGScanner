package ru.ngscanner.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/**
 * Панель обзора трендов: мини-графики всех параметров, у которых накопилась
 * история. Каждый — компактная линия с порогами и текущим значением; тап
 * разворачивает параметр в полноэкранный график (та же навигация, что у дашборда).
 * Пока история копится (первые секунды опроса) — показываем подсказку.
 */
@Composable
internal fun GraphsPanel(
    metrics: Map<ObdPid, Double>,
    history: Map<ObdPid, List<MetricSample>>,
    onOpenGraph: (ObdPid) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // Параметры с достаточной историей — в порядке каталога PID.
    val pids = ObdPid.entries.filter { (history[it]?.size ?: 0) >= 2 }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        if (pids.isEmpty()) {
            InfoCard(
                Icons.Rounded.ShowChart,
                "Графики появляются по мере опроса — история копится на лету. Подождите " +
                    "несколько секунд после подключения, и здесь будут тренды всех параметров.",
            )
        } else {
            Text(
                "ТРЕНДЫ ПАРАМЕТРОВ",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.6.sp,
                color = cs.onSurfaceVariant,
            )
            // Две колонки мини-графиков.
            pids.chunked(2).forEach { rowPids ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowPids.forEach { pid ->
                        MiniGraphCard(pid, metrics[pid], history[pid].orEmpty(), onOpenGraph, Modifier.weight(1f))
                    }
                    if (rowPids.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Text(
                "Нажмите на график, чтобы открыть его во весь экран, наложить другие параметры " +
                    "и увидеть норму для вашей машины.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun MiniGraphCard(
    pid: ObdPid,
    value: Double?,
    samples: List<MetricSample>,
    onOpen: (ObdPid) -> Unit,
    modifier: Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val status = metricStatus(pid, value)
    val color = if (value == null || status == MetricStatus.NORMAL) cs.primary else statusColor(status)
    ElevatedCard(
        onClick = { onOpen(pid) },
        shape = RoundedCornerShape(16.dp),
        modifier = modifier,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(metricIcon(pid), null, Modifier.size(15.dp), tint = if (value == null) cs.onSurfaceVariant else color)
                Spacer(Modifier.width(6.dp))
                Text(
                    pid.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value?.let { formatMetric(it) } ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = if (value == null || status == MetricStatus.NORMAL) cs.onSurface else color,
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    pid.unit,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            MiniTrendChart(pid, samples, color, Modifier.fillMaxWidth().height(52.dp))
        }
    }
}

/** Компактная линия тренда с заливкой и тонкими пунктирными порогами (без осей). */
@Composable
private fun MiniTrendChart(pid: ObdPid, samples: List<MetricSample>, color: Color, modifier: Modifier) {
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
    if (samples.size < 2) {
        // Данных пока нет — просто пустое поле с базовой линией.
        Canvas(modifier) { drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1f) }
        return
    }
    val (yMin, yMax) = yRangeFor(pid, samples)
    val ySpan = (yMax - yMin).coerceAtLeast(1e-6)
    val tMin = samples.first().tMs
    val tSpan = (samples.last().tMs - tMin).coerceAtLeast(1L).toDouble()
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        fun xAt(t: Long) = (((t - tMin).toDouble()) / tSpan).toFloat() * w
        fun yAt(v: Double) = h - (((v - yMin) / ySpan).toFloat()) * h

        // Пороги тонким пунктиром — чтобы отклонение читалось без чисел.
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
        fun threshold(v: Double?, c: Color) {
            if (v == null || v < yMin || v > yMax) return
            drawLine(c.copy(alpha = 0.5f), Offset(0f, yAt(v)), Offset(w, yAt(v)), 1f, pathEffect = dash)
        }
        threshold(pid.critHigh, ru.ngscanner.ui.theme.GaugeRed)
        threshold(pid.warnHigh, Color(0xFFF2A65A))
        threshold(pid.warnLow, Color(0xFFF2A65A))
        threshold(pid.critLow, ru.ngscanner.ui.theme.GaugeRed)

        val line = Path()
        val area = Path().apply { moveTo(xAt(tMin), h) }
        samples.forEachIndexed { i, s ->
            val x = xAt(s.tMs)
            val y = yAt(s.value)
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
            area.lineTo(x, y)
        }
        area.lineTo(xAt(samples.last().tMs), h)
        area.close()
        drawPath(area, color.copy(alpha = 0.12f))
        drawPath(line, color, style = Stroke(width = 2f, cap = StrokeCap.Round))
        drawCircle(color, 3f, Offset(xAt(samples.last().tMs), yAt(samples.last().value)))
    }
}
