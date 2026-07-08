package ru.ngscanner.transport

import android.bluetooth.BluetoothDevice
import java.util.UUID

/**
 * Транспорт поверх классического Bluetooth (RFCOMM / Serial Port Profile).
 * Подходит большинству дешёвых клонов ELM327 (v1.5 / v2.1).
 */
class ClassicSppTransport(
    private val device: BluetoothDevice,
) : ObdTransport {

    override val kind = TransportKind.CLASSIC_SPP

    override var isConnected: Boolean = false
        private set

    /** Стандартный UUID профиля Serial Port Profile. */
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    override suspend fun connect() {
        // TODO(этап 1): device.createRfcommSocketToServiceRecord(sppUuid) + socket.connect();
        //   при неудаче — резервный путь через рефлексию createRfcommSocket(1).
        TODO("Этап 1: подключение по RFCOMM (device=$device, uuid=$sppUuid)")
    }

    override suspend fun write(command: String) {
        TODO("Этап 1: запись в выходной поток RFCOMM-сокета")
    }

    override suspend fun readResponse(timeoutMs: Long): String {
        TODO("Этап 1: чтение из входного потока до символа '>'")
    }

    override fun close() {
        isConnected = false
        // TODO(этап 1): закрыть сокет и потоки.
    }
}
