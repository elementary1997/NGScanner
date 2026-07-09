@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package ru.ngscanner.ui.components

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.ngscanner.llm.LlmImage
import ru.ngscanner.util.Exporter
import ru.ngscanner.util.ImageEncoder
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ripple
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.DpOffset
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
    val visionEnabled = isVisionModel(ui.provider, ui.model)
    // Переключились на модель без vision (напр. Cloud.ru) — прикреплённое фото
    // сбрасываем, иначе отправка ушла бы в API с ошибкой.
    LaunchedEffect(visionEnabled) {
        if (!visionEnabled && ui.pendingImage != null) onClearImage()
    }
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
            visionEnabled = visionEnabled,
            pendingImage = ui.pendingImage,
            onAttachImage = onAttachImage,
            onClearImage = onClearImage,
            onSend = onSend,
        )
    }
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
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var menuOpen by remember { mutableStateOf(false) }
    var pressAt by remember { mutableStateOf(Offset.Zero) }
    val interaction = remember { MutableInteractionSource() }
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.align(alignment).fillMaxWidth(widthFraction)) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = bg,
                // Подсвечиваем сообщение, для которого открыто меню.
                border = if (menuOpen) BorderStroke(1.5.dp, cs.primary) else null,
            ) {
                Box(
                    Modifier
                        // Тап/долгое нажатие открывают меню действий у точки касания;
                        // ripple даёт понятную обратную связь, что нажатие поймано.
                        .indication(interaction, ripple())
                        .pointerInput(msg.text) {
                            detectTapGestures(
                                onPress = {
                                    val press = PressInteraction.Press(it)
                                    interaction.emit(press)
                                    interaction.emit(
                                        if (tryAwaitRelease()) PressInteraction.Release(press) else PressInteraction.Cancel(press),
                                    )
                                },
                                onTap = { pressAt = it; menuOpen = true },
                                onLongPress = { pressAt = it; menuOpen = true },
                            )
                        },
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
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                offset = DpOffset(with(density) { pressAt.x.toDp() }, with(density) { pressAt.y.toDp() }),
            ) {
                DropdownMenuItem(
                    text = { Text("Копировать") },
                    leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                    onClick = {
                        menuOpen = false
                        clipboard.setText(AnnotatedString(msg.text))
                        Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Поделиться") },
                    leadingIcon = { Icon(Icons.Rounded.IosShare, null) },
                    onClick = { menuOpen = false; Exporter.shareText(context, msg.text) },
                )
                if (msg.role == ChatRole.ASSISTANT) {
                    DropdownMenuItem(
                        text = { Text("Сохранить в PDF") },
                        leadingIcon = { Icon(Icons.Rounded.PictureAsPdf, null) },
                        onClick = {
                            menuOpen = false
                            scope.launch {
                                val uri = withContext(Dispatchers.IO) {
                                    Exporter.buildPdf(context, "Ответ NG Scanner", msg.text, msg.text.hashCode().toLong())
                                }
                                Exporter.sharePdf(context, uri)
                            }
                        },
                    )
                }
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
) {
    var text by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        // Кодируем выбранное фото и кладём в состояние ViewModel (переживает поворот).
        if (uri != null) scope.launch { ImageEncoder.encode(context, uri)?.let(onAttachImage) }
    }

    val cs = MaterialTheme.colorScheme
    val canSend = enabled && (text.isNotBlank() || pendingImage != null)

    // Одна компактная «пилюля»: «+» прикладывает вложение, поле на BasicTextField
    // (без тяжёлого хрома Material) и круглая кнопка отправки. imePadding поднимает
    // панель над клавиатурой без двойного отступа (навбар съеден consumeWindowInsets).
    Surface(modifier = Modifier.imePadding(), color = cs.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            // Прикреплённое фото — компактный чип над полем (только для vision-модели).
            if (pendingImage != null && visionEnabled) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = cs.surfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Image, null, Modifier.size(18.dp), tint = cs.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Фото", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                        IconButton(onClick = onClearImage, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Rounded.Close, "Убрать фото", Modifier.size(18.dp), tint = cs.onSurfaceVariant)
                        }
                    }
                }
            }

            Surface(shape = RoundedCornerShape(26.dp), color = cs.surfaceVariant.copy(alpha = 0.55f)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // «+» — прикрепить вложение (фото). Доступно только для vision-моделей.
                    if (visionEnabled) {
                        IconButton(
                            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            modifier = Modifier.size(38.dp),
                        ) {
                            Icon(Icons.Rounded.Add, "Прикрепить вложение", Modifier.size(22.dp), tint = cs.onSurfaceVariant)
                        }
                    } else {
                        Spacer(Modifier.width(16.dp))
                    }

                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 9.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = cs.onSurface),
                        cursorBrush = SolidColor(cs.primary),
                        maxLines = 5,
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (text.isEmpty()) {
                                    Text(
                                        "Опишите проблему или вопрос…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = cs.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                    Spacer(Modifier.width(4.dp))
                    FilledIconButton(
                        onClick = {
                            val images = if (visionEnabled) pendingImage?.let { listOf(it) } ?: emptyList() else emptyList()
                            onSend(text, images)
                            text = ""
                            onClearImage()
                        },
                        enabled = canSend,
                        shape = CircleShape,
                        modifier = Modifier.size(34.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = cs.primary,
                            contentColor = cs.onPrimary,
                            disabledContainerColor = cs.surfaceVariant,
                            disabledContentColor = cs.onSurfaceVariant,
                        ),
                    ) {
                        // Стрелка вверх центрируется ровно (в отличие от «бумажного самолётика»).
                        Icon(Icons.Rounded.ArrowUpward, "Отправить", Modifier.size(19.dp))
                    }
                }
            }
        }
    }
}

private const val QUICK_DIAGNOSE_PROMPT =
    "Проведи диагностику автомобиля: прочитай активные коды неисправностей и текущие " +
        "параметры двигателя, затем дай понятный вердикт — что вероятно неисправно и что делать."
