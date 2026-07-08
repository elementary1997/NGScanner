package ru.ngscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.ngscanner.obd.ObdParser

class ObdParserTest {

    // 41 0C 1A F8 → ((0x1A*256)+0xF8)/4 = (6656+248)/4 = 1726
    @Test
    fun parsesRpmFromSpacedResponse() {
        assertEquals(1726, ObdParser.parseRpm("41 0C 1A F8"))
    }

    @Test
    fun parsesRpmFromCompactResponse() {
        assertEquals(1726, ObdParser.parseRpm("410C1AF8"))
    }

    @Test
    fun parsesRpmIgnoringEchoAndPrompt() {
        assertEquals(1726, ObdParser.parseRpm("010C\r41 0C 1A F8\r\r>"))
    }

    @Test
    fun returnsNullOnGarbage() {
        assertNull(ObdParser.parseRpm("NO DATA"))
    }

    // 41 05 5A → 0x5A − 40 = 90 − 40 = 50 °C
    @Test
    fun parsesCoolantTemp() {
        assertEquals(50, ObdParser.parseCoolantTemp("41 05 5A"))
    }
}
