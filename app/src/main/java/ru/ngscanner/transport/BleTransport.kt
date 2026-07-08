package ru.ngscanner.transport

import android.bluetooth.BluetoothDevice

/**
 * Транспорт поверх Bluetooth Low Energy (GATT).
 * Для BLE-адаптеров ELM327: обмен через характеристики notify/write,
 * типичные сервисы `FFE0` / `FFF0`.
 */
class BleTransport(
    private val device: BluetoothDevice,
) : ObdTransport {

    override val kind = TransportKind.BLE

    override var isConnected: Boolean = false
        private set

    override suspend fun connect() {
        // TODO(этап 1+): device.connectGatt(...), найти сервис/характеристики,
        //   включить notifications, собрать поток ответов.
        TODO("Этап 1+: подключение по GATT (device=$device)")
    }

    override suspend fun write(command: String) {
        TODO("Этап 1+: запись в write-характеристику")
    }

    override suspend fun readResponse(timeoutMs: Long): String {
        TODO("Этап 1+: сборка ответа из notify-пакетов")
    }

    override fun close() {
        isConnected = false
        // TODO(этап 1+): gatt.close()
    }
}
