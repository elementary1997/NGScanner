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

    // С включёнными заголовками (ATH1) ответ несёт CAN-ID и PCI: 7E8 04 41 0C 1A F8.
    // indexOf-парсеры живых данных должны находить 41 0C и после заголовка.
    @Test
    fun parsesRpmWithCanHeader() {
        assertEquals(1726, ObdParser.parseRpm("7E80441 0C1AF8\r>"))
    }

    @Test
    fun dataBytesWithCanHeader() {
        // 7E8 06 41 00 BE 3E B8 11 → маска поддержки PID после заголовка.
        val data = ObdParser.dataBytes("7E8064100BE3EB811", "0100")
        assertEquals(4, data?.size)
        assertEquals(0xBE, data?.get(0))
    }

    @Test
    fun freezeFrameBytesWithCanHeader() {
        // 7E8 04 42 0C 00 1A F8 → freeze frame RPM: после 42 0C пропускаем номер
        // кадра (00) и берём данные даже при включённых заголовках.
        val data = ObdParser.freezeFrameBytes("7E8044 20C001AF8", "0C")
        assertEquals(0x1A, data?.get(0))
        assertEquals(0xF8, data?.get(1))
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
        val result = ObdParser.parseDtcs("43 01 33", isCan = false)
        assertTrue(result is DtcResult.Ok)
        assertEquals(listOf("P0133"), (result as DtcResult.Ok).codes)
    }

    @Test
    fun parsesTwoLegacyDtcs() {
        val result = ObdParser.parseDtcs("43 01 33 04 20", isCan = false) as DtcResult.Ok
        assertEquals(listOf("P0133", "P0420"), result.codes)
    }

    // ---- DTC: CAN-формат (первый байт — счётчик кодов) ----
    @Test
    fun parsesCanDtcWithCounter() {
        // 43 02 0133 0420 → счётчик 02, коды P0133 и P0420 (без фантомов)
        val result = ObdParser.parseDtcs("43 02 01 33 04 20", isCan = true) as DtcResult.Ok
        assertEquals(listOf("P0133", "P0420"), result.codes)
    }

    @Test
    fun parsesCanDtcWithPadding() {
        // 43 01 0301 0000 → счётчик 01, код P0301, хвост-заполнитель игнорируется
        val result = ObdParser.parseDtcs("43 01 03 01 00 00", isCan = true) as DtcResult.Ok
        assertEquals(listOf("P0301"), result.codes)
    }

    @Test
    fun parsesCanSingleFrameWithFullPadding() {
        // 43 01 0301 000000 — заполнение до 7 байт кадра; старый парсер по чётности
        // читал бы это как легаси и давал фантомы P0103/P0100. Счётчик даёт P0301.
        val result = ObdParser.parseDtcs("43 01 03 01 00 00 00", isCan = true) as DtcResult.Ok
        assertEquals(listOf("P0301"), result.codes)
    }

    // ---- DTC: ответы нескольких ЭБУ (разные строки) не сливаются ----
    @Test
    fun multiEcuCanDtcsNoPhantom() {
        // Модуль A: 43 01 0133 (P0133); модуль B: 43 00 (кодов нет).
        // Слияние в один поток дало бы фантом C0300 — теперь его нет.
        val result = ObdParser.parseDtcs("43 01 01 33\r43 00", isCan = true) as DtcResult.Ok
        assertEquals(listOf("P0133"), result.codes)
    }

    @Test
    fun multiEcuLegacyDtcsCombined() {
        // Два легаси-ЭБУ отвечают разными строками — коды собираются из обоих.
        val result = ObdParser.parseDtcs("43 01 33\r43 04 20", isCan = false) as DtcResult.Ok
        assertEquals(listOf("P0133", "P0420"), result.codes)
    }

    // ---- DTC: заголовки CAN (ATH1) — группировка по ЭБУ (эталонные векторы) ----
    // Формат строки: [CAN-ID 3 hex][PCI][данные][паддинг], пробелы off.

    @Test
    fun headeredSingleEcuTwoDtcs() {
        // 7E8 | 06(SF,6б) | 43 02 0133 0143 | 00 → P0133, P0143.
        val result = ObdParser.parseDtcs("7E80643020133014300\r\r>", isCan = true, headerHexLen = 3) as DtcResult.Ok
        assertEquals(listOf("P0133", "P0143"), result.codes)
    }

    @Test
    fun headeredTwoEcusNoPhantom() {
        // Баг ревью: 7E8→P0113, 7E9→P0700. Слияние дало бы фантом P0043 и потеряло
        // бы P0700. Группировка по 7E8/7E9 → ровно два реальных кода.
        val raw = "7E80443010113000000\r7E90443010700000000\r\r>"
        val result = ObdParser.parseDtcs(raw, isCan = true, headerHexLen = 3) as DtcResult.Ok
        assertEquals(listOf("P0113", "P0700"), result.codes)
    }

    @Test
    fun headeredMultiFrameFiveDtcs() {
        // Один ЭБУ, First Frame (100C=12б) + Consecutive (21): 5 кодов.
        val raw = "7E8100C430501330143\r7E82101710300042000\r\r>"
        val result = ObdParser.parseDtcs(raw, isCan = true, headerHexLen = 3) as DtcResult.Ok
        assertEquals(listOf("P0133", "P0143", "P0171", "P0300", "P0420"), result.codes)
    }

    @Test
    fun headeredSingleDtcPaddingStripped() {
        // 7E8 | 04(SF,4б) | 43 01 0301 | 000000 паддинг → только P0301.
        val result = ObdParser.parseDtcs("7E80443010301000000\r\r>", isCan = true, headerHexLen = 3) as DtcResult.Ok
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

    @Test
    fun parsesMultiframeVinWithCanHeader() {
        // Боевой путь: заголовки ATH1, один ЭБУ 7E8, First+Consecutive Frames.
        // FF 1014 (len 20) + CF 21/22 → VIN «XTA210990Y1234567».
        val raw = "7E81014490201585441\r7E82132313039393059\r7E82231323334353637\r\r>"
        assertEquals("XTA210990Y1234567", ObdParser.parseVin(raw, headerHexLen = 3))
    }

    // ---- ISO-TP: многофреймовый VIN, авто-форматирование ELM327 (CAF on) ----
    @Test
    fun parsesMultiframeVinColonFormat() {
        // «014» — длина 20 байт; сегменты 0/1/2 с чистыми данными 4902 01 + ASCII.
        val raw = "014\r0:49020158544132\r1:31303939305931\r2:323334353637\r>"
        assertEquals("XTA210990Y1234567", ObdParser.parseVin(raw))
    }

    // ---- ISO-TP: сырые кадры First/Consecutive Frame (CAF off) ----
    @Test
    fun parsesMultiframeVinRawFrames() {
        // 1014 — First Frame, длина 0x014; 21../22.. — Consecutive Frames.
        val raw = "1014490201585441\r2132313039393059\r2231323334353637\r>"
        assertEquals("XTA210990Y1234567", ObdParser.parseVin(raw))
    }

    // ---- ISO-TP: длинный список кодов, собранный из нескольких кадров ----
    @Test
    fun parsesMultiframeDtcColonFormat() {
        // «00C» — 12 байт; 43 05 (счётчик) + пять кодов, разбитых на два сегмента.
        val raw = "00C\r0:43050133042003\r1:0101710300\r>"
        val result = ObdParser.parseDtcs(raw) as DtcResult.Ok
        assertEquals(listOf("P0133", "P0420", "P0301", "P0171", "P0300"), result.codes)
    }

    // ---- DTC: Mode 0A (permanent), заголовок ответа 4A — формат как 43/47 ----
    @Test
    fun parsesPermanentDtcCan() {
        // 4A 02 0133 0420 → счётчик 02, коды P0133 и P0420 (вектор из сверки формата).
        val result = ObdParser.parseDtcs("4A 02 01 33 04 20", isCan = true, respHeader = "4A") as DtcResult.Ok
        assertEquals(listOf("P0133", "P0420"), result.codes)
    }

    @Test
    fun parsesPermanentDtcAllSystems() {
        // 4A 04 + P/C/B/U с первой цифрой 0: 0133/4133/8133/C133.
        val result = ObdParser.parseDtcs("4A 04 01 33 41 33 81 33 C1 33", isCan = true, respHeader = "4A") as DtcResult.Ok
        assertEquals(listOf("P0133", "C0133", "B0133", "U0133"), result.codes)
    }

    @Test
    fun permanentDtcEmptyIsOkNotNoData() {
        // 4A 00 — ЭБУ ответил, постоянных кодов нет (не путать с NO DATA).
        val result = ObdParser.parseDtcs("4A 00", isCan = true, respHeader = "4A")
        assertTrue(result is DtcResult.Ok)
        assertTrue((result as DtcResult.Ok).codes.isEmpty())
    }

    @Test
    fun parseDtcsMatchesOnlyExpectedHeader() {
        // Запрос Mode 03 (respHeader 43), а в ответе только 4A — не должен ловить чужой
        // заголовок и придумывать код: ищем ровно ожидаемый ответ-байт.
        val result = ObdParser.parseDtcs("4A 01 01 33", isCan = true, respHeader = "43")
        assertTrue(result is DtcResult.Unknown)
    }

    // ---- Readiness: Mode 01 PID 01 (вектор из сверки с python-OBD) ----
    @Test
    fun parsesReadinessSparkVector() {
        // 41 01 81 07 65 04: MIL горит, 1 код, бензин; Catalyst готов, EVAP не готов,
        // датчик O2 и его подогрев готовы.
        val r = ObdParser.parseReadiness("41 01 81 07 65 04")!!
        assertTrue(r.milOn)
        assertEquals(1, r.dtcCount)
        assertEquals(false, r.compression)
        // Непрерывные (B=07): все три поддержаны и готовы.
        assertTrue(r.monitors.first { it.name == "Пропуски воспламенения" }.ready)
        // Разовые: C=65 (Catalyst,EVAP,O2,O2-heater поддержаны), D=04 (только EVAP не готов).
        assertTrue(r.monitors.first { it.name == "Катализатор" }.ready)
        assertEquals(false, r.monitors.first { it.name == "Система EVAP" }.ready)
        assertTrue(r.monitors.first { it.name == "Датчик кислорода" }.ready)
    }

    @Test
    fun parsesReadinessCompressionIgnitionType() {
        // 41 01 00 0F 6B 08: MIL off, дизель (B3=1); Наддув не готов (D3=1).
        val r = ObdParser.parseReadiness("41 01 00 0F 6B 08")!!
        assertEquals(false, r.milOn)
        assertTrue(r.compression)
        assertEquals(false, r.monitors.first { it.name == "Наддув" }.ready)
        assertTrue(r.monitors.first { it.name == "NMHC-катализатор" }.ready)
    }

    @Test
    fun readinessWithCanHeader() {
        // 7E8 06 41 01 81 07 65 04 — с заголовком (ATH1) статус тоже читается.
        val r = ObdParser.parseReadiness("7E8064101810765 04", headerHexLen = 3)!!
        assertTrue(r.milOn)
        assertEquals(1, r.dtcCount)
    }

    @Test
    fun readinessAggregatesAcrossEcus() {
        // Два ЭБУ по 7Ex: 7E8 — Catalyst поддержан и ГОТОВ (C=01,D=00);
        // 7E9 — Catalyst поддержан и НЕ готов (C=01,D=01), плюс 1 код.
        // Чтение только первого ЭБУ дало бы ложное «готов»; агрегация → НЕ готов.
        // Дискриминирует и headerHexLen: без группировки взялся бы лишь первый 4101.
        val r = ObdParser.parseReadiness("7E806410100000100\r7E906410101000101", headerHexLen = 3)!!
        assertEquals(1, r.dtcCount) // 0 + 1 по обоим ЭБУ
        assertEquals(false, r.monitors.first { it.name == "Катализатор" }.ready)
    }

    // ---- Calibration ID: Mode 09 PID 04 (векторы из сверки формата) ----
    @Test
    fun parsesSingleCalibrationId() {
        // 49 04 01 + 16 байт «39800000AC» с правым паддингом 00.
        val raw = "49 04 01 33 39 38 30 30 30 30 30 41 43 00 00 00 00 00 00"
        assertEquals(listOf("39800000AC"), ObdParser.parseCalibrationIds(raw))
    }

    @Test
    fun parsesTwoCalibrationIds() {
        // 49 04 02 + два блока по 16 байт: «JMB*36761500» и «JMB*36751200».
        val raw = "49 04 02 4A4D422A3336373631353030 00000000 4A4D422A3336373531323030 00000000"
        assertEquals(listOf("JMB*36761500", "JMB*36751200"), ObdParser.parseCalibrationIds(raw))
    }

    @Test
    fun parsesMultiframeCalibrationIdRawFrames() {
        // Cal ID, собираемый из ISO-TP кадров (CAF off): FF 1013 + два CF.
        val raw = "1013490401333938\r2130303030304143\r22000000000000"
        assertEquals(listOf("39800000AC"), ObdParser.parseCalibrationIds(raw))
    }

    // ---- VIN: усечённый (битый мультифрейм) не отдаём ----
    @Test
    fun rejectsTruncatedVin() {
        // Меньше 17 ASCII символов (битый мультифрейм) — parseVin должен вернуть null.
        val raw = "4902015741 4A4D4235 34313332"
        assertNull(ObdParser.parseVin(raw))
    }

    // ---- Freeze frame: код, к которому привязан снимок (Mode 02 PID 02) ----
    @Test
    fun freezeFrameDtcDecodesCode() {
        // 42 02 <кадр 00> 01 33 → пропускаем номер кадра, DTC 0133 → P0133.
        assertEquals("P0133", ObdParser.freezeFrameDtc("42 02 00 01 33"))
    }

    @Test
    fun freezeFrameDtcNullWhenNoCode() {
        assertNull(ObdParser.freezeFrameDtc("42 02 00 00 00")) // 0000 — кода нет
        assertNull(ObdParser.freezeFrameDtc("NO DATA")) // нет 4202
    }

    @Test
    fun freezeFrameDtcWithCanHeader() {
        // С заголовком ЭБУ и SF-байтом длины перед 4202 — декодируется тот же код.
        assertEquals("P0133", ObdParser.freezeFrameDtc("7E8 05 42 02 00 01 33"))
    }
}
