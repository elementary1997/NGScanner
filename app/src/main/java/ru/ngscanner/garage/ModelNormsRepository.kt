package ru.ngscanner.garage

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Кэш «нормальных значений» параметров под конкретную машину, полученных от
 * ассистента. Ключ верхнего уровня — id машины, внутренний ключ — команда PID
 * (например `010C`), значение — короткая строка нормы («780–840 об/мин»).
 *
 * Кэш избавляет от повторных запросов к модели: один раз узнали норму для
 * машины — она сохраняется и показывается в приборах офлайн.
 */
class ModelNormsRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    /** Нормы для машины: `pidCmd -> текст нормы`. */
    fun normsFor(carId: String): Map<String, String> = load()[carId].orEmpty()

    fun setNorm(carId: String, pidCmd: String, norm: String) {
        val all = load().toMutableMap()
        val forCar = all[carId].orEmpty().toMutableMap()
        forCar[pidCmd] = norm
        all[carId] = forCar
        prefs.edit().putString(KEY, json.encodeToString(all)).apply()
    }

    private fun load(): Map<String, Map<String, String>> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, Map<String, String>>>(raw) }.getOrDefault(emptyMap())
    }

    private companion object {
        const val PREFS = "ngscanner_norms"
        const val KEY = "norms"
    }
}
