package ru.ngscanner.obd

/**
 * Разбор ответов OBD-II. Ответы приходят как hex-байты; парсеры терпимы к
 * пробелам, эху команды и постороннему мусору вокруг полезных данных.
 */
object ObdParser {

    /** RPM из Mode 01 PID 0C: `41 0C A B` → ((A*256)+B)/4. */
    fun parseRpm(raw: String): Int? {
        val hex = normalize(raw)
        val idx = hex.indexOf("410C")
        if (idx < 0 || hex.length < idx + 8) return null
        return runCatching {
            val a = hex.substring(idx + 4, idx + 6).toInt(16)
            val b = hex.substring(idx + 6, idx + 8).toInt(16)
            ((a * 256) + b) / 4
        }.getOrNull()
    }

    /** Температура ОЖ из Mode 01 PID 05: `41 05 A` → A − 40 (°C). */
    fun parseCoolantTemp(raw: String): Int? {
        val hex = normalize(raw)
        val idx = hex.indexOf("4105")
        if (idx < 0 || hex.length < idx + 6) return null
        return runCatching { hex.substring(idx + 4, idx + 6).toInt(16) - 40 }.getOrNull()
    }

    /**
     * Результат запроса кодов неисправностей. Важно отличать «кодов нет»
     * (ЭБУ ответил, всё исправно) от «шина недоступна» — иначе диагноз
     * ложноотрицательный: пользователь решит, что авто исправно, при
     * недоступном ЭБУ.
     */
    sealed interface DtcResult {
        /** ЭБУ ответил; список может быть пустым (реально кодов нет). */
        data class Ok(val codes: List<String>) : DtcResult

        /** NO DATA — ЭБУ не вернул данные (нет кодов либо режим не поддержан). */
        data object NoData : DtcResult

        /** Ошибка шины: UNABLE TO CONNECT / BUS INIT / CAN ERROR — связь с ЭБУ не установлена. */
        data object BusError : DtcResult

        /** Непонятный ответ адаптера (для показа сырого текста). */
        data class Unknown(val raw: String) : DtcResult
    }

    /**
     * Коды неисправностей из ответа Mode 03/07/0A (`43`/`47`/`4A` + пары байт).
     * Каждый код: 2 старших бита — система (P/C/B/U), далее — цифры.
     *
     * [isCan] — протокол ЭБУ: в CAN (ISO 15765-4) после заголовка идёт
     * байт-счётчик кодов, в легаси (ISO 9141/KWP/J1850) счётчика нет. Формат
     * нельзя надёжно угадать по самим байтам (легаси-код P01xx неотличим от
     * CAN-счётчика 01), поэтому протокол передаётся снаружи (см. Elm327.isCan).
     */
    fun parseDtcs(raw: String, isCan: Boolean = true, headerHexLen: Int = 0): DtcResult {
        val upper = raw.uppercase()
        when {
            "UNABLE TO CONNECT" in upper || "BUS INIT" in upper || "BUSINIT" in upper ||
                "CAN ERROR" in upper || "BUS ERROR" in upper -> return DtcResult.BusError
            "NO DATA" in upper || "NODATA" in upper -> return DtcResult.NoData
        }
        // Каждый ответ ЭБУ разбираем отдельно. С заголовками (ATH1) кадры группируются
        // по CAN-ID — единственный надёжный способ не слить ответы разных модулей.
        val messages = IsoTp.messages(raw, headerHexLen)
        if (messages.isEmpty()) {
            return if (normalize(raw).isBlank()) DtcResult.NoData else DtcResult.Unknown(raw.trim())
        }
        val codes = mutableListOf<String>()
        var sawHeader = false
        for (msg in messages) {
            // Заголовок ответа режима стоит в начале собранного payload: 43 (Mode 03),
            // 47 (Mode 07) или 4A (Mode 0A). Берём самый ранний — это и есть заголовок.
            val start = listOf("43", "47", "4A")
                .mapNotNull { h -> msg.indexOf(h).takeIf { it >= 0 } }
                .minOrNull() ?: -1
            if (start < 0) continue
            sawHeader = true
            codes += parseDtcPayload(msg.substring(start + 2), isCan)
        }
        if (!sawHeader) {
            return if (normalize(raw).isBlank()) DtcResult.NoData else DtcResult.Unknown(raw.trim())
        }
        return DtcResult.Ok(codes.distinct())
    }

    private fun parseDtcPayload(payload: String, isCan: Boolean): List<String> =
        if (isCan) parseCanDtcs(payload) else parseLegacyDtcs(payload)

    /**
     * CAN (ISO 15765-4): после `43` идёт байт-счётчик числа кодов, далее пары DTC
     * и, возможно, хвост-заполнитель `00`. Берём ровно столько кодов, сколько
     * указал счётчик — заполнитель отсекается по счётчику, без догадок о длине.
     */
    private fun parseCanDtcs(payload: String): List<String> {
        if (payload.length < 2) return emptyList()
        val count = payload.substring(0, 2).toIntOrNull(16) ?: return emptyList()
        val body = payload.drop(2)
        val codes = mutableListOf<String>()
        var i = 0
        while (i + 4 <= body.length && codes.size < count) {
            val word = body.substring(i, i + 4)
            i += 4
            if (word == "0000") continue // заполнитель
            runCatching { decodeDtc(word) }.getOrNull()?.let { codes.add(it) }
        }
        return codes
    }

    /** Легаси (ISO 9141/KWP/J1850): счётчика нет — только пары DTC. */
    private fun parseLegacyDtcs(payload: String): List<String> {
        val codes = mutableListOf<String>()
        var i = 0
        while (i + 4 <= payload.length) {
            val word = payload.substring(i, i + 4)
            i += 4
            if (word == "0000") continue // заполнитель / пустой слот
            runCatching { decodeDtc(word) }.getOrNull()?.let { codes.add(it) }
        }
        return codes
    }

    private fun decodeDtc(word: String): String {
        val value = word.toInt(16)
        val system = when (value ushr 14) {
            0 -> 'P'
            1 -> 'C'
            2 -> 'B'
            else -> 'U'
        }
        val d1 = (value ushr 12) and 0x3
        val rest = value and 0x0FFF
        return "$system$d1" + "%03X".format(rest)
    }

    /**
     * VIN из ответа Mode 09 PID 02 (`49 02 …`). Ответ всегда мультифреймовый
     * (17 символов + служебные байты > 7): сначала собираем ISO-TP в единый
     * поток, затем после `4902` пропускаем байт-счётчик и читаем ASCII-байты,
     * оставляя 17 буквенно-цифровых символов.
     */
    fun parseVin(raw: String, headerHexLen: Int = 0): String? {
        // VIN отвечает один ЭБУ — берём сообщение, содержащее заголовок 4902.
        val hex = IsoTp.messages(raw, headerHexLen).firstOrNull { it.contains("4902") } ?: normalize(raw)
        if (!hex.contains("4902")) return null
        val data = StringBuilder()
        for (part in hex.split("4902").drop(1)) {
            // Первый байт фрейма — номер/счётчик, пропускаем.
            if (part.length >= 2) data.append(part.substring(2))
        }
        val ascii = data.toString().chunked(2)
            .filter { it.length == 2 }
            .mapNotNull { runCatching { it.toInt(16) }.getOrNull() }
            .filter { it in 0x20..0x7E }
            .map { it.toChar() }
            .joinToString("")
        val vin = ascii.filter { it.isLetterOrDigit() }
        return vin.takeIf { it.length >= 11 }?.take(17)
    }

    /** Один бортовой монитор готовности: поддерживается ли и завершён ли самотест. */
    data class Monitor(val name: String, val ready: Boolean)

    /**
     * Готовность бортовых мониторов (Mode 01 PID 01). [milOn] — горит ли Check Engine,
     * [dtcCount] — число подтверждённых emission-кодов, [compression] — дизель (иначе бензин),
     * [monitors] — только поддерживаемые данным авто мониторы.
     */
    data class Readiness(
        val milOn: Boolean,
        val dtcCount: Int,
        val compression: Boolean,
        val monitors: List<Monitor>,
    )

    // Наборы непрерывных и разовых мониторов (порядок бит 0..7 в байтах C/D).
    // Выверено по python-OBD (obd/codes.py) — см. sae J1979 / ISO 15031-5.
    private val CONTINUOUS = listOf("Пропуски воспламенения", "Топливная система", "Компоненты")
    private val SPARK_MONITORS = listOf(
        "Катализатор", "Подогрев катализатора", "Система EVAP", "Вторичный воздух",
        "Хладагент A/C", "Датчик кислорода", "Подогрев датчика O₂", "EGR/VVT",
    )
    private val COMPRESSION_MONITORS = listOf(
        "NMHC-катализатор", "NOx/SCR", null, "Наддув",
        null, "Датчик ОГ", "Сажевый фильтр", "EGR/VVT",
    )

    /**
     * Статус готовности мониторов из Mode 01 PID 01 (`41 01 A B C D`).
     * A7 — MIL, A6..A0 — число кодов; B3 — тип зажигания (0 бензин / 1 дизель);
     * непрерывные мониторы в B (supported B0..B2, complete B4..B6, где бит=0 — готов),
     * разовые — байт C (supported=1) и D (complete: бит=0 — готов). Монитор считаем
     * присутствующим только при supported; готов = supported и complete-бит равен 0.
     */
    fun parseReadiness(raw: String, headerHexLen: Int = 0): Readiness? {
        val data = dataBytes(raw, "0101") ?: return null
        if (data.size < 4) return null
        val (a, b, c, d) = listOf(data[0], data[1], data[2], data[3])
        val monitors = mutableListOf<Monitor>()
        // Непрерывные (байт B): supported B0..B2, «не готов» B4..B6 (бит=1 → не завершён).
        for (i in 0..2) {
            if ((b shr i) and 1 == 1) monitors.add(Monitor(CONTINUOUS[i], ready = (b shr (i + 4)) and 1 == 0))
        }
        // Разовые (байт C supported, байт D готовность). Набор зависит от типа двигателя.
        val compression = (b shr 3) and 1 == 1
        val names = if (compression) COMPRESSION_MONITORS else SPARK_MONITORS
        for (i in 0..7) {
            val name = names[i] ?: continue
            if ((c shr i) and 1 == 1) monitors.add(Monitor(name, ready = (d shr i) and 1 == 0))
        }
        return Readiness(milOn = (a and 0x80) != 0, dtcCount = a and 0x7F, compression = compression, monitors = monitors)
    }

    /**
     * Номера калибровок ЭБУ (прошивок) из Mode 09 PID 04 (`49 04 NODI + NODI×16 байт ASCII`).
     * После заголовка `4904` идёт байт-счётчик числа калибровок, затем блоки строго по
     * 16 байт (правый паддинг `00`). Отвечать может несколько ЭБУ — берём первый.
     */
    fun parseCalibrationIds(raw: String, headerHexLen: Int = 0): List<String> {
        val hex = IsoTp.messages(raw, headerHexLen).firstOrNull { it.contains("4904") } ?: normalize(raw)
        val idx = hex.indexOf("4904")
        if (idx < 0) return emptyList()
        val after = hex.substring(idx + 4)
        if (after.length < 2) return emptyList()
        val count = after.substring(0, 2).toIntOrNull(16) ?: return emptyList()
        val body = after.drop(2)
        // Число блоков берём по счётчику; при неадекватном счётчике — по длине тела.
        val blocks = if (count in 1..16) count else body.length / 32
        val result = mutableListOf<String>()
        var i = 0
        repeat(blocks) {
            if (i + 32 > body.length) return@repeat
            val ascii = body.substring(i, i + 32).chunked(2)
                .mapNotNull { runCatching { it.toInt(16) }.getOrNull() }
                .filter { it in 0x20..0x7E } // печатаемые; хвостовой паддинг 00 отсекается
                .map { it.toChar() }.joinToString("").trim()
            i += 32
            if (ascii.isNotBlank()) result.add(ascii)
        }
        return result
    }

    /**
     * Номера поддерживаемых PID из ответа на маску 0100/0120/0140.
     * Ответ — 4 байта (32 бита): старший бит соответствует `base + 1`.
     * Например для 0100 бит 31 → PID 0x01, бит 0 → PID 0x20.
     */
    fun supportedPids(raw: String, base: Int): List<Int> {
        val data = dataBytes(raw, "01" + "%02X".format(base)) ?: return emptyList()
        if (data.size < 4) return emptyList()
        val bits = (data[0].toLong() shl 24) or (data[1].toLong() shl 16) or
            (data[2].toLong() shl 8) or data[3].toLong()
        val result = mutableListOf<Int>()
        for (i in 0 until 32) {
            if ((bits shr (31 - i)) and 1L == 1L) result.add(base + i + 1)
        }
        return result
    }

    /** Байты данных (A,B,…) из ответа на команду «01XX» → после заголовка «41XX». */
    fun dataBytes(raw: String, cmd: String): IntArray? {
        val hex = normalize(raw)
        val prefix = "41" + cmd.uppercase().removePrefix("01")
        val idx = hex.indexOf(prefix)
        if (idx < 0) return null
        val dataHex = hex.substring(idx + prefix.length)
        if (dataHex.length < 2) return null
        return dataHex.chunked(2).filter { it.length == 2 }.map { it.toInt(16) }.toIntArray()
    }

    /**
     * Байты данных из ответа Mode 02 (freeze frame) — заголовок «42» + суффикс PID.
     * Формат ответа: `42 PID FRAME# data…`, поэтому после «42{PID}» пропускаем
     * байт номера кадра (frame#), иначе значение читается со сдвигом на байт.
     * Например для 020C ищем «420C», отбрасываем номер кадра и берём данные.
     */
    fun freezeFrameBytes(raw: String, pidSuffix: String): IntArray? {
        val hex = normalize(raw)
        val prefix = "42" + pidSuffix.uppercase()
        val idx = hex.indexOf(prefix)
        if (idx < 0) return null
        val dataHex = hex.substring(idx + prefix.length).drop(2)
        if (dataHex.length < 2) return null
        return dataHex.chunked(2).filter { it.length == 2 }.map { it.toInt(16) }.toIntArray()
    }

    private fun normalize(raw: String): String =
        raw.uppercase().replace(Regex("[^0-9A-F]"), "")
}
