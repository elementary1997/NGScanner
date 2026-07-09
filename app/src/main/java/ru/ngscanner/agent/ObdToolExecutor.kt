package ru.ngscanner.agent

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
    private val allowClearDtcs: () -> Boolean = { false },
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun execute(call: ToolCall): ToolResult {
        val e = elm ?: return ToolResult(
            call.id,
            "Адаптер ELM327 не подключён. Подключитесь на вкладке «Приборы».",
            isError = true,
        )
        return try {
            ToolResult(call.id, runTool(e, call))
        } catch (ex: Exception) {
            ToolResult(call.id, "Ошибка выполнения инструмента: ${ex.message}", isError = true)
        }
    }

    private suspend fun runTool(elm: Elm327, call: ToolCall): String = when (call.name) {
        "get_connection_status" ->
            "Соединение активно. Протокол: ${elm.command("ATDP").ifBlank { "неизвестен" }}"
        "read_vehicle_info" -> "Ответ VIN (0902): ${elm.command("0902")}"
        "list_supported_pids" -> "Поддерживаемые PID (0100): ${elm.command("0100")}"
        "read_dtcs" -> formatDtcs("Активные коды неисправностей", elm.command("03"))
        "read_pending_dtcs" -> formatDtcs("Неподтверждённые коды", elm.command("07"))
        "read_freeze_frame" -> "Freeze frame (0202): ${elm.command("0202")}"
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

    private fun formatDtcs(title: String, raw: String): String {
        val codes = ObdParser.parseDtcs(raw)
        return if (codes.isEmpty()) "$title: не обнаружены." else "$title: ${codes.joinToString(", ")}"
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

    private suspend fun monitorPid(elm: Elm327, argsJson: String): String {
        val pid = runCatching {
            json.parseToJsonElement(argsJson).jsonObject["pid"]?.jsonPrimitive?.content
        }.getOrNull() ?: return "Не указан PID."
        return "$pid: ${readNamedPid(elm, pid) ?: "нет данных"} (запись серии значений — в разработке)"
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
}
