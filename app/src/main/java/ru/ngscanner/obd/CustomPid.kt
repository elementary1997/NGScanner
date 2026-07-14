package ru.ngscanner.obd

import kotlinx.serialization.Serializable

/**
 * Заводской («mfr-specific») PID, заданный пользователем.
 *
 * Стандарт OBD-II (Mode 01) описывает лишь общий набор параметров; всё интересное на
 * конкретном ЭБУ — ВАЗовские Bosch M7.9.7 / M74 / ME17.9.7, «Январь 7.2» и прочие —
 * лежит в заводских модах (обычно 21/22) по адресам, которые производитель не публикует.
 *
 * Сочинять эти адреса нельзя: неверный адрес даёт не ошибку, а правдоподобное
 * НЕВЕРНОЕ число — худший вид вранья в диагностике. Поэтому приложение не «знает»
 * заводские PID заранее, а даёт задать их тому, у кого есть проверенное описание
 * (даташит ЭБУ, документация производителя, собственные замеры): команда, где в ответе
 * лежит значение и как его пересчитать в физическую величину.
 *
 * Формула: `значение = raw * scale + offset`, где `raw` — [byteCount] байт данных
 * начиная с [byteOffset] (big-endian), со знаком при [signed].
 *
 * @property cmd команда целиком в hex — мода + PID, например `2101` или `22F190`
 */
@Serializable
data class CustomPid(
    val id: String,
    val name: String,
    val cmd: String,
    val unit: String = "",
    val byteOffset: Int = 0,
    val byteCount: Int = 1,
    val scale: Double = 1.0,
    val offset: Double = 0.0,
    val signed: Boolean = false,
) {
    /** Декодирует байты данных ответа; `null`, если параметры не сходятся с длиной ответа. */
    fun decode(data: IntArray): Double? {
        if (byteOffset < 0 || byteCount !in 1..MAX_BYTES) return null
        if (data.size < byteOffset + byteCount) return null
        var raw = 0
        for (i in 0 until byteCount) raw = (raw shl 8) or (data[byteOffset + i] and 0xFF)
        val v = if (signed) {
            val bits = byteCount * 8
            val signBit = 1 shl (bits - 1)
            if (raw and signBit != 0) raw - (1 shl bits) else raw
        } else {
            raw
        }
        return v * scale + offset
    }

    /** Команда валидна: hex, чётной длины, есть мода и хотя бы байт PID. */
    fun isValid(): Boolean = CMD_RE.matches(cmd.uppercase()) && byteCount in 1..MAX_BYTES && byteOffset >= 0

    companion object {
        const val MAX_BYTES = 2
        private val CMD_RE = Regex("^[0-9A-F]{4,12}$")
    }
}
