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
     * Коды неисправностей из ответа Mode 03/07 (`43`/`47` + пары байт).
     * Каждый код: 2 старших бита — система (P/C/B/U), далее — цифры.
     *
     * [isCan] — протокол ЭБУ: в CAN (ISO 15765-4) после заголовка идёт
     * байт-счётчик кодов, в легаси (ISO 9141/KWP/J1850) счётчика нет. Формат
     * нельзя надёжно угадать по самим байтам (легаси-код P01xx неотличим от
     * CAN-счётчика 01), поэтому протокол передаётся снаружи (см. Elm327.isCan).
     */
    fun parseDtcs(raw: String, isCan: Boolean = true): DtcResult {
        val upper = raw.uppercase()
        when {
            "UNABLE TO CONNECT" in upper || "BUS INIT" in upper || "BUSINIT" in upper ||
                "CAN ERROR" in upper || "BUS ERROR" in upper -> return DtcResult.BusError
            "NO DATA" in upper || "NODATA" in upper -> return DtcResult.NoData
        }
        // Каждый ответ ЭБУ разбираем отдельно: на CAN без заголовков разные модули
        // отвечают разными строками, слияние в единый поток рождает фантомные коды.
        val messages = IsoTp.messages(raw)
        if (messages.isEmpty()) {
            return if (normalize(raw).isBlank()) DtcResult.NoData else DtcResult.Unknown(raw.trim())
        }
        val codes = mutableListOf<String>()
        var sawHeader = false
        for (msg in messages) {
            val start = msg.indexOf("43").takeIf { it >= 0 } ?: msg.indexOf("47")
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
    fun parseVin(raw: String): String? {
        // VIN отвечает один ЭБУ — берём сообщение, содержащее заголовок 4902.
        val hex = IsoTp.messages(raw).firstOrNull { it.contains("4902") } ?: normalize(raw)
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
