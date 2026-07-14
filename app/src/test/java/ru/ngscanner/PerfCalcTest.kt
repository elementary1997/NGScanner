package ru.ngscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.ngscanner.perf.PerfCalc
import ru.ngscanner.perf.PerfKind
import ru.ngscanner.perf.PerfPhase
import ru.ngscanner.perf.PerfState

/** Конечный автомат замеров: фазы, интерполяция порога, интеграл дистанции, прерывание. */
class PerfCalcTest {

    /** Прогоняет серию (мс, км/ч) и отдаёт последнее состояние. */
    private fun run(kind: PerfKind, samples: List<Pair<Long, Double>>): PerfState {
        val calc = PerfCalc(kind)
        var last: PerfState? = null
        samples.forEach { (t, v) -> last = calc.sample(t, v) }
        return last!!
    }

    @Test
    fun accelArmsOnlyAfterStandstill() {
        val calc = PerfCalc(PerfKind.ACCEL_0_100)
        // Едем — замер ещё не взведён.
        assertEquals(PerfPhase.ARMING, calc.sample(0, 40.0).phase)
        // Остановились — взведён.
        assertEquals(PerfPhase.READY, calc.sample(100, 0.0).phase)
        // Тронулись — пошёл отсчёт.
        assertEquals(PerfPhase.RUNNING, calc.sample(200, 3.0).phase)
    }

    @Test
    fun accelResultInterpolatesThreshold() {
        // Стоим, затем равномерный разгон 10 км/ч за 100 мс → 100 км/ч на 1000 мс от старта.
        val samples = mutableListOf(0L to 0.0)
        for (i in 1..12) samples.add((i * 100L) to (i * 10.0))
        val s = run(PerfKind.ACCEL_0_100, samples)
        assertEquals(PerfPhase.DONE, s.phase)
        assertNotNull(s.resultSec)
        // t0 = последний нулевой сэмпл (0 мс), 100 км/ч ровно на 1000 мс.
        assertEquals(1.0, s.resultSec!!, 0.01)
    }

    @Test
    fun accelAbortsIfStops() {
        val s = run(
            PerfKind.ACCEL_0_100,
            listOf(0L to 0.0, 100L to 20.0, 200L to 30.0, 300L to 0.0),
        )
        assertEquals(PerfPhase.ABORTED, s.phase)
        assertNull(s.resultSec)
    }

    @Test
    fun brakeArmsAtHundredAndMeasuresToZero() {
        // Держим 110, потом тормозим до 0.
        val s = run(
            PerfKind.BRAKE_100_0,
            listOf(
                0L to 110.0,     // взвод (>= 100)
                100L to 100.0,   // всё ещё 100 — старт ещё не пересечён
                200L to 80.0,    // пересекли 100 вниз между 100 и 200 мс
                300L to 50.0,
                400L to 20.0,
                500L to 0.0,
            ),
        )
        assertEquals(PerfPhase.DONE, s.phase)
        assertNotNull(s.resultSec)
        // Пересечение 100 → ровно на 100 мс (v=100 в t=100), финиш 500 мс → 0.4 c.
        assertEquals(0.4, s.resultSec!!, 0.02)
    }

    @Test
    fun brakeAbortsIfReaccelerates() {
        val s = run(
            PerfKind.BRAKE_100_0,
            listOf(0L to 110.0, 100L to 90.0, 200L to 70.0, 300L to 90.0),
        )
        assertEquals(PerfPhase.ABORTED, s.phase)
    }

    @Test
    fun quarterMileIntegratesDistance() {
        // Постоянные 72 км/ч = 20 м/с: 402.336 м ≈ 20.12 c после старта.
        val samples = mutableListOf(0L to 0.0)
        // Мгновенно «набираем» 72 и держим; шаг 100 мс, 250 c с запасом.
        for (i in 1..250) samples.add((i * 100L) to 72.0)
        val s = run(PerfKind.QUARTER_MILE, samples)
        assertEquals(PerfPhase.DONE, s.phase)
        assertNotNull(s.resultSec)
        // Первый шаг стартует с 0→72 (трапеция даёт половину), поэтому чуть больше 20.12.
        assertTrue("ожидали ~20 c, получили ${s.resultSec}", s.resultSec!! in 20.0..20.4)
        assertEquals(72.0, s.trapSpeedKmh!!, 0.5)
    }

    @Test
    fun stateIsIdleBeforeLaunch() {
        val calc = PerfCalc(PerfKind.QUARTER_MILE)
        val s = calc.sample(0, 0.0)
        assertEquals(PerfPhase.READY, s.phase)
        assertEquals(0.0, s.distanceM, 0.0001)
        assertEquals(0.0, s.elapsedSec, 0.0001)
    }
}
