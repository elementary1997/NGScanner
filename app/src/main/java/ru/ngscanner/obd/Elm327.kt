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

    /** Кэш признака CAN-протокола (см. [isCan]); сбрасывается на новом соединении. */
    private var canProtocol: Boolean? = null

    /**
     * Является ли текущий протокол CAN (ISO 15765-4). Нужно парсеру DTC: в CAN
     * после `43` идёт байт-счётчик кодов, в легаси-протоколах (ISO 9141/KWP/J1850)
     * счётчика нет, а по самим байтам форматы неотличимы. Определяем по `ATDPN`
     * (номер протокола; при автоопределении — с префиксом «A», напр. «A6») и
     * кэшируем. Вызывать после первого OBD-запроса — тогда протокол уже выбран.
     */
    suspend fun isCan(): Boolean {
        canProtocol?.let { return it }
        val n = command("ATDPN").trim().takeLast(1).toIntOrNull(16)
        if (n == null || n == 0) return false // протокол ещё не определён — не кэшируем
        val result = n >= 6 // 6..9, A — CAN; 1..5 — легаси
        canProtocol = result
        return result
    }

    /** Последовательность инициализации адаптера перед работой. */
    suspend fun initialize() = mutex.withLock {
        canProtocol = null
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

    /**
     * Набор команд PID Mode 01, которые поддерживает данный автомобиль
     * (по маскам 0100/0120/0140). Пустой набор — не удалось определить.
     */
    suspend fun readSupportedPids(): Set<String> {
        val supported = mutableSetOf<String>()
        for (base in listOf(0x00, 0x20, 0x40)) {
            val nums = ObdParser.supportedPids(command("01" + "%02X".format(base)), base)
            nums.forEach { supported.add("01" + "%02X".format(it)) }
        }
        return supported
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
