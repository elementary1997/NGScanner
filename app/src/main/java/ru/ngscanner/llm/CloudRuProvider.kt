package ru.ngscanner.llm

/**
 * Провайдер Cloud.ru Evolution Foundation Models — OpenAI-совместимый API.
 *
 * Base URL: `https://foundation-models.api.cloud.ru/v1`, авторизация
 * заголовком `Authorization: Bearer <ключ>`. Function calling — в формате
 * OpenAI (`tools` / `tool_calls`); доступен не на всех моделях, поэтому
 * список моделей и их возможности берутся из живого `GET /v1/models`.
 */
class CloudRuProvider(
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : LlmProvider {

    override val id = ProviderId.CLOUD_RU
    override val displayName = "Cloud.ru Foundation Models"

    override suspend fun availableModels(): List<LlmModel> {
        // TODO(этап 3): GET {baseUrl}/models; supportsTools — по capability-тегам модели.
        TODO("Этап 3: GET /v1/models (baseUrl=$baseUrl)")
    }

    override suspend fun send(request: LlmRequest): LlmResponse {
        // TODO(этап 3): POST {baseUrl}/chat/completions в формате OpenAI;
        //   ToolSpec -> tools[]; разобрать tool_calls в LlmResponse.ToolUse.
        TODO("Этап 3: OpenAI-совместимый /chat/completions")
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://foundation-models.api.cloud.ru/v1"
    }
}
