@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package ru.ngscanner.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import ru.ngscanner.llm.LlmModel
import ru.ngscanner.llm.ProviderId
import ru.ngscanner.settings.AppSettings
import ru.ngscanner.settings.ModelPrice
import ru.ngscanner.settings.ModelUsage
import ru.ngscanner.ui.ConnectionState
import ru.ngscanner.ui.UiState
import ru.ngscanner.ui.MainViewModel
import ru.ngscanner.ui.TestStatus

@Composable
internal fun SettingsTab(
    ui: UiState,
    vm: MainViewModel,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
) {
    // Ключ не хранится в наблюдаемом состоянии — сеем поле разово из настроек
    // (пересев при смене провайдера). Поле замаскировано (PasswordVisualTransformation).
    var keyText by remember(ui.provider) { mutableStateOf(vm.currentApiKey()) }
    // Обновляем список сопряжённых устройств и состояние Bluetooth при открытии.
    LaunchedEffect(Unit) { vm.refreshDevices() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val connectionSummary = when (ui.connection) {
            ConnectionState.Connected -> "Подключено" + (ui.connectedName?.let { " · $it" } ?: "")
            ConnectionState.Connecting -> "Подключение…"
            ConnectionState.Disconnected -> "Не подключено · избранных: ${ui.favorites.size}"
        }
        CollapsibleCard(
            title = "Подключение (Bluetooth)",
            summary = connectionSummary,
            initiallyExpanded = ui.connection != ConnectionState.Connected,
        ) {
            BluetoothPanel(
                ui = ui,
                onScan = onScan,
                onStopScan = onStopScan,
                onConnect = vm::connect,
                onDisconnect = vm::disconnect,
                onToggleFavorite = vm::toggleFavorite,
            )
        }

        val providerLabel = if (ui.provider == ProviderId.CLAUDE) "Anthropic Claude" else "Cloud.ru"
        val keyStatus = if (ui.hasKey) "ключ сохранён" else "ключ не задан"
        // Блок провайдера сворачиваем: когда всё настроено — не занимает экран;
        // раскрыт по умолчанию, пока ключ не задан (первичная настройка).
        CollapsibleCard(
            title = "Провайдер LLM",
            summary = "$providerLabel · ${ui.model} · $keyStatus",
            initiallyExpanded = !ui.hasKey,
        ) {
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
                value = keyText,
                onValueChange = { keyText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API-ключ") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )

            Button(
                onClick = { vm.testConnection(keyText) },
                enabled = !ui.testing && keyText.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                if (ui.testing) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Rounded.Bolt, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Тест подключения")
                }
            }

            when (val st = ui.testStatus) {
                is TestStatus.Success -> Text(
                    "✓ Подключение успешно — выберите модель ниже",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                is TestStatus.Error -> Text(
                    "✗ ${st.message}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                null -> if (ui.hasKey) {
                    Text(
                        "Ключ сохранён. Нажмите «Тест», чтобы загрузить список моделей.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (ui.availableModels.isNotEmpty()) {
                ModelDropdown(ui.availableModels, ui.model, vm::setModel)
            } else {
                // Список ещё не загружен, но выбранная модель сохранена — показываем её.
                OutlinedTextField(
                    value = ui.model,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Текущая модель") },
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Нажмите «Тест», чтобы выбрать другую модель") },
                )
            }
        }

        val totalTokens = ui.modelUsage.sumOf { it.total }
        CollapsibleCard(
            title = "Расход · $providerLabel",
            summary = "всего ${formatTokens(totalTokens)} ток.",
            initiallyExpanded = false,
        ) {
            UsageContent(
                ui,
                onReset = vm::resetUsage,
                onSetPrice = vm::setModelPrice,
                onRemove = vm::removeModelUsage,
            )
        }

        CollapsibleCard(
            title = "Поведение",
            summary = (if (ui.batteryGuard) "защита АКБ" else "без защиты АКБ") + " · " + pollRateLabel(ui.pollIntervalMs),
            initiallyExpanded = false,
        ) {
            BehaviorContent(ui, vm)
        }

        CollapsibleCard(
            title = "Обновление приложения",
            summary = ui.updateInfo?.let { "доступна ${it.version}" } ?: "версия ${ui.appVersion}",
            initiallyExpanded = ui.updateInfo != null,
        ) {
            UpdateContent(ui, vm)
        }

        CollapsibleCard(
            title = "Приватность и хранение",
            summary = if (ui.keysEncrypted) "ключ зашифрован · данные" else "⚠️ ключ без шифрования",
            initiallyExpanded = false,
        ) {
            InfoContent(ui)
        }
    }
}

/** Секция «Поведение»: защита АКБ, экран, частота опроса. */
@Composable
private fun BehaviorContent(ui: UiState, vm: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    SettingSwitchRow(
        title = "Защита аккумулятора",
        subtitle = "Отключать адаптер, если ЭБУ долго молчит (забытый в разъёме ELM327 сажает АКБ).",
        checked = ui.batteryGuard,
        onCheckedChange = vm::setBatteryGuard,
    )
    SettingSwitchRow(
        title = "Не гасить экран",
        subtitle = "Держать экран включённым во время сессии с адаптером.",
        checked = ui.keepScreenOn,
        onCheckedChange = vm::setKeepScreenOn,
    )
    Text("Частота опроса приборов", style = MaterialTheme.typography.labelLarge)
    Text(
        "Реже — меньше нагрузка на дешёвые клоны и экономнее для АКБ; чаще — живее графики.",
        style = MaterialTheme.typography.bodySmall,
        color = cs.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PollChip("Экономно", AppSettings.POLL_MS_ECONOMY, ui.pollIntervalMs, vm::setPollIntervalMs)
        PollChip("Обычно", AppSettings.DEFAULT_POLL_MS, ui.pollIntervalMs, vm::setPollIntervalMs)
        PollChip("Быстро", AppSettings.POLL_MS_FAST, ui.pollIntervalMs, vm::setPollIntervalMs)
    }
}

@Composable
private fun PollChip(label: String, ms: Long, current: Long, onSelect: (Long) -> Unit) {
    FilterChip(selected = current == ms, onClick = { onSelect(ms) }, label = { Text(label) })
}

@Composable
private fun SettingSwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Секция «Обновление приложения»: версия, проверка, установка из GitHub Releases. */
@Composable
private fun UpdateContent(ui: UiState, vm: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    Text("Текущая версия: ${ui.appVersion}", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)

    var showChangelog by remember { mutableStateOf(false) }
    TextButton(
        onClick = { showChangelog = true },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Icon(Icons.Rounded.Info, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Что нового (история изменений)")
    }
    if (showChangelog) {
        val context = LocalContext.current
        val changelog = remember {
            runCatching { context.assets.open("CHANGELOG.md").bufferedReader().use { it.readText() } }
                .getOrDefault("История изменений недоступна.")
        }
        ChangelogDialog(changelog, ui.appVersion) { showChangelog = false }
    }

    SettingSwitchRow(
        title = "Проверять при запуске",
        subtitle = "Уведомлять о новой версии из GitHub Releases.",
        checked = ui.updateCheck,
        onCheckedChange = vm::setUpdateCheck,
    )
    val info = ui.updateInfo
    if (info != null) {
        HorizontalDivider(color = cs.outlineVariant)
        Text(
            "Доступна версия ${info.version}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = cs.primary,
        )
        if (info.notes.isNotBlank()) {
            Text(info.notes.take(400), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Button(
            onClick = vm::installUpdate,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Rounded.OpenInNew, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Скачать и установить ${info.version}")
        }
        Text(
            "Для установки поверх нужно разрешить «Установку неизвестных приложений» и тот же ключ подписи.",
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
        )
    } else {
        OutlinedButton(
            onClick = { vm.checkForUpdate(manual = true) },
            enabled = !ui.updateChecking,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            if (ui.updateChecking) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text("Проверить обновления")
            }
        }
    }
    ui.updateStatus?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
}

private fun pollRateLabel(ms: Long): String = when (ms) {
    AppSettings.POLL_MS_ECONOMY -> "опрос: экономно"
    AppSettings.POLL_MS_FAST -> "опрос: быстро"
    else -> "опрос: обычно"
}

private data class ChangeEntry(val version: String, val body: String)

/** Разбирает CHANGELOG.md на секции версий (по заголовкам «## vX.Y.Z»). */
private fun parseChangelog(md: String): List<ChangeEntry> {
    val entries = mutableListOf<ChangeEntry>()
    var version: String? = null
    val body = StringBuilder()
    fun flush() {
        version?.let { entries.add(ChangeEntry(it, body.toString().trim())) }
        body.setLength(0)
    }
    for (line in md.lines()) {
        val h = line.trim()
        when {
            h.startsWith("## ") -> { flush(); version = h.removePrefix("## ").trim() }
            h.startsWith("# ") -> {} // заголовок файла — пропускаем
            version != null -> body.appendLine(line)
        }
    }
    flush()
    return entries
}

/** Диалог истории изменений: каждая версия — плашкой, установленная выделена. */
@Composable
private fun ChangelogDialog(text: String, currentVersion: String, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val versions = remember(text) { parseChangelog(text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        icon = { Icon(Icons.Rounded.Info, null, tint = cs.primary) },
        title = { Text("Что нового") },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (versions.isEmpty()) Text(text, style = MaterialTheme.typography.bodyMedium)
                versions.forEach { entry ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        VersionPill(entry.version, current = entry.version.removePrefix("v") == currentVersion)
                        Markdown(content = entry.body)
                    }
                }
            }
        },
    )
}

@Composable
private fun VersionPill(version: String, current: Boolean) {
    val cs = MaterialTheme.colorScheme
    val color = if (current) cs.primary else cs.onSurfaceVariant
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(8.dp)) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(version, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            if (current) {
                Spacer(Modifier.width(6.dp))
                Text("• установлена", color = color, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * Содержимое сворачиваемой секции «Расход»: помодельная статистика (токены,
 * запросы) и оценка суммы по цене, которую пользователь задаёт из своего тарифа
 * (API денег не отдаёт). Без внешней карточки — её даёт CollapsibleCard.
 */
@Composable
private fun UsageContent(
    ui: UiState,
    onReset: () -> Unit,
    onSetPrice: (String, Double, Double) -> Unit,
    onRemove: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    val (billingUrl, billingLabel) = if (ui.provider == ProviderId.CLOUD_RU) {
        "https://console.cloud.ru" to "Кабинет Cloud.ru"
    } else {
        "https://console.anthropic.com/settings/billing" to "Биллинг Anthropic"
    }
    val totalTokens = ui.modelUsage.sumOf { it.total }
    val totalCost = ui.modelUsage.sumOf { modelCost(it, ui.modelPrices[it.model]) }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        UsageStat("За сессию", "${formatTokens(ui.sessionTokens.toLong())} ток.")
        UsageStat("Всего", "${formatTokens(totalTokens)} ток.")
        if (totalCost > 0) UsageStat("Сумма ≈", "${formatRub(totalCost)} ₽")
    }

    if (ui.modelUsage.isEmpty()) {
        Text(
            "Расхода пока нет — появится после первого запроса к модели.",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
        )
    } else {
        HorizontalDivider(color = cs.outlineVariant)
        // key по модели: при фоновой пересортировке списка (расход обновился) слот
        // строки не переносится на другую модель — недопечатанная цена не теряется.
        ui.modelUsage.forEach { usage ->
            key(usage.model) {
                ModelUsageRow(usage, ui.modelPrices[usage.model], onSetPrice) { onRemove(usage.model) }
            }
        }
    }

    Text(
        "API отдаёт расход в токенах, а не деньги. Укажите цену модели из своего тарифа " +
            "(₽ за 1 млн токенов) — посчитаем сумму; точный остаток средств смотрите в кабинете провайдера.",
        style = MaterialTheme.typography.bodySmall,
        color = cs.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { uriHandler.openUri(billingUrl) },
            modifier = Modifier.weight(1f).height(48.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Rounded.OpenInNew, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(billingLabel, maxLines = 1)
        }
        if (totalTokens > 0) {
            TextButton(onClick = onReset) { Text("Сбросить") }
        }
    }
}

/**
 * Строка одной модели: входные/выходные токены, запросы, оценка суммы и два поля
 * цены (₽ за 1 млн входных и генерируемых токенов — они у Cloud.ru разные).
 */
@Composable
private fun ModelUsageRow(
    usage: ModelUsage,
    price: ModelPrice?,
    onSetPrice: (String, Double, Double) -> Unit,
    onRemove: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val inPrice = price?.input ?: 0.0
    val outPrice = price?.output ?: 0.0
    var inText by remember(usage.model, inPrice) { mutableStateOf(if (inPrice > 0) formatPrice(inPrice) else "") }
    var outText by remember(usage.model, outPrice) { mutableStateOf(if (outPrice > 0) formatPrice(outPrice) else "") }
    val cost = modelCost(usage, price)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    usage.model.substringAfterLast('/'),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    "вход ${formatTokens(usage.prompt)} · выход ${formatTokens(usage.completion)} · ${usage.requests} запр.",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = cs.onSurfaceVariant,
                )
            }
            if (cost > 0) {
                Text(
                    "≈ ${formatRub(cost)} ₽",
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.primary,
                )
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Rounded.DeleteOutline, "Убрать модель из учёта", Modifier.size(18.dp), tint = cs.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PriceField("Вход, ₽/1М", inText, Modifier.weight(1f)) {
                inText = it
                onSetPrice(usage.model, inText.toPrice(), outText.toPrice())
            }
            PriceField("Выход, ₽/1М", outText, Modifier.weight(1f)) {
                outText = it
                onSetPrice(usage.model, inText.toPrice(), outText.toPrice())
            }
        }
    }
}

@Composable
private fun PriceField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(9)) },
        modifier = modifier,
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = RoundedCornerShape(12.dp),
    )
}

/** Оценка суммы модели: входные токены × цена входа + выходные × цена выхода. */
private fun modelCost(usage: ModelUsage, price: ModelPrice?): Double {
    if (price == null) return 0.0
    return (usage.prompt / 1_000_000.0) * price.input + (usage.completion / 1_000_000.0) * price.output
}

private fun String.toPrice(): Double = replace(',', '.').toDoubleOrNull() ?: 0.0

/** Содержимое сворачиваемой секции «Приватность и хранение». */
@Composable
private fun InfoContent(ui: UiState) {
    InfoCard(
        Icons.Rounded.ErrorOutline,
        if (ui.keysEncrypted) {
            "Ключ хранится в зашифрованном виде только на устройстве. " +
                "Claude — console.anthropic.com; Cloud.ru — личный кабинет (сервисный аккаунт)."
        } else {
            "⚠️ Шифрованное хранилище недоступно на этом устройстве — ключ хранится без " +
                "шифрования в приватных данных приложения. Будьте осторожны на устройствах с root."
        },
    )
    InfoCard(
        Icons.Rounded.Info,
        "Вердикт LLM носит рекомендательный характер и не заменяет осмотр мастером. " +
            "Тексты диалога передаются провайдеру модели, а VIN при распознавании — сервису " +
            "NHTSA (США): это трансграничная передача данных. Не вводите персональные данные.",
    )
}

/**
 * Карточка-секция настроек с заголовком и сворачиваемым содержимым. В свёрнутом
 * виде показывает краткую сводку (summary), чтобы не терять контекст.
 */
@Composable
private fun CollapsibleCard(
    title: String,
    summary: String,
    initiallyExpanded: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (!expanded) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                if (expanded) "Свернуть" else "Развернуть",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun UsageStat(caption: String, value: String) {
    val cs = MaterialTheme.colorScheme
    Column {
        Text(caption.uppercase(), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** «1234567» → «1 234 567» (разделители разрядов). */
private fun formatTokens(n: Long): String =
    if (n >= 1000) "%,d".format(n).replace(',', ' ') else n.toString()

/** Сумма в рублях: пробел-разряды, запятая-десятичные (1234.5 → «1 234,50»). */
private fun formatRub(d: Double): String =
    String.format(java.util.Locale.US, "%,.2f", d).replace(',', ' ').replace('.', ',')

/** Цена без хвостовых нулей для поля ввода (1500.0 → «1500», 12.5 → «12.5»). */
private fun formatPrice(d: Double): String =
    if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

@Composable
private fun ModelDropdown(models: List<LlmModel>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = models.firstOrNull { it.id == selected }?.label ?: selected
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Модель") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.forEach { m ->
                DropdownMenuItem(text = { Text(m.label) }, onClick = { onSelect(m.id); expanded = false })
            }
        }
    }
}

/** Показывать ли кнопку прикрепления фото — Claude всегда vision, Cloud.ru — по имени модели. */
internal fun isVisionModel(provider: ProviderId, model: String): Boolean =
    provider == ProviderId.CLAUDE ||
        Regex("(?i)(vl|vision|4v|4\\.5v|gpt-4o|gpt-4\\.1|gpt-5|gemini|omni)").containsMatchIn(model)
