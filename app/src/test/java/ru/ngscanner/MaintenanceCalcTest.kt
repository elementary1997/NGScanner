package ru.ngscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.ngscanner.garage.MaintenanceCalc
import ru.ngscanner.garage.MaintenanceInterval
import ru.ngscanner.garage.MaintenanceKind
import ru.ngscanner.garage.MaintenanceUrgency
import java.time.LocalDate

/** Расчёт «осталось до ТО»: пробег/календарь, пороги SOON/OVERDUE, худший канал. */
class MaintenanceCalcTest {

    private val today = LocalDate.of(2026, 7, 10)

    private fun interval(km: Int? = null, months: Int? = null, lastKm: Int? = null, lastDate: String? = null) =
        MaintenanceInterval(
            id = "x", kind = MaintenanceKind.ENGINE_OIL,
            intervalKm = km, intervalMonths = months, lastServiceKm = lastKm, lastServiceDateIso = lastDate,
        )

    @Test
    fun remainingKmFromBase() {
        val r = MaintenanceCalc.compute(interval(km = 10000, lastKm = 50000), 53000, today)
        assertEquals(7000, r.remainingKm)
        assertEquals(MaintenanceUrgency.OK, r.urgency)
    }

    @Test
    fun soonBoundary() {
        assertEquals(MaintenanceUrgency.SOON, MaintenanceCalc.compute(interval(km = 10000, lastKm = 50000), 59000, today).urgency)
        assertEquals(MaintenanceUrgency.OK, MaintenanceCalc.compute(interval(km = 10000, lastKm = 50000), 58999, today).urgency)
    }

    @Test
    fun overdueByKm() {
        val r = MaintenanceCalc.compute(interval(km = 10000, lastKm = 50000), 61000, today)
        assertTrue(r.remainingKm!! <= 0)
        assertEquals(MaintenanceUrgency.OVERDUE, r.urgency)
    }

    @Test
    fun overdueByMonths() {
        // ТО 2026-01-01, интервал 3 мес → срок 2026-04-01, сегодня 2026-07-10 → просрочено.
        val r = MaintenanceCalc.compute(interval(months = 3, lastDate = "2026-01-01"), null, today)
        assertNotNull(r.remainingDays)
        assertTrue(r.remainingDays!! < 0)
        assertEquals(MaintenanceUrgency.OVERDUE, r.urgency)
    }

    @Test
    fun worstChannelWins() {
        val r = MaintenanceCalc.compute(interval(km = 10000, months = 3, lastKm = 50000, lastDate = "2026-01-01"), 51000, today)
        assertEquals(MaintenanceUrgency.OVERDUE, r.urgency)
    }

    @Test
    fun nullMileageNoKmChannel() {
        assertNull(MaintenanceCalc.compute(interval(km = 10000), null, today).remainingKm)
    }

    @Test
    fun lastKmGreaterThanCurrentClamped() {
        assertEquals(10000, MaintenanceCalc.compute(interval(km = 10000, lastKm = 60000), 50000, today).remainingKm)
    }
}
