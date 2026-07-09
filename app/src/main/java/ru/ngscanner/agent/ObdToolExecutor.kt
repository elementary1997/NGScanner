package ru.ngscanner.agent

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.ngscanner.llm.ToolCall
import ru.ngscanner.llm.ToolResult
import ru.ngscanner.obd.Elm327
import ru.ngscanner.obd.ObdParser
import ru.ngscanner.obd.ObdPid

/**
 * Исполняет инструменты, запрошенные моделью, обращаясь к адаптеру через [Elm327].
 * Если адаптер не подключён ([elm] == null), инструменты возвращают понятную
 * ошибку — модель тогда попросит пользователя подключиться. Инструмент
 * `clear_dtcs` выполняется только при [allowClearDtcs] == `true` (гейт из UI).
 */
class ObdToolExecutor(
    private val elm: Elm327?,
    private val allowClearDtcs: suspend () -> Boolean = { false },
    private val saveNote: (String) -> Boolean = { false },
    private val describeDtc: suspend (String) -> String? = { null },
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun execute(call: ToolCall): ToolResult {
        // Запись в бортжурнал не требует адаптера — обрабатываем до проверки связи.
        if (call.name == "save_to_logbook") {
            return try {
                ToolResult(call.id, saveToLogbook(call.argumentsJson))
            } catch (ex: Exception) {
                ToolResult(call.id, "Ошибка сохранения записи: ${ex.message}", isError = true)
            }
        }
        val e = elm ?: return ToolResult(
            call.id,
            "Адаптер ELM327 не подключён. Подключитесь на вкладке «Приборы».",
            isError = true,
        )
        return try {
            ToolResult(call.id, runTool(e, call))
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // Отмена диагностики должна распространяться, а не превращаться в tool-error.
            throw ce
        } catch (ex: Exception) {
            ToolResult(call.id, "Ошибка выполнения инструмента: ${ex.message}", isError = true)
        }
    }

    private fun saveToLogbook(argsJson: String): String {
        val note = runCatching {
            json.parseToJsonElement(argsJson).jsonObject["note"]?.jsonPrimitive?.content
        }.getOrNull()
        if (note.isNullOrBlank()) return "Пустая запись — нечего сохранять."
        return if (saveNote(note)) {
            "Записано в бортжурнал."
        } else {
            "Активный автомобиль не выбран — запись не сохранена. Предложи пользователю добавить машину в «Гараж»."
        }
    }

    private suspend fun runTool(elm: Elm327, call: ToolCall): String = when (call.name) {
        "get_connection_status" ->
            "Соединение активно. Протокол: ${elm.command("ATDP").ifBlank { "неизвестен" }}"
        "read_vehicle_info" -> {
            val vinRaw = elm.command("0902")
            val vin = ObdParser.parseVin(vinRaw, elm.headerHexLen())
            val cals = ObdParser.parseCalibrationIds(elm.command("0904"), elm.headerHexLen())
            buildString {
                append(vin?.let { "VIN автомобиля: $it" } ?: "Не удалось прочитать VIN (ответ адаптера: ${vinRaw.trim()}).")
                if (cals.isNotEmpty()) append("\nКалибровки ЭБУ (прошивки): ${cals.joinToString(", ")}")
            }
        }
        "list_supported_pids" -> "Поддерживаемые PID (0100): ${elm.command("0100")}"
        "read_dtcs" -> formatDtcs("Активные коды неисправностей", elm.command("03"), elm.isCan(), elm.headerHexLen(), "43")
        "read_pending_dtcs" -> formatDtcs("Неподтверждённые коды", elm.command("07"), elm.isCan(), elm.headerHexLen(), "47")
        "read_permanent_dtcs" -> formatDtcs("Постоянные коды (не стираются сбросом)", elm.command("0A"), elm.isCan(), elm.headerHexLen(), "4A")
        "read_readiness" -> readReadiness(elm)
        "read_freeze_frame" -> readFreezeFrame(elm)
        "read_live_data" -> readLiveData(elm, call.argumentsJson)
        "monitor_pid" -> monitorPid(elm, call.argumentsJson)
        "clear_dtcs" ->
            if (allowClearDtcs()) {
                elm.command("04")
                "Коды неисправностей сброшены (Mode 04)."
            } else {
                "Отклонено: сброс кодов требует явного подтверждения пользователя в приложении."
            }
        else -> "Неизвестный инструмент: ${call.name}"
    }

    private suspend fun formatDtcs(title: String, raw: String, isCan: Boolean, headerHexLen: Int, respHeader: String): String =
        when (val result = ObdParser.parseDtcs(raw, isCan, headerHexLen, respHeader)) {
            is ObdParser.DtcResult.Ok -> if (result.codes.isEmpty()) {
                "$title: не обнаружены."
            } else {
                val sb = StringBuilder("$title:")
                for (code in result.codes) {
                    val desc = describeDtc(code)
                    sb.append('\n').append(if (desc != null) "• $code — $desc" else "• $code")
                }
                sb.toString()
            }
            ObdParser.DtcResult.NoData ->
                "$title: ЭБУ вернул NO DATA. Возможно, кодов нет или режим не поддерживается — " +
                    "это НЕ гарантия исправности."
            ObdParser.DtcResult.BusError ->
                "$title: НЕТ СВЯЗИ С ЭБУ (ошибка шины). Это не значит «исправно» — проверь " +
                    "зажигание и разъём OBD, затем повтори. Не делай вывод об отсутствии неисправностей."
            is ObdParser.DtcResult.Unknown ->
                "$title: непонятный ответ адаптера: ${result.raw}"
        }

    private suspend fun readLiveData(elm: Elm327, argsJson: String): String {
        val pids = runCatching {
            json.parseToJsonElement(argsJson).jsonObject["pids"]?.jsonArray
                ?.map { it.jsonPrimitive.content }.orEmpty()
        }.getOrDefault(emptyList())
        if (pids.isEmpty()) return "Не указаны PID для чтения."
        val sb = StringBuilder()
        for (name in pids) {
            sb.append(name).append(": ").append(readNamedPid(elm, name) ?: "нет данных").append('\n')
        }
        return sb.toString().trimEnd()
    }

    /** Готовность бортовых мониторов (Mode 01 PID 01) — статус MIL и самотестов систем. */
    private suspend fun readReadiness(elm: Elm327): String {
        val raw = elm.command("0101")
        val upper = raw.uppercase()
        // Ошибку шины отделяем от «не поддержан»: обрыв нельзя принимать за «нет готовности».
        if ("UNABLE TO CONNECT" in upper || "BUS INIT" in upper || "BUSINIT" in upper ||
            "CAN ERROR" in upper || "BUS ERROR" in upper
        ) {
            return "Готовность мониторов: НЕТ СВЯЗИ С ЭБУ (ошибка шины). Проверь зажигание и разъём " +
                "OBD, затем повтори — это не значит «мониторы не готовы»."
        }
        val r = ObdParser.parseReadiness(raw, elm.headerHexLen())
            ?: return "Готовность мониторов: ЭБУ вернул NO DATA или Mode 01 PID 01 не поддерживается."
        val sb = StringBuilder("Готовность мониторов (Mode 01 PID 01):\n")
        sb.append("• Check Engine (MIL): ${if (r.milOn) "ГОРИТ" else "не горит"}\n")
        sb.append("• Подтверждённых кодов: ${r.dtcCount}\n")
        sb.append("• Двигатель: ${if (r.compression) "дизель" else "бензин"}\n")
        if (r.monitors.isEmpty()) {
            sb.append("Поддерживаемые мониторы не обнаружены.")
        } else {
            val ready = r.monitors.count { it.ready }
            sb.append("Мониторы ($ready из ${r.monitors.size} готовы):\n")
            for (m in r.monitors) {
                sb.append(if (m.ready) "  ✓ ${m.name}\n" else "  ✗ ${m.name} — не завершён\n")
            }
        }
        return sb.toString().trimEnd()
    }

    /** Снимок параметров в момент фиксации кода (Mode 02, заголовок ответа «42»). */
    private suspend fun readFreezeFrame(elm: Elm327): String {
        val pids = listOf(
            ObdPid.RPM, ObdPid.COOLANT, ObdPid.SPEED, ObdPid.ENGINE_LOAD,
            ObdPid.THROTTLE, ObdPid.STFT, ObdPid.MAF,
        )
        val sb = StringBuilder()
        for (p in pids) {
            val suffix = p.cmd.removePrefix("01")
            // Mode 02 требует байт номера кадра (00 — первый сохранённый снимок).
            val data = ObdParser.freezeFrameBytes(elm.command("02${suffix}00"), suffix)
            val value = data?.let { runCatching { p.decode(it) }.getOrNull() }
            if (value != null) sb.append("• ${p.label}: ${formatValue(value)} ${p.unit}\n")
        }
        return if (sb.isEmpty()) {
            "Freeze frame недоступен: нет сохранённого снимка (или активных кодов)."
        } else {
            "Параметры в момент фиксации кода (freeze frame):\n${sb.toString().trimEnd()}"
        }
    }

    /** Записывает серию значений одного PID за N секунд и возвращает min/max/avg и разброс. */
    private suspend fun monitorPid(elm: Elm327, argsJson: String): String {
        val obj = runCatching { json.parseToJsonElement(argsJson).jsonObject }.getOrNull()
        val pidName = obj?.get("pid")?.jsonPrimitive?.content ?: return "Не указан PID."
        val duration = obj["duration_sec"]?.jsonPrimitive?.content?.toIntOrNull()?.coerceIn(1, 60) ?: 5
        val pid = matchPid(pidName) ?: return "Неизвестный PID: $pidName"

        val samples = mutableListOf<Double>()
        val iterations = (duration * 1000 / SAMPLE_INTERVAL_MS).toInt().coerceIn(3, 120)
        repeat(iterations) {
            elm.read(pid)?.let { samples.add(it) }
            delay(SAMPLE_INTERVAL_MS)
        }
        if (samples.isEmpty()) return "$pidName: нет данных за ${duration} с."

        val min = samples.min()
        val max = samples.max()
        val avg = samples.average()
        val swing = max - min
        val threshold = if (pid == ObdPid.RPM) 150.0 else maxOf(2.0, kotlin.math.abs(avg) * 0.1)
        val note = if (swing > threshold) " — заметное плавание/нестабильность" else " — стабильно"
        return "$pidName за ${duration} с (${samples.size} замеров): среднее ${formatValue(avg)}, " +
            "мин ${formatValue(min)}, макс ${formatValue(max)}, разброс ${formatValue(swing)} ${pid.unit}$note"
    }

    private suspend fun readNamedPid(elm: Elm327, name: String): String? {
        val pid = matchPid(name) ?: return null
        val value = elm.read(pid) ?: return null
        return "${formatValue(value)} ${pid.unit}"
    }

    private fun matchPid(name: String): ObdPid? {
        val u = name.uppercase()
        return ObdPid.entries.firstOrNull { it.name == u }
            ?: when (u) {
                "ENGINE_RPM" -> ObdPid.RPM
                "VEHICLE_SPEED" -> ObdPid.SPEED
                "COOLANT_TEMP" -> ObdPid.COOLANT
                else -> null
            }
    }

    private fun formatValue(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)

    private companion object {
        const val SAMPLE_INTERVAL_MS = 400L
    }
}
