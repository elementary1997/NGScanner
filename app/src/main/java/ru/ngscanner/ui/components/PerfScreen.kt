package ru.ngscanner.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.ngscanner.perf.PerfKind
import ru.ngscanner.perf.PerfPhase
import ru.ngscanner.perf.PerfRun
import ru.ngscanner.perf.PerfState
import java.util.Locale

/**
 * Экран перф-замеров: выбор замера, живой секундомер по скорости с ЭБУ и список
 * сохранённых заездов. Точность ограничена OBD (задержка шины, целые км/ч) — об этом
 * честно сказано на экране: это оценка, а не спортивный таймер.
 */
@Composable
internal fun PerfScreen(
    kind: PerfKind?,
    state: PerfState?,
    runs: List<PerfRun>,
    onBack: () -> Unit,
    onStart: (PerfKind) -> Unit,
    onRestart: () -> Unit,
    onStop: () -> Unit,
    onDeleteRun: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (kind != null) onStop() else onBack() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", tint = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                kind?.label ?: "Замеры",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        if (kind != null && state != null) {
            PerfLive(kind, state, onRestart, onStop)
        } else {
            PerfPicker(onStart)
            PerfAccuracyNote()
            PerfHistory(runs, onDeleteRun)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PerfPicker(onStart: (PerfKind) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PerfSectionLabel("Что меряем")
        PerfKind.entries.forEach { k ->
            ElevatedCard(
                onClick = { onStart(k) },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Timer, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(k.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            k.hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PerfLive(kind: PerfKind, state: PerfState, onRestart: () -> Unit, onStop: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val done = state.phase == PerfPhase.DONE
    ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 26.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                phaseText(kind, state.phase),
                style = MaterialTheme.typography.labelLarge,
                color = when (state.phase) {
                    PerfPhase.DONE -> cs.primary
                    PerfPhase.ABORTED -> cs.error
                    PerfPhase.RUNNING -> cs.tertiary
                    else -> cs.onSurfaceVariant
                },
            )
            Text(
                String.format(Locale.US, "%.2f", state.resultSec ?: state.elapsedSec),
                style = MaterialTheme.typography.displayLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if (done) cs.primary else cs.onSurface,
            )
            Text("секунд", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                PerfStat("Скорость", String.format(Locale.US, "%.0f км/ч", state.speedKmh))
                if (kind == PerfKind.QUARTER_MILE) {
                    PerfStat("Дистанция", String.format(Locale.US, "%.0f м", state.distanceM))
                }
                state.trapSpeedKmh?.let {
                    PerfStat("На финише", String.format(Locale.US, "%.0f км/ч", it))
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onRestart, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text(if (state.phase == PerfPhase.DONE || state.phase == PerfPhase.ABORTED) "Ещё раз" else "Сброс")
        }
        OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(14.dp)) {
            Text("Готово")
        }
    }
    PerfAccuracyNote()
}

@Composable
private fun PerfStat(label: String, value: String) {
    val cs = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 0.8.sp,
            color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(3.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, color = cs.onSurface)
    }
}

/** Подсказка по фазе — что делать прямо сейчас. */
private fun phaseText(kind: PerfKind, phase: PerfPhase): String = when (phase) {
    PerfPhase.ARMING -> when (kind) {
        PerfKind.BRAKE_100_0 -> "Разгонитесь до 100 км/ч…"
        else -> "Остановитесь полностью…"
    }
    PerfPhase.READY -> when (kind) {
        PerfKind.BRAKE_100_0 -> "Готово — тормозите"
        else -> "Готово — трогайтесь"
    }
    PerfPhase.RUNNING -> "Замер идёт"
    PerfPhase.DONE -> "Результат"
    PerfPhase.ABORTED -> "Заезд прерван"
}

@Composable
private fun PerfAccuracyNote() {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cs.primary.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, cs.primary.copy(alpha = 0.16f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "Скорость берётся с ЭБУ по OBD (целые км/ч, задержка шины), поэтому результат " +
                "оценочный — сравнивать заезды между собой можно, спортивный таймер он не заменяет. " +
                "На время замера опрос остальных приборов приостановлен. Замеряйте только там, где это " +
                "безопасно и разрешено.",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurface,
            lineHeight = 18.sp,
            modifier = Modifier.padding(14.dp),
        )
    }
}

@Composable
private fun PerfHistory(runs: List<PerfRun>, onDelete: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PerfSectionLabel("Заезды")
        if (runs.isEmpty()) {
            Text(
                "Заездов пока нет.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
        } else {
            runs.forEach { run ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${String.format(Locale.US, "%.2f", run.seconds)} с · ${run.kind.label}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurface,
                        )
                        Spacer(Modifier.height(2.dp))
                        val extra = listOfNotNull(
                            run.dateIso,
                            run.carTitle.takeIf { it.isNotBlank() },
                            run.trapSpeedKmh?.let { String.format(Locale.US, "финиш %.0f км/ч", it) },
                        ).joinToString(" · ")
                        Text(
                            extra,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onDelete(run.id) }) {
                        Icon(Icons.Rounded.DeleteOutline, "Удалить заезд", Modifier.size(18.dp), tint = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun PerfSectionLabel(title: String) {
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
