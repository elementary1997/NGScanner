@file:OptIn(ExperimentalMaterial3Api::class)

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.ngscanner.obd.ObdProtocol
import ru.ngscanner.ui.ConnectionState
import ru.ngscanner.ui.MainViewModel
import ru.ngscanner.ui.UiState

/**
 * Настройка протокола шины: выбор вручную + зонд «какие протоколы отвечают».
 *
 * Автоопределение адаптера — чёрный ящик: на K-line и клонах оно молча «залипает» и
 * отдаёт NO DATA на живом ЭБУ. Здесь видно, что именно пробовали и что откликнулось.
 */
@Composable
internal fun ProtocolContent(ui: UiState, vm: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    val connected = ui.connection == ConnectionState.Connected
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Что определилось фактически (по ATDPN) — не то же, что выбрано.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Сейчас на связи:",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                ui.ecuProtocol ?: if (connected) "не определён" else "—",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = if (ui.ecuProtocol != null) cs.primary else cs.onSurfaceVariant,
            )
        }

        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = ui.obdProtocol.label,
                onValueChange = {},
                readOnly = true,
                label = { Text("Протокол") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ObdProtocol.entries.forEach { p ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(p.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    p.about,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = cs.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = { expanded = false; vm.setObdProtocol(p) },
                    )
                }
            }
        }

        Text(
            ui.obdProtocol.about,
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )

        OutlinedButton(
            onClick = { vm.probeProtocols() },
            enabled = connected && !ui.protocolProbing,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            if (ui.protocolProbing) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Rounded.Search, null, Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    ui.protocolProbing -> ui.protocolProbeStep?.let { "Пробую: ${it.label}…" } ?: "Пробую…"
                    else -> "Определить протокол"
                },
                maxLines = 1,
            )
        }
        if (!connected) {
            Text(
                "Зонд доступен при подключённом адаптере.",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
        }

        ui.protocolProbeResult?.let { found ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = (if (found.isEmpty()) cs.error else cs.primary).copy(alpha = 0.06f),
                border = BorderStroke(1.dp, (if (found.isEmpty()) cs.error else cs.primary).copy(alpha = 0.2f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (found.isEmpty()) {
                        Text(
                            "Ни один протокол не ответил",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = cs.error,
                        )
                        Text(
                            "ЭБУ молчит на всех протоколах. Обычные причины: выключено зажигание, " +
                                "адаптер не в разъёме до щелчка, нет питания на 16-м контакте OBD, " +
                                "либо машина не поддерживает OBD-II.",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurface,
                            lineHeight = 18.sp,
                        )
                    } else {
                        Text(
                            "Ответили:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = cs.onSurfaceVariant,
                        )
                        found.forEach { p ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.CheckCircle, null, Modifier.size(14.dp), tint = cs.primary)
                                Spacer(Modifier.width(8.dp))
                                Text(p.label, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
                            }
                        }
                        Text(
                            "Выберите рабочий протокол в списке выше — подключение станет быстрым и " +
                                "перестанет зависеть от автопоиска.",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }
        }
    }
}
