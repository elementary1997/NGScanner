package ru.ngscanner.ui.components

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import ru.ngscanner.obd.ObdPid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
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
    // Имя открытого параметра (String сохраняется в Bundle → график переживает поворот).
    var graphName by rememberSaveable { mutableStateOf<String?>(null) }
    // При обрыве связи закрываем график, иначе при авто-реконнекте early-return
    // прыгнул бы сразу в него, минуя дашборд.
    LaunchedEffect(ui.connection) {
        if (ui.connection != ConnectionState.Connected) graphName = null
    }
    val graph = graphName?.let { name -> runCatching { ObdPid.valueOf(name) }.getOrNull() }
    // Полноэкранный график заменяет весь экран вкладки (у него собственный скролл),
    // а не вкладывается в скролл дашборда — иначе вложенные verticalScroll падают.
    if (graph != null && ui.connection == ConnectionState.Connected) {
        BackHandler { graphName = null }
        ParameterGraphScreen(
            pid = graph,
            value = ui.metrics[graph],
            history = ui.history,
            onBack = { graphName = null },
            modelNorm = ui.modelNorms[graph.cmd],
            loading = ui.normLoadingPid == graph.cmd,
            onRequestNorm = onRequestNorm,
            activeCarTitle = ui.garage.activeCar?.title,
        )
        return
    }
    // При активном соединении — приборы и графики на двух свайпаемых панелях; иначе
    // (нет данных для графиков) прежний одиночный экран со статусом и избранными.
    if (ui.connection == ConnectionState.Connected) {
        DevicesConnected(ui, onDisconnect, onOpenGraph = { graphName = it.name })
    } else {
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
                ConnectionState.Connecting -> ConnectingCard()
                else -> FavoritesQuickConnect(ui.favorites, onConnect, onOpenConnection)
            }
            ui.error?.let { ErrorCard(it) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * Подключённое состояние: статус сверху, под ним свайпаемые панели «Приборы»
 * (дашборд без изменений) и «Графики» (обзор трендов). Пейджер занимает
 * оставшуюся высоту (у каждой панели свой вертикальный скролл — оси не конфликтуют).
 */
@Composable
private fun DevicesConnected(
    ui: UiState,
    onDisconnect: () -> Unit,
    onOpenGraph: (ObdPid) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(8.dp))
        Column(Modifier.padding(horizontal = 16.dp)) { StatusCard(ui.connection, ui.connectedName) }
        Spacer(Modifier.height(12.dp))
        PanelTabs(pagerState.currentPage) { page -> scope.launch { pagerState.animateScrollToPage(page) } }
        Spacer(Modifier.height(4.dp))
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
            if (page == 0) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Spacer(Modifier.height(4.dp))
                    Dashboard(ui.metrics, ui.history, onDisconnect, onOpenGraph)
                    ui.error?.let { ErrorCard(it) }
                    Spacer(Modifier.height(16.dp))
                }
            } else {
                GraphsPanel(ui.metrics, ui.history, onOpenGraph)
            }
        }
    }
}

/** Два переключателя панелей — кликом и синхронно со свайпом пейджера. */
@Composable
private fun PanelTabs(current: Int, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val tabs = listOf("Приборы" to Icons.Rounded.Speed, "Графики" to Icons.Rounded.ShowChart)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tabs.forEachIndexed { i, (label, icon) ->
            val selected = i == current
            Surface(
                onClick = { onSelect(i) },
                shape = RoundedCornerShape(12.dp),
                color = if (selected) cs.primaryContainer else cs.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.weight(1f).height(40.dp),
            ) {
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, null, Modifier.size(16.dp), tint = if (selected) cs.primary else cs.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
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
