package ru.ngscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.ngscanner.obd.ObdParser
import ru.ngscanner.obd.ObdParser.DtcResult

class ObdParserTest {

    // 41 0C 1A F8 → ((0x1A*256)+0xF8)/4 = 1726
    @Test
    fun parsesRpmFromSpacedResponse() {
        assertEquals(1726, ObdParser.parseRpm("41 0C 1A F8"))
    }

    @Test
    fun parsesRpmIgnoringEchoAndPrompt() {
        assertEquals(1726, ObdParser.parseRpm("010C\r41 0C 1A F8\r\r>"))
    }

    @Test
    fun returnsNullOnGarbageRpm() {
        assertNull(ObdParser.parseRpm("NO DATA"))
    }

    // 41 05 5A → 0x5A − 40 = 50 °C
    @Test
    fun parsesCoolantTemp() {
        assertEquals(50, ObdParser.parseCoolantTemp("41 05 5A"))
    }

    // ---- DTC: легаси-формат (без счётчика) ----
    @Test
    fun parsesLegacyDtc() {
        val result = ObdParser.parseDtcs("43 01 33")
        assertTrue(result is DtcResult.Ok)
        assertEquals(listOf("P0133"), (result as DtcResult.Ok).codes)
    }

    @Test
    fun parsesTwoLegacyDtcs() {
        val result = ObdParser.parseDtcs("43 01 33 04 20") as DtcResult.Ok
        assertEquals(listOf("P0133", "P0420"), result.codes)
    }

    // ---- DTC: CAN-формат (первый байт — счётчик кодов) ----
    @Test
    fun parsesCanDtcWithCounter() {
        // 43 02 0133 0420 → счётчик 02, коды P0133 и P0420 (без фантомов)
        val result = ObdParser.parseDtcs("43 02 01 33 04 20") as DtcResult.Ok
        assertEquals(listOf("P0133", "P0420"), result.codes)
    }

    @Test
    fun parsesCanDtcWithPadding() {
        // 43 01 0301 0000 → счётчик 01, код P0301, хвост-заполнитель игнорируется
        val result = ObdParser.parseDtcs("43 01 03 01 00 00") as DtcResult.Ok
        assertEquals(listOf("P0301"), result.codes)
    }

    // ---- DTC: различаем «кодов нет» и «нет связи» ----
    @Test
    fun emptyDtcListWhenNoCodes() {
        val result = ObdParser.parseDtcs("43 00 00")
        assertTrue(result is DtcResult.Ok)
        assertTrue((result as DtcResult.Ok).codes.isEmpty())
    }

    @Test
    fun noDataIsNotOk() {
        assertEquals(DtcResult.NoData, ObdParser.parseDtcs("NO DATA"))
    }

    @Test
    fun busErrorsAreReported() {
        assertEquals(DtcResult.BusError, ObdParser.parseDtcs("UNABLE TO CONNECT"))
        assertEquals(DtcResult.BusError, ObdParser.parseDtcs("CAN ERROR"))
        assertEquals(DtcResult.BusError, ObdParser.parseDtcs("BUS INIT: ...ERROR"))
    }

    // ---- VIN (Mode 09) ----
    @Test
    fun parsesVin() {
        // 4902 01 + ASCII "XTA210990Y1234567"
        val raw = "490201" + "5854413231303939305931323334353637"
        assertEquals("XTA210990Y1234567", ObdParser.parseVin(raw))
    }

    @Test
    fun vinNullWhenNoFrame() {
        assertNull(ObdParser.parseVin("NO DATA"))
    }
}
