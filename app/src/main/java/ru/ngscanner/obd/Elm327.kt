package ru.ngscanner.obd

import ru.ngscanner.transport.ObdTransport

/**
 * Драйвер ELM327 поверх произвольного [ObdTransport].
 * Отвечает за инициализацию адаптера и отправку OBD-II запросов
 * (AT-команды конфигурации + PID-запросы к ЭБУ).
 */
class Elm327(private val transport: ObdTransport) {

    /** Последовательность инициализации адаптера перед работой. */
    suspend fun initialize() {
        // TODO(этап 1): прогнать INIT_SEQUENCE, проверить ответы, определить протокол.
        TODO("Этап 1: инициализация ELM327")
    }

    /** Отправить сырую команду (AT или PID) и вернуть ответ адаптера. */
    suspend fun command(raw: String): String {
        transport.write(raw)
        return transport.readResponse()
    }

    companion object {
        /** ATZ — сброс; ATE0 — эхо выкл; ATL0 — перевод строк выкл; ATS0 — пробелы выкл;
         *  ATSP0 — автоопределение протокола. */
        val INIT_SEQUENCE = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATSP0")
    }
}
