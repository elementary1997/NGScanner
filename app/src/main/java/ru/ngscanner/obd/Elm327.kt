package ru.ngscanner.obd

import kotlinx.coroutines.delay
import ru.ngscanner.transport.ObdTransport

/**
 * Драйвер ELM327 поверх произвольного [ObdTransport].
 * Инициализация адаптера и отправка OBD-II запросов.
 */
class Elm327(private val transport: ObdTransport) {

    /** Последовательность инициализации адаптера перед работой. */
    suspend fun initialize() {
        for (cmd in INIT_SEQUENCE) {
            transport.write(cmd)
            transport.readResponse()
            delay(120)
        }
    }

    /** Отправить сырую команду (AT или PID) и вернуть ответ адаптера. */
    suspend fun command(raw: String): String {
        transport.write(raw)
        return transport.readResponse()
    }

    /** Обороты двигателя (Mode 01, PID 0C). `null`, если ответ не распознан. */
    suspend fun readEngineRpm(): Int? = ObdParser.parseRpm(command("010C"))

    /** Температура охлаждающей жидкости (Mode 01, PID 05), °C. */
    suspend fun readCoolantTemp(): Int? = ObdParser.parseCoolantTemp(command("0105"))

    companion object {
        /** ATZ — сброс; ATE0 — эхо выкл; ATL0 — переводы строк выкл;
         *  ATS0 — пробелы выкл; ATSP0 — автоопределение протокола. */
        val INIT_SEQUENCE = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATSP0")
    }
}
