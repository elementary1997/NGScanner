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
        onEvent: (AgentEvent) -> Unit,
    ): List<LlmMessage> {
        val messages = history.toMutableList()
        messages.add(LlmMessage(Role.USER, content = userMessage, images = images))
        // Системный промпт зависит от того, подключён ли адаптер: без него агент
        // отвечает на общие вопросы и не упирается в OBD-инструменты.
        val system = buildString {
            append(SYSTEM_PROMPT)
            append("\n\n")
            append(if (adapterConnected) ADAPTER_ONLINE_NOTE else ADAPTER_OFFLINE_NOTE)
            if (!carContext.isNullOrBlank()) append("\n\n").append(carContext)
        }

        var steps = 0
        while (steps++ < MAX_STEPS) {
            val response = provider.send(LlmRequest(model, system, messages, ObdTools.all))
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
                "Порядок работы: пойми жалобу пользователя, собери нужные данные инструментами " +
                "(коды неисправностей, freeze frame, живые параметры), проанализируй их и дай " +
                "понятный вердикт: что вероятнее всего неисправно, насколько это серьёзно и какие " +
                "шаги проверки или ремонта предпринять. Объясняй простым языком.\n\n" +
                "Инструмент clear_dtcs (сброс кодов) вызывай только если пользователь явно просит " +
                "сбросить коды; он требует отдельного подтверждения в приложении.\n\n" +
                "Когда сформулировал вердикт или заметил важный факт по этой машине — сохрани его " +
                "кратко инструментом save_to_logbook, чтобы учесть при будущих диагностиках. Не " +
                "дублируй уже имеющиеся записи из контекста автомобиля.\n\n" +
                "Не выдумывай коды и значения — опирайся только на данные, полученные инструментами.\n\n" +
                "Форматирование: экран телефона узкий. НЕ используй таблицы Markdown — они не " +
                "помещаются по ширине и их невозможно прочитать. Представляй данные маркированным " +
                "списком или строками вида «Параметр: значение». Заголовки и подписи держи короткими."

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

/** События агента для отображения в чате. */
sealed interface AgentEvent {
    data class Assistant(val text: String) : AgentEvent
    data class ToolCall(val name: String) : AgentEvent
}
