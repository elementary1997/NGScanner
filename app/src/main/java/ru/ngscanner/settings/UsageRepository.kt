package ru.ngscanner.settings

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.ngscanner.llm.ProviderId

/** Расход по одной модели: токены (prompt/completion) и число запросов. */
@Serializable
data class ModelUsage(
    val model: String,
    val prompt: Long = 0,
    val completion: Long = 0,
    val requests: Int = 0,
) {
    val total: Long get() = prompt + completion
}

/** Цена модели, ₽ за 1 млн токенов, раздельно для входных и выходных (генерируемых). */
@Serializable
data class ModelPrice(val input: Double = 0.0, val output: Double = 0.0) {
    val isSet: Boolean get() = input > 0 || output > 0
}

/**
 * Помодельный расход токенов по провайдерам и заданные пользователем цены
 * (₽ за 1 млн токенов). API отдаёт только потребление токенов (поле usage), а
 * не денежный баланс — сумму оцениваем по цене, которую пользователь берёт из
 * своего тарифа; остаток средств виден в личном кабинете провайдера.
 */
class UsageRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun models(provider: ProviderId): List<ModelUsage> {
        val raw = prefs.getString(modelsKey(provider), null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<ModelUsage>>(raw) }.getOrDefault(emptyList())
    }

    /** Прибавляет расход модели и возвращает обновлённый список (по убыванию токенов). */
    fun add(provider: ProviderId, model: String, prompt: Int, completion: Int): List<ModelUsage> {
        val list = models(provider).toMutableList()
        val i = list.indexOfFirst { it.model == model }
        if (i >= 0) {
            val u = list[i]
            list[i] = u.copy(prompt = u.prompt + prompt, completion = u.completion + completion, requests = u.requests + 1)
        } else {
            list.add(ModelUsage(model, prompt.toLong(), completion.toLong(), 1))
        }
        val sorted = list.sortedByDescending { it.total }
        prefs.edit().putString(modelsKey(provider), json.encodeToString(sorted)).apply()
        return sorted
    }

    fun reset(provider: ProviderId) {
        prefs.edit().remove(modelsKey(provider)).apply()
    }

    /** Убирает одну модель из учёта провайдера и возвращает обновлённый список. */
    fun removeModel(provider: ProviderId, model: String): List<ModelUsage> {
        val updated = models(provider).filterNot { it.model == model }
        prefs.edit().putString(modelsKey(provider), json.encodeToString(updated)).apply()
        return updated
    }

    /** Цены по id модели (общая карта для всех провайдеров). */
    fun prices(): Map<String, ModelPrice> {
        val raw = prefs.getString(KEY_PRICES, null) ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, ModelPrice>>(raw) }.getOrDefault(emptyMap())
    }

    /** Задаёт цену модели (обе нулевые — убирает) и возвращает обновлённую карту. */
    fun setPrice(model: String, input: Double, output: Double): Map<String, ModelPrice> {
        val m = prices().toMutableMap()
        val price = ModelPrice(input, output)
        if (price.isSet) m[model] = price else m.remove(model)
        prefs.edit().putString(KEY_PRICES, json.encodeToString(m)).apply()
        return m
    }

    private fun modelsKey(p: ProviderId) = "models_${p.name}"

    private companion object {
        const val PREFS = "ngscanner_usage"
        const val KEY_PRICES = "prices"
    }
}
