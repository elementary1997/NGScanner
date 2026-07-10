package ru.ngscanner.trips

import kotlinx.serialization.Serializable

/** Тип записи: поездка целиком или короткое событие «чёрного ящика» вокруг аномалии. */
@Serializable
enum class TripKind { TRIP, EVENT }

/** Снимок всех прочитанных параметров в один момент. [t] — абсолютное время, мс. */
@Serializable
data class TripSample(val t: Long, val v: Map<String, Double>)

/**
 * Записанная поездка или событие. [pids] — имена параметров (в порядке показа),
 * [samples] — синхронные снимки по времени. Для [TripKind.EVENT] в [trigger]
 * лежит причина срабатывания (например «Темп. ОЖ критично»).
 */
@Serializable
data class Trip(
    val id: String,
    val kind: TripKind,
    val startMs: Long,
    val endMs: Long,
    val carTitle: String? = null,
    val trigger: String? = null,
    val pids: List<String> = emptyList(),
    val samples: List<TripSample> = emptyList(),
    // Расход за поездку — физика (литры и км), стоимость считается в UI по цене топлива.
    // Старые файлы без этих полей десериализуются в 0.0.
    val fuelLiters: Double = 0.0,
    val distanceKm: Double = 0.0,
) {
    fun toMeta() = TripMeta(id, kind, startMs, endMs, carTitle, trigger, samples.size, fuelLiters, distanceKm)

    /** Средний расход л/100км; `null` на слишком короткой поездке. */
    val avgLper100: Double? get() = if (distanceKm > 0.05) fuelLiters / distanceKm * 100.0 else null
}

/** Лёгкая карточка записи для списка — без самих точек (их много). */
@Serializable
data class TripMeta(
    val id: String,
    val kind: TripKind,
    val startMs: Long,
    val endMs: Long,
    val carTitle: String? = null,
    val trigger: String? = null,
    val sampleCount: Int,
    val fuelLiters: Double = 0.0,
    val distanceKm: Double = 0.0,
) {
    /** Длительность записи в секундах. */
    val durationSec: Long get() = ((endMs - startMs) / 1000L).coerceAtLeast(0)

    /** Средний расход л/100км; `null` на слишком короткой поездке (нет надёжной дистанции). */
    val avgLper100: Double? get() = if (distanceKm > 0.05) fuelLiters / distanceKm * 100.0 else null
}
