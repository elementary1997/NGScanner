package ru.ngscanner.obd

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.ngscanner.transport.ObdTransport

/**
 * Драйвер ELM327 поверх произвольного [ObdTransport].
 * Инициализация адаптера и отправка OBD-II запросов.
 *
 * ELM327 — half-duplex: один запрос-ответ за раз. Все обращения к транспорту
 * сериализованы [mutex], иначе кадры двух параллельных команд (опрос приборов
 * и инструменты агента) смешиваются и парсер получает мусор.
 */
class Elm327(private val transport: ObdTransport) {

    private val mutex = Mutex()

    /** Последовательность инициализации адаптера перед работой. */
    suspend fun initialize() = mutex.withLock {
        for (cmd in INIT_SEQUENCE) {
            transport.write(cmd)
            transport.readResponse()
            delay(120)
        }
    }

    /** Отправить сырую команду (AT или PID) и вернуть ответ адаптера. */
    suspend fun command(raw: String): String = mutex.withLock {
        transport.write(raw)
        transport.readResponse()
    }

    /** Прочитать и декодировать параметр Mode 01; `null`, если ответ не распознан. */
    suspend fun read(pid: ObdPid): Double? {
        val data = ObdParser.dataBytes(command(pid.cmd), pid.cmd) ?: return null
        return runCatching { pid.decode(data) }.getOrNull()
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
