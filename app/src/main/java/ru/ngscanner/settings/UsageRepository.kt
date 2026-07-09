package ru.ngscanner.settings

import android.content.Context
import ru.ngscanner.llm.ProviderId

/**
 * Накопленный расход токенов по провайдерам. API отдаёт только потребление
 * токенов (поле `usage` в ответе), а не денежный баланс аккаунта — деньги видны
 * в личном кабинете провайдера. Здесь копим суммарные токены для показа.
 */
class UsageRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun total(provider: ProviderId): Long = prefs.getLong(key(provider), 0L)

    /** Прибавляет израсходованные токены и возвращает новый суммарный итог. */
    fun add(provider: ProviderId, tokens: Int): Long {
        val updated = total(provider) + tokens
        prefs.edit().putLong(key(provider), updated).apply()
        return updated
    }

    fun reset(provider: ProviderId) {
        prefs.edit().remove(key(provider)).apply()
    }

    private fun key(p: ProviderId) = "tokens_${p.name}"

    private companion object {
        const val PREFS = "ngscanner_usage"
    }
}
