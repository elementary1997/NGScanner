package ru.ngscanner.llm

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException

/**
 * Повторяет запрос при временных ошибках (429/408/5xx, включая Anthropic 529
 * «overloaded») и транзиентных сетевых сбоях с экспоненциальным бэкоффом.
 * Уважает заголовок `Retry-After` (секунды).
 *
 * Сон между попытками кооперативен к отмене: он режется на короткие слайсы и
 * прерывается, если вызов отменён (`Call.cancel()` при отмене корутины) —
 * иначе после отмены диагностики интерцептор досыпал бы и слал платные запросы.
 */
class RetryInterceptor(private val maxRetries: Int = 3) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var attempt = 0
        while (true) {
            val response = try {
                chain.proceed(chain.request())
            } catch (e: IOException) {
                // Сетевой сбой. Повтор POST опасен: если обрыв случился в фазе чтения
                // тела, сервер мог уже сгенерировать (и списать) ответ — повтор дал бы
                // второй completion и двойное списание. Поэтому IOException ретраим
                // только для идемпотентного GET или заведомо до-серверных сбоев
                // (соединение не установилось — ответа физически быть не могло).
                val safeToRetry = chain.request().method == "GET" ||
                    e is ConnectException || e is UnknownHostException
                if (attempt >= maxRetries || chain.call().isCanceled() || !safeToRetry) throw e
                sleepBackoff(chain, BASE_DELAY_MS shl attempt)
                attempt++
                continue
            }
            if (response.code !in RETRYABLE || attempt >= maxRetries) return response
            val retryAfterMs = parseRetryAfterMs(response.header("Retry-After"))
            response.close()
            val delay = retryAfterMs?.coerceAtMost(RETRY_AFTER_MAX_MS)
                ?: (BASE_DELAY_MS shl attempt).coerceAtMost(MAX_DELAY_MS)
            sleepBackoff(chain, delay)
            attempt++
        }
    }

    /** Сон с проверкой отмены и восстановлением флага прерывания. */
    private fun sleepBackoff(chain: Interceptor.Chain, totalMs: Long) {
        var slept = 0L
        while (slept < totalMs) {
            if (chain.call().isCanceled()) throw IOException("Запрос отменён")
            val step = minOf(SLEEP_SLICE_MS, totalMs - slept)
            try {
                Thread.sleep(step)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt() // не глотаем прерывание — восстанавливаем флаг
                throw IOException("Запрос прерван во время повторной попытки", e)
            }
            slept += step
        }
    }

    /** `Retry-After` в секундах (форма HTTP-date не поддерживается — тогда бэкофф). */
    private fun parseRetryAfterMs(header: String?): Long? =
        header?.trim()?.toLongOrNull()?.let { if (it in 0..300) it * 1000 else null }

    private companion object {
        // 529 — Anthropic overloaded; 408 — request timeout; оба транзиентны.
        val RETRYABLE = setOf(408, 429, 500, 502, 503, 504, 529)
        const val BASE_DELAY_MS = 600L
        const val MAX_DELAY_MS = 8000L
        const val RETRY_AFTER_MAX_MS = 60_000L
        const val SLEEP_SLICE_MS = 100L
    }
}
