package ru.ngscanner.perf

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Заезды перф-замеров: результатов мало и они крошечные — держим в
 * SharedPreferences одним JSON-списком (по образцу [ru.ngscanner.garage.MaintenanceRepository]),
 * новые первыми, не больше [MAX_RUNS].
 */
class PerfRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun runs(): List<PerfRun> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<PerfRun>>(raw) }.getOrDefault(emptyList())
    }

    /** Добавляет заезд (новые первыми) и возвращает актуальный список. */
    fun add(run: PerfRun): List<PerfRun> {
        val kept = (listOf(run) + runs()).take(MAX_RUNS)
        persist(kept)
        return kept
    }

    fun delete(id: String): List<PerfRun> {
        val kept = runs().filterNot { it.id == id }
        persist(kept)
        return kept
    }

    private fun persist(runs: List<PerfRun>) {
        runCatching { prefs.edit().putString(KEY, json.encodeToString(runs)).apply() }
    }

    companion object {
        const val MAX_RUNS = 30
        fun newId(): String = UUID.randomUUID().toString()
        private const val PREFS = "ngscanner_perf"
        private const val KEY = "runs"
    }
}
