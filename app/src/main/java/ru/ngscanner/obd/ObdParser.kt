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
     */
    fun parseDtcs(raw: String): DtcResult {
        val upper = raw.uppercase()
        when {
            "UNABLE TO CONNECT" in upper || "BUS INIT" in upper || "BUSINIT" in upper ||
                "CAN ERROR" in upper || "BUS ERROR" in upper -> return DtcResult.BusError
            "NO DATA" in upper || "NODATA" in upper -> return DtcResult.NoData
        }
        val hex = normalize(raw)
        val start = hex.indexOf("43").takeIf { it >= 0 } ?: hex.indexOf("47")
        if (start < 0) {
            return if (hex.isBlank()) DtcResult.NoData else DtcResult.Unknown(raw.trim())
        }
        return DtcResult.Ok(parseDtcPayload(hex.substring(start + 2)))
    }

    /**
     * Разбирает тело ответа после заголовка `43`/`47`.
     *
     * В CAN (ISO 15765-4) сразу после `43` идёт байт-счётчик числа кодов,
     * поэтому длина тела в hex ≡ 2 (mod 4). В легаси-протоколах (ISO 9141/KWP)
     * счётчика нет и длина ≡ 0 (mod 4). Эта чётность однозначно различает
     * форматы — иначе на авто с 2008+ появляются фантомные коды из-за сдвига.
     */
    private fun parseDtcPayload(afterHeader: String): List<String> {
        val payload = if (afterHeader.length % 4 == 2) afterHeader.drop(2) else afterHeader
        val codes = mutableListOf<String>()
        var i = 0
        while (i + 4 <= payload.length) {
            val word = payload.substring(i, i + 4)
            i += 4
            if (word == "0000") continue // заполнитель
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
     * VIN из ответа Mode 09 PID 02 (`49 02 …`). Ответ мультифреймовый: после
     * каждого заголовка `4902` идёт байт-счётчик фрейма, затем ASCII-байты.
     * Собираем все ASCII-символы и оставляем 17 буквенно-цифровых.
     */
    fun parseVin(raw: String): String? {
        val hex = normalize(raw)
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

    private fun normalize(raw: String): String =
        raw.uppercase().replace(Regex("[^0-9A-F]"), "")
}
