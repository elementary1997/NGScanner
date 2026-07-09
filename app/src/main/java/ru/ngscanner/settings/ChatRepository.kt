package ru.ngscanner.settings

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.ngscanner.llm.LlmMessage
import ru.ngscanner.ui.ChatMessage

/**
 * Персистентность диалога диагностики: сохраняет показанные сообщения и
 * историю для модели, чтобы смерть процесса не стирала контекст.
 *
 * Картинки из истории не сохраняются (они нужны только в момент запроса и
 * раздули бы хранилище), поэтому в восстановленной истории поле images пустое.
 */
class ChatRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun save(chat: List<ChatMessage>, history: List<LlmMessage>) {
        val slimHistory = history.map { it.copy(images = emptyList()) }
        prefs.edit()
            .putString(KEY_CHAT, json.encodeToString(chat))
            .putString(KEY_HISTORY, json.encodeToString(slimHistory))
            .apply()
    }

    fun loadChat(): List<ChatMessage> = decode(KEY_CHAT)

    fun loadHistory(): List<LlmMessage> = decode(KEY_HISTORY)

    fun clear() {
        prefs.edit().remove(KEY_CHAT).remove(KEY_HISTORY).apply()
    }

    private inline fun <reified T> decode(key: String): List<T> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<T>>(raw) }.getOrDefault(emptyList())
    }

    private companion object {
        const val PREFS = "ngscanner_chat"
        const val KEY_CHAT = "chat"
        const val KEY_HISTORY = "history"
    }
}
