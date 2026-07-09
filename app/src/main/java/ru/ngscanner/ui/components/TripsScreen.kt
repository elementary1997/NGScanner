package ru.ngscanner.ui.components

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.ngscanner.obd.ObdPid
import ru.ngscanner.trips.Trip
import ru.ngscanner.trips.TripKind
import ru.ngscanner.trips.TripMeta
import ru.ngscanner.ui.MetricSample
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экран записей: список поездок и событий «чёрного ящика». Тап открывает запись
 * с графиками всех параметров; иконки — экспорт в CSV и удаление. Просмотр одной
 * записи живёт во внутреннем состоянии (список ↔ запись), как график на «Приборах».
 */
@Composable
internal fun TripsScreen(
    metas: List<TripMeta>,
    onBack: () -> Unit,
    onDelete: (String) -> Unit,
    onExport: (String) -> Unit,
    loadTrip: suspend (String) -> Trip?,
) {
    var viewing by remember { mutableStateOf<String?>(null) }
    val openId = viewing
    if (openId != null) {
        BackHandler { viewing = null }
        TripView(openId, onBack = { viewing = null }, onExport = onExport, loadTrip = loadTrip)
        return
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        TripsTopBar("Поездки и события", onBack)
        if (metas.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InfoCard(
                    Icons.Rounded.DirectionsCar,
                    "Записей пока нет. Поездка записывается автоматически, пока подключён адаптер, " +
                        "и сохраняется при отключении. События фиксируются, когда параметр уходит " +
                        "в критическую зону (сохраняется окно ±30 с вокруг момента).",
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(metas, key = { it.id }) { meta ->
                    TripRow(meta, onOpen = { viewing = meta.id }, onExport = { onExport(meta.id) }, onDelete = { onDelete(meta.id) })
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun TripRow(meta: TripMeta, onOpen: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val isEvent = meta.kind == TripKind.EVENT
    ElevatedCard(onClick = onOpen, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (isEvent) Icons.Rounded.Warning else Icons.Rounded.DirectionsCar,
                null,
                Modifier.size(22.dp),
                tint = if (isEvent) cs.error else cs.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (isEvent) (meta.trigger ?: "Событие") else "Поездка",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    "${formatDateTime(meta.startMs)} · ${formatDuration(meta.durationSec)} · ${meta.sampleCount} точек",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
                meta.carTitle?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
            }
            IconButton(onClick = onExport) { Icon(Icons.Rounded.IosShare, "Экспорт CSV", Modifier.size(20.dp)) }
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.DeleteOutline, "Удалить", Modifier.size(20.dp), tint = cs.error) }
        }
    }
}

@Composable
private fun TripView(
    id: String,
    onBack: () -> Unit,
    onExport: (String) -> Unit,
    loadTrip: suspend (String) -> Trip?,
) {
    val cs = MaterialTheme.colorScheme
    var trip by remember(id) { mutableStateOf<Trip?>(null) }
    var loading by remember(id) { mutableStateOf(true) }
    LaunchedEffect(id) {
        trip = loadTrip(id)
        loading = false
    }
    Column(Modifier.fillMaxSize()) {
        val t = trip
        TripsTopBar(
            if (t?.kind == TripKind.EVENT) "Событие" else "Поездка",
            onBack,
            action = { IconButton(onClick = { onExport(id) }) { Icon(Icons.Rounded.IosShare, "Экспорт CSV") } },
        )
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                loading -> Text("Загрузка…", Modifier.padding(top = 24.dp), color = cs.onSurfaceVariant)
                t == null -> Text("Запись не найдена.", Modifier.padding(top = 24.dp), color = cs.onSurfaceVariant)
                else -> {
                    t.trigger?.let {
                        Surface(shape = RoundedCornerShape(12.dp), color = cs.errorContainer, modifier = Modifier.fillMaxWidth()) {
                            Text(it, Modifier.padding(12.dp), color = cs.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Text(
                        "${formatDateTime(t.startMs)} · ${formatDuration((t.endMs - t.startMs) / 1000)} · ${t.samples.size} точек",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                    val pids = t.pids.mapNotNull { runCatching { ObdPid.valueOf(it) }.getOrNull() }
                    pids.forEach { pid ->
                        val series = tripSeries(t, pid)
                        if (series.size >= 2) TripParamCard(pid, series)
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun TripParamCard(pid: ObdPid, series: List<MetricSample>) {
    val cs = MaterialTheme.colorScheme
    val vals = series.map { it.value }
    ElevatedCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(metricIcon(pid), null, Modifier.size(16.dp), tint = cs.primary)
                Spacer(Modifier.width(8.dp))
                Text(pid.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    "мин ${formatMetric(vals.min())} · сред ${formatMetric(vals.average())} · макс ${formatMetric(vals.max())} ${pid.unit}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = cs.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            MiniTrendChart(pid, series, cs.primary, Modifier.fillMaxWidth().height(90.dp))
        }
    }
}

@Composable
private fun TripsTopBar(title: String, onBack: () -> Unit, action: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
        Spacer(Modifier.width(4.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/** Серия одного параметра из записи (снимки, где он присутствует). */
private fun tripSeries(trip: Trip, pid: ObdPid): List<MetricSample> =
    trip.samples.mapNotNull { s -> s.v[pid.name]?.let { MetricSample(s.t, it) } }

private fun formatDateTime(ms: Long): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(ms))

private fun formatDuration(sec: Long): String =
    if (sec >= 60) "${sec / 60} мин ${sec % 60} с" else "$sec с"
