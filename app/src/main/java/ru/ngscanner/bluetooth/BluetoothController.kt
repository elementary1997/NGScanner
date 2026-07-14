package ru.ngscanner.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
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
    private var leCallback: ScanCallback? = null

    val isAvailable: Boolean get() = adapter != null
    val isEnabled: Boolean get() = adapter?.isEnabled == true

    /**
     * Сопряжённые устройства. Требует `BLUETOOTH_CONNECT` (запрашивается в UI).
     * Весь вызов обёрнут в runCatching: если разрешение не выдано/отозвано,
     * `adapter.bondedDevices` бросает SecurityException — возвращаем пустой список,
     * а не роняем приложение.
     */
    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<BluetoothDevice> =
        runCatching {
            adapter?.bondedDevices
                ?.sortedBy { d -> runCatching { d.name }.getOrNull() ?: d.address }
                .orEmpty()
        }.getOrDefault(emptyList())

    fun deviceByAddress(address: String): BluetoothDevice? =
        runCatching { adapter?.getRemoteDevice(address) }.getOrNull()

    /**
     * Запускает поиск видимых (ещё не сопряжённых) устройств. [onFound] вызывается
     * на каждое найденное устройство, [onFinished] — по завершении поиска.
     * Требует `BLUETOOTH_SCAN` (Android 12+) или геолокацию (до Android 12).
     *
     * Ищем ОБОИМИ способами сразу: классический инквайр (BR/EDR) находит только
     * SPP-адаптеры, а BLE-адаптеры (Vgate iCar Pro BLE, OBDLink CX, клоны на HM-10)
     * видны исключительно через LE-сканер. Раньше искали только классику — и такие
     * адаптеры не появлялись в списке вовсе.
     */
    @SuppressLint("MissingPermission")
    fun startDiscovery(onFound: (BluetoothDevice) -> Unit, onFinished: () -> Unit) {
        val a = adapter
        if (a == null) {
            onFinished()
            return
        }
        cancelDiscovery()
        startLeScan(a, onFound)
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
        // Без BLUETOOTH_SCAN (Android 12+) или геолокации startDiscovery бросает
        // SecurityException — не падаем, а честно завершаем поиск.
        val started = runCatching {
            appContext.registerReceiver(r, filter)
            if (a.isDiscovering) a.cancelDiscovery()
            a.startDiscovery()
        }.isSuccess
        if (!started) {
            runCatching { appContext.unregisterReceiver(r) }
            receiver = null
            onFinished()
        }
    }

    /**
     * LE-сканирование параллельно классическому инквайру. Идёт до [cancelDiscovery]:
     * своего «завершения» у него нет, поэтому окончанием поиска считается завершение
     * классического инквайра (он ограничен по времени системой).
     */
    @SuppressLint("MissingPermission")
    private fun startLeScan(a: BluetoothAdapter, onFound: (BluetoothDevice) -> Unit) {
        val scanner = runCatching { a.bluetoothLeScanner }.getOrNull() ?: return
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                runCatching { onFound(result.device) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { r -> runCatching { onFound(r.device) } }
            }
        }
        // LOW_LATENCY: адаптер должен найтись за секунды, а не за полминуты.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        // Без BLUETOOTH_SCAN startScan бросает SecurityException — не падаем, просто
        // остаёмся с классическим поиском.
        val started = runCatching { scanner.startScan(null, settings, cb) }.isSuccess
        leCallback = if (started) cb else null
    }

    /** Останавливает оба поиска (классический и LE) и снимает приёмник событий. */
    @SuppressLint("MissingPermission")
    fun cancelDiscovery() {
        runCatching { adapter?.cancelDiscovery() }
        leCallback?.let { cb ->
            runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) }
        }
        leCallback = null
        receiver?.let { r -> runCatching { appContext.unregisterReceiver(r) } }
        receiver = null
    }
}
