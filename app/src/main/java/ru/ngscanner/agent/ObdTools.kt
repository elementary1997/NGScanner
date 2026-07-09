package ru.ngscanner.agent

import ru.ngscanner.llm.ToolSpec

/**
 * Каталог инструментов, которые LLM вызывает для доступа к автомобилю —
 * это «глаза» модели на машину. Формат нейтральный ([ToolSpec]); конкретный
 * провайдер конвертирует его под свой API function calling.
 */
object ObdTools {

    private const val NO_ARGS = """{"type":"object","properties":{},"additionalProperties":false}"""

    val all: List<ToolSpec> = listOf(
        ToolSpec(
            name = "get_connection_status",
            description = "Статус связи с адаптером и выбранный протокол OBD-II.",
            parametersJsonSchema = NO_ARGS,
        ),
        ToolSpec(
            name = "read_vehicle_info",
            description = "VIN и калибровки ЭБУ (OBD-II Mode 09).",
            parametersJsonSchema = NO_ARGS,
        ),
        ToolSpec(
            name = "list_supported_pids",
            description = "Список параметров (PID), которые поддерживает данный автомобиль.",
            parametersJsonSchema = NO_ARGS,
        ),
        ToolSpec(
            name = "read_dtcs",
            description = "Активные диагностические коды неисправностей (Mode 03) с расшифровкой.",
            parametersJsonSchema = NO_ARGS,
        ),
        ToolSpec(
            name = "read_pending_dtcs",
            description = "Неподтверждённые (pending) коды неисправностей (Mode 07).",
            parametersJsonSchema = NO_ARGS,
        ),
        ToolSpec(
            name = "read_freeze_frame",
            description = "Снимок параметров в момент возникновения кода неисправности (Mode 02).",
            parametersJsonSchema = NO_ARGS,
        ),
        ToolSpec(
            name = "read_live_data",
            description = "Текущие значения параметров двигателя (Mode 01): обороты, скорость, " +
                "температура ОЖ, нагрузка, топливные коррекции и т.д.",
            parametersJsonSchema = """
                {"type":"object","properties":{
                  "pids":{"type":"array","items":{"type":"string"},
                    "description":"Имена PID, например RPM, SPEED, COOLANT_TEMP"}},
                "required":["pids"],"additionalProperties":false}
            """.trimIndent(),
        ),
        ToolSpec(
            name = "monitor_pid",
            description = "Записать серию значений одного PID за N секунд " +
                "(для поиска плавающих неисправностей).",
            parametersJsonSchema = """
                {"type":"object","properties":{
                  "pid":{"type":"string"},
                  "duration_sec":{"type":"integer","minimum":1,"maximum":120}},
                "required":["pid","duration_sec"],"additionalProperties":false}
            """.trimIndent(),
        ),
        ToolSpec(
            name = "clear_dtcs",
            description = "Сбросить коды неисправностей и погасить Check Engine (Mode 04). " +
                "ОПАСНО: стирает диагностические данные. Требует явного подтверждения " +
                "пользователя в приложении перед выполнением.",
            parametersJsonSchema = NO_ARGS,
        ),
        ToolSpec(
            name = "save_to_logbook",
            description = "Сохранить важный вывод, диагноз или наблюдение в бортжурнал активного " +
                "автомобиля, чтобы учесть это при будущих диагностиках. Пиши кратко и по делу — " +
                "только подтверждённые находки, одна мысль на запись.",
            parametersJsonSchema = """
                {"type":"object","properties":{
                  "note":{"type":"string","description":"Краткая запись, например: подтверждён пропуск воспламенения в 3-м цилиндре, вероятна катушка зажигания"}},
                "required":["note"],"additionalProperties":false}
            """.trimIndent(),
        ),
    )
}
