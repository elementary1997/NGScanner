package ru.ngscanner.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.ngscanner.ui.ConnectionState
import ru.ngscanner.ui.DeviceUi
import ru.ngscanner.ui.UiState

/**
 * Панель управления Bluetooth-подключением к адаптеру: поиск устройств рядом,
 * список сопряжённых, отметка избранных (звёздочка) и подключение. Используется
 * в «Настройках»; на «Приборах» остаётся только статус и избранные.
 */
@Composable
internal fun BluetoothPanel(
    ui: UiState,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onToggleFavorite: (DeviceUi) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        StatusCard(ui.connection, ui.connectedName)

        when (ui.connection) {
            ConnectionState.Connected -> {
                OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Rounded.LinkOff, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Отключить адаптер")
                }
            }
            ConnectionState.Connecting -> Unit // статус уже показывает «Подключение…»
            ConnectionState.Disconnected -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Поиск адаптера", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (ui.scanning) {
                        FilledTonalButton(onClick = onStopScan, shape = RoundedCornerShape(12.dp)) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Стоп")
                        }
                    } else {
                        FilledTonalButton(onClick = onScan, shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.Rounded.Search, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Искать")
                        }
                    }
                }

                if (!ui.btEnabled) {
                    InfoCard(
                        Icons.Rounded.BluetoothDisabled,
                        "Включите Bluetooth, чтобы найти и подключить адаптер ELM327.",
                    )
                }

                // Найденные рядом (ещё не сопряжённые) устройства.
                if (ui.scanning || ui.discovered.isNotEmpty()) {
                    SectionLabelSmall("Найденные рядом")
                    ui.discovered.forEach { device ->
                        DeviceRow(
                            device = device,
                            isFavorite = ui.favorites.any { it.address == device.address },
                            onConnect = { onConnect(device.address) },
                            onToggleFavorite = { onToggleFavorite(device) },
                        )
                    }
                    if (ui.scanning && ui.discovered.isEmpty()) {
                        Text(
                            "Идёт поиск устройств… Убедитесь, что адаптер включён (горит светодиод).",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Сопряжённые — свёрнуты по умолчанию.
                ExpandableSection("Сопряжённые (${ui.devices.size})") {
                    if (ui.devices.isEmpty()) {
                        InfoCard(
                            Icons.Rounded.Search,
                            "Нет сопряжённых устройств. Сопрягите ELM327 в настройках Bluetooth " +
                                "(PIN 1234 или 0000) или найдите его поиском выше.",
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ui.devices.forEach { device ->
                                DeviceRow(
                                    device = device,
                                    isFavorite = ui.favorites.any { it.address == device.address },
                                    onConnect = { onConnect(device.address) },
                                    onToggleFavorite = { onToggleFavorite(device) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabelSmall(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.4.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ExpandableSection(title: String, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabelSmall(title)
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                if (expanded) "Свернуть" else "Развернуть",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) content()
    }
}

@Composable
private fun DeviceRow(
    device: DeviceUi,
    isFavorite: Boolean,
    onConnect: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    ElevatedCard(onClick = onConnect, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Bluetooth, null, tint = cs.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium)
                Text(device.address, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    if (isFavorite) "Убрать из избранного" else "В избранное",
                    tint = if (isFavorite) cs.primary else cs.onSurfaceVariant,
                )
            }
        }
    }
}
