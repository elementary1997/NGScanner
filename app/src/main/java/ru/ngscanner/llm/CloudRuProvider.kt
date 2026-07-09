package ru.ngscanner.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Провайдер Cloud.ru Evolution Foundation Models — OpenAI-совместимый API
 * (`/v1/chat/completions`, авторизация `Bearer`, tool use в формате OpenAI
 * `tools` / `tool_calls`).
 */
class CloudRuProvider(
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : LlmProvider {

    override val id = ProviderId.CLOUD_RU
    override val displayName = "Cloud.ru Foundation Models"

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .callTimeout(180, TimeUnit.SECONDS)
        .addInterceptor(RetryInterceptor())
        .build()

    override suspend fun availableModels(): List<LlmModel> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/models")
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw LlmException.fromHttp(resp.code, text)
            val data = json.parseToJsonElement(text).jsonObject["data"]?.jsonArray ?: return@use emptyList()
            data.mapNotNull { m ->
                val id = m.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                LlmModel(id, id.substringAfterLast('/'), supportsTools = true)
            }
        }
    }

    override suspend fun send(request: LlmRequest): LlmResponse = withContext(Dispatchers.IO) {
        val httpReq = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(buildBody(request).toString().toRequestBody(JSON_MEDIA))
            .build()
        http.newCall(httpReq).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw LlmException.fromHttp(resp.code, text)
            parseResponse(text)
        }
    }

    private fun buildBody(request: LlmRequest): JsonObject = buildJsonObject {
        put("model", request.model)
        putJsonArray("messages") {
            add(buildJsonObject { put("role", "system"); put("content", request.system) })
            request.messages.forEach { addMessage(it) }
        }
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                request.tools.forEach { t ->
                    add(
                        buildJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", t.name)
                                put("description", t.description)
                                put("parameters", json.parseToJsonElement(t.parametersJsonSchema))
                            }
                        },
                    )
                }
            }
        }
    }

    private fun JsonArrayBuilder.addMessage(m: LlmMessage) {
        when (m.role) {
            Role.USER -> add(
                buildJsonObject {
                    put("role", "user")
                    if (m.images.isEmpty()) {
                        put("content", m.content ?: "")
                    } else {
                        putJsonArray("content") {
                            if (!m.content.isNullOrBlank()) {
                                add(buildJsonObject { put("type", "text"); put("text", m.content) })
                            }
                            m.images.forEach { img ->
                                add(
                                    buildJsonObject {
                                        put("type", "image_url")
                                        putJsonObject("image_url") {
                                            put("url", "data:${img.mediaType};base64,${img.base64}")
                                        }
                                    },
                                )
                            }
                        }
                    }
                },
            )
            Role.ASSISTANT -> add(
                buildJsonObject {
                    put("role", "assistant")
                    put("content", m.content ?: "")
                    if (m.toolCalls.isNotEmpty()) {
                        putJsonArray("tool_calls") {
                            m.toolCalls.forEach { c ->
                                add(
                                    buildJsonObject {
                                        put("id", c.id)
                                        put("type", "function")
                                        putJsonObject("function") {
                                            put("name", c.name)
                                            put("arguments", c.argumentsJson.ifBlank { "{}" })
                                        }
                                    },
                                )
                            }
                        }
                    }
                },
            )
            Role.TOOL -> m.toolResults.forEach { r ->
                add(
                    buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", r.callId)
                        put("content", r.content)
                    },
                )
            }
        }
    }

    private fun parseResponse(text: String): LlmResponse {
        val root = json.parseToJsonElement(text).jsonObject
        val message = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?: return LlmResponse.Final("")
        val content = message["content"]?.jsonPrimitive?.contentOrNull
        val toolCalls = message["tool_calls"]?.jsonArray
        if (!toolCalls.isNullOrEmpty()) {
            // Пропускаем нестандартные элементы, а не роняем весь агентный цикл на !!.
            val calls = toolCalls.mapNotNull { tc ->
                val o = tc.jsonObject
                val fn = o["function"]?.jsonObject ?: return@mapNotNull null
                val name = fn["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                ToolCall(
                    id = o["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    name = name,
                    argumentsJson = fn["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}",
                )
            }
            if (calls.isNotEmpty()) return LlmResponse.ToolUse(content, calls)
        }
        return LlmResponse.Final(content.orEmpty())
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://foundation-models.api.cloud.ru/v1"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
