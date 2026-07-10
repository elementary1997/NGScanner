package ru.ngscanner.obd

/**
 * Расчёт расхода топлива из потоковых параметров (MAF, скорость, λ) — чистые функции
 * без Android, покрыты тестами.
 *
 * Физика: масса воздуха MAF [г/с] → масса топлива = MAF/AFR (стехиометрия по массе,
 * бензин AFR≈14.7); объёмный расход л/ч = MAF·3600/(AFR·ρ), плотность бензина ρ≈745 г/л.
 * При наличии широкополосной λ реальный AFR = 14.7·λ.
 */
object FuelCalc {

    const val GASOLINE_AFR = 14.7
    const val GASOLINE_DENSITY_G_PER_L = 745.0

    /** Кап интервала между снимками (с): не тянуть интеграл через паузы диагностики/реконнекта. */
    const val DT_CAP_SEC = 6.0

    private fun afr(lambda: Double?): Double =
        GASOLINE_AFR * (lambda?.takeIf { it in 0.5..1.5 } ?: 1.0)

    /** Мгновенный расход, л/ч, из MAF (г/с) и (опц.) λ. */
    fun litersPerHour(mafGs: Double, lambda: Double? = null): Double =
        mafGs * 3600.0 / (afr(lambda) * GASOLINE_DENSITY_G_PER_L)

    /** Мгновенный расход, л/100км; `null` при скорости ≈0 (на месте не определён). */
    fun instantLper100(mafGs: Double, speedKmh: Double, lambda: Double? = null): Double? {
        if (speedKmh < 1.0) return null
        return litersPerHour(mafGs, lambda) / speedKmh * 100.0
    }

    /**
     * Прирост (литры, км) за интервал [dtSec] между двумя снимками. [dtSec] коэрсится
     * в [0, DT_CAP_SEC]. Отсутствующий MAF/скорость → нулевой соответствующий прирост.
     */
    fun step(mafGs: Double?, speedKmh: Double?, dtSec: Double, lambda: Double? = null): Pair<Double, Double> {
        val dt = dtSec.coerceIn(0.0, DT_CAP_SEC)
        if (dt <= 0.0) return 0.0 to 0.0
        val fuel = if (mafGs != null) litersPerHour(mafGs, lambda) / 3600.0 * dt else 0.0
        val dist = if (speedKmh != null) speedKmh / 3600.0 * dt else 0.0
        return fuel to dist
    }

    /** Средний расход л/100км по накопленным литрам и км; `null` на слишком короткой поездке. */
    fun avgLper100(fuelLiters: Double, distanceKm: Double): Double? =
        if (distanceKm > 0.05) fuelLiters / distanceKm * 100.0 else null
}
