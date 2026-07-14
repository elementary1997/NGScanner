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
            name = "full_scan",
            description = "Полный диагностический снимок ОДНИМ вызовом: активные, неподтверждённые и " +
                "постоянные коды, готовность мониторов, freeze frame (снимок в момент ошибки) и " +
                "ключевые живые параметры (обороты, температура, нагрузка, топливные коррекции STFT/LTFT, " +
                "MAF, дроссель, лямбда, напряжение). ВЫЗЫВАЙ ЭТО ПЕРВЫМ при жалобе на неисправность или " +
                "запросе диагностики — так собираешь весь контекст сразу, а не по одному параметру.",
            parametersJsonSchema = NO_ARGS,
        ),
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
            name = "read_permanent_dtcs",
            description = "Постоянные (permanent) коды неисправностей (Mode 0A) — их нельзя стереть " +
                "сбросом, пока ЭБУ сам не убедится в устранении. Показывают, что реально не починено.",
            parametersJsonSchema = NO_ARGS,
        ),
        ToolSpec(
            name = "read_readiness",
            description = "Готовность бортовых мониторов (Mode 01 PID 01): статус Check Engine и " +
                "какие системы (катализатор, EVAP, датчики O₂, EGR…) прошли самопроверку. Нужно " +
                "перед техосмотром и после сброса кодов — незавершённые мониторы означают, что " +
                "авто нужно проехать цикл готовности.",
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
                  "duration_sec":{"type":"integer","minimum":1,"maximum":60}},
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
            name = "submit_verdict",
            description = "ЗАВЕРШИТЬ диагностику структурным вердиктом. Вызывай ЭТИМ инструментом, " +
                "когда готов поставить диагноз — не пиши вердикт свободным текстом. Каждая причина " +
                "обязана нести evidence: конкретные коды и значения ИЗ ОТВЕТОВ ИНСТРУМЕНТОВ, на " +
                "которых она стоит (например «P0301 — пропуски в 3-м цилиндре», «LTFT +18% на " +
                "холостых»). Приложение сверит их с реальными данными адаптера и покажет " +
                "пользователю, что подтверждено, а что нет. Не ссылайся на коды, которых не было в " +
                "ответах инструментов.",
            parametersJsonSchema = """
                {"type":"object","properties":{
                  "severity":{"type":"string","enum":["КРИТИЧНО","ВНИМАНИЕ","НОРМА","НУЖНЫ ДАННЫЕ"],
                    "description":"КРИТИЧНО — ехать нельзя; ВНИМАНИЕ — можно аккуратно доехать до сервиса; НОРМА — можно ездить; НУЖНЫ ДАННЫЕ — данных для вывода не хватает"},
                  "summary":{"type":"string","description":"Одна короткая строка: что вероятнее всего и можно ли ехать"},
                  "causes":{"type":"array","description":"Вероятные причины по убыванию вероятности",
                    "items":{"type":"object","properties":{
                      "title":{"type":"string","description":"Причина простым языком"},
                      "confidence":{"type":"integer","minimum":0,"maximum":100,"description":"Насколько уверен, %"},
                      "evidence":{"type":"array","items":{"type":"string"},
                        "description":"Коды и значения ИЗ ОТВЕТОВ ИНСТРУМЕНТОВ, на которых стоит эта причина"}},
                    "required":["title","confidence","evidence"]}},
                  "checks":{"type":"array","items":{"type":"string"},
                    "description":"Что проверить ДО замены деталей: «проверь X; если Y — тогда деталь Z»"},
                  "diy":{"type":"string","description":"Своими силами или в сервис и насколько серьёзна работа"}},
                "required":["severity","summary"],"additionalProperties":false}
            """.trimIndent(),
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
