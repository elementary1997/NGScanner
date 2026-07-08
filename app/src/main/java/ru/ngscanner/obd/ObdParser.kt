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

    private fun normalize(raw: String): String =
        raw.uppercase().replace(Regex("[^0-9A-F]"), "")
}
