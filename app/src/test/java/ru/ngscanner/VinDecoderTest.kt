package ru.ngscanner

import kotlinx.coroutines.runBlocking
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

    // Офлайн-разбор VDS для АвтоВАЗ (WMI XTA). Эталонный VIN и ожидаемые
    // значения взяты из тестов vininfo (BSD-3): VDS «GFK330» → Vesta, двиг. 21179.
    // Ветка XTA возвращается офлайн, до обращения к сети, поэтому тест детерминирован.

    @Test
    fun decodesAvtoVazVestaFromVds() = runBlocking {
        val info = VinDecoder.decode("XTAGFK330JY144213")!!
        assertEquals("Lada", info.make)
        assertEquals("Vesta", info.model)
        assertEquals("21179", info.engine)
    }

    @Test
    fun avtoVazUnknownModelStaysBlank() = runBlocking {
        // Код модели вне таблицы (VDS[1] не A/F) → марка есть, модель и двигатель пусты.
        val info = VinDecoder.decode("XTA21099031234567")!!
        assertEquals("Lada", info.make)
        assertEquals("", info.model)
        assertNull(info.engine)
    }

    @Test
    fun ruAssembledZWmiDecodesOfflineWithoutNetwork() = runBlocking {
        // Z94 — Hyundai Solaris/Creta (сборка СПб). Раньше уходил в NHTSA (висел до 15 c);
        // теперь марка определяется офлайн сразу, без обращения к сети.
        val info = VinDecoder.decode("Z94CB41BABR000001")!!
        assertEquals("Hyundai", info.make)
    }
}
