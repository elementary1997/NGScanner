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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

/** Готовая связка параметров для наложенного графика. */
private data class Combo(val title: String, val pids: List<ObdPid>)

private val COMBOS = listOf(
    Combo("Обороты · нагрузка · дроссель", listOf(ObdPid.RPM, ObdPid.ENGINE_LOAD, ObdPid.THROTTLE)),
    Combo("Топливо: STFT · LTFT · MAF", listOf(ObdPid.STFT, ObdPid.LTFT, ObdPid.MAF)),
    Combo("Температуры: ОЖ · впуск", listOf(ObdPid.COOLANT, ObdPid.INTAKE_TEMP)),
)

/** Цвета серий комбо-графика (отличимые). */
private val COMBO_COLORS = listOf(
    Color(0xFF57C4E5), Color(0xFFF2A65A), Color(0xFFB388FF), Color(0xFFAED581),
)

/**
 * Панель обзора трендов: связки-комбо, мини-графики выбранных параметров и вход
 * в «Поездки и события». Набор параметров настраивается; тап по графику
 * разворачивает его в полноэкранный (для комбо — с предналожением).
 */
@Composable
internal fun GraphsPanel(
    metrics: Map<ObdPid, Double>,
    history: Map<ObdPid, List<MetricSample>>,
    graphPids: List<String>,
    recording: Boolean,
    onOpenGraph: (ObdPid) -> Unit,
    onOpenCombo: (List<ObdPid>) -> Unit,
    onSetGraphPids: (List<String>) -> Unit,
    onOpenTrips: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var showConfig by remember { mutableStateOf(false) }

    // Выбор параметров: пусто = все; иначе только отмеченные, в порядке каталога.
    val chosen = if (graphPids.isEmpty()) {
        ObdPid.entries.toList()
    } else {
        ObdPid.entries.filter { it.name in graphPids }
    }
    val pids = chosen.filter { (history[it]?.size ?: 0) >= 2 }
    val combos = COMBOS.filter { c -> c.pids.count { (history[it]?.size ?: 0) >= 2 } >= 2 }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("ТРЕНДЫ ПАРАМЕТРОВ", Modifier.weight(1f))
            if (recording) RecordingChip()
            TextButton(onClick = { showConfig = true }) {
                Icon(Icons.Rounded.Tune, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Параметры")
            }
        }

        if (pids.isEmpty() && combos.isEmpty()) {
            InfoCard(
                Icons.Rounded.ShowChart,
                "Графики появляются по мере опроса — история копится на лету. Подождите " +
                    "несколько секунд после подключения, и здесь будут тренды параметров.",
            )
        }

        // Комбо-связки: несколько параметров на одной оси времени.
        if (combos.isNotEmpty()) {
            SectionLabel("СВЯЗКИ", Modifier)
            combos.forEach { combo -> ComboCard(combo, history, onOpenCombo) }
        }

        // Отдельные мини-графики параметров, по две колонки.
        if (pids.isNotEmpty()) {
            pids.chunked(2).forEach { rowPids ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowPids.forEach { pid ->
                        MiniGraphCard(pid, metrics[pid], history[pid].orEmpty(), onOpenGraph, Modifier.weight(1f))
                    }
                    if (rowPids.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        OutlinedButton(
            onClick = onOpenTrips,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Rounded.History, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Поездки и события")
        }
        Spacer(Modifier.height(16.dp))
    }

    if (showConfig) {
        GraphPidsDialog(
            selected = if (graphPids.isEmpty()) ObdPid.entries.map { it.name }.toSet() else graphPids.toSet(),
            onDismiss = { showConfig = false },
            onSave = { names ->
                // Все выбраны → сохраняем пустой список (= «показывать все»).
                onSetGraphPids(if (names.size == ObdPid.entries.size) emptyList() else names)
                showConfig = false
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.6.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RecordingChip() {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(cs.error))
        Spacer(Modifier.width(5.dp))
        Text("запись", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun ComboCard(combo: Combo, history: Map<ObdPid, List<MetricSample>>, onOpen: (List<ObdPid>) -> Unit) {
    val cs = MaterialTheme.colorScheme
    ElevatedCard(
        onClick = { onOpen(combo.pids) },
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Timeline, null, Modifier.size(16.dp), tint = cs.primary)
                Spacer(Modifier.width(6.dp))
                Text(combo.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            // Легенда серий с цветами.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                combo.pids.forEachIndexed { i, p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(COMBO_COLORS[i % COMBO_COLORS.size]))
                        Spacer(Modifier.width(4.dp))
                        Text(p.label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            ComboChart(combo, history, Modifier.fillMaxWidth().height(64.dp))
        }
    }
}

/** Наложенные нормированные серии комбо (сравнение по форме, без единиц). */
@Composable
private fun ComboChart(combo: Combo, history: Map<ObdPid, List<MetricSample>>, modifier: Modifier) {
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
    val series = combo.pids.map { it to history[it].orEmpty() }.filter { it.second.size >= 2 }
    if (series.isEmpty()) {
        Canvas(modifier) { drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1f) }
        return
    }
    val tMin = series.minOf { it.second.first().tMs }
    val tMax = series.maxOf { it.second.last().tMs }
    val tSpan = (tMax - tMin).coerceAtLeast(1L).toDouble()
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        fun xAt(t: Long) = (((t - tMin).toDouble()) / tSpan).toFloat() * w
        series.forEachIndexed { idx, (pid, samples) ->
            val lo = samples.minOf { it.value }
            val hi = samples.maxOf { it.value }
            val span = (hi - lo).takeIf { it > 1e-6 } ?: 1.0
            val path = Path()
            samples.forEachIndexed { i, s ->
                val x = xAt(s.tMs)
                val norm = ((s.value - lo) / span).toFloat()
                val y = h - (norm * h * 0.9f) - h * 0.05f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, COMBO_COLORS[idx % COMBO_COLORS.size], style = Stroke(width = 2f, cap = StrokeCap.Round))
        }
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
internal fun MiniTrendChart(pid: ObdPid, samples: List<MetricSample>, color: Color, modifier: Modifier) {
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
    if (samples.size < 2) {
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

/** Диалог выбора параметров для панели графиков. */
@Composable
private fun GraphPidsDialog(
    selected: Set<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    var chosen by remember { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Параметры на панели") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                ObdPid.entries.forEach { pid ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            chosen = if (pid.name in chosen) chosen - pid.name else chosen + pid.name
                        }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = pid.name in chosen, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(pid.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(chosen.toList().ifEmpty { ObdPid.entries.map { it.name } }) }) {
                Text("Готово")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
