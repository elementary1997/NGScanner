package ru.ngscanner.ui.components

import androidx.compose.foundation.layout.Arrangement
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
import ru.ngscanner.obd.ObdPid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.ngscanner.ui.UiState
import ru.ngscanner.ui.ConnectionState
import ru.ngscanner.ui.DeviceUi

/**
 * Вкладка «Приборы»: статус подключения, список избранных адаптеров для быстрого
 * подключения и приборная панель при активном соединении. Полное управление
 * Bluetooth (поиск, сопряжённые, добавление в избранное) вынесено в «Настройки».
 */
@Composable
internal fun DevicesTab(
    ui: UiState,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onRequestNorm: (ObdPid) -> Unit,
    onOpenConnection: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        StatusCard(ui.connection, ui.connectedName)
        when (ui.connection) {
            ConnectionState.Connected -> Dashboard(
                metrics = ui.metrics,
                history = ui.history,
                onDisconnect = onDisconnect,
                modelNorms = ui.modelNorms,
                normLoadingPid = ui.normLoadingPid,
                onRequestNorm = onRequestNorm,
                activeCarTitle = ui.garage.activeCar?.title,
            )
            ConnectionState.Connecting -> ConnectingCard()
            ConnectionState.Disconnected -> FavoritesQuickConnect(ui.favorites, onConnect, onOpenConnection)
        }
        ui.error?.let { ErrorCard(it) }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
internal fun StatusCard(state: ConnectionState, name: String?) {
    val cs = MaterialTheme.colorScheme
    val icon: ImageVector
    val text: String
    val bg: Color
    val fg: Color
    when (state) {
        ConnectionState.Connected -> {
            icon = Icons.Rounded.BluetoothConnected
            text = "Подключено" + (name?.let { " · $it" } ?: "")
            bg = cs.primaryContainer
            fg = cs.onPrimaryContainer
        }
        ConnectionState.Connecting -> {
            icon = Icons.AutoMirrored.Rounded.BluetoothSearching
            text = "Подключение…"
            bg = cs.tertiaryContainer
            fg = cs.onTertiaryContainer
        }
        ConnectionState.Disconnected -> {
            icon = Icons.Rounded.Bluetooth
            text = "Не подключено"
            bg = cs.surfaceVariant
            fg = cs.onSurfaceVariant
        }
    }
    Surface(shape = RoundedCornerShape(20.dp), color = bg, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = fg)
            Spacer(Modifier.width(16.dp))
            Text(text, color = fg, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun FavoritesQuickConnect(
    favorites: List<DeviceUi>,
    onConnect: (String) -> Unit,
    onOpenConnection: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Избранные адаптеры", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (favorites.isEmpty()) {
            InfoCard(
                Icons.Rounded.Bluetooth,
                "Избранных адаптеров пока нет. Откройте «Подключение» в настройках, найдите свой " +
                    "ELM327 и отметьте его звёздочкой — он появится здесь для подключения в один тап.",
            )
        } else {
            favorites.forEach { fav ->
                ElevatedCard(
                    onClick = { onConnect(fav.address) },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Bluetooth, null, tint = cs.primary)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(fav.name, style = MaterialTheme.typography.titleMedium)
                            Text(fav.address, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        Icon(Icons.Rounded.PlayArrow, "Подключить", tint = cs.primary)
                    }
                }
            }
        }
        OutlinedButton(
            onClick = onOpenConnection,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Настроить подключение")
        }
    }
}
