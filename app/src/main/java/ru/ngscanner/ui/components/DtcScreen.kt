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
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.ngscanner.ui.DtcCategory
import ru.ngscanner.ui.DtcItem
import ru.ngscanner.ui.UiState
import ru.ngscanner.ui.theme.StatusCrit
import ru.ngscanner.ui.theme.StatusGood
import ru.ngscanner.ui.theme.StatusWarn

/**
 * Экран кодов неисправностей: читает активные (Mode 03), неподтверждённые (07) и
 * постоянные (0A) коды, показывает их по группам с расшифровкой; по каждому можно
 * запросить у ИИ разбор причин и ремонта. Сброс кодов — под подтверждением.
 */
@Composable
internal fun DtcScreen(
    ui: UiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onExplain: (DtcItem) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var confirmClear by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
            Spacer(Modifier.width(4.dp))
            Text("Коды неисправностей", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = onRefresh, enabled = !ui.dtcReading) { Icon(Icons.Rounded.Refresh, "Обновить") }
        }

        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                ui.dtcReading -> Row(Modifier.padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Читаю коды из ЭБУ…", color = cs.onSurfaceVariant)
                }
                ui.dtcError != null -> Surface(shape = RoundedCornerShape(16.dp), color = cs.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(ui.dtcError, Modifier.padding(16.dp), color = cs.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                }
                ui.dtcChecked && ui.dtcItems.isEmpty() -> Row(
                    Modifier.fillMaxWidth().padding(top = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = StatusGood)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Коды не обнаружены", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Активных, неподтверждённых и постоянных кодов нет. Это не 100% гарантия " +
                                "исправности, но хороший знак.",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    ui.dtcWarning?.let { warning ->
                        Surface(shape = RoundedCornerShape(14.dp), color = StatusWarn.copy(alpha = 0.16f), modifier = Modifier.fillMaxWidth()) {
                            Text(warning, Modifier.padding(14.dp), color = StatusWarn, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    DriveVerdict(ui.dtcItems)
                    Category("Активные", "горят прямо сейчас", ui.dtcItems.filter { it.category == DtcCategory.ACTIVE }, cs.error, onExplain)
                    Category("Неподтверждённые", "намечающиеся, текущий цикл", ui.dtcItems.filter { it.category == DtcCategory.PENDING }, cs.tertiary, onExplain)
                    Category("Постоянные", "не стираются сбросом, пока ЭБУ не убедится", ui.dtcItems.filter { it.category == DtcCategory.PERMANENT }, cs.error, onExplain)
                }
            }

            if (ui.dtcItems.any { it.category == DtcCategory.ACTIVE } && !ui.dtcReading) {
                OutlinedButton(
                    onClick = { confirmClear = true },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Rounded.DeleteSweep, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Сбросить коды и погасить Check Engine")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Сбросить коды?") },
            text = {
                Text(
                    "Mode 04 сотрёт сохранённые коды, freeze frame и сбросит готовность мониторов. " +
                        "Freeze frame — снимок условий (обороты, температура, нагрузка) в момент " +
                        "фиксации кода: ценнейшее для диагностики. Стоит сначала разобрать код через " +
                        "ИИ (кнопка у кода прочитает снимок), а уже потом сбрасывать. Если причина не " +
                        "устранена — код появится снова. Постоянные коды сбросом не стираются.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }) { Text("Сбросить") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun Category(title: String, hint: String, items: List<DtcItem>, accent: Color, onExplain: (DtcItem) -> Unit) {
    if (items.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.width(8.dp))
            Text(
                "${title.uppercase()} · ${items.size}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
                color = cs.onSurfaceVariant,
            )
        }
        Text(hint, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 14.dp))
        items.forEach { DtcCard(it, accent, onExplain) }
    }
}

@Composable
private fun DtcCard(item: DtcItem, accent: Color, onExplain: (DtcItem) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val danger = dtcDanger(item.code)
    ElevatedCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.code,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                )
                if (danger != DtcDanger.UNKNOWN) {
                    Spacer(Modifier.width(8.dp))
                    DangerChip(danger)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                item.description ?: subsystemHint(item.code),
                style = MaterialTheme.typography.bodyMedium,
                color = if (item.description != null) cs.onSurface else cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { onExplain(item) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Icon(Icons.AutoMirrored.Rounded.HelpOutline, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Разобрать причины и ремонт")
            }
        }
    }
}

/**
 * Плашка «можно ли ехать» — консервативный агрегат по активным/постоянным кодам.
 * Триаж-вердикт есть в чате (SeverityBadge) от агента; здесь — быстрый ориентир на
 * самом экране кодов, чтобы пользователь не видел «красную простыню» без вывода.
 */
@Composable
private fun DriveVerdict(items: List<DtcItem>) {
    val active = items.filter { it.category == DtcCategory.ACTIVE || it.category == DtcCategory.PERMANENT }
    val (color, title, subtitle) = when {
        active.any { dtcDanger(it.code) == DtcDanger.CRITICAL } -> Triple(
            StatusCrit,
            "Ехать рискованно",
            "Среди активных кодов есть потенциально опасные для двигателя (пропуски/перегрев/" +
                "давление масла). До разбора воздержитесь от поездок.",
        )
        active.isNotEmpty() -> Triple(
            StatusWarn,
            "Можно доехать до сервиса",
            "Есть активные коды, но однозначно критичных по подсистемам не видно. Откладывать " +
                "диагностику не стоит.",
        )
        else -> Triple(
            StatusGood,
            "Пока только намечающиеся коды",
            "Активных и постоянных кодов нет — есть неподтверждённые (текущий цикл). Понаблюдайте.",
        )
    }
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(16.dp), color = cs.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = color)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Text(
                    "Точную оценку «можно ли ехать» даст разбор через ИИ (кнопка у кода).",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** Небольшой цветной чип тяжести у кода (только для однозначных групп). */
@Composable
private fun DangerChip(danger: DtcDanger) {
    val (color, label) = when (danger) {
        DtcDanger.CRITICAL -> StatusCrit to "критично"
        DtcDanger.MINOR -> StatusGood to "некритично"
        DtcDanger.UNKNOWN -> return
    }
    Surface(color = color.copy(alpha = 0.18f), shape = RoundedCornerShape(6.dp)) {
        Text(
            label,
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Грубая оценка опасности кода по подсистеме. Консервативно: в CRITICAL и MINOR
 * попадают только однозначные группы, остальное — UNKNOWN (полагаемся на разбор ИИ),
 * чтобы не мискатегоризировать и не нарушать правило «не выдумывать значения».
 */
private enum class DtcDanger { CRITICAL, MINOR, UNKNOWN }

private fun dtcDanger(code: String): DtcDanger {
    val c = code.uppercase()
    return when {
        // Пропуски воспламенения P0300–P030C — риск катализатора/двигателя.
        c.matches(Regex("P030[0-9A-C]")) -> DtcDanger.CRITICAL
        // Перегрев ДВС/АКПП; давление масла P0520–P0524.
        c == "P0217" || c == "P0218" || c.matches(Regex("P052[0-4]")) -> DtcDanger.CRITICAL
        // EVAP-негерметичность P044x–P045x — некритично («подтяните крышку бака»).
        c.matches(Regex("P04[45][0-9A-F]")) -> DtcDanger.MINOR
        else -> DtcDanger.UNKNOWN
    }
}

/** Подсказка по подсистеме для нераспознанных кодов (нет в локальной базе). */
private fun subsystemHint(code: String): String = when (code.firstOrNull()?.uppercaseChar()) {
    'B' -> "Код кузова/подушек безопасности — локальной расшифровки нет; разберите через ИИ или дилерскую базу."
    'C' -> "Код шасси (ABS/ESP/подвеска) — локальной расшифровки нет; разберите через ИИ."
    'U' -> "Код сети между блоками (CAN) — локальной расшифровки нет; разберите через ИИ."
    else -> "Нет расшифровки в локальной базе — разберите через ИИ."
}
