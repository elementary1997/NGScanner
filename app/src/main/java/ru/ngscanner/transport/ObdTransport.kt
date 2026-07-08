package ru.ngscanner.transport

/**
 * Абстракция физического канала до OBD-II адаптера ELM327.
 *
 * Скрывает различия транспортов (классический Bluetooth SPP, BLE, USB, Wi-Fi),
 * чтобы вышележащий OBD-слой ([ru.ngscanner.obd.Elm327]) работал с любым
 * адаптером одинаково. Добавление нового типа адаптера — это новая реализация
 * этого интерфейса, без изменений в остальном коде.
 */
interface ObdTransport {
    val kind: TransportKind
    val isConnected: Boolean

    /** Установить соединение. Бросает [ObdTransportException] при неудаче. */
    suspend fun connect()

    /** Отправить команду адаптеру (ELM327 ожидает завершающий возврат каретки). */
    suspend fun write(command: String)

    /** Прочитать ответ до символа-приглашения '>' либо до истечения таймаута. */
    suspend fun readResponse(timeoutMs: Long = DEFAULT_TIMEOUT_MS): String

    /** Закрыть соединение и освободить ресурсы. */
    fun close()

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L
    }
}

/** Поддерживаемые виды физического транспорта до адаптера. */
enum class TransportKind { CLASSIC_SPP, BLE, USB_SERIAL, WIFI }

class ObdTransportException(message: String, cause: Throwable? = null) : Exception(message, cause)
