package ru.ngscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.ngscanner.garage.VinDecoder

class VinDecoderTest {

    // 10-я позиция (индекс 9) — код года. Выбираем более свежий цикл, если он
    // не в будущем; иначе откатываемся. Не зависим от 7-й позиции (US-конвенция),
    // которая занижала год для АвтоВАЗ/китайцев.

    @Test
    fun prefersRecentCycleWhenNotFuture() {
        // 'J' → 1988 или 2018; при 2026 берём 2018.
        assertEquals(2018, VinDecoder.yearFromVin("XTA210990J1234567", currentYear = 2026))
    }

    @Test
    fun fallsBackWhenNewerCycleIsFuture() {
        // 'W' → 1998 или 2028; 2028 в будущем → 1998 (а не 2028).
        assertEquals(1998, VinDecoder.yearFromVin("XTA210990W1234567", currentYear = 2026))
    }

    @Test
    fun digitCodeYear() {
        // '5' → 2005 или 2035; 2035 в будущем → 2005.
        assertEquals(2005, VinDecoder.yearFromVin("XTA2109905123456", currentYear = 2026))
    }

    @Test
    fun nullOnShortVin() {
        assertNull(VinDecoder.yearFromVin("XTA12", currentYear = 2026))
    }

    @Test
    fun nullOnInvalidYearCode() {
        // Символы 0, I, O, Q, U, Z не используются как код года (10-я позиция).
        assertNull(VinDecoder.yearFromVin("XTA2109900123456", currentYear = 2026))
    }
}
