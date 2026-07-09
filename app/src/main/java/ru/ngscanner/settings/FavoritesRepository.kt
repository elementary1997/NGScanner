package ru.ngscanner.settings

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.ngscanner.ui.DeviceUi

/**
 * Хранилище избранных OBD-адаптеров (имя + MAC-адрес) в SharedPreferences.
 * MAC-адрес не секрет, поэтому обычное приватное хранилище без шифрования.
 * Избранные показываются на вкладке «Приборы» для быстрого подключения.
 */
class FavoritesRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<DeviceUi> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<DeviceUi>>(raw) }.getOrDefault(emptyList())
    }

    /** Сохраняет полный список избранного (источник истины — состояние ViewModel). */
    fun save(favorites: List<DeviceUi>) {
        prefs.edit().putString(KEY, json.encodeToString(favorites)).apply()
    }

    private companion object {
        const val PREFS = "ngscanner_favorites"
        const val KEY = "favorites"
    }
}
