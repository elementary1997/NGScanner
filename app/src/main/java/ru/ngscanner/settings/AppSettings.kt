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

    /** Выбор и ПОРЯДОК PID для дашборда (пустой список = дефолтная раскладка). */
    var dashboardPids: List<String>
        get() = runCatching { prefs.getString(KEY_DASHBOARD_PIDS, "") }.getOrNull()
            ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        set(value) { runCatching { prefs.edit().putString(KEY_DASHBOARD_PIDS, value.joinToString(",")).apply() } }

    /**
     * Авто-отключение адаптера, если ЭБУ долго молчит (защита АКБ от разряда
     * забытым в разъёме ELM327). По умолчанию включено; можно выключить для
     * длительного мониторинга на заведённом двигателе.
     */
    var batteryGuard: Boolean
        get() = runCatching { prefs.getBoolean(KEY_BATTERY_GUARD, true) }.getOrDefault(true)
        set(value) { runCatching { prefs.edit().putBoolean(KEY_BATTERY_GUARD, value).apply() } }

    /** Не гасить экран во время активной сессии с адаптером (удобно при диагностике). */
    var keepScreenOn: Boolean
        get() = runCatching { prefs.getBoolean(KEY_KEEP_SCREEN, true) }.getOrDefault(true)
        set(value) { runCatching { prefs.edit().putBoolean(KEY_KEEP_SCREEN, value).apply() } }

    /** Проверять новую версию приложения при запуске и уведомлять о ней. */
    var updateCheck: Boolean
        get() = runCatching { prefs.getBoolean(KEY_UPDATE_CHECK, true) }.getOrDefault(true)
        set(value) { runCatching { prefs.edit().putBoolean(KEY_UPDATE_CHECK, value).apply() } }

    /**
     * Частота опроса приборов. Медленнее = меньше нагрузка на дешёвые клоны ELM327
     * (реже теряют кадры) и экономнее для АКБ; быстрее = живее графики. Значение —
     * базовый интервал в мс между циклами опроса.
     */
    var pollIntervalMs: Long
        get() = runCatching { prefs.getLong(KEY_POLL_INTERVAL, DEFAULT_POLL_MS) }.getOrDefault(DEFAULT_POLL_MS)
        set(value) { runCatching { prefs.edit().putLong(KEY_POLL_INTERVAL, value).apply() } }

    /** Цена топлива, ₽/л (0 = не задана). SharedPreferences не держит Double — храним Float. */
    var fuelPrice: Double
        get() = runCatching { prefs.getFloat(KEY_FUEL_PRICE, 0f).toDouble() }.getOrDefault(0.0)
        set(value) { runCatching { prefs.edit().putFloat(KEY_FUEL_PRICE, value.toFloat()).apply() } }

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
        private const val KEY_DASHBOARD_PIDS = "dashboard_pids"
        private const val KEY_BATTERY_GUARD = "battery_guard"
        private const val KEY_KEEP_SCREEN = "keep_screen_on"
        private const val KEY_UPDATE_CHECK = "update_check"
        private const val KEY_POLL_INTERVAL = "poll_interval_ms"
        private const val KEY_FUEL_PRICE = "fuel_price"
        const val DEFAULT_MODEL = "claude-opus-4-8"

        /** Базовый интервал опроса приборов (мс) — «обычный» режим. */
        const val DEFAULT_POLL_MS = 1500L
        const val POLL_MS_ECONOMY = 3000L
        const val POLL_MS_FAST = 800L
    }
}
