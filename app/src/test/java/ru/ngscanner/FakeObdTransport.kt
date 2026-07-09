package ru.ngscanner

import ru.ngscanner.transport.ObdTransport
import ru.ngscanner.transport.TransportKind

/**
 * Тестовый транспорт: возвращает заранее заданные ответы по последней команде.
 * Позволяет тестировать [ru.ngscanner.obd.Elm327] и исполнитель инструментов
 * без Android и реального Bluetooth.
 */
class FakeObdTransport(
    private val responses: Map<String, String> = emptyMap(),
    private val default: String = "OK",
) : ObdTransport {
    override val kind = TransportKind.CLASSIC_SPP
    override val isConnected = true

    private var lastCommand = ""
    val written = mutableListOf<String>()

    override suspend fun connect() {}

    override suspend fun write(command: String) {
        lastCommand = command.trim().uppercase()
        written.add(lastCommand)
    }

    override suspend fun readResponse(timeoutMs: Long): String =
        responses[lastCommand] ?: default

    override fun close() {}
}
