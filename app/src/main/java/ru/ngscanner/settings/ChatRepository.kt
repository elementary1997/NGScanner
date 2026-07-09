package ru.ngscanner.settings

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.ngscanner.llm.LlmMessage
import ru.ngscanner.ui.ChatMessage
import ru.ngscanner.ui.ChatRole

/** Архивная сессия диагностики: показанные сообщения + история для модели. */
@Serializable
data class ChatSession(
    val id: String,
    val dateIso: String,
    val title: String,
    val chat: List<ChatMessage>,
    val history: List<LlmMessage>,
)

/** Лёгкая карточка сессии для списка (без полного содержимого диалога). */
data class SessionSummary(
    val id: String,
    val title: String,
    val dateIso: String,
    val count: Int,
)

/**
 * Персистентность диалога диагностики: сохраняет активный диалог (показанные
 * сообщения и историю для модели), а также архив последних сессий, чтобы смерть
 * процесса или начало нового диалога не стирали контекст.
 *
 * Картинки из истории не сохраняются (они нужны только в момент запроса и
 * раздули бы хранилище), поэтому в восстановленной истории поле images пустое.
 */
class ChatRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    // ---- Активный диалог ----

    fun save(chat: List<ChatMessage>, history: List<LlmMessage>) {
        // Ограничиваем размер, чтобы блоб в SharedPreferences не рос без предела.
        prefs.edit()
            .putString(KEY_CHAT, json.encodeToString(chat.takeLast(MAX_CHAT)))
            .putString(KEY_HISTORY, json.encodeToString(slim(history)))
            .apply()
    }

    fun loadChat(): List<ChatMessage> = decode(KEY_CHAT)

    fun loadHistory(): List<LlmMessage> = decode(KEY_HISTORY)

    fun clear() {
        prefs.edit().remove(KEY_CHAT).remove(KEY_HISTORY).apply()
    }

    // ---- Архив сессий (последние [MAX_SESSIONS]) ----

    fun sessions(): List<SessionSummary> = loadSessions().map {
        SessionSummary(it.id, it.title, it.dateIso, it.chat.count { m -> m.role != ChatRole.TOOL })
    }

    /**
     * Архивирует текущий диалог в список последних сессий (не более [MAX_SESSIONS]).
     * Пустой диалог не сохраняется. Возвращает обновлённый список карточек.
     */
    fun archive(chat: List<ChatMessage>, history: List<LlmMessage>, id: String, dateIso: String): List<SessionSummary> {
        if (chat.isEmpty()) return sessions()
        val title = chat.firstOrNull { it.role == ChatRole.USER }?.text?.trim()
            ?.take(48)?.ifBlank { null } ?: "Сессия"
        // Ограничиваем размер архивируемого диалога, иначе пять сессий могут
        // накопить большой блоб, разбираемый на старте.
        val session = ChatSession(id, dateIso, title, chat.takeLast(MAX_CHAT), slim(history))
        val updated = (listOf(session) + loadSessions()).take(MAX_SESSIONS)
        prefs.edit().putString(KEY_SESSIONS, json.encodeToString(updated)).apply()
        return updated.map { SessionSummary(it.id, it.title, it.dateIso, it.chat.count { m -> m.role != ChatRole.TOOL }) }
    }

    fun session(id: String): ChatSession? = loadSessions().firstOrNull { it.id == id }

    /** Убирает сессию из архива (например, при её восстановлении в активную). */
    fun removeSession(id: String): List<SessionSummary> {
        val updated = loadSessions().filterNot { it.id == id }
        prefs.edit().putString(KEY_SESSIONS, json.encodeToString(updated)).apply()
        return updated.map { SessionSummary(it.id, it.title, it.dateIso, it.chat.count { m -> m.role != ChatRole.TOOL }) }
    }

    private fun loadSessions(): List<ChatSession> {
        val raw = prefs.getString(KEY_SESSIONS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<ChatSession>>(raw) }.getOrElse {
            // Повреждение/несовместимая схема — сохраняем сырой JSON, чтобы следующая
            // запись архива его не затёрла (как для гаража, ради консистентности).
            if (prefs.getString(KEY_SESSIONS_BACKUP, null) == null) {
                prefs.edit().putString(KEY_SESSIONS_BACKUP, raw).apply()
            }
            emptyList()
        }
    }

    private fun slim(history: List<LlmMessage>): List<LlmMessage> = history.map { it.copy(images = emptyList()) }

    private inline fun <reified T> decode(key: String): List<T> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<T>>(raw) }.getOrDefault(emptyList())
    }

    private companion object {
        const val PREFS = "ngscanner_chat"
        const val KEY_CHAT = "chat"
        const val KEY_HISTORY = "history"
        const val KEY_SESSIONS = "sessions"
        const val KEY_SESSIONS_BACKUP = "sessions_backup_raw"
        const val MAX_SESSIONS = 5
        const val MAX_CHAT = 300
    }
}
