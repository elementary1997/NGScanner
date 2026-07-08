@file:OptIn(ExperimentalMaterial3Api::class)

package ru.ngscanner.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.BluetoothSearching
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ru.ngscanner.llm.ProviderId

private enum class Tab(val label: String, val icon: ImageVector) {
    Devices("Приборы", Icons.Rounded.Speed),
    Chat("Диагностика", Icons.Rounded.Chat),
    Settings("Настройки", Icons.Rounded.Settings),
}

@Composable
fun MainScreen(vm: MainViewModel) {
    val ui by vm.ui.collectAsState()
    var tab by remember { mutableStateOf(Tab.Devices) }

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
                        Text("NG Scanner", fontWeight = FontWeight.Bold)
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.Devices -> DevicesTab(
                    ui = ui,
                    onRefresh = { launcher.launch(permissions); vm.refreshDevices() },
                    onConnect = vm::connect,
                    onRead = vm::readLiveData,
                    onDisconnect = vm::disconnect,
                )
                Tab.Chat -> ChatTab(ui, onSend = vm::sendMessage, onClear = vm::clearChat)
                Tab.Settings -> SettingsTab(ui, vm)
            }
        }
    }
}

@Composable
private fun bluetoothPermissions(): Array<String> = remember {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

// ---------- Вкладка «Приборы» ----------

@Composable
private fun DevicesTab(
    ui: UiState,
    onRefresh: () -> Unit,
    onConnect: (String) -> Unit,
    onRead: () -> Unit,
    onDisconnect: () -> Unit,
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
            ConnectionState.Connected -> Dashboard(ui, onRead, onDisconnect)
            ConnectionState.Connecting -> ConnectingCard()
            ConnectionState.Disconnected -> DevicePicker(ui, onRefresh, onConnect)
        }
        ui.error?.let { ErrorCard(it) }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun StatusCard(state: ConnectionState, name: String?) {
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
private fun DevicePicker(ui: UiState, onRefresh: () -> Unit, onConnect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Сопряжённые адаптеры", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            FilledTonalIconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, "Обновить список") }
        }
        when {
            !ui.btEnabled -> InfoCard(
                Icons.Rounded.BluetoothDisabled,
                "Включите Bluetooth и сопрягите адаптер ELM327 в настройках телефона.",
            )
            ui.devices.isEmpty() -> InfoCard(
                Icons.Rounded.Search,
                "Нет сопряжённых устройств. Сопрягите ELM327 в настройках Bluetooth " +
                    "(PIN 1234 или 0000) и нажмите обновить.",
            )
            else -> ui.devices.forEach { device -> DeviceRow(device) { onConnect(device.address) } }
        }
    }
}

@Composable
private fun DeviceRow(device: DeviceUi, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Bluetooth, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium)
                Text(device.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Dashboard(ui: UiState, onRead: () -> Unit, onDisconnect: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MetricCard(Modifier.weight(1f), Icons.Rounded.Speed, "Обороты", ui.rpm?.toString() ?: "—", "об/мин")
            MetricCard(Modifier.weight(1f), Icons.Rounded.Thermostat, "Темп. ОЖ", ui.coolantTemp?.toString() ?: "—", "°C")
        }
        Button(
            onClick = onRead,
            enabled = !ui.reading,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            if (ui.reading) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Icon(Icons.Rounded.Sync, null)
                Spacer(Modifier.width(8.dp))
                Text("Читать данные")
            }
        }
        OutlinedButton(
            onClick = onDisconnect,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(Icons.Rounded.LinkOff, null)
            Spacer(Modifier.width(8.dp))
            Text("Отключить")
        }
    }
}

@Composable
private fun MetricCard(modifier: Modifier, icon: ImageVector, label: String, value: String, unit: String) {
    ElevatedCard(modifier = modifier, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(4.dp))
                Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
    }
}

@Composable
private fun ConnectingCard() {
    ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            Spacer(Modifier.width(16.dp))
            Text("Подключение и инициализация ELM327…")
        }
    }
}

@Composable
private fun InfoCard(icon: ImageVector, text: String) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(16.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

// ---------- Вкладка «Диагностика» (чат с LLM) ----------

@Composable
private fun ChatTab(ui: UiState, onSend: (String) -> Unit, onClear: () -> Unit) {
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (ui.chat.isEmpty()) {
                item { EmptyChatHint { onSend(QUICK_DIAGNOSE_PROMPT) } }
            }
            items(ui.chat) { msg -> ChatBubble(msg) }
            if (ui.diagnosing) {
                item {
                    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Диагностирую…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        ChatInput(enabled = !ui.diagnosing, onSend = onSend, onClear = onClear, hasChat = ui.chat.isNotEmpty())
    }
}

@Composable
private fun EmptyChatHint(onDiagnose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 56.dp, start = 8.dp, end = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.HealthAndSafety, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Диагностика через LLM", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Опишите симптом или запустите быструю диагностику — модель прочитает коды и " +
                "параметры с адаптера и даст понятный вердикт.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onDiagnose, shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Rounded.Bolt, null)
            Spacer(Modifier.width(8.dp))
            Text("Быстрая диагностика")
        }
    }
}

@Composable
private fun ChatBubble(msg: ChatMessage) {
    val cs = MaterialTheme.colorScheme
    val bg: Color
    val fg: Color
    when (msg.role) {
        ChatRole.USER -> { bg = cs.primary; fg = cs.onPrimary }
        ChatRole.ASSISTANT -> { bg = cs.surfaceVariant; fg = cs.onSurfaceVariant }
        ChatRole.TOOL -> { bg = cs.tertiaryContainer; fg = cs.onTertiaryContainer }
        ChatRole.SYSTEM -> { bg = cs.errorContainer; fg = cs.onErrorContainer }
    }
    val alignment = if (msg.role == ChatRole.USER) Alignment.End else Alignment.Start
    Column(Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.align(alignment).fillMaxWidth(0.9f),
            shape = RoundedCornerShape(16.dp),
            color = bg,
        ) {
            Text(msg.text, Modifier.padding(14.dp), color = fg, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ChatInput(enabled: Boolean, onSend: (String) -> Unit, onClear: () -> Unit, hasChat: Boolean) {
    var text by remember { mutableStateOf("") }
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasChat) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Rounded.DeleteSweep, "Очистить чат", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Опишите проблему…") },
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = { if (text.isNotBlank()) { onSend(text); text = "" } },
                enabled = enabled && text.isNotBlank(),
            ) {
                Icon(Icons.AutoMirrored.Rounded.Send, "Отправить")
            }
        }
    }
}

// ---------- Вкладка «Настройки» ----------

@Composable
private fun SettingsTab(ui: UiState, vm: MainViewModel) {
    var keyText by remember(ui.provider) { mutableStateOf("") }
    var modelText by remember(ui.provider) { mutableStateOf(ui.model) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Провайдер LLM", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = ui.provider == ProviderId.CLAUDE,
                onClick = { vm.setProvider(ProviderId.CLAUDE) },
                label = { Text("Anthropic Claude") },
            )
            FilterChip(
                selected = ui.provider == ProviderId.CLOUD_RU,
                onClick = { vm.setProvider(ProviderId.CLOUD_RU) },
                label = { Text("Cloud.ru") },
            )
        }

        OutlinedTextField(
            value = modelText,
            onValueChange = { modelText = it; vm.setModel(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Модель") },
            singleLine = true,
        )

        OutlinedTextField(
            value = keyText,
            onValueChange = { keyText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API-ключ") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        Button(
            onClick = { vm.setApiKey(keyText) },
            enabled = keyText.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("Сохранить ключ")
        }
        Text(
            if (ui.hasKey) "✓ Ключ сохранён для этого провайдера" else "Ключ не задан",
            style = MaterialTheme.typography.bodyMedium,
            color = if (ui.hasKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InfoCard(
            Icons.Rounded.ErrorOutline,
            "Ключ хранится только на устройстве и не попадает в код приложения. " +
                "Claude — console.anthropic.com; Cloud.ru — личный кабинет (сервисный аккаунт).",
        )
    }
}

private const val QUICK_DIAGNOSE_PROMPT =
    "Проведи диагностику автомобиля: прочитай активные коды неисправностей и текущие " +
        "параметры двигателя, затем дай понятный вердикт — что вероятно неисправно и что делать."
