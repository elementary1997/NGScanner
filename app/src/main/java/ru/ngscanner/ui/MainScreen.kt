@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package ru.ngscanner.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.Garage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.ngscanner.ui.components.ChatTab
import ru.ngscanner.ui.components.DevicesTab
import ru.ngscanner.ui.components.GarageTab
import ru.ngscanner.ui.components.SettingsTab

private enum class Tab(val label: String, val icon: ImageVector) {
    Devices("Приборы", Icons.Rounded.Speed),
    Chat("Диагностика", Icons.AutoMirrored.Rounded.Chat),
    Garage("Гараж", Icons.Rounded.Garage),
    Settings("Настройки", Icons.Rounded.Settings),
}

@Composable
fun MainScreen(vm: MainViewModel) {
    val ui by vm.ui.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.Devices) }
    // Стек посещённых вкладок: «назад» возвращает на предыдущую, а не на стартовую.
    val backStack = rememberSaveable(
        saver = listSaver(
            save = { it.map(Tab::name) },
            restore = { it.map(Tab::valueOf).toMutableStateList() },
        ),
    ) { mutableStateListOf<Tab>() }
    val goToTab: (Tab) -> Unit = { target ->
        if (target != tab) {
            backStack.add(tab)
            tab = target
        }
    }
    // Держит per-вкладку сохраняемое состояние: черновики форм, стек навигации
    // Гаража и прокрутка не сбрасываются при переключении вкладок.
    val tabStateHolder = rememberSaveableStateHolder()

    val permissions = bluetoothPermissions()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { vm.refreshDevices() }
    LaunchedEffect(Unit) { launcher.launch(permissions) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.DirectionsCar, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("NG Scanner", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            ui.garage.activeCar?.let { car ->
                                Text(
                                    car.title,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { goToTab(t) },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        // «Назад» возвращает на предыдущую посещённую вкладку (стек), а не сразу на
        // стартовую и не закрывает приложение. Внутренняя навигация экранов
        // (например, Гараж) перехватывает «назад» раньше — её BackHandler объявлен
        // ниже по дереву и потому имеет приоритет над этим.
        BackHandler(enabled = backStack.isNotEmpty()) {
            tab = backStack.removeAt(backStack.lastIndex)
        }
        // consumeWindowInsets сообщает вложенным элементам, что нижний отступ (навбар)
        // уже применён Scaffold — тогда imePadding у панели ввода не удваивает его.
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            tabStateHolder.SaveableStateProvider(tab) {
                when (tab) {
                    Tab.Devices -> DevicesTab(
                        ui = ui,
                        onConnect = vm::connect,
                        onDisconnect = vm::disconnect,
                        onRequestNorm = vm::requestNorm,
                        onOpenConnection = { goToTab(Tab.Settings) },
                    )
                    Tab.Chat -> ChatTab(
                        ui,
                        onSend = vm::sendMessage,
                        onClear = vm::clearChat,
                        onCancel = vm::cancelDiagnosis,
                        onLocalDiagnose = vm::localDiagnose,
                        onRestore = vm::restoreSession,
                    )
                    Tab.Garage -> GarageTab(ui, vm)
                    Tab.Settings -> SettingsTab(
                        ui = ui,
                        vm = vm,
                        onScan = { launcher.launch(permissions); vm.refreshDevices(); vm.startScan() },
                        onStopScan = vm::stopScan,
                    )
                }
            }
        }
    }

    if (ui.clearDtcsPending) {
        ClearDtcsDialog(
            onConfirm = { vm.confirmClearDtcs(true) },
            onDismiss = { vm.confirmClearDtcs(false) },
        )
    }
}

@Composable
private fun ClearDtcsDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.DeleteSweep, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Сбросить коды неисправностей?") },
        text = {
            Text(
                "Ассистент просит выполнить сброс кодов (Mode 04). Это сотрёт сохранённые коды " +
                    "и обнулит мониторы готовности (readiness) — после сброса автомобиль может не " +
                    "пройти инструментальный контроль, пока не «наездит» циклы заново.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Сбросить", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun bluetoothPermissions(): Array<String> = remember {
    buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        // Android 13+: без этого разрешения ongoing-уведомление foreground-сервиса
        // (предупреждение о риске разряда АКБ подключённым адаптером) подавляется.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()
}
