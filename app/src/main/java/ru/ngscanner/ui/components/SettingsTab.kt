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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ErrorOutline
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ru.ngscanner.llm.LlmModel
import ru.ngscanner.llm.ProviderId
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
    var keyText by remember(ui.provider, ui.apiKey) { mutableStateOf(ui.apiKey) }
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
            UsageContent(ui, onReset = vm::resetUsage, onSetPrice = vm::setModelPrice)
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

/**
 * Содержимое сворачиваемой секции «Расход»: помодельная статистика (токены,
 * запросы) и оценка суммы по цене, которую пользователь задаёт из своего тарифа
 * (API денег не отдаёт). Без внешней карточки — её даёт CollapsibleCard.
 */
@Composable
private fun UsageContent(ui: UiState, onReset: () -> Unit, onSetPrice: (String, Double) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    val (billingUrl, billingLabel) = if (ui.provider == ProviderId.CLOUD_RU) {
        "https://console.cloud.ru" to "Кабинет Cloud.ru"
    } else {
        "https://console.anthropic.com/settings/billing" to "Биллинг Anthropic"
    }
    val totalTokens = ui.modelUsage.sumOf { it.total }
    val totalCost = ui.modelUsage.sumOf { (it.total / 1_000_000.0) * (ui.modelPrices[it.model] ?: 0.0) }

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
        ui.modelUsage.forEach { usage ->
            ModelUsageRow(usage, ui.modelPrices[usage.model] ?: 0.0, onSetPrice)
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

/** Строка одной модели: название, токены/запросы, оценка суммы и поле цены. */
@Composable
private fun ModelUsageRow(usage: ModelUsage, price: Double, onSetPrice: (String, Double) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var priceText by remember(usage.model, price) { mutableStateOf(if (price > 0) formatPrice(price) else "") }
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
                    "${formatTokens(usage.total)} ток · ${usage.requests} запр.",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = cs.onSurfaceVariant,
                )
            }
            if (price > 0) {
                Text(
                    "≈ ${formatRub((usage.total / 1_000_000.0) * price)} ₽",
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.primary,
                )
            }
        }
        OutlinedTextField(
            value = priceText,
            onValueChange = { v ->
                val filtered = v.filter { it.isDigit() || it == '.' || it == ',' }.take(9)
                priceText = filtered
                onSetPrice(usage.model, filtered.replace(',', '.').toDoubleOrNull() ?: 0.0)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Цена, ₽ за 1 млн токенов") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(12.dp),
        )
    }
}

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
