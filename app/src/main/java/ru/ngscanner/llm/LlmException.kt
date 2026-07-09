package ru.ngscanner.llm

import java.io.IOException

/**
 * Ошибка обращения к LLM-провайдеру с понятным пользователю сообщением на
 * русском. Заменяет сырые RuntimeException с англоязычным трейсом в UI.
 */
class LlmException(val kind: Kind, val httpCode: Int? = null, detail: String? = null) :
    Exception(detail ?: kind.name) {

    enum class Kind { AUTH, RATE_LIMIT, SERVER, BAD_REQUEST, NETWORK, UNKNOWN }

    fun userMessage(): String = when (kind) {
        Kind.AUTH -> "Неверный или недействительный API-ключ. Проверьте ключ в настройках."
        Kind.RATE_LIMIT -> "Слишком много запросов к модели. Подождите немного и повторите."
        Kind.SERVER -> "Сервер модели временно недоступен. Повторите через минуту."
        Kind.BAD_REQUEST -> "Запрос отклонён. Возможно, выбранная модель недоступна для вашего ключа."
        Kind.NETWORK -> "Нет связи с сервером. Проверьте интернет-соединение."
        Kind.UNKNOWN -> "Не удалось связаться с моделью."
    }

    companion object {
        /** Строит ошибку по HTTP-коду ответа. */
        fun fromHttp(code: Int, body: String): LlmException {
            val kind = when (code) {
                401, 403 -> Kind.AUTH
                429 -> Kind.RATE_LIMIT
                400, 404, 422 -> Kind.BAD_REQUEST
                in 500..599 -> Kind.SERVER
                else -> Kind.UNKNOWN
            }
            return LlmException(kind, code, "HTTP $code: ${body.take(300)}")
        }

        /** Оборачивает произвольное исключение (сеть/прочее). */
        fun from(t: Throwable): LlmException = when (t) {
            is LlmException -> t
            is IOException -> LlmException(Kind.NETWORK, detail = t.message)
            else -> LlmException(Kind.UNKNOWN, detail = t.message)
        }
    }
}
