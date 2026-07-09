package ru.ngscanner.llm

import kotlinx.serialization.Serializable

/**
 * Абстракция LLM-провайдера.
 *
 * Прячет различия между Anthropic Claude (Messages API, блоки
 * `tool_use` / `tool_result`, заголовок `x-api-key`) и OpenAI-совместимыми
 * API — Cloud.ru Evolution Foundation Models (`tools` / `tool_calls`,
 * `Authorization: Bearer`).
 *
 * Диагностический агентный цикл ([ru.ngscanner.agent.DiagnosticAgent])
 * работает только с этим интерфейсом и не знает, какой провайдер выбран
 * в настройках приложения.
 */
interface LlmProvider {
    val id: ProviderId
    val displayName: String

    /** Доступные модели провайдера — для выбора в UI. */
    suspend fun availableModels(): List<LlmModel>

    /**
     * Один шаг диалога: отправить историю сообщений и определения инструментов,
     * получить ответ (текст и/или запросы на вызов инструментов).
     */
    suspend fun send(request: LlmRequest): LlmResponse
}

enum class ProviderId { CLAUDE, CLOUD_RU }

data class LlmModel(
    val id: String,
    val label: String,
    val supportsTools: Boolean,
)

data class LlmRequest(
    val model: String,
    val system: String,
    val messages: List<LlmMessage>,
    val tools: List<ToolSpec>,
)

@Serializable
data class LlmMessage(
    val role: Role,
    val content: String? = null,
    val images: List<LlmImage> = emptyList(),
    val toolCalls: List<ToolCall> = emptyList(),
    val toolResults: List<ToolResult> = emptyList(),
)

/** Изображение для vision-моделей: base64 без префикса `data:` + MIME-тип. */
@Serializable
data class LlmImage(val base64: String, val mediaType: String = "image/jpeg")

@Serializable
enum class Role { USER, ASSISTANT, TOOL }

/**
 * Описание инструмента в нейтральном виде.
 * Каждый провайдер конвертирует его в свой формат function calling.
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val parametersJsonSchema: String,
)

@Serializable
data class ToolCall(val id: String, val name: String, val argumentsJson: String)

@Serializable
data class ToolResult(val callId: String, val content: String, val isError: Boolean = false)

/** Результат одного шага диалога с моделью. */
sealed interface LlmResponse {
    /** Модель запросила вызов инструментов — их нужно исполнить и вернуть результаты. */
    data class ToolUse(val text: String?, val calls: List<ToolCall>) : LlmResponse

    /** Финальный ответ пользователю. */
    data class Final(val text: String) : LlmResponse
}
