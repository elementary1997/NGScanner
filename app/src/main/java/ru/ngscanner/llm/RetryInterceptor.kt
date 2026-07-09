package ru.ngscanner.llm

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Повторяет запрос при временных ошибках (429 и 5xx) с экспоненциальным
 * бэкоффом. Уважает заголовок `Retry-After` (секунды). Один транзиентный
 * `overloaded` на мобильной сети больше не роняет всю диагностику.
 */
class RetryInterceptor(private val maxRetries: Int = 3) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var attempt = 0
        var response = chain.proceed(chain.request())
        while (response.code in RETRYABLE && attempt < maxRetries) {
            val retryAfterMs = response.header("Retry-After")?.toLongOrNull()?.times(1000)
            response.close()
            val backoff = (retryAfterMs ?: (BASE_DELAY_MS shl attempt)).coerceAtMost(MAX_DELAY_MS)
            runCatching { Thread.sleep(backoff) }
            attempt++
            response = chain.proceed(chain.request())
        }
        return response
    }

    private companion object {
        val RETRYABLE = setOf(429, 500, 502, 503, 504)
        const val BASE_DELAY_MS = 600L
        const val MAX_DELAY_MS = 8000L
    }
}
