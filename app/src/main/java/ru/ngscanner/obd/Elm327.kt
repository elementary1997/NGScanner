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
class Elm327(
    private val transport: ObdTransport,
    /**
     * Желаемый протокол шины. [ObdProtocol.AUTO] — автоопределение адаптером (`ATSP0`),
     * иначе протокол задаётся жёстко: на K-line и «залипающих» клонах это единственный
     * способ подключиться. Меняется на лету через [setProtocol].
     */
    @Volatile var protocol: ObdProtocol = ObdProtocol.AUTO,
) {

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

    /** Определившийся протокол (по `ATDPN`); `null`, пока не определён. */
    suspend fun activeProtocol(): ObdProtocol? = ObdProtocol.fromNumber(protocolNumber())

    /**
     * Является ли текущий протокол CAN (ISO 15765-4 / J1939). Нужно парсеру DTC: в CAN
     * после `43` идёт байт-счётчик кодов, в легаси (ISO 9141/KWP/J1850) счётчика
     * нет, а по самим байтам форматы неотличимы.
     */
    suspend fun isCan(): Boolean = activeProtocol()?.isCan == true

    /**
     * Длина заголовка CAN-ID в hex-символах при включённых заголовках (ATH1).
     * Нужна для группировки ответов по ЭБУ (иначе кадры разных модулей сливаются
     * и рождают фантомные коды). Легаси/неизвестно → 0 (группировка не применяется).
     */
    suspend fun headerHexLen(): Int = activeProtocol()?.headerHexLen ?: 0

    /** Человекочитаемое имя протокола; `null`, если ещё не определён. */
    suspend fun protocolName(): String? = activeProtocol()?.label

    /**
     * Перезапускает связь на текущем желаемом протоколе ([protocol]) и триггерит
     * подключение пробным запросом. Нужно, когда ELM327-клон «залип» на неудачном
     * автопоиске и упорно отдаёт NO DATA, хотя ЭБУ на связи. Сбрасывает кэш номера.
     */
    suspend fun resetProtocol() = mutex.withLock {
        protocolNum = null
        transport.write("ATSP${protocol.code}")
        transport.readResponse()
        delay(60)
        transport.write("0100") // любой OBD-запрос запускает подключение заново
        transport.readResponse()
    }

    /**
     * Переключает протокол на лету (без переподключения адаптера) и проверяет, отвечает
     * ли ЭБУ. `true` — на этом протоколе связь есть.
     */
    suspend fun setProtocol(p: ObdProtocol): Boolean {
        protocol = p
        mutex.withLock {
            protocolNum = null
            transport.write("ATSP${p.code}")
            transport.readResponse()
            delay(60)
        }
        return probe()
    }

    /**
     * Перебирает протоколы и возвращает те, на которых ЭБУ реально ответил. Это ответ на
     * вопрос «почему не подключается»: автопоиск адаптера — чёрный ящик, а здесь видно,
     * что именно пробовали и что откликнулось. По окончании возвращает адаптер на
     * [protocol] (желаемый), чтобы зонд не оставил связь в чужом состоянии.
     *
     * @param onStep вызывается перед каждой пробой — для показа прогресса в UI
     */
    suspend fun probeProtocols(onStep: (ObdProtocol) -> Unit = {}): List<ObdProtocol> {
        val found = mutableListOf<ObdProtocol>()
        for (p in ObdProtocol.PROBE_ORDER) {
            onStep(p)
            mutex.withLock {
                protocolNum = null
                transport.write("ATSP${p.code}")
                transport.readResponse()
                delay(60)
            }
            if (probe()) found.add(p)
        }
        // Возвращаем адаптер к желаемому протоколу — иначе останется последний пробный.
        mutex.withLock {
            protocolNum = null
            transport.write("ATSP${protocol.code}")
            transport.readResponse()
        }
        return found
    }

    /** Отвечает ли ЭБУ прямо сейчас: любой валидный ответ на 0100 (маска PID). */
    private suspend fun probe(): Boolean {
        val raw = command("0100").uppercase()
        return raw.contains("41 00") || raw.replace(" ", "").contains("4100")
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
        // Протокол ставится последней командой: AUTO → ATSP0, иначе жёстко выбранный.
        for (cmd in INIT_SEQUENCE + "ATSP${protocol.code}") {
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
        // Протокол (ATSP) добавляется в initialize() из выбранного пользователем.
        val INIT_SEQUENCE = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH1")
    }
}
