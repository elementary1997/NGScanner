package ru.ngscanner.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
 * Провайдер Anthropic Claude через Messages API (HTTP).
 *
 * Реализован на OkHttp, а не на Java SDK, ради единого подхода с
 * [CloudRuProvider] и полного контроля над форматом tool use
 * (`tool_use` / `tool_result`). Модель по умолчанию — `claude-opus-4-8`.
 */
class ClaudeProvider(
    private val apiKey: String,
) : LlmProvider {

    override val id = ProviderId.CLAUDE
    override val displayName = "Anthropic Claude"

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder().callTimeout(180, TimeUnit.SECONDS).build()

    override suspend fun availableModels(): List<LlmModel> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$API_URL/models?limit=100")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("Claude API ${resp.code}: ${text.take(200)}")
            val data = json.parseToJsonElement(text).jsonObject["data"]?.jsonArray ?: return@use emptyList()
            data.mapNotNull { m ->
                val o = m.jsonObject
                val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                LlmModel(id, o["display_name"]?.jsonPrimitive?.contentOrNull ?: id, supportsTools = true)
            }
        }
    }

    override suspend fun send(request: LlmRequest): LlmResponse = withContext(Dispatchers.IO) {
        val httpReq = Request.Builder()
            .url("$API_URL/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("content-type", "application/json")
            .post(buildBody(request).toString().toRequestBody(JSON_MEDIA))
            .build()
        http.newCall(httpReq).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("Claude API ${resp.code}: ${text.take(300)}")
            parseResponse(text)
        }
    }

    private fun buildBody(request: LlmRequest): JsonObject = buildJsonObject {
        put("model", request.model)
        put("max_tokens", 4096)
        put("system", request.system)
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                request.tools.forEach { t ->
                    add(
                        buildJsonObject {
                            put("name", t.name)
                            put("description", t.description)
                            put("input_schema", json.parseToJsonElement(t.parametersJsonSchema))
                        },
                    )
                }
            }
        }
        putJsonArray("messages") {
            request.messages.forEach { addMessage(it) }
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
                            m.images.forEach { img ->
                                add(
                                    buildJsonObject {
                                        put("type", "image")
                                        putJsonObject("source") {
                                            put("type", "base64")
                                            put("media_type", img.mediaType)
                                            put("data", img.base64)
                                        }
                                    },
                                )
                            }
                            if (!m.content.isNullOrBlank()) {
                                add(buildJsonObject { put("type", "text"); put("text", m.content) })
                            }
                        }
                    }
                },
            )
            Role.ASSISTANT -> add(
                buildJsonObject {
                    put("role", "assistant")
                    putJsonArray("content") {
                        if (!m.content.isNullOrBlank()) {
                            add(buildJsonObject { put("type", "text"); put("text", m.content) })
                        }
                        m.toolCalls.forEach { c ->
                            add(
                                buildJsonObject {
                                    put("type", "tool_use")
                                    put("id", c.id)
                                    put("name", c.name)
                                    put("input", json.parseToJsonElement(c.argumentsJson.ifBlank { "{}" }))
                                },
                            )
                        }
                    }
                },
            )
            Role.TOOL -> add(
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        m.toolResults.forEach { r ->
                            add(
                                buildJsonObject {
                                    put("type", "tool_result")
                                    put("tool_use_id", r.callId)
                                    put("content", r.content)
                                    if (r.isError) put("is_error", true)
                                },
                            )
                        }
                    }
                },
            )
        }
    }

    private fun parseResponse(text: String): LlmResponse {
        val root = json.parseToJsonElement(text).jsonObject
        val content = root["content"]?.jsonArray ?: JsonArray(emptyList())
        val sb = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        content.forEach { block ->
            val o = block.jsonObject
            when (o["type"]?.jsonPrimitive?.content) {
                "text" -> sb.append(o["text"]?.jsonPrimitive?.content.orEmpty())
                "tool_use" -> calls.add(
                    ToolCall(
                        id = o["id"]?.jsonPrimitive?.content.orEmpty(),
                        name = o["name"]?.jsonPrimitive?.content.orEmpty(),
                        argumentsJson = o["input"]?.jsonObject?.toString() ?: "{}",
                    ),
                )
            }
        }
        val stop = root["stop_reason"]?.jsonPrimitive?.content
        return if (stop == "tool_use" || calls.isNotEmpty()) {
            LlmResponse.ToolUse(sb.toString().ifBlank { null }, calls)
        } else {
            LlmResponse.Final(sb.toString())
        }
    }

    companion object {
        private const val API_URL = "https://api.anthropic.com/v1"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
