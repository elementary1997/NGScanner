package ru.ngscanner.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.ngscanner.obd.CustomPid
import ru.ngscanner.obd.CustomPidRepository
import java.util.Locale

/**
 * Настройка заводских PID. Приложение НЕ знает адресов заводских параметров ВАЗ и
 * прочих ЭБУ и не выдумывает их (неверный адрес вернул бы правдоподобное, но ложное
 * число) — их задаёт тот, у кого есть проверенное описание.
 */
@Composable
internal fun CustomPidsContent(
    pids: List<CustomPid>,
    onSave: (CustomPid) -> Unit,
    onDelete: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var editing by remember { mutableStateOf<CustomPid?>(null) }
    var showForm by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = cs.primary.copy(alpha = 0.06f),
            border = BorderStroke(1.dp, cs.primary.copy(alpha = 0.16f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Стандарт OBD-II описывает только общие параметры. Всё заводское (ВАЗ Bosch " +
                    "M7.9.7 / M74, «Январь» и др.) лежит в модах 21/22 по адресам, которых нет в " +
                    "открытом доступе. Придумывать их нельзя: неверный адрес даёт не ошибку, а " +
                    "правдоподобное неверное число. Задайте PID сами, если у вас есть проверенное " +
                    "описание — команду, смещение байта и формулу.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurface,
                lineHeight = 18.sp,
                modifier = Modifier.padding(12.dp),
            )
        }
        if (pids.isEmpty()) {
            Text(
                "Своих PID нет.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
        } else {
            pids.forEach { pid ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier.weight(1f).padding(vertical = 2.dp),
                    ) {
                        Text(pid.name, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            formula(pid),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { editing = pid; showForm = true }) { Text("Правка") }
                    IconButton(onClick = { onDelete(pid.id) }) {
                        Icon(Icons.Rounded.DeleteOutline, "Удалить PID", Modifier.size(18.dp), tint = cs.onSurfaceVariant)
                    }
                }
            }
        }
        if (pids.size < CustomPidRepository.MAX_PIDS) {
            TextButton(onClick = { editing = null; showForm = true }) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Добавить PID")
            }
        }
    }
    if (showForm) {
        CustomPidDialog(
            initial = editing,
            onDismiss = { showForm = false },
            onSave = { showForm = false; onSave(it) },
        )
    }
}

/** Человекочитаемая формула: `2101 · байт 3, 2 б · ×0.25 −40 об/мин`. */
private fun formula(p: CustomPid): String = buildString {
    append(p.cmd.uppercase())
    append(" · байт ").append(p.byteOffset)
    if (p.byteCount > 1) append("–").append(p.byteOffset + p.byteCount - 1)
    if (p.signed) append(" (зн.)")
    append(" · ×").append(trim(p.scale))
    if (p.offset != 0.0) {
        append(if (p.offset < 0) " −" else " +").append(trim(kotlin.math.abs(p.offset)))
    }
    if (p.unit.isNotBlank()) append(' ').append(p.unit)
}

private fun trim(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.US, "%.4g", v)

@Composable
private fun CustomPidDialog(initial: CustomPid?, onDismiss: () -> Unit, onSave: (CustomPid) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var cmd by remember { mutableStateOf(initial?.cmd ?: "") }
    var unit by remember { mutableStateOf(initial?.unit ?: "") }
    var offsetB by remember { mutableStateOf((initial?.byteOffset ?: 0).toString()) }
    var count by remember { mutableStateOf((initial?.byteCount ?: 1).toString()) }
    var scale by remember { mutableStateOf((initial?.scale ?: 1.0).toString()) }
    var shift by remember { mutableStateOf((initial?.offset ?: 0.0).toString()) }
    var signed by remember { mutableStateOf(initial?.signed ?: false) }

    val built = CustomPid(
        id = initial?.id ?: "",
        name = name.trim(),
        cmd = cmd.trim().uppercase(),
        unit = unit.trim(),
        byteOffset = offsetB.toIntOrNull() ?: -1,
        byteCount = count.toIntOrNull() ?: 0,
        scale = scale.toDoubleOrNull() ?: Double.NaN,
        offset = shift.toDoubleOrNull() ?: Double.NaN,
        signed = signed,
    )
    val valid = built.isValid() && built.name.isNotBlank() &&
        !built.scale.isNaN() && !built.offset.isNaN()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Свой PID" else "Правка PID") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("Название") }, placeholder = { Text("Напр. Угол опережения") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = cmd, onValueChange = { cmd = it }, singleLine = true,
                    label = { Text("Команда (hex): мода + PID") }, placeholder = { Text("2101") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = offsetB, onValueChange = { offsetB = it }, singleLine = true,
                        label = { Text("Байт №") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = count, onValueChange = { count = it }, singleLine = true,
                        label = { Text("Байт (1–2)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = scale, onValueChange = { scale = it }, singleLine = true,
                        label = { Text("×") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = shift, onValueChange = { shift = it }, singleLine = true,
                        label = { Text("+") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = unit, onValueChange = { unit = it }, singleLine = true,
                        label = { Text("Ед.") },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = signed, onCheckedChange = { signed = it })
                    Text("Значение со знаком", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "значение = raw × множитель + сдвиг",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onSave(built.copy(id = initial?.id ?: CustomPidRepository.newId())) },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Плитка значений своих PID на панели приборов; при пустом списке не рисуется. */
@Composable
internal fun CustomPidCard(pids: List<CustomPid>, values: Map<String, Double>) {
    if (pids.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    ElevatedCard(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "СВОИ PID",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.6.sp,
                color = cs.onSurfaceVariant,
            )
            pids.forEach { pid ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pid.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    val v = values[pid.id]
                    Text(
                        if (v == null) "—" else String.format(Locale.US, "%.2f", v) +
                            (if (pid.unit.isNotBlank()) " ${pid.unit}" else ""),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = if (v == null) cs.onSurfaceVariant else cs.onSurface,
                    )
                }
            }
        }
    }
}
