package ru.ngscanner.agent

import ru.ngscanner.llm.LlmImage
import ru.ngscanner.llm.LlmMessage
import ru.ngscanner.llm.LlmProvider
import ru.ngscanner.llm.LlmRequest
import ru.ngscanner.llm.LlmResponse
import ru.ngscanner.llm.Role

/**
 * Диагностический агентный цикл.
 *
 * Прогоняет запрос пользователя через выбранный [LlmProvider] с каталогом
 * инструментов [ObdTools], исполняет запрошенные вызовы через [ObdToolExecutor]
 * и возвращает результаты модели, пока та не сформирует финальный вердикт.
 * События ([AgentEvent]) стримятся в UI по мере работы.
 */
class DiagnosticAgent(
    private val provider: LlmProvider,
    private val model: String,
    private val executor: ObdToolExecutor,
) {
    suspend fun run(
        userMessage: String,
        images: List<LlmImage>,
        history: List<LlmMessage>,
        carContext: String? = null,
        adapterConnected: Boolean = true,
        connectionInfo: String? = null,
        onEvent: (AgentEvent) -> Unit,
    ): List<LlmMessage> {
        val messages = history.toMutableList()
        messages.add(LlmMessage(Role.USER, content = userMessage, images = images))
        // Системный промпт зависит от того, подключён ли адаптер: без него агент
        // отвечает на общие вопросы и не упирается в OBD-инструменты. Инфо о связи
        // (протокол, отвечает ли ЭБУ) заземляет агента, чтобы он не выдумывал причины.
        val system = buildString {
            append(SYSTEM_PROMPT)
            append("\n\n")
            append(if (adapterConnected) ADAPTER_ONLINE_NOTE else ADAPTER_OFFLINE_NOTE)
            if (!connectionInfo.isNullOrBlank()) append("\n\n").append(connectionInfo)
            if (!carContext.isNullOrBlank()) append("\n\n").append(carContext)
        }

        var steps = 0
        while (steps++ < MAX_STEPS) {
            val response = provider.send(LlmRequest(model, system, messages, ObdTools.all))
            response.usage?.let { onEvent(AgentEvent.Usage(it.prompt, it.completion)) }
            when (response) {
                is LlmResponse.Final -> {
                    messages.add(LlmMessage(Role.ASSISTANT, content = response.text))
                    onEvent(AgentEvent.Assistant(response.text))
                    return messages
                }
                is LlmResponse.ToolUse -> {
                    response.text?.takeIf { it.isNotBlank() }?.let { onEvent(AgentEvent.Assistant(it)) }
                    messages.add(
                        LlmMessage(Role.ASSISTANT, content = response.text, toolCalls = response.calls),
                    )
                    val results = response.calls.map { call ->
                        onEvent(AgentEvent.ToolCall(call.name))
                        executor.execute(call)
                    }
                    messages.add(LlmMessage(Role.TOOL, toolResults = results))
                }
            }
        }
        onEvent(AgentEvent.Assistant("Достигнут лимит шагов диагностики — прерываю."))
        return messages
    }

    companion object {
        const val MAX_STEPS = 12

        const val SYSTEM_PROMPT: String =
            "Ты — опытный автомеханик-диагност. У тебя есть инструменты для чтения данных " +
                "автомобиля через OBD-II адаптер ELM327.\n\n" +
                "Порядок работы: пойми жалобу, собери нужные данные инструментами (активные, " +
                "неподтверждённые и постоянные коды, freeze frame, готовность мониторов, живые " +
                "параметры), проанализируй их и дай вердикт.\n\n" +
                "ГЛАВНОЕ ПРАВИЛО — заземление. Опирайся ТОЛЬКО на реальные данные из инструментов и " +
                "контекст этой машины. Не выдумывай коды, значения и причины. Если данных для вывода " +
                "не хватает — честно скажи, что нужно измерить, и НЕ придумывай правдоподобный " +
                "диагноз. Уверенно-неверный вывод про безопасность хуже честного «нужны данные».\n\n" +
                "ФОРМАТ ИТОГОВОГО ВЕРДИКТА (обязателен, когда ставишь диагноз). Начни ответ РОВНО с " +
                "одной метки тяжести в квадратных скобках, отдельной строкой:\n" +
                "[СТАТУС: КРИТИЧНО] — ехать нельзя, риск для двигателя/безопасности (перегрев, падение " +
                "давления масла, детонация, сильные пропуски с риском катализатора).\n" +
                "[СТАТУС: ВНИМАНИЕ] — можно аккуратно доехать до сервиса, но откладывать нельзя.\n" +
                "[СТАТУС: НОРМА] — можно ездить и наблюдать, критичного нет.\n" +
                "[СТАТУС: НУЖНЫ ДАННЫЕ] — данных недостаточно для вывода.\n" +
                "После метки — одна короткая строка: что вероятнее всего и можно ли ехать. Затем " +
                "разделы простым языком:\n" +
                "• Вероятные причины — по убыванию вероятности, кратко на какие данные опираешься.\n" +
                "• Что проверить — конкретные шаги ДО замены деталей (не «поменяй катушку», а " +
                "«проверь X; если Y — тогда катушка»).\n" +
                "• Своими силами или в сервис — и насколько серьёзна работа.\n" +
                "Метку ставь по реальным данным, не на всякий случай. При [СТАТУС: НУЖНЫ ДАННЫЕ] — " +
                "перечисли, что именно снять/измерить.\n\n" +
                "Постоянные коды (read_permanent_dtcs) не стираются сбросом, пока ЭБУ не убедится в " +
                "ремонте — если есть, проблема не устранена. Готовность мониторов (read_readiness) — " +
                "перед техосмотром и после сброса кодов.\n\n" +
                "Инструмент clear_dtcs вызывай только по явной просьбе пользователя; он требует " +
                "отдельного подтверждения в приложении.\n\n" +
                "Сформулировав вердикт или заметив важный факт по машине — сохрани кратко через " +
                "save_to_logbook, чтобы учесть при будущих диагностиках. Не дублируй записи из " +
                "контекста автомобиля.\n\n" +
                "Форматирование: экран узкий. НЕ используй Markdown-таблицы — их не прочитать. Данные " +
                "давай списком или строками «Параметр: значение». Заголовки держи короткими."

        /** Ситуативная вставка, когда адаптер подключён и инструменты доступны. */
        const val ADAPTER_ONLINE_NOTE: String =
            "Адаптер ELM327 подключён — инструменты чтения данных доступны. Если пользователь " +
                "описал симптом и не просил иного, начни со чтения активных кодов неисправностей."

        /** Ситуативная вставка, когда адаптер не подключён — общий режим Q&A. */
        const val ADAPTER_OFFLINE_NOTE: String =
            "Адаптер ELM327 сейчас НЕ подключён. Отвечай на общие вопросы об устройстве, " +
                "эксплуатации, обслуживании и ремонте автомобиля напрямую, своими знаниями — не " +
                "вызывай OBD-инструменты и не требуй подключать адаптер. Предложи подключить " +
                "адаптер на вкладке «Приборы» только если для точного ответа действительно нужны " +
                "коды или живые параметры именно этой машины."
    }
}

/** События агента для отображения в чате и учёта расхода. */
sealed interface AgentEvent {
    data class Assistant(val text: String) : AgentEvent
    data class ToolCall(val name: String) : AgentEvent

    /** Расход токенов за один ответ модели. */
    data class Usage(val prompt: Int, val completion: Int) : AgentEvent
}
