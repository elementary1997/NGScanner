package ru.ngscanner.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import ru.ngscanner.llm.ProviderId

/**
 * Настройки приложения и API-ключи провайдеров.
 *
 * Ключи хранятся в [EncryptedSharedPreferences] (шифрование на ключе из
 * Android Keystore). Если шифрованное хранилище недоступно (редкие устройства
 * с повреждённым keystore) — откатываемся на обычные приватные prefs, чтобы
 * приложение не падало.
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        context.getSharedPreferences(PREFS_FALLBACK, Context.MODE_PRIVATE)
    }

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
        private const val PREFS = "ngscanner_settings_enc"
        private const val PREFS_FALLBACK = "ngscanner_settings"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_MODEL = "model"
        const val DEFAULT_MODEL = "claude-opus-4-8"
    }
}
