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
     * Коды неисправностей из ответа Mode 03/07/0A (`43`/`47`/`4A` + пары байт).
     * Каждый код: 2 старших бита — система (P/C/B/U), далее — цифры.
     *
     * [isCan] — протокол ЭБУ: в CAN (ISO 15765-4) после заголовка идёт
     * байт-счётчик кодов, в легаси (ISO 9141/KWP/J1850) счётчика нет. Формат
     * нельзя надёжно угадать по самим байтам (легаси-код P01xx неотличим от
     * CAN-счётчика 01), поэтому протокол передаётся снаружи (см. Elm327.isCan).
     */
    fun parseDtcs(raw: String, isCan: Boolean = true, headerHexLen: Int = 0, respHeader: String = "43"): DtcResult {
        val upper = raw.uppercase()
        when {
            "UNABLE TO CONNECT" in upper || "BUS INIT" in upper || "BUSINIT" in upper ||
                "CAN ERROR" in upper || "BUS ERROR" in upper -> return DtcResult.BusError
            "NO DATA" in upper || "NODATA" in upper -> return DtcResult.NoData
        }
        // Каждый ответ ЭБУ разбираем отдельно. С заголовками (ATH1) кадры группируются
        // по CAN-ID — единственный надёжный способ не слить ответы разных модулей.
        val messages = IsoTp.messages(raw, headerHexLen)
        if (messages.isEmpty()) {
            return if (normalize(raw).isBlank()) DtcResult.NoData else DtcResult.Unknown(raw.trim())
        }
        val codes = mutableListOf<String>()
        var sawHeader = false
        for (msg in messages) {
            // Ищем ИМЕННО ожидаемый заголовок ответа (43 Mode03 / 47 Mode07 / 4A Mode0A),
            // а не «любой из трёх»: без ATH1 байт 4A/47/43 в данных на чётном смещении мог
            // бы ложно выиграть и породить фантомный код.
            val start = msg.indexOf(respHeader)
            if (start < 0) continue
            sawHeader = true
            codes += parseDtcPayload(msg.substring(start + 2), isCan)
        }
        if (!sawHeader) {
            // Положительного заголовка (43/47/4A) нет. Проверяем отрицательный ответ
            // ЭБУ «7F <sid> <nrc>» — здесь это безопасно: раз 43 не найден, «7F03» не
            // может быть байтами валидного кода. Матчим строго по коду режима.
            negativeResponse(normalize(raw), respHeader)?.let { return DtcResult.Unknown(it) }
            return if (normalize(raw).isBlank()) DtcResult.NoData else DtcResult.Unknown(raw.trim())
        }
        return DtcResult.Ok(codes.distinct())
    }

    /**
     * Расшифровка отрицательного ответа ЭБУ «7F <sid> <nrc>» для данного режима
     * (03→7F03, 07→7F07, 0A→7F0A). `null`, если это не негативный ответ на режим.
     */
    private fun negativeResponse(hex: String, respHeader: String): String? {
        val reqMode = when (respHeader) { "43" -> "03"; "47" -> "07"; "4A" -> "0A"; else -> return null }
        val m = Regex("7F$reqMode([0-9A-F]{2})").find(hex) ?: return null
        val text = when (m.groupValues[1]) {
            "11" -> "режим не поддерживается ЭБУ"
            "12" -> "подфункция не поддерживается"
            "22" -> "условия запроса не выполнены"
            "31" -> "запрос вне диапазона"
            "78" -> "ЭБУ занят (ответ отложен)"
            else -> "отказ ЭБУ (NRC 0x${m.groupValues[1]})"
        }
        return "Отрицательный ответ ЭБУ: $text"
    }

    private fun parseDtcPayload(payload: String, isCan: Boolean): List<String> =
        if (isCan) parseCanDtcs(payload) else parseLegacyDtcs(payload)

    /**
     * CAN (ISO 15765-4): после `43` идёт байт-счётчик числа кодов, далее пары DTC
     * и, возможно, хвост-заполнитель `00`. Берём ровно столько кодов, сколько
     * указал счётчик — заполнитель отсекается по счётчику, без догадок о длине.
     */
    private fun parseCanDtcs(payload: String): List<String> {
        if (payload.length < 2) return emptyList()
        val count = payload.substring(0, 2).toIntOrNull(16) ?: return emptyList()
        val body = payload.drop(2)
        val codes = mutableListOf<String>()
        var i = 0
        while (i + 4 <= body.length && codes.size < count) {
            val word = body.substring(i, i + 4)
            i += 4
            if (word == "0000") continue // заполнитель
            runCatching { decodeDtc(word) }.getOrNull()?.let { codes.add(it) }
        }
        return codes
    }

    /** Легаси (ISO 9141/KWP/J1850): счётчика нет — только пары DTC. */
    private fun parseLegacyDtcs(payload: String): List<String> {
        val codes = mutableListOf<String>()
        var i = 0
        while (i + 4 <= payload.length) {
            val word = payload.substring(i, i + 4)
            i += 4
            if (word == "0000") continue // заполнитель / пустой слот
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
     * VIN из ответа Mode 09 PID 02 (`49 02 …`). Ответ всегда мультифреймовый
     * (17 символов + служебные байты > 7): сначала собираем ISO-TP в единый
     * поток, затем после `4902` пропускаем байт-счётчик и читаем ASCII-байты,
     * оставляя 17 буквенно-цифровых символов.
     */
    fun parseVin(raw: String, headerHexLen: Int = 0): String? {
        // VIN отвечает один ЭБУ — берём сообщение, содержащее заголовок 4902.
        val hex = IsoTp.messages(raw, headerHexLen).firstOrNull { it.contains("4902") } ?: normalize(raw)
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
        // OBD-II VIN — ровно 17 символов. Усечённый (битый мультифрейм) не отдаём:
        // иначе он молча ушёл бы в сетевой decode и мог сохраниться как невалидный.
        return vin.takeIf { it.length >= 17 }?.take(17)
    }

    /** Один бортовой монитор готовности: поддерживается ли и завершён ли самотест. */
    data class Monitor(val name: String, val ready: Boolean)

    /**
     * Готовность бортовых мониторов (Mode 01 PID 01). [milOn] — горит ли Check Engine,
     * [dtcCount] — число подтверждённых emission-кодов, [compression] — дизель (иначе бензин),
     * [monitors] — только поддерживаемые данным авто мониторы.
     */
    data class Readiness(
        val milOn: Boolean,
        val dtcCount: Int,
        val compression: Boolean,
        val monitors: List<Monitor>,
    )

    // Наборы непрерывных и разовых мониторов (порядок бит 0..7 в байтах C/D).
    // Выверено по python-OBD (obd/codes.py) — см. sae J1979 / ISO 15031-5.
    private val CONTINUOUS = listOf("Пропуски воспламенения", "Топливная система", "Компоненты")
    private val SPARK_MONITORS = listOf(
        "Катализатор", "Подогрев катализатора", "Система EVAP", "Вторичный воздух",
        "Хладагент A/C", "Датчик кислорода", "Подогрев датчика O₂", "EGR/VVT",
    )
    private val COMPRESSION_MONITORS = listOf(
        "NMHC-катализатор", "NOx/SCR", null, "Наддув",
        null, "Датчик ОГ", "Сажевый фильтр", "EGR/VVT",
    )

    /** Сырой статус монитора одного ЭБУ до агрегации. */
    private data class RawMonitor(val name: String, val supported: Boolean, val ready: Boolean)

    /**
     * Статус готовности мониторов из Mode 01 PID 01 (`41 01 A B C D`).
     * A7 — MIL, A6..A0 — число кодов; B3 — тип зажигания (0 бензин / 1 дизель);
     * непрерывные мониторы в B (supported B0..B2, complete B4..B6, где бит=0 — готов),
     * разовые — байт C (supported=1) и D (complete: бит=0 — готов).
     *
     * На CAN отвечать могут несколько ЭБУ (каждый своим `4101`) — агрегируем по всем:
     * MIL и «поддерживается» по ИЛИ, число кодов суммируем, а монитор считаем готовым,
     * только когда его завершили ВСЕ поддерживающие модули (И). Читать первый ЭБУ
     * опасно — недооценили бы готовность вплоть до ложного «всё готово».
     */
    fun parseReadiness(raw: String, headerHexLen: Int = 0): Readiness? {
        val messages = IsoTp.messages(raw, headerHexLen).filter { it.contains("4101") }
            .ifEmpty { normalize(raw).takeIf { it.contains("4101") }?.let { listOf(it) } ?: emptyList() }
        if (messages.isEmpty()) return null

        var milOn = false
        var dtcCount = 0
        var compression = false
        var typeKnown = false
        // Порядок мониторов сохраняем; supported = ИЛИ, ready = И по поддерживающим ЭБУ.
        val supported = LinkedHashMap<String, Boolean>()
        val ready = HashMap<String, Boolean>()
        for (msg in messages) {
            val bytes = readinessBytes(msg) ?: continue
            val (a, b, c, d) = listOf(bytes[0], bytes[1], bytes[2], bytes[3])
            milOn = milOn || (a and 0x80) != 0
            dtcCount += a and 0x7F
            val comp = (b shr 3) and 1 == 1
            if (!typeKnown) { compression = comp; typeKnown = true }
            for (m in decodeMonitors(b, c, d, comp)) {
                supported[m.name] = (supported[m.name] ?: false) || m.supported
                if (m.supported) ready[m.name] = (ready[m.name] ?: true) && m.ready
            }
        }
        if (!typeKnown) return null
        val monitors = supported.filterValues { it }.keys.map { Monitor(it, ready[it] ?: false) }
        return Readiness(milOn = milOn, dtcCount = dtcCount, compression = compression, monitors = monitors)
    }

    /** Байты A,B,C,D из сообщения одного ЭБУ (после заголовка `4101`). */
    private fun readinessBytes(msg: String): IntArray? {
        val idx = msg.indexOf("4101")
        if (idx < 0) return null
        val after = msg.substring(idx + 4)
        if (after.length < 8) return null
        return runCatching { after.take(8).chunked(2).map { it.toInt(16) }.toIntArray() }.getOrNull()
    }

    /** Мониторы одного ЭБУ: непрерывные (байт B) и разовые (C supported / D готовность). */
    private fun decodeMonitors(b: Int, c: Int, d: Int, compression: Boolean): List<RawMonitor> {
        val list = ArrayList<RawMonitor>(11)
        for (i in 0..2) {
            list.add(RawMonitor(CONTINUOUS[i], supported = (b shr i) and 1 == 1, ready = (b shr (i + 4)) and 1 == 0))
        }
        val names = if (compression) COMPRESSION_MONITORS else SPARK_MONITORS
        for (i in 0..7) {
            val name = names[i] ?: continue
            list.add(RawMonitor(name, supported = (c shr i) and 1 == 1, ready = (d shr i) and 1 == 0))
        }
        return list
    }

    /**
     * Номера калибровок ЭБУ (прошивок) из Mode 09 PID 04 (`49 04 NODI + NODI×16 байт ASCII`).
     * После заголовка `4904` идёт байт-счётчик числа калибровок, затем блоки строго по
     * 16 байт (правый паддинг `00`). Отвечать может несколько ЭБУ — берём первый.
     */
    fun parseCalibrationIds(raw: String, headerHexLen: Int = 0): List<String> {
        val hex = IsoTp.messages(raw, headerHexLen).firstOrNull { it.contains("4904") } ?: normalize(raw)
        val idx = hex.indexOf("4904")
        if (idx < 0) return emptyList()
        val after = hex.substring(idx + 4)
        if (after.length < 2) return emptyList()
        val count = after.substring(0, 2).toIntOrNull(16) ?: return emptyList()
        val body = after.drop(2)
        // Число блоков берём по счётчику; при неадекватном счётчике — по длине тела.
        val blocks = if (count in 1..16) count else body.length / 32
        val result = mutableListOf<String>()
        var i = 0
        repeat(blocks) {
            if (i + 32 > body.length) return@repeat
            val ascii = body.substring(i, i + 32).chunked(2)
                .mapNotNull { runCatching { it.toInt(16) }.getOrNull() }
                .filter { it in 0x20..0x7E } // печатаемые; хвостовой паддинг 00 отсекается
                .map { it.toChar() }.joinToString("").trim()
            i += 32
            if (ascii.isNotBlank()) result.add(ascii)
        }
        return result
    }

    /**
     * Номера поддерживаемых PID из ответа на маску 0100/0120/0140.
     * Ответ — 4 байта (32 бита): старший бит соответствует `base + 1`.
     * Например для 0100 бит 31 → PID 0x01, бит 0 → PID 0x20.
     *
     * На CAN на маску могут ответить несколько ЭБУ (двигатель `7E8`, АКПП `7E9`…),
     * каждый своей битовой маской. Объединяем биты по ИЛИ по всем ЭБУ — иначе PID-ы,
     * поддерживаемые только вторичным модулем, потерялись бы и их приборы скрылись
     * с дашборда. Для live-значений хватает первого ЭБУ, но маску поддержки надо
     * агрегировать (см. также parseReadiness).
     */
    fun supportedPids(raw: String, base: Int, headerHexLen: Int = 0): List<Int> {
        val prefix = "41" + "%02X".format(base)
        val messages = IsoTp.messages(raw, headerHexLen).filter { it.contains(prefix) }
            .ifEmpty { normalize(raw).takeIf { it.contains(prefix) }?.let { listOf(it) } ?: emptyList() }
        var bits = 0L
        for (msg in messages) {
            val idx = msg.indexOf(prefix)
            if (idx < 0) continue
            val bytes = msg.substring(idx + prefix.length).chunked(2)
                .filter { it.length == 2 }
                .mapNotNull { runCatching { it.toInt(16) }.getOrNull() }
            if (bytes.size < 4) continue
            bits = bits or ((bytes[0].toLong() shl 24) or (bytes[1].toLong() shl 16) or
                (bytes[2].toLong() shl 8) or bytes[3].toLong())
        }
        if (bits == 0L) return emptyList()
        val result = mutableListOf<Int>()
        for (i in 0 until 32) {
            if ((bits shr (31 - i)) and 1L == 1L) result.add(base + i + 1)
        }
        return result
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

    /**
     * Байты данных из ответа Mode 02 (freeze frame) — заголовок «42» + суффикс PID.
     * Формат ответа: `42 PID FRAME# data…`, поэтому после «42{PID}» пропускаем
     * байт номера кадра (frame#), иначе значение читается со сдвигом на байт.
     * Например для 020C ищем «420C», отбрасываем номер кадра и берём данные.
     */
    fun freezeFrameBytes(raw: String, pidSuffix: String): IntArray? {
        val hex = normalize(raw)
        val prefix = "42" + pidSuffix.uppercase()
        val idx = hex.indexOf(prefix)
        if (idx < 0) return null
        val dataHex = hex.substring(idx + prefix.length).drop(2)
        if (dataHex.length < 2) return null
        return dataHex.chunked(2).filter { it.length == 2 }.map { it.toInt(16) }.toIntArray()
    }

    /**
     * Код, к которому привязан сохранённый снимок (Mode 02 PID 02): ответ `42 02
     * FRAME# DTChi DTClo`. Пропускаем байт номера кадра, декодируем пару байт DTC.
     * `null` — снимка/кода нет («0000») или ответ не распознан.
     */
    fun freezeFrameDtc(raw: String): String? {
        val hex = normalize(raw)
        val idx = hex.indexOf("4202")
        if (idx < 0) return null
        val after = hex.substring(idx + 4).drop(2) // пропускаем байт номера кадра
        if (after.length < 4) return null
        val word = after.substring(0, 4)
        if (word == "0000") return null
        return runCatching { decodeDtc(word) }.getOrNull()
    }

    private fun normalize(raw: String): String =
        raw.uppercase().replace(Regex("[^0-9A-F]"), "")
}
