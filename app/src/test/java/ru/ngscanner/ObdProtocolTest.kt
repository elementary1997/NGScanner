package ru.ngscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.ngscanner.obd.ObdProtocol

/** Каталог протоколов ELM327: номера, CAN-признак, длина заголовка, разбор кодов. */
class ObdProtocolTest {

    @Test
    fun numbersMatchElmSpec() {
        assertEquals(1, ObdProtocol.J1850_PWM.number)
        assertEquals(5, ObdProtocol.KWP_FAST.number)
        assertEquals(6, ObdProtocol.CAN_11_500.number)
        assertEquals(9, ObdProtocol.CAN_29_250.number)
        // A/B/C — hex: ATDPN отдаёт их буквами.
        assertEquals(10, ObdProtocol.J1939.number)
        assertEquals(11, ObdProtocol.USER1.number)
        assertEquals(12, ObdProtocol.USER2.number)
    }

    @Test
    fun canFamilyDetected() {
        assertFalse(ObdProtocol.AUTO.isCan)
        assertFalse(ObdProtocol.ISO_9141_2.isCan)
        assertFalse(ObdProtocol.KWP_FAST.isCan)
        assertFalse(ObdProtocol.J1850_VPW.isCan)
        assertTrue(ObdProtocol.CAN_11_500.isCan)
        assertTrue(ObdProtocol.CAN_29_250.isCan)
        // Регресс: J1939/USER — тоже CAN (раньше проваливались в «легаси»).
        assertTrue(ObdProtocol.J1939.isCan)
        assertTrue(ObdProtocol.USER1.isCan)
        assertTrue(ObdProtocol.USER2.isCan)
    }

    @Test
    fun headerLengthByAddressing() {
        // 11-битные → 3 hex-символа (7Ex)
        assertEquals(3, ObdProtocol.CAN_11_500.headerHexLen)
        assertEquals(3, ObdProtocol.CAN_11_250.headerHexLen)
        assertEquals(3, ObdProtocol.USER1.headerHexLen)
        assertEquals(3, ObdProtocol.USER2.headerHexLen)
        // 29-битные → 8 (18DAF1xx)
        assertEquals(8, ObdProtocol.CAN_29_500.headerHexLen)
        assertEquals(8, ObdProtocol.CAN_29_250.headerHexLen)
        // Регресс: J1939 29-битный — раньше давал 0 и группировка ЭБУ отключалась.
        assertEquals(8, ObdProtocol.J1939.headerHexLen)
        // Легаси — без группировки
        assertEquals(0, ObdProtocol.ISO_9141_2.headerHexLen)
        assertEquals(0, ObdProtocol.KWP_FAST.headerHexLen)
        assertEquals(0, ObdProtocol.J1850_PWM.headerHexLen)
        assertEquals(0, ObdProtocol.AUTO.headerHexLen)
    }

    @Test
    fun fromNumberMapsAtdpn() {
        assertEquals(ObdProtocol.CAN_11_500, ObdProtocol.fromNumber(6))
        assertEquals(ObdProtocol.J1939, ObdProtocol.fromNumber(10))
        // 0 = ещё не определён, не протокол
        assertNull(ObdProtocol.fromNumber(0))
        assertNull(ObdProtocol.fromNumber(null))
        assertNull(ObdProtocol.fromNumber(99))
    }

    @Test
    fun fromCodeFallsBackToAuto() {
        assertEquals(ObdProtocol.KWP_FAST, ObdProtocol.fromCode("5"))
        assertEquals(ObdProtocol.J1939, ObdProtocol.fromCode("a"))
        assertEquals(ObdProtocol.AUTO, ObdProtocol.fromCode("0"))
        // Мусор из настроек не должен ронять подключение — безопасный дефолт.
        assertEquals(ObdProtocol.AUTO, ObdProtocol.fromCode("Z"))
        assertEquals(ObdProtocol.AUTO, ObdProtocol.fromCode(null))
    }

    @Test
    fun probeOrderIsRealProtocolsOnly() {
        assertFalse(ObdProtocol.AUTO in ObdProtocol.PROBE_ORDER)
        // Самые частые — первыми, иначе зонд долго тыкается в экзотику.
        assertEquals(ObdProtocol.CAN_11_500, ObdProtocol.PROBE_ORDER.first())
        assertTrue(ObdProtocol.KWP_FAST in ObdProtocol.PROBE_ORDER)
    }
}
