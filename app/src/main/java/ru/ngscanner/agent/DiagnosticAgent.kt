package ru.ngscanner.agent

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
        history: List<LlmMessage>,
        onEvent: (AgentEvent) -> Unit,
    ): List<LlmMessage> {
        val messages = history.toMutableList()
        messages.add(LlmMessage(Role.USER, content = userMessage))

        var steps = 0
        while (steps++ < MAX_STEPS) {
            val response = provider.send(LlmRequest(model, SYSTEM_PROMPT, messages, ObdTools.all))
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
                "Если пользователь не указал иное — начинай с чтения активных кодов неисправностей. " +
                "Инструмент clear_dtcs (сброс кодов) вызывай только если пользователь явно просит " +
                "сбросить коды; он требует отдельного подтверждения в приложении.\n\n" +
                "Не выдумывай коды и значения — опирайся только на данные, полученные инструментами."
    }
}

/** События агента для отображения в чате. */
sealed interface AgentEvent {
    data class Assistant(val text: String) : AgentEvent
    data class ToolCall(val name: String) : AgentEvent
}
