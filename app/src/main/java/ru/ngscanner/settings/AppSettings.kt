package ru.ngscanner.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import ru.ngscanner.llm.ProviderId

/**
 * Настройки приложения и API-ключи провайдеров.
 *
 * Ключи хранятся в [EncryptedSharedPreferences] (шифрование на ключе из Android
 * Keystore). Если шифрованное хранилище недоступно (редкие устройства с
 * повреждённым keystore) — откатываемся на обычные приватные prefs, чтобы
 * приложение не падало, и явно помечаем это флагом [encrypted]: UI показывает
 * предупреждение, что ключ хранится без шифрования.
 *
 * При первом запуске новой (шифрованной) версии данные из прежнего plaintext-
 * хранилища переносятся один раз, после чего старый файл очищается.
 */
class AppSettings(context: Context) {

    /** Хранятся ли ключи в зашифрованном виде (false — редкий фолбэк на plaintext). */
    val encrypted: Boolean

    private val prefs: SharedPreferences

    init {
        val enc = runCatching { buildEncrypted(context) }
            .onFailure { Log.w(TAG, "EncryptedSharedPreferences недоступно, откат на plaintext", it) }
            .getOrNull()
        if (enc != null) {
            encrypted = true
            prefs = enc
            migrateLegacy(context, enc)
        } else {
            encrypted = false
            prefs = context.getSharedPreferences(PREFS_FALLBACK, Context.MODE_PRIVATE)
        }
    }

    var provider: ProviderId
        get() = runCatching { ProviderId.valueOf(prefs.getString(KEY_PROVIDER, "").orEmpty()) }
            .getOrDefault(ProviderId.CLAUDE)
        set(value) { runCatching { prefs.edit().putString(KEY_PROVIDER, value.name).apply() } }

    // EncryptedSharedPreferences расшифровывает лениво прямо в getString — при
    // повреждённом keystore это бросает исключение, поэтому чтение обёрнуто.
    var model: String
        get() = runCatching { prefs.getString(KEY_MODEL, DEFAULT_MODEL) }.getOrNull() ?: DEFAULT_MODEL
        set(value) { runCatching { prefs.edit().putString(KEY_MODEL, value).apply() } }

    /** Выбранные пользователем PID для панели графиков (пустой список = показывать все). */
    var graphPids: List<String>
        get() = runCatching { prefs.getString(KEY_GRAPH_PIDS, "") }.getOrNull()
            ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        set(value) { runCatching { prefs.edit().putString(KEY_GRAPH_PIDS, value.joinToString(",")).apply() } }

    fun apiKey(p: ProviderId): String =
        runCatching { prefs.getString(keyFor(p), "") }.getOrNull().orEmpty()

    fun setApiKey(p: ProviderId, key: String) {
        runCatching { prefs.edit().putString(keyFor(p), key).apply() }
    }

    private fun keyFor(p: ProviderId) = "api_key_${p.name}"

    private fun buildEncrypted(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * Разовый перенос настроек из прежнего plaintext-хранилища (до перехода на
     * шифрование) в новое. После переноса старый файл очищается, чтобы ключ не
     * оставался в открытом виде.
     */
    private fun migrateLegacy(context: Context, enc: SharedPreferences) {
        val legacy = context.getSharedPreferences(PREFS_FALLBACK, Context.MODE_PRIVATE)
        if (legacy.all.isEmpty()) return
        // Переносим только если новое хранилище ещё не заполнено.
        val encEmpty = !enc.contains(KEY_PROVIDER) &&
            !enc.contains(keyFor(ProviderId.CLAUDE)) &&
            !enc.contains(keyFor(ProviderId.CLOUD_RU))
        if (encEmpty) {
            val editor = enc.edit()
            legacy.all.forEach { (k, v) -> if (v is String) editor.putString(k, v) }
            // Синхронный commit(): plaintext чистим только при подтверждённом успехе,
            // иначе убийство процесса между двумя async-коммитами потеряло бы ключ.
            if (runCatching { editor.commit() }.getOrDefault(false)) {
                legacy.edit().clear().apply()
            }
        } else {
            // Новое хранилище уже заполнено — устаревший plaintext можно очистить.
            legacy.edit().clear().apply()
        }
    }

    companion object {
        private const val TAG = "AppSettings"
        private const val PREFS = "ngscanner_settings_enc"
        private const val PREFS_FALLBACK = "ngscanner_settings"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_MODEL = "model"
        private const val KEY_GRAPH_PIDS = "graph_pids"
        const val DEFAULT_MODEL = "claude-opus-4-8"
    }
}
