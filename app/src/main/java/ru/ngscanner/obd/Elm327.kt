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

    /** Кэш номера протокола ELM327 (ATDPN); сбрасывается на новом соединении. */
    private var protocolNum: Int? = null

    /**
     * Номер текущего протокола ELM327 по `ATDPN` (при автоопределении — с префиксом
     * «A», напр. «A6»). Кэшируется; `null`, пока протокол не определён. Вызывать
     * после первого OBD-запроса — тогда автоопределение уже завершилось.
     */
    private suspend fun protocolNumber(): Int? {
        protocolNum?.let { return it }
        val n = command("ATDPN").trim().takeLast(1).toIntOrNull(16)
        if (n == null || n == 0) return null // ещё не определён — не кэшируем
        protocolNum = n
        return n
    }

    /**
     * Является ли текущий протокол CAN (ISO 15765-4). Нужно парсеру DTC: в CAN
     * после `43` идёт байт-счётчик кодов, в легаси (ISO 9141/KWP/J1850) счётчика
     * нет, а по самим байтам форматы неотличимы.
     */
    suspend fun isCan(): Boolean = (protocolNumber() ?: 0) >= 6

    /**
     * Длина заголовка CAN-ID в hex-символах при включённых заголовках (ATH1).
     * Нужна для группировки ответов по ЭБУ (иначе кадры разных модулей сливаются
     * и рождают фантомные коды). 11-bit CAN → 3 (7Ex), 29-bit → 8 (18DAF1xx),
     * легаси/неизвестно → 0 (группировка не применяется).
     */
    suspend fun headerHexLen(): Int = when (protocolNumber()) {
        6, 8 -> 3
        7, 9 -> 8
        else -> 0
    }

    /** Человекочитаемое имя протокола ELM327 по номеру; `null`, если ещё не определён. */
    suspend fun protocolName(): String? = when (protocolNumber()) {
        1 -> "SAE J1850 PWM"
        2 -> "SAE J1850 VPW"
        3 -> "ISO 9141-2"
        4 -> "ISO 14230 KWP (5 baud)"
        5 -> "ISO 14230 KWP (fast)"
        6 -> "CAN 11-bit, 500 кбит"
        7 -> "CAN 29-bit, 500 кбит"
        8 -> "CAN 11-bit, 250 кбит"
        9 -> "CAN 29-bit, 250 кбит"
        else -> null
    }

    /**
     * Перезапускает автоопределение протокола (`ATSP0`) и триггерит автопоиск пробным
     * запросом. Нужно, когда ELM327-клон «залип» на неудачном автопоиске и упорно
     * отдаёт NO DATA, хотя ЭБУ на связи. Сбрасывает кэш номера протокола.
     */
    suspend fun resetProtocol() = mutex.withLock {
        protocolNum = null
        transport.write("ATSP0")
        transport.readResponse()
        delay(60)
        transport.write("0100") // любой OBD-запрос запускает автопоиск заново
        transport.readResponse()
    }

    /**
     * Последовательность инициализации адаптера перед работой.
     *
     * Ответы больше не игнорируются: если адаптер вовсе не отвечает — это мёртвый
     * или поддельный клон, и молча «успешная» инициализация обернулась бы неверной
     * диагностикой. Отдельно ловим отказ от ATH1 (клон не показывает заголовки CAN):
     * без заголовков кадры разных ЭБУ сливаются в фантомные коды — честнее сообщить.
     */
    suspend fun initialize() = mutex.withLock {
        protocolNum = null
        var sawResponse = false
        var athRejected = false
        for (cmd in INIT_SEQUENCE) {
            transport.write(cmd)
            val resp = transport.readResponse().uppercase()
            if (resp.isNotBlank()) sawResponse = true
            // '?' — команда не распознана адаптером. Критично именно для ATH1.
            if (cmd == "ATH1" && resp.contains('?')) athRejected = true
            delay(120)
        }
        // Проба живости: настоящий ELM327 на ATI возвращает строку с «ELM».
        transport.write("ATI")
        val id = transport.readResponse()
        if (!sawResponse && !id.uppercase().contains("ELM")) {
            throw ru.ngscanner.transport.ObdTransportException(
                "Адаптер не отвечает — проверьте питание OBD-разъёма и соединение.",
            )
        }
        if (athRejected) {
            throw ru.ngscanner.transport.ObdTransportException(
                "Адаптер не поддерживает показ заголовков (ATH1) — коды с такого клона недостоверны.",
            )
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
     * Прочитать и декодировать заводской PID пользователя (мода 21/22 и т.п.).
     * `null`, если ЭБУ не ответил на команду или ответ короче, чем ждёт [pid].
     */
    suspend fun readCustom(pid: CustomPid): Double? {
        val data = ObdParser.dataBytesFor(command(pid.cmd), pid.cmd) ?: return null
        return runCatching { pid.decode(data) }.getOrNull()
    }

    /**
     * Набор команд PID Mode 01, которые поддерживает данный автомобиль
     * (по маскам 0100/0120/0140). Пустой набор — не удалось определить.
     */
    suspend fun readSupportedPids(): Set<String> {
        val supported = mutableSetOf<String>()
        for (base in listOf(0x00, 0x20, 0x40)) {
            // Порядок важен: сначала запрос (первый 0100 запускает автоопределение
            // протокола), затем headerHexLen() — тогда протокол уже определён и маски
            // разных ЭБУ группируются по заголовку и объединяются.
            val raw = command("01" + "%02X".format(base))
            val nums = ObdParser.supportedPids(raw, base, headerHexLen())
            nums.forEach { supported.add("01" + "%02X".format(it)) }
        }
        return supported
    }

    /**
     * Напряжение на разъёме OBD по команде адаптера `ATRV` (это бортовое напряжение).
     * Надёжный источник, когда ЭБУ не поддерживает Mode 01 PID 42 (частый случай на
     * старых/российских ЭБУ). Ответ вида «12.3V» → 12.3; `null`, если не распознан.
     */
    suspend fun readAdapterVoltage(): Double? {
        val raw = command("ATRV")
        return Regex("([0-9]+\\.?[0-9]*)").find(raw)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
    }

    /** Обороты двигателя (Mode 01, PID 0C). `null`, если ответ не распознан. */
    suspend fun readEngineRpm(): Int? = ObdParser.parseRpm(command("010C"))

    /** Температура охлаждающей жидкости (Mode 01, PID 05), °C. */
    suspend fun readCoolantTemp(): Int? = ObdParser.parseCoolantTemp(command("0105"))

    companion object {
        /** ATZ — сброс; ATE0 — эхо выкл; ATL0 — переводы строк выкл; ATS0 — пробелы
         *  выкл; ATH1 — показывать заголовки (CAN-ID), чтобы различать ЭБУ в ответах;
         *  ATSP0 — автоопределение протокола. */
        val INIT_SEQUENCE = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH1", "ATSP0")
    }
}
