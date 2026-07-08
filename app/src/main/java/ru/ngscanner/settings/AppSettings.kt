package ru.ngscanner.settings

import android.content.Context
import ru.ngscanner.llm.ProviderId

/**
 * Настройки приложения и API-ключи провайдеров.
 *
 * TODO(безопасность): перевести хранилище на EncryptedSharedPreferences
 * (androidx.security:security-crypto). Пока ключи лежат в приватных
 * SharedPreferences приложения — они не попадают ни в APK, ни в git.
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var provider: ProviderId
        get() = runCatching { ProviderId.valueOf(prefs.getString(KEY_PROVIDER, "").orEmpty()) }
            .getOrDefault(ProviderId.CLAUDE)
        set(value) = prefs.edit().putString(KEY_PROVIDER, value.name).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    fun apiKey(p: ProviderId): String = prefs.getString(keyFor(p), "").orEmpty()

    fun setApiKey(p: ProviderId, key: String) {
        prefs.edit().putString(keyFor(p), key).apply()
    }

    private fun keyFor(p: ProviderId) = "api_key_${p.name}"

    companion object {
        private const val PREFS = "ngscanner_settings"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_MODEL = "model"
        const val DEFAULT_MODEL = "claude-opus-4-8"
    }
}
