package ru.ngscanner.obd

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Заводские PID пользователя. Их немного и они крошечные — держим одним JSON-списком
 * в SharedPreferences (по образцу [ru.ngscanner.perf.PerfRepository]).
 */
class CustomPidRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun all(): List<CustomPid> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<CustomPid>>(raw) }.getOrDefault(emptyList())
    }

    /** Добавляет или заменяет PID по id; возвращает актуальный список. */
    fun upsert(pid: CustomPid): List<CustomPid> {
        val list = all().toMutableList()
        val idx = list.indexOfFirst { it.id == pid.id }
        if (idx >= 0) list[idx] = pid else list.add(pid)
        val kept = list.take(MAX_PIDS)
        persist(kept)
        return kept
    }

    fun delete(id: String): List<CustomPid> {
        val kept = all().filterNot { it.id == id }
        persist(kept)
        return kept
    }

    private fun persist(pids: List<CustomPid>) {
        runCatching { prefs.edit().putString(KEY, json.encodeToString(pids)).apply() }
    }

    companion object {
        // Каждый PID — отдельный запрос в цикле опроса; десятки убили бы частоту обновления.
        const val MAX_PIDS = 12
        fun newId(): String = UUID.randomUUID().toString()
        private const val PREFS = "ngscanner_custom_pids"
        private const val KEY = "pids"
    }
}
