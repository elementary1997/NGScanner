package ru.ngscanner.garage

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Интервалы ТО по машинам в отдельном хранилище (не в Car — не ломаем схему гаража).
 * Ключ верхнего уровня — id машины, значение — список интервалов. По образцу
 * [ModelNormsRepository].
 */
class MaintenanceRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun intervalsFor(carId: String): List<MaintenanceInterval> = load()[carId].orEmpty()

    /** Добавляет или заменяет интервал по id. */
    fun upsert(carId: String, interval: MaintenanceInterval) {
        val all = load().toMutableMap()
        val list = all[carId].orEmpty().toMutableList()
        val idx = list.indexOfFirst { it.id == interval.id }
        if (idx >= 0) list[idx] = interval else list.add(interval)
        all[carId] = list
        persist(all)
    }

    fun delete(carId: String, intervalId: String) {
        val all = load().toMutableMap()
        val list = all[carId].orEmpty().filter { it.id != intervalId }
        if (list.isEmpty()) all.remove(carId) else all[carId] = list
        persist(all)
    }

    /** Отмечает ТО выполненным: обновляет базу отсчёта (пробег/дата). */
    fun markServiced(carId: String, intervalId: String, km: Int?, dateIso: String) {
        val all = load().toMutableMap()
        all[carId] = all[carId].orEmpty().map {
            if (it.id == intervalId) it.copy(lastServiceKm = km, lastServiceDateIso = dateIso) else it
        }
        persist(all)
    }

    /** Удаляет все интервалы машины (при удалении её из гаража). */
    fun clearFor(carId: String) {
        val all = load()
        if (carId !in all) return
        persist(all.toMutableMap().apply { remove(carId) })
    }

    private fun persist(all: Map<String, List<MaintenanceInterval>>) {
        runCatching { prefs.edit().putString(KEY, json.encodeToString(all)).apply() }
    }

    private fun load(): Map<String, List<MaintenanceInterval>> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, List<MaintenanceInterval>>>(raw) }.getOrDefault(emptyMap())
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
        private const val PREFS = "ngscanner_maintenance"
        private const val KEY = "intervals"
    }
}
