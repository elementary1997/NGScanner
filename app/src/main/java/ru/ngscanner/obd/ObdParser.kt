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
     * Коды неисправностей из ответа Mode 03/07 (`43`/`47` + пары байт).
     * Каждый код: 2 старших бита — система (P/C/B/U), далее — цифры.
     */
    fun parseDtcs(raw: String): List<String> {
        val hex = normalize(raw)
        val start = hex.indexOf("43").takeIf { it >= 0 } ?: hex.indexOf("47")
        if (start < 0) return emptyList()
        val body = hex.substring(start + 2)
        val codes = mutableListOf<String>()
        var i = 0
        while (i + 4 <= body.length) {
            val word = body.substring(i, i + 4)
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
