package ru.ngscanner

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.ngscanner.agent.AgentEvent
import ru.ngscanner.agent.DiagnosticAgent
import ru.ngscanner.agent.ObdToolExecutor
import ru.ngscanner.llm.LlmModel
import ru.ngscanner.llm.LlmProvider
import ru.ngscanner.llm.LlmRequest
import ru.ngscanner.llm.LlmResponse
import ru.ngscanner.llm.ProviderId
import ru.ngscanner.llm.Role
import ru.ngscanner.llm.ToolCall
import ru.ngscanner.llm.TokenUsage

/** Провайдер по скрипту: ответ зависит от номера вызова и пустоты списка инструментов. */
private class ScriptedProvider(
    private val script: (call: Int, toolsEmpty: Boolean) -> LlmResponse,
) : LlmProvider {
    var calls = 0
    override val id = ProviderId.CLAUDE
    override val displayName = "fake"
    override suspend fun availableModels(): List<LlmModel> = emptyList()
    override suspend fun send(request: LlmRequest): LlmResponse {
        val r = script(calls, request.tools.isEmpty())
        calls++
        return r
    }
}

class DiagnosticAgentTest {

    @Test
    fun runsToolThenFinalizes() = runBlocking {
        val provider = ScriptedProvider { i, _ ->
            if (i == 0) {
                LlmResponse.ToolUse(text = null, calls = listOf(ToolCall("1", "read_dtcs", "{}")), usage = TokenUsage(10, 5))
            } else {
                LlmResponse.Final("[СТАТУС: НОРМА] всё ок", usage = TokenUsage(3, 4))
            }
        }
        val events = mutableListOf<AgentEvent>()
        val msgs = DiagnosticAgent(provider, "m", ObdToolExecutor(elm = null))
            .run("проверь коды", emptyList(), emptyList(), onEvent = { events.add(it) })

        // История заканчивается ответом ассистента (корректная последовательность).
        assertEquals(Role.ASSISTANT, msgs.last().role)
        assertTrue(msgs.last().content!!.contains("НОРМА"))
        // Был вызов инструмента и учёт токенов.
        assertTrue(events.any { it is AgentEvent.ToolCall && it.name == "read_dtcs" })
        assertTrue(events.any { it is AgentEvent.Usage })
    }

    @Test
    fun finalizesWithoutToolsAtStepLimit() = runBlocking {
        // Модель всё время просит инструменты; на лимите шагов агент делает финальный
        // ход БЕЗ инструментов (tools пуст) и получает вердикт — история не обрывается
        // на tool_result (иначе Anthropic вернул бы 400 на следующем запросе).
        val provider = ScriptedProvider { _, toolsEmpty ->
            if (toolsEmpty) {
                LlmResponse.Final("[СТАТУС: НУЖНЫ ДАННЫЕ] достигнут лимит", usage = null)
            } else {
                LlmResponse.ToolUse(text = null, calls = listOf(ToolCall("1", "read_dtcs", "{}")), usage = null)
            }
        }
        val msgs = DiagnosticAgent(provider, "m", ObdToolExecutor(elm = null))
            .run("зациклись", emptyList(), emptyList(), onEvent = {})

        assertEquals(Role.ASSISTANT, msgs.last().role)
        assertTrue(msgs.last().content!!.contains("лимит"))
    }
}
