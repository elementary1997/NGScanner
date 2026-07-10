package ru.ngscanner.report

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Файловое хранилище протоколов диагностики: каждый — отдельный JSON в
 * `filesDir/reports`, лёгкий индекс метаданных там же в `index.json` (по образцу
 * [ru.ngscanner.trips.TripRepository]). Протоколы привязаны к машине; на машину
 * держим не больше [MAX_PER_CAR] (старые вытесняются). Индекс упорядочен: новые —
 * первыми. Не потокобезопасен — вызывать с одного диспетчера (IO).
 */
class ReportRepository(context: Context) {

    private val dir = File(context.filesDir, "reports").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Метаданные всех протоколов, новые — первыми. */
    fun metas(): List<ReportMeta> {
        if (!indexFile.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<ReportMeta>>(indexFile.readText()) }.getOrDefault(emptyList())
    }

    /** Полный протокол по id или null. */
    fun load(id: String): DiagnosticReport? {
        val f = File(dir, "$id.json")
        if (!f.exists()) return null
        return runCatching { json.decodeFromString<DiagnosticReport>(f.readText()) }.getOrNull()
    }

    /** Сохраняет протокол и обновляет индекс; вытесняет старые сверх лимита на машину. */
    fun save(report: DiagnosticReport): List<ReportMeta> {
        runCatching { File(dir, "${report.id}.json").writeText(json.encodeToString(report)) }
        val metas = listOf(report.toMeta()) + metas().filterNot { it.id == report.id }
        val kept = enforceLimit(metas)
        writeIndex(kept)
        return kept
    }

    /** Переименовывает протокол (обрезка до 80 символов); пустое имя игнорируется. */
    fun rename(id: String, newTitle: String): List<ReportMeta> {
        val title = newTitle.trim().take(80)
        if (title.isBlank()) return metas()
        load(id)?.let { r -> runCatching { File(dir, "$id.json").writeText(json.encodeToString(r.copy(title = title))) } }
        val metas = metas().map { if (it.id == id) it.copy(title = title) else it }
        writeIndex(metas)
        return metas
    }

    fun delete(id: String): List<ReportMeta> {
        runCatching { File(dir, "$id.json").delete() }
        val kept = metas().filterNot { it.id == id }
        writeIndex(kept)
        return kept
    }

    /** Удаляет все протоколы машины (при удалении её из гаража) — иначе осиротеют. */
    fun deleteForCar(carId: String) {
        val (drop, keep) = metas().partition { it.carId == carId }
        if (drop.isEmpty()) return
        drop.forEach { runCatching { File(dir, "${it.id}.json").delete() } }
        writeIndex(keep)
    }

    // Оставляем не больше лимита протоколов на машину; лишние старые удаляем с файлами.
    private fun enforceLimit(metas: List<ReportMeta>): List<ReportMeta> {
        val dropIds = metas.groupBy { it.carId }.values.flatMap { it.drop(MAX_PER_CAR) }.map { it.id }.toSet()
        if (dropIds.isEmpty()) return metas
        dropIds.forEach { runCatching { File(dir, "$it.json").delete() } }
        return metas.filterNot { it.id in dropIds }
    }

    private fun writeIndex(metas: List<ReportMeta>) {
        runCatching { indexFile.writeText(json.encodeToString(metas)) }
    }

    companion object {
        const val MAX_PER_CAR = 20
        fun newId(): String = UUID.randomUUID().toString()
    }
}
