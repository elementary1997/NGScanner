package ru.ngscanner.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Транспорт поверх классического Bluetooth (RFCOMM / Serial Port Profile).
 * Подходит большинству дешёвых клонов ELM327 (в т.ч. «ELM327 mini» v1.5 / v2.1).
 *
 * Разрешение `BLUETOOTH_CONNECT` (Android 12+) запрашивается в UI до вызова
 * [connect], поэтому вызовы Bluetooth API помечены `@SuppressLint`.
 */
@SuppressLint("MissingPermission")
class ClassicSppTransport(
    private val device: BluetoothDevice,
) : ObdTransport {

    override val kind = TransportKind.CLASSIC_SPP

    // @Volatile: пишется из IO-потока (connect/write/read), читается из UI —
    // без него возможна устаревшая видимость состояния соединения.
    @Volatile
    override var isConnected: Boolean = false
        private set

    /** Стандартный UUID профиля Serial Port Profile. */
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override suspend fun connect() {
        withContext(Dispatchers.IO) {
            try {
                val primary = device.createRfcommSocketToServiceRecord(sppUuid)
                val s = try {
                    primary.apply { connect() }
                } catch (_: Exception) {
                    // Первый сокет уже держит нативный дескриптор — закрываем его перед
                    // резервным путём, иначе при повторных попытках к «капризному»
                    // клону файловые дескрипторы утекают.
                    runCatching { primary.close() }
                    val fallback = device.javaClass
                        .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        .invoke(device, 1) as BluetoothSocket
                    try {
                        fallback.apply { connect() }
                    } catch (e: Exception) {
                        runCatching { fallback.close() }
                        throw e
                    }
                }
                socket = s
                input = s.inputStream
                output = s.outputStream
                isConnected = true
            } catch (e: Exception) {
                close()
                throw ObdTransportException("Не удалось подключиться к адаптеру: ${e.message}", e)
            }
        }
    }

    override suspend fun write(command: String) {
        withContext(Dispatchers.IO) {
            val out = output ?: throw ObdTransportException("Нет активного соединения")
            // Дренируем «хвост» предыдущего ответа: если прошлый readResponse вышел по
            // таймауту, не дочитав приглашение '>', его остаток (и само '>') остаётся в
            // буфере. Без очистки следующая команда прочитала бы чужой ответ — и
            // соответствие запрос→ответ сдвинулось бы навсегда (frame desync).
            drainInput()
            try {
                out.write((command + "\r").toByteArray())
                out.flush()
            } catch (e: IOException) {
                isConnected = false
                throw ObdTransportException("Связь с адаптером потеряна", e)
            }
        }
    }

    /** Сбрасывает залежавшиеся во входном буфере байты (хвост прошлого ответа). */
    private fun drainInput() {
        val inp = input ?: return
        runCatching { while (inp.available() > 0) { if (inp.read() == -1) break } }
    }

    override suspend fun readResponse(timeoutMs: Long): String = withContext(Dispatchers.IO) {
        val inp = input ?: throw ObdTransportException("Нет активного соединения")
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (System.currentTimeMillis() < deadline) {
                // Уважать отмену корутины даже при непрерывном потоке байт от клона
                // (иначе пауза опроса под диалог агента висела бы до таймаута).
                ensureActive()
                if (inp.available() > 0) {
                    val c = inp.read()
                    if (c == -1) break
                    val ch = c.toChar()
                    if (ch == '>') break // приглашение ELM327 — конец ответа
                    sb.append(ch)
                } else {
                    delay(20)
                }
            }
        } catch (e: IOException) {
            isConnected = false
            throw ObdTransportException("Связь с адаптером потеряна", e)
        }
        sb.toString().trim()
    }

    override fun close() {
        isConnected = false
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }
}
