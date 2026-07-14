package ru.ngscanner

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.ngscanner.obd.CustomPid
import ru.ngscanner.obd.ObdParser

/** Заводские PID пользователя: разбор ответа не-01 моды и декодирование по формуле. */
class CustomPidTest {

    private fun pid(
        cmd: String = "2101",
        byteOffset: Int = 0,
        byteCount: Int = 1,
        scale: Double = 1.0,
        offset: Double = 0.0,
        signed: Boolean = false,
    ) = CustomPid(
        id = "1", name = "Тест", cmd = cmd, unit = "ед.",
        byteOffset = byteOffset, byteCount = byteCount, scale = scale, offset = offset, signed = signed,
    )

    // ---- ObdParser.dataBytesFor ----

    @Test
    fun mode21ResponseHeaderIs61() {
        // Запрос 2101 → ответ 61 01 <данные>
        val data = ObdParser.dataBytesFor("61 01 AB CD", "2101")
        assertArrayEquals(intArrayOf(0xAB, 0xCD), data)
    }

    @Test
    fun mode22ResponseHeaderIs62WithTwoBytePid() {
        val data = ObdParser.dataBytesFor("62 F1 90 12 34", "22F190")
        assertArrayEquals(intArrayOf(0x12, 0x34), data)
    }

    @Test
    fun mode01StillWorksThroughGenericPath() {
        // 010C → 41 0C; общий путь должен совпасть со специализированным.
        assertArrayEquals(intArrayOf(0x1A, 0xF8), ObdParser.dataBytesFor("41 0C 1A F8", "010C"))
    }

    @Test
    fun noMatchingHeaderReturnsNull() {
        assertNull(ObdParser.dataBytesFor("NO DATA", "2101"))
        // Ответ на другой PID — не наш.
        assertNull(ObdParser.dataBytesFor("61 02 AB", "2101"))
    }

    @Test
    fun malformedCommandReturnsNull() {
        assertNull(ObdParser.dataBytesFor("61 01 AB", "21"))    // нет байта PID
        assertNull(ObdParser.dataBytesFor("61 01 AB", "21ZZ"))  // не hex
    }

    // ---- CustomPid.decode ----

    @Test
    fun decodeSingleByteWithScaleAndOffset() {
        // Классика: A/2 − 64 (угол опережения на многих ЭБУ)
        val v = pid(scale = 0.5, offset = -64.0).decode(intArrayOf(200))
        assertEquals(36.0, v!!, 0.0001)
    }

    @Test
    fun decodeTwoBytesBigEndian() {
        // 0x1AF8 = 6904; ×0.25 = 1726 (как обороты в J1979)
        val v = pid(byteCount = 2, scale = 0.25).decode(intArrayOf(0x1A, 0xF8))
        assertEquals(1726.0, v!!, 0.0001)
    }

    @Test
    fun decodeRespectsByteOffset() {
        val v = pid(byteOffset = 2, byteCount = 1).decode(intArrayOf(0x11, 0x22, 0x33))
        assertEquals(0x33.toDouble(), v!!, 0.0001)
    }

    @Test
    fun decodeSignedByte() {
        // 0xF6 со знаком = −10
        val v = pid(signed = true).decode(intArrayOf(0xF6))
        assertEquals(-10.0, v!!, 0.0001)
        // Без знака тот же байт = 246
        assertEquals(246.0, pid(signed = false).decode(intArrayOf(0xF6))!!, 0.0001)
    }

    @Test
    fun decodeShortResponseReturnsNull() {
        assertNull(pid(byteOffset = 1, byteCount = 2).decode(intArrayOf(0x11, 0x22)))
    }

    @Test
    fun validation() {
        assertTrue(pid().isValid())
        assertTrue(pid(cmd = "22F190").isValid())
        assertFalse(pid(cmd = "21").isValid())        // слишком коротко
        assertFalse(pid(cmd = "21G1").isValid())      // не hex
        assertFalse(pid(byteCount = 3).isValid())     // больше 2 байт не поддерживаем
        assertFalse(pid(byteOffset = -1).isValid())
    }
}
