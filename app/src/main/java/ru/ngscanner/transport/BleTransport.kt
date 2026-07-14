package ru.ngscanner.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * Транспорт поверх Bluetooth Low Energy (GATT) для BLE-адаптеров ELM327 —
 * Vgate iCar Pro BLE, OBDLink CX, клоны на модулях HM-10 и им подобные.
 * Классический SPP ([ClassicSppTransport]) с такими адаптерами не работает вовсе:
 * они не поднимают RFCOMM.
 *
 * Почему это не «тот же код с другим сокетом»:
 * - **Нет потока байт.** Данные приходят пачками в колбэке `onCharacteristicChanged`;
 *   ответ собирается из нескольких уведомлений до приглашения `'>'`.
 * - **Одна GATT-операция за раз.** Стек Android молча теряет вторую параллельную
 *   запись, поэтому операции сериализованы ([opMutex]) и каждая ждёт свой колбэк.
 * - **MTU по умолчанию 23 байта** (20 полезных) — длинные команды режутся на пакеты.
 *
 * Разрешение `BLUETOOTH_CONNECT` (Android 12+) запрашивается в UI до [connect].
 */
@SuppressLint("MissingPermission")
class BleTransport(
    context: Context,
    private val device: BluetoothDevice,
) : ObdTransport {

    private val appContext = context.applicationContext

    override val kind = TransportKind.BLE

    @Volatile
    override var isConnected: Boolean = false
        private set

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    @Volatile
    private var mtu = DEFAULT_MTU

    // Накопитель принятого. Колбэк GATT пишет сюда, readResponse читает — разные потоки.
    private val rx = StringBuilder()
    private val rxLock = Any()

    // Одна GATT-операция за раз: стек Android не ставит их в очередь сам.
    private val opMutex = Mutex()

    private var connected: CompletableDeferred<Unit>? = null
    private var discovered: CompletableDeferred<Unit>? = null
    private var written: CompletableDeferred<Unit>? = null
    private var descriptorWritten: CompletableDeferred<Unit>? = null
    private var mtuChanged: CompletableDeferred<Unit>? = null

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> connected?.complete(Unit)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    isConnected = false
                    // Обрыв во время ожидания — будим ждущего, иначе он висит до таймаута.
                    val err = ObdTransportException("Связь с BLE-адаптером потеряна (status=$status)")
                    connected?.completeExceptionally(err)
                    discovered?.completeExceptionally(err)
                    written?.completeExceptionally(err)
                    descriptorWritten?.completeExceptionally(err)
                    mtuChanged?.completeExceptionally(err)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                discovered?.complete(Unit)
            } else {
                discovered?.completeExceptionally(ObdTransportException("Не удалось прочитать сервисы адаптера"))
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            mtuChanged?.complete(Unit) // не критично: продолжим на дефолтном MTU
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                written?.complete(Unit)
            } else {
                written?.completeExceptionally(ObdTransportException("Адаптер не принял команду (status=$status)"))
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                descriptorWritten?.complete(Unit)
            } else {
                descriptorWritten?.completeExceptionally(
                    ObdTransportException("Адаптер не включил уведомления (status=$status)"),
                )
            }
        }

        /** API 33+: значение приходит параметром. */
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = onRx(value)

        /** До API 33: значение читается из характеристики. */
        @Deprecated("Колбэк до API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            onRx(c.value ?: return)
        }
    }

    private fun onRx(bytes: ByteArray) {
        synchronized(rxLock) { rx.append(String(bytes, Charsets.US_ASCII)) }
    }

    override suspend fun connect() {
        try {
            connected = CompletableDeferred()
            val g = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw ObdTransportException("Не удалось открыть GATT-соединение")
            gatt = g
            withTimeout(CONNECT_TIMEOUT_MS) { connected!!.await() }

            // MTU просим до подписки: иначе длинные ответы режутся на 20-байтные куски.
            mtuChanged = CompletableDeferred()
            if (g.requestMtu(REQUESTED_MTU)) {
                runCatching { withTimeout(OP_TIMEOUT_MS) { mtuChanged!!.await() } }
            }

            discovered = CompletableDeferred()
            if (!g.discoverServices()) throw ObdTransportException("Не удалось запросить сервисы адаптера")
            withTimeout(OP_TIMEOUT_MS) { discovered!!.await() }

            findCharacteristics(g)
            enableNotifications(g)
            isConnected = true
        } catch (e: TimeoutCancellationException) {
            close()
            throw ObdTransportException("BLE-адаптер не отвечает — подойдите ближе и повторите", e)
        } catch (e: ObdTransportException) {
            close()
            throw e
        } catch (e: Exception) {
            close()
            throw ObdTransportException("Не удалось подключиться к BLE-адаптеру: ${e.message}", e)
        }
    }

    /**
     * Ищет пару характеристик «писать команду» / «получать ответ»: сначала по известным
     * UUID популярных адаптеров, затем эвристикой по свойствам (WRITE + NOTIFY) — клоны
     * плодят собственные UUID, и жёсткий список их не покрывает.
     */
    private fun findCharacteristics(g: BluetoothGatt) {
        for ((service, write, notify) in KNOWN_PROFILES) {
            val s = g.getService(service) ?: continue
            val w = s.getCharacteristic(write) ?: continue
            val n = s.getCharacteristic(notify) ?: continue
            writeChar = w
            notifyChar = n
            return
        }
        // Фолбэк: в одном сервисе — характеристика на запись и характеристика с
        // уведомлениями. Часто это одна и та же (HM-10: FFE1 умеет и то, и другое).
        for (s in g.services.orEmpty()) {
            val w = s.characteristics.orEmpty().firstOrNull { it.canWrite() }
            val n = s.characteristics.orEmpty().firstOrNull { it.canNotify() }
            if (w != null && n != null) {
                writeChar = w
                notifyChar = n
                return
            }
        }
        throw ObdTransportException(
            "Это BLE-устройство не похоже на ELM327: нет характеристик для обмена данными.",
        )
    }

    /** Подписка: мало включить уведомления локально — надо записать CCCD в сам адаптер. */
    private suspend fun enableNotifications(g: BluetoothGatt) {
        val n = notifyChar ?: throw ObdTransportException("Нет характеристики уведомлений")
        if (!g.setCharacteristicNotification(n, true)) {
            throw ObdTransportException("Не удалось включить уведомления адаптера")
        }
        val cccd = n.getDescriptor(CCCD) ?: return // редкие адаптеры обходятся без CCCD
        val value = if (n.canIndicate()) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        descriptorWritten = CompletableDeferred()
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, value)
        } else {
            cccd.value = value
            g.writeDescriptor(cccd)
        }
        withTimeout(OP_TIMEOUT_MS) { descriptorWritten!!.await() }
    }

    override suspend fun write(command: String) {
        val g = gatt ?: throw ObdTransportException("Нет активного соединения")
        val c = writeChar ?: throw ObdTransportException("Нет активного соединения")
        // Как и в SPP: сбрасываем хвост прошлого ответа, иначе следующая команда прочтёт
        // чужие байты и соответствие запрос→ответ сдвинется навсегда (frame desync).
        synchronized(rxLock) { rx.setLength(0) }
        val payload = (command + "\r").toByteArray(Charsets.US_ASCII)
        // Пакет ограничен MTU−3 (заголовок ATT).
        val chunk = (mtu - ATT_OVERHEAD).coerceAtLeast(MIN_CHUNK)
        for (part in payload.toList().chunked(chunk)) {
            opMutex.withLock {
                written = CompletableDeferred()
                val bytes = part.toByteArray()
                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // API 33+ возвращает BluetoothStatusCodes, а не GATT_SUCCESS
                    // (численно совпадают, но это разные наборы констант).
                    g.writeCharacteristic(c, bytes, writeTypeFor(c)) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        c.writeType = writeTypeFor(c)
                        c.value = bytes
                        g.writeCharacteristic(c)
                    }
                }
                if (!ok) {
                    isConnected = false
                    throw ObdTransportException("Связь с адаптером потеряна")
                }
                withTimeout(OP_TIMEOUT_MS) { written!!.await() }
            }
        }
    }

    override suspend fun readResponse(timeoutMs: Long): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            // Уважать отмену корутины (пауза опроса под диалог агента) — не ждать таймаут.
            currentCoroutineContext().ensureActive()
            if (synchronized(rxLock) { rx.indexOf(">") } >= 0) break
            if (!isConnected) throw ObdTransportException("Связь с адаптером потеряна")
            delay(POLL_MS)
        }
        return synchronized(rxLock) {
            val all = rx.toString()
            val end = all.indexOf('>')
            val answer = if (end >= 0) all.substring(0, end) else all
            // Разобранное съедаем вместе с приглашением; остаток достанется следующему
            // чтению — так же, как непрочитанные байты в потоковом SPP.
            rx.setLength(0)
            if (end >= 0 && end + 1 < all.length) rx.append(all, end + 1, all.length)
            answer.trim()
        }
    }

    override fun close() {
        isConnected = false
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        writeChar = null
        notifyChar = null
        synchronized(rxLock) { rx.setLength(0) }
    }

    /** Без подтверждения — быстрее и достаточно для ELM327; с подтверждением — если иначе никак. */
    private fun writeTypeFor(c: BluetoothGattCharacteristic): Int =
        if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

    private fun BluetoothGattCharacteristic.canWrite(): Boolean =
        properties and (
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
            ) != 0

    private fun BluetoothGattCharacteristic.canNotify(): Boolean =
        properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0

    private fun BluetoothGattCharacteristic.canIndicate(): Boolean =
        properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0

    companion object {
        private fun uuid(s: String): UUID = UUID.fromString(s)

        /** Стандартный дескриптор Client Characteristic Configuration. */
        private val CCCD = uuid("00002902-0000-1000-8000-00805F9B34FB")

        /**
         * Известные профили BLE-адаптеров ELM327: (сервис, запись, уведомления).
         * Порядок важен — сначала самые распространённые.
         */
        private val KNOWN_PROFILES: List<Triple<UUID, UUID, UUID>> = listOf(
            // HM-10 и клоны: одна характеристика и на запись, и на уведомления.
            Triple(
                uuid("0000FFE0-0000-1000-8000-00805F9B34FB"),
                uuid("0000FFE1-0000-1000-8000-00805F9B34FB"),
                uuid("0000FFE1-0000-1000-8000-00805F9B34FB"),
            ),
            // Vgate iCar Pro BLE, Konnwei и др.: раздельные характеристики.
            Triple(
                uuid("0000FFF0-0000-1000-8000-00805F9B34FB"),
                uuid("0000FFF2-0000-1000-8000-00805F9B34FB"),
                uuid("0000FFF1-0000-1000-8000-00805F9B34FB"),
            ),
            // Часть Vgate: сервис 18F0.
            Triple(
                uuid("000018F0-0000-1000-8000-00805F9B34FB"),
                uuid("00002AF1-0000-1000-8000-00805F9B34FB"),
                uuid("00002AF0-0000-1000-8000-00805F9B34FB"),
            ),
            // Nordic UART Service — у адаптеров на чипах nRF.
            Triple(
                uuid("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"),
                uuid("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"),
                uuid("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"),
            ),
        )

        private const val DEFAULT_MTU = 23
        private const val REQUESTED_MTU = 247
        private const val ATT_OVERHEAD = 3
        private const val MIN_CHUNK = 20
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val OP_TIMEOUT_MS = 8_000L
        private const val POLL_MS = 15L
    }
}
