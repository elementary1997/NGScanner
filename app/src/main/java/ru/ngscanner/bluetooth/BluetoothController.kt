package ru.ngscanner.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/** Тонкая обёртка над системным Bluetooth: адаптер, сопряжённые устройства и поиск. */
class BluetoothController(context: Context) {

    private val appContext = context.applicationContext
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter

    private var receiver: BroadcastReceiver? = null

    val isAvailable: Boolean get() = adapter != null
    val isEnabled: Boolean get() = adapter?.isEnabled == true

    /** Сопряжённые устройства. Требует `BLUETOOTH_CONNECT` (запрашивается в UI). */
    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<BluetoothDevice> =
        adapter?.bondedDevices?.sortedBy { runCatching { it.name }.getOrNull() ?: it.address }.orEmpty()

    fun deviceByAddress(address: String): BluetoothDevice? =
        runCatching { adapter?.getRemoteDevice(address) }.getOrNull()

    /**
     * Запускает поиск видимых (ещё не сопряжённых) устройств. [onFound] вызывается
     * на каждое найденное устройство, [onFinished] — по завершении поиска.
     * Требует `BLUETOOTH_SCAN` (Android 12+) или геолокацию (до Android 12).
     */
    @SuppressLint("MissingPermission")
    fun startDiscovery(onFound: (BluetoothDevice) -> Unit, onFinished: () -> Unit) {
        val a = adapter
        if (a == null) {
            onFinished()
            return
        }
        cancelDiscovery()
        val r = object : BroadcastReceiver() {
            @Suppress("DEPRECATION")
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        if (device != null) onFound(device)
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> onFinished()
                }
            }
        }
        receiver = r
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        appContext.registerReceiver(r, filter)
        if (a.isDiscovering) a.cancelDiscovery()
        a.startDiscovery()
    }

    /** Останавливает поиск и снимает приёмник событий. */
    @SuppressLint("MissingPermission")
    fun cancelDiscovery() {
        runCatching { adapter?.cancelDiscovery() }
        receiver?.let { r -> runCatching { appContext.unregisterReceiver(r) } }
        receiver = null
    }
}
