package ru.ngscanner.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context

/** Тонкая обёртка над системным Bluetooth: адаптер и сопряжённые устройства. */
class BluetoothController(context: Context) {

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter

    val isAvailable: Boolean get() = adapter != null
    val isEnabled: Boolean get() = adapter?.isEnabled == true

    /** Сопряжённые устройства. Требует `BLUETOOTH_CONNECT` (запрашивается в UI). */
    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<BluetoothDevice> =
        adapter?.bondedDevices?.sortedBy { runCatching { it.name }.getOrNull() ?: it.address }.orEmpty()

    fun deviceByAddress(address: String): BluetoothDevice? =
        runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
}
