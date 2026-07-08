package ru.ngscanner.llm

/**
 * Провайдер на официальном Anthropic Java SDK (Messages API).
 * Модель по умолчанию — `claude-opus-4-8` с adaptive thinking;
 * инструменты передаются в формате Anthropic (`tool_use` / `tool_result`).
 */
class ClaudeProvider(
    private val apiKey: String,
) : LlmProvider {

    override val id = ProviderId.CLAUDE
    override val displayName = "Anthropic Claude"

    override suspend fun availableModels(): List<LlmModel> = listOf(
        LlmModel("claude-opus-4-8", "Claude Opus 4.8", supportsTools = true),
        LlmModel("claude-sonnet-5", "Claude Sonnet 5", supportsTools = true),
        LlmModel("claude-haiku-4-5", "Claude Haiku 4.5", supportsTools = true),
    )

    override suspend fun send(request: LlmRequest): LlmResponse {
        // TODO(этап 3): AnthropicOkHttpClient c apiKey; client.messages().create(...);
        //   thinking = adaptive; ToolSpec -> Anthropic Tool; разобрать tool_use блоки
        //   в LlmResponse.ToolUse, иначе LlmResponse.Final.
        TODO("Этап 3: интеграция Anthropic SDK")
    }
}
