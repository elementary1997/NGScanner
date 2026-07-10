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

    // ELM327 печатает CAF-сегменты последовательно, поэтому склеиваем в порядке
    // поступления. Сортировать по индексу нельзя: он одно-нибловый и после F
    // оборачивается в 0 — на сообщении длиннее 16 сегментов сортировка переставила
    // бы «обёрнутые» сегменты в начало и собрала данные неверно.
    @Test
    fun colonSegmentsPreserveOrderPastSixteen() {
        // 17 сегментов: индексы 0..F, затем снова 0 (счётчик обернулся). Данные
        // каждого сегмента — его порядковый номер (00..10) в hex.
        val sb = StringBuilder("011\r") // declared = 0x11 = 17 байт
        val expected = StringBuilder()
        for (n in 0 until 17) {
            val idx = Integer.toHexString(n % 16)
            val dataByte = "%02X".format(n)
            sb.append(idx).append(':').append(dataByte).append('\r')
            expected.append(dataByte)
        }
        sb.append('>')
        assertEquals(expected.toString(), IsoTp.reassemble(sb.toString()))
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

    // С заголовками (ATH1) кадры группируются по CAN-ID → отдельное сообщение на ЭБУ.
    @Test
    fun headeredGroupsFramesByEcu() {
        val raw = "7E80443010113000000\r7E90443010700000000\r\r>"
        val msgs = IsoTp.messages(raw, headerHexLen = 3)
        assertEquals(2, msgs.size)
        assertEquals("43010113", msgs[0]) // 7E8: SF(4б) 43 01 0113
        assertEquals("43010700", msgs[1]) // 7E9: SF(4б) 43 01 0700
    }

    // Статус-токен в буфере не должен отключать группировку по ЭБУ.
    @Test
    fun headeredIgnoresStatusToken() {
        val raw = "SEARCHING...\r7E80443010113000000\r\r>"
        val msgs = IsoTp.messages(raw, headerHexLen = 3)
        assertEquals(1, msgs.size)
        assertEquals("43010113", msgs[0])
    }
}
