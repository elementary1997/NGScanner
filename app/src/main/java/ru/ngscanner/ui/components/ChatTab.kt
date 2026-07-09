@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package ru.ngscanner.ui.components

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import ru.ngscanner.llm.LlmImage
import ru.ngscanner.util.ImageEncoder
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import ru.ngscanner.settings.SessionSummary
import ru.ngscanner.ui.ConnectionState
import ru.ngscanner.ui.UiState
import ru.ngscanner.ui.ChatMessage
import ru.ngscanner.ui.ChatRole

@Composable
internal fun ChatTab(
    ui: UiState,
    onSend: (String, List<LlmImage>) -> Unit,
    onClear: () -> Unit,
    onCancel: () -> Unit,
    onLocalDiagnose: () -> Unit,
    onRestore: (String) -> Unit,
    onAttachImage: (LlmImage) -> Unit,
    onClearImage: () -> Unit,
) {
    val listState = rememberLazyListState()
    val context = LocalContext.current
    // «Назад» из открытого диалога закрывает его к списку последних сессий (текущий
    // диалог уходит в архив, не теряется), а не переключает вкладку. Обработчик
    // объявлен глубже MainScreen → имеет приоритет над стеком вкладок.
    BackHandler(enabled = ui.chat.isNotEmpty() && !ui.diagnosing) { onClear() }
    // Автопрокрутка к последнему сообщению при появлении новых и при печати статусов.
    LaunchedEffect(ui.chat.size, ui.diagnosing) {
        val count = ui.chat.size + if (ui.diagnosing) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (ui.chat.isEmpty()) {
                item {
                    EmptyChatHint(
                        onSend = { onSend(it, emptyList()) },
                        onLocalDiagnose = onLocalDiagnose,
                        adapterConnected = ui.connection == ConnectionState.Connected,
                        sessions = ui.sessions,
                        onRestore = onRestore,
                    )
                }
            }
            items(ui.chat) { msg -> ChatBubble(msg) }
            if (ui.diagnosing) {
                item {
                    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Диагностирую…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onCancel) { Text("Прервать") }
                    }
                }
            }
        }
        ChatInput(
            enabled = !ui.diagnosing,
            visionEnabled = isVisionModel(ui.provider, ui.model),
            pendingImage = ui.pendingImage,
            onAttachImage = onAttachImage,
            onClearImage = onClearImage,
            onSend = onSend,
            onClear = onClear,
            onShare = { shareReport(context, ui.chat, ui.garage.activeCar?.title) },
            canShare = ui.chat.any { it.role == ChatRole.ASSISTANT },
            hasChat = ui.chat.isNotEmpty(),
        )
    }
}

/** Собирает диалог в текстовый отчёт и открывает системный «Поделиться». */
private fun shareReport(context: android.content.Context, chat: List<ChatMessage>, carTitle: String?) {
    val sb = StringBuilder("Диагностика NG Scanner")
    carTitle?.let { sb.append(" — ").append(it) }
    sb.append("\n\n")
    for (m in chat) {
        when (m.role) {
            ChatRole.USER -> sb.append("❓ ").append(m.text).append("\n\n")
            ChatRole.ASSISTANT -> sb.append(m.text).append("\n\n")
            else -> Unit
        }
    }
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_SUBJECT, "Диагностика авто — NG Scanner")
        putExtra(android.content.Intent.EXTRA_TEXT, sb.toString().trim())
    }
    context.startActivity(android.content.Intent.createChooser(intent, "Поделиться отчётом"))
}

/**
 * Преобразует GFM-таблицы Markdown в маркированный список: широкие таблицы не
 * помещаются на узком экране, а списком «первая ячейка · Заголовок: значение»
 * видно всю информацию. Не-табличный текст остаётся без изменений.
 */
private fun flattenMarkdownTables(md: String): String {
    if (!md.contains('|')) return md // быстрый путь — таблиц нет
    val lines = md.split("\n")
    val out = StringBuilder()
    var i = 0
    var inFence = false
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        // Ограждённые блоки кода (``` / ~~~): внутри строки с | — это код, не таблица.
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            inFence = !inFence
            out.append(line).append('\n')
            i++
            continue
        }
        val sep = lines.getOrNull(i + 1)
        // Настоящая таблица = заголовок + разделитель + хотя бы одна строка тела.
        val hasBody = lines.getOrNull(i + 2)?.let { isTableRow(it) } == true
        if (!inFence && isTableRow(line) && sep != null && isTableSeparator(sep) && hasBody) {
            val headers = tableCells(line)
            i += 2 // пропускаем строку заголовков и разделитель |---|
            while (i < lines.size && isTableRow(lines[i])) {
                val cells = tableCells(lines[i])
                out.append("- ")
                if (cells.isNotEmpty()) out.append("**").append(cells[0]).append("**")
                for (c in 1 until cells.size) {
                    val h = headers.getOrNull(c)?.takeIf { it.isNotBlank() }
                    out.append(if (h != null) " · $h: ${cells[c]}" else " · ${cells[c]}")
                }
                out.append('\n')
                i++
            }
        } else {
            out.append(line).append('\n')
            i++
        }
    }
    return out.toString().trimEnd('\n')
}

private fun isTableRow(line: String): Boolean {
    val t = line.trim()
    return t.startsWith("|") && t.count { it == '|' } >= 2
}

private fun isTableSeparator(line: String): Boolean {
    val t = line.trim()
    return t.startsWith("|") && t.contains('-') && t.all { it == '|' || it == '-' || it == ':' || it == ' ' }
}

private fun tableCells(line: String): List<String> =
    line.trim().trim('|').split('|').map { it.trim() }

private val SYMPTOMS = listOf(
    "Плавают обороты на холостых",
    "Троит на холодную",
    "Горит Check Engine",
    "Повышенный расход топлива",
    "Вибрация или странный звук",
    "Плохо заводится",
)

@Composable
private fun EmptyChatHint(
    onSend: (String) -> Unit,
    onLocalDiagnose: () -> Unit,
    adapterConnected: Boolean,
    sessions: List<SessionSummary>,
    onRestore: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 36.dp, start = 8.dp, end = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.HealthAndSafety, null, Modifier.size(52.dp), tint = cs.primary)
        Text("Диагностика через LLM", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Опишите симптом или задайте любой вопрос об авто. С подключённым адаптером модель " +
                "прочитает коды и параметры и даст вердикт; без адаптера — ответит на общие вопросы.",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            SYMPTOMS.forEach { symptom ->
                AssistChip(onClick = { onSend(symptom) }, label = { Text(symptom) })
            }
        }
        Button(onClick = { onSend(QUICK_DIAGNOSE_PROMPT) }, shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Rounded.Bolt, null)
            Spacer(Modifier.width(8.dp))
            Text("Быстрая диагностика")
        }
        // Офлайн-диагностика читает коды с ЭБУ — без адаптера ей неоткуда брать данные.
        OutlinedButton(
            onClick = onLocalDiagnose,
            enabled = adapterConnected,
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(Icons.Rounded.CloudOff, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Без интернета — по кодам")
        }
        if (!adapterConnected) {
            Text(
                "Диагностика по кодам требует подключённого адаптера — коды читаются с ЭБУ " +
                    "автомобиля. Подключите ELM327 на вкладке «Приборы».",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        if (sessions.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            SessionsBlock(sessions, onRestore)
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "Диагностика охватывает двигатель и систему выпуска (generic OBD-II). ABS, подушки " +
                "безопасности и АКПП требуют дилерских протоколов и здесь недоступны.",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
    }
}

/** Список последних сессий диагностики: тап восстанавливает диалог. */
@Composable
private fun SessionsBlock(sessions: List<SessionSummary>, onRestore: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.History, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(
                "Последние сессии",
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurfaceVariant,
            )
        }
        sessions.forEach { s ->
            Surface(
                onClick = { onRestore(s.id) },
                shape = RoundedCornerShape(14.dp),
                color = cs.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            s.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        Text(
                            "${s.dateIso} · ${s.count} сообщ.",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = cs.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(msg: ChatMessage) {
    val cs = MaterialTheme.colorScheme

    // Служебный статус инструмента — компактная строка, как «думаю…» у агента.
    if (msg.role == ChatRole.TOOL) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Sync, null, Modifier.size(16.dp), tint = cs.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(
                msg.text,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = cs.onSurfaceVariant,
            )
        }
        return
    }

    val bg: Color
    val fg: Color
    when (msg.role) {
        ChatRole.USER -> { bg = cs.primary; fg = cs.onPrimary }
        ChatRole.SYSTEM -> { bg = cs.errorContainer; fg = cs.onErrorContainer }
        else -> { bg = cs.surfaceVariant; fg = cs.onSurfaceVariant } // ASSISTANT
    }
    val alignment = if (msg.role == ChatRole.USER) Alignment.End else Alignment.Start
    // Ответ модели занимает всю ширину — таблицы и код помещаются целиком.
    val widthFraction = if (msg.role == ChatRole.ASSISTANT) 1f else 0.9f
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    // Долгое нажатие копирует текст сообщения — удобно переслать свой вопрос заново,
    // если запрос сорвался (нет сети), не перепечатывая его руками.
    val copy = {
        clipboard.setText(AnnotatedString(msg.text))
        Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
    }
    Column(Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .align(alignment)
                .fillMaxWidth(widthFraction)
                // Долгое нажатие = копировать; без ripple по всей плашке ответа.
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                    onLongClick = copy,
                ),
            shape = RoundedCornerShape(16.dp),
            color = bg,
        ) {
            if (msg.role == ChatRole.ASSISTANT) {
                // Markdown-ответ модели рендерится; широкие таблицы предварительно
                // сплющиваются в список, иначе на узком экране их не прочитать.
                Markdown(content = flattenMarkdownTables(msg.text), modifier = Modifier.padding(14.dp))
            } else {
                Text(msg.text, Modifier.padding(14.dp), color = fg, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ChatInput(
    enabled: Boolean,
    visionEnabled: Boolean,
    pendingImage: LlmImage?,
    onAttachImage: (LlmImage) -> Unit,
    onClearImage: () -> Unit,
    onSend: (String, List<LlmImage>) -> Unit,
    onClear: () -> Unit,
    onShare: () -> Unit,
    canShare: Boolean,
    hasChat: Boolean,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        // Кодируем выбранное фото и кладём в состояние ViewModel (переживает поворот).
        if (uri != null) scope.launch { ImageEncoder.encode(context, uri)?.let(onAttachImage) }
    }

    val cs = MaterialTheme.colorScheme
    // Панель ввода поднимается над клавиатурой. Навбар уже съеден consumeWindowInsets
    // на уровне контента, поэтому imePadding даёт ровно высоту клавиатуры без двойного отступа.
    Surface(modifier = Modifier.imePadding(), tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.6f))

            // Действия над диалогом — тонкая строка справа, чтобы не тесниться в поле ввода.
            if (hasChat || canShare) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (canShare) {
                        TextButton(onClick = onShare, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                            Icon(Icons.Rounded.IosShare, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Поделиться", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (hasChat) {
                        TextButton(onClick = onClear, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                            Icon(Icons.Rounded.DeleteSweep, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Новый диалог", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            // Прикреплённое фото — компактный чип.
            if (pendingImage != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = cs.surfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Image, null, Modifier.size(18.dp), tint = cs.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Фото прикреплено", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                        IconButton(onClick = onClearImage, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Rounded.Close, "Убрать фото", Modifier.size(18.dp), tint = cs.onSurfaceVariant)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 10.dp, top = 8.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (visionEnabled) {
                    IconButton(onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) {
                        Icon(Icons.Rounded.AddPhotoAlternate, "Прикрепить фото", tint = cs.onSurfaceVariant)
                    }
                } else {
                    Spacer(Modifier.width(6.dp))
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Опишите проблему или вопрос…") },
                    maxLines = 5,
                    shape = RoundedCornerShape(26.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = cs.surfaceVariant.copy(alpha = 0.45f),
                        unfocusedContainerColor = cs.surfaceVariant.copy(alpha = 0.45f),
                        focusedBorderColor = cs.primary,
                        unfocusedBorderColor = Color.Transparent,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = {
                        onSend(text, pendingImage?.let { listOf(it) } ?: emptyList())
                        text = ""
                        onClearImage()
                    },
                    enabled = enabled && (text.isNotBlank() || pendingImage != null),
                    shape = CircleShape,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, "Отправить")
                }
            }
        }
    }
}

private const val QUICK_DIAGNOSE_PROMPT =
    "Проведи диагностику автомобиля: прочитай активные коды неисправностей и текущие " +
        "параметры двигателя, затем дай понятный вердикт — что вероятно неисправно и что делать."
