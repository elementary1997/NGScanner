package ru.ngscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.ngscanner.obd.FuelCalc

/** Формулы расхода и инкрементальный интеграл — чтобы л/100км и стоимость были верны. */
class FuelCalcTest {

    @Test
    fun litersPerHourFromMaf() {
        assertEquals(0.986, FuelCalc.litersPerHour(3.0), 0.01)
        assertEquals(9.86, FuelCalc.litersPerHour(30.0), 0.1)
    }

    @Test
    fun lambdaAdjustsAndIgnoredOutOfRange() {
        assertEquals(FuelCalc.litersPerHour(10.0) / 1.1, FuelCalc.litersPerHour(10.0, 1.1), 0.001)
        assertEquals(FuelCalc.litersPerHour(10.0), FuelCalc.litersPerHour(10.0, 3.0), 0.001)
    }

    @Test
    fun instantLper100AndZeroSpeed() {
        assertEquals(5.48, FuelCalc.instantLper100(15.0, 90.0)!!, 0.1)
        assertNull(FuelCalc.instantLper100(15.0, 0.0))
    }

    @Test
    fun stepIntegratesAndCapsDt() {
        // 36 км/ч = 10 м/с, dt 5 с → 0.05 км.
        assertEquals(0.05, FuelCalc.step(null, 36.0, 5.0).second, 1e-6)
        // Разрыв 60 с ограничивается DT_CAP — не тянем интеграл через паузу.
        assertEquals(36.0 / 3600.0 * FuelCalc.DT_CAP_SEC, FuelCalc.step(null, 36.0, 60.0).second, 1e-6)
    }

    @Test
    fun avgOnlyWithDistance() {
        assertNull(FuelCalc.avgLper100(1.0, 0.0))
        assertEquals(8.0, FuelCalc.avgLper100(8.0, 100.0)!!, 1e-6)
    }
}
