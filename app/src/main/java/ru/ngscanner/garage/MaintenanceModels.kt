package ru.ngscanner.garage

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Вид работ ТО. Подписи фиксированы; интервалы НЕ хардкодим — вводит пользователь. */
enum class MaintenanceKind(val label: String) {
    ENGINE_OIL("Моторное масло"),
    TIMING_BELT("Ремень ГРМ"),
    AIR_FILTER("Воздушный фильтр"),
    OIL_FILTER("Масляный фильтр"),
    FUEL_FILTER("Топливный фильтр"),
    CABIN_FILTER("Салонный фильтр"),
    SPARK_PLUGS("Свечи зажигания"),
    BRAKE_FLUID("Тормозная жидкость"),
    COOLANT("Охлаждающая жидкость"),
    OTHER("Другое"),
}

/**
 * Интервал обслуживания машины. Хотя бы один из [intervalKm]/[intervalMonths] задан.
 * [lastServiceKm]/[lastServiceDateIso] — база отсчёта (последнее ТО); если пусто,
 * считаем от текущего пробега/без даты.
 */
@Serializable
data class MaintenanceInterval(
    val id: String,
    val kind: MaintenanceKind,
    val customName: String? = null,
    val intervalKm: Int? = null,
    val intervalMonths: Int? = null,
    val lastServiceKm: Int? = null,
    val lastServiceDateIso: String? = null,
) {
    /** Отображаемое имя: пользовательское (для OTHER) либо подпись вида работ. */
    val title: String get() = customName?.takeIf { it.isNotBlank() } ?: kind.label
}

/** Срочность ТО для окраски и уведомлений. */
enum class MaintenanceUrgency { OK, SOON, OVERDUE }

/** Вычисленная позиция ТО (не хранится): интервал + остаток + срочность. */
data class MaintenanceItem(
    val interval: MaintenanceInterval,
    val remainingKm: Int?,
    val remainingDays: Int?,
    val urgency: MaintenanceUrgency,
    val dueByMileage: Boolean,
)

/** Чистый расчёт «осталось до ТО» по пробегу и/или календарю (без Android). */
object MaintenanceCalc {

    const val SOON_KM = 1000
    const val SOON_DAYS = 30

    fun compute(i: MaintenanceInterval, currentMileageKm: Int?, today: LocalDate): MaintenanceItem {
        val remainingKm: Int? = if (i.intervalKm != null && currentMileageKm != null) {
            val base = i.lastServiceKm ?: currentMileageKm
            val elapsed = (currentMileageKm - base).coerceAtLeast(0) // защита от last>current
            i.intervalKm - elapsed
        } else {
            null
        }
        val remainingDays: Int? = run {
            val baseDate = i.lastServiceDateIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (i.intervalMonths != null && baseDate != null) {
                ChronoUnit.DAYS.between(today, baseDate.plusMonths(i.intervalMonths.toLong())).toInt()
            } else {
                null
            }
        }
        val overdue = (remainingKm != null && remainingKm <= 0) || (remainingDays != null && remainingDays <= 0)
        val soon = (remainingKm != null && remainingKm in 1..SOON_KM) || (remainingDays != null && remainingDays in 1..SOON_DAYS)
        val urgency = when {
            overdue -> MaintenanceUrgency.OVERDUE
            soon -> MaintenanceUrgency.SOON
            else -> MaintenanceUrgency.OK
        }
        // Какой канал наступает раньше (для подписи) — по нормированному запасу.
        val kmFrac = if (remainingKm != null && (i.intervalKm ?: 0) > 0) remainingKm.toDouble() / i.intervalKm!! else Double.MAX_VALUE
        val dayFrac = if (remainingDays != null && (i.intervalMonths ?: 0) > 0) remainingDays.toDouble() / (i.intervalMonths!! * 30.0) else Double.MAX_VALUE
        return MaintenanceItem(i, remainingKm, remainingDays, urgency, dueByMileage = kmFrac <= dayFrac)
    }

    fun computeAll(list: List<MaintenanceInterval>, currentMileageKm: Int?, today: LocalDate): List<MaintenanceItem> =
        list.map { compute(it, currentMileageKm, today) }
}
