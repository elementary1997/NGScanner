package ru.ngscanner.trips

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Хранилище записанных поездок и событий «чёрного ящика». Каждая запись —
 * отдельный JSON-файл в `filesDir/trips` (точек много — в SharedPreferences
 * не помещаются); лёгкий индекс метаданных лежит там же в `index.json`.
 *
 * Держим ограниченное число записей каждого типа (старые вытесняются), чтобы
 * не расти без предела. Не потокобезопасен — вызывать с одного диспетчера (IO).
 */
class TripRepository(context: Context) {

    private val dir = File(context.filesDir, "trips").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Метаданные всех записей, новые — первыми. */
    fun metas(): List<TripMeta> {
        if (!indexFile.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<TripMeta>>(indexFile.readText()) }
            .getOrDefault(emptyList())
            .sortedByDescending { it.startMs }
    }

    /** Полная запись по id (с точками) или null. */
    fun load(id: String): Trip? {
        val f = File(dir, "$id.json")
        if (!f.exists()) return null
        return runCatching { json.decodeFromString<Trip>(f.readText()) }.getOrNull()
    }

    /**
     * Сохраняет запись и обновляет индекс; при переполнении лимита своего типа
     * удаляет самые старые записи. Возвращает актуальный список метаданных.
     */
    fun save(trip: Trip): List<TripMeta> {
        runCatching { File(dir, "${trip.id}.json").writeText(json.encodeToString(trip)) }
        val metas = (metas().filterNot { it.id == trip.id } + trip.toMeta())
            .sortedByDescending { it.startMs }
        val kept = enforceLimits(metas)
        writeIndex(kept)
        return kept
    }

    /** Удаляет запись и её файл; возвращает обновлённый список метаданных. */
    fun delete(id: String): List<TripMeta> {
        runCatching { File(dir, "$id.json").delete() }
        val kept = metas().filterNot { it.id == id }
        writeIndex(kept)
        return kept
    }

    // Оставляем не больше лимита записей каждого типа; лишние старые удаляем с файлами.
    private fun enforceLimits(metas: List<TripMeta>): List<TripMeta> {
        val trips = metas.filter { it.kind == TripKind.TRIP }
        val events = metas.filter { it.kind == TripKind.EVENT }
        val drop = trips.drop(MAX_TRIPS) + events.drop(MAX_EVENTS)
        drop.forEach { runCatching { File(dir, "${it.id}.json").delete() } }
        val keptIds = (trips.take(MAX_TRIPS) + events.take(MAX_EVENTS)).map { it.id }.toSet()
        return metas.filter { it.id in keptIds }
    }

    private fun writeIndex(metas: List<TripMeta>) {
        runCatching { indexFile.writeText(json.encodeToString(metas.sortedByDescending { it.startMs })) }
    }

    companion object {
        const val MAX_TRIPS = 20
        const val MAX_EVENTS = 30
        fun newId(): String = UUID.randomUUID().toString()
    }
}
