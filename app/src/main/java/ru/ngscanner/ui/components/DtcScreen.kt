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
import ru.ngscanner.ui.theme.StatusGood

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
                        "Если причина не устранена — код появится снова. Постоянные коды сбросом не " +
                        "стираются.",
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
    ElevatedCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(
                item.code,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                item.description ?: "Нет расшифровки в локальной базе — разберите через ИИ.",
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
