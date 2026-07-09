package ru.ngscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.ngscanner.obd.IsoTp

class IsoTpTest {

    // Однофреймовый ответ без ISO-TP PCI (Mode 01) проходит как есть.
    @Test
    fun singleFrameStaysAsIs() {
        assertEquals("410C1AF8", IsoTp.reassemble("41 0C 1A F8\r>"))
    }

    // Авто-форматирование: длина + сегменты собираются в объявленные 20 байт.
    @Test
    fun colonSegmentsAreJoinedAndTrimmed() {
        val raw = "014\r0:49020158544132\r1:31303939305931\r2:323334353637\r>"
        assertEquals("4902015854413231303939305931323334353637", IsoTp.reassemble(raw))
    }

    // Сегменты в перепутанном порядке пересобираются по индексу.
    @Test
    fun colonSegmentsSortedByIndex() {
        val raw = "008\r1:04050607\r0:00010203\r>"
        assertEquals("0001020304050607", IsoTp.reassemble(raw))
    }

    // Сырые First/Consecutive Frame: PCI снимается, данные обрезаются по длине.
    @Test
    fun rawFramesStripPci() {
        val raw = "1014490201585441\r2132313039393059\r2231323334353637\r>"
        assertEquals("4902015854413231303939305931323334353637", IsoTp.reassemble(raw))
    }

    @Test
    fun blankIsNull() {
        assertNull(IsoTp.reassemble("\r>"))
    }
}
