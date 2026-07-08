package ru.ngscanner.ui

import android.annotation.SuppressLint
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.ngscanner.bluetooth.BluetoothController
import ru.ngscanner.obd.Elm327
import ru.ngscanner.transport.ClassicSppTransport

enum class ConnectionState { Disconnected, Connecting, Connected }

data class DeviceUi(val name: String, val address: String)

data class UiState(
    val btEnabled: Boolean = false,
    val devices: List<DeviceUi> = emptyList(),
    val connection: ConnectionState = ConnectionState.Disconnected,
    val connectedName: String? = null,
    val reading: Boolean = false,
    val rpm: Int? = null,
    val coolantTemp: Int? = null,
    val error: String? = null,
)

/**
 * Управляет состоянием экрана: список сопряжённых адаптеров, подключение к
 * выбранному, инициализация ELM327 и чтение живых данных. Не знает про UI.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val controller = BluetoothController(app)
    private var transport: ClassicSppTransport? = null
    private var elm: Elm327? = null

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    @SuppressLint("MissingPermission") // разрешения запрашиваются в UI до вызова
    fun refreshDevices() {
        _ui.update {
            it.copy(
                btEnabled = controller.isEnabled,
                devices = controller.bondedDevices().map { d ->
                    DeviceUi(name = d.name ?: "Без имени", address = d.address)
                },
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        val device = controller.deviceByAddress(address) ?: return
        _ui.update { it.copy(connection = ConnectionState.Connecting, error = null) }
        viewModelScope.launch {
            try {
                val t = ClassicSppTransport(device)
                t.connect()
                val e = Elm327(t)
                e.initialize()
                transport = t
                elm = e
                _ui.update {
                    it.copy(
                        connection = ConnectionState.Connected,
                        connectedName = device.name ?: address,
                    )
                }
            } catch (ex: Exception) {
                transport?.close()
                transport = null
                elm = null
                _ui.update {
                    it.copy(connection = ConnectionState.Disconnected, error = ex.message)
                }
            }
        }
    }

    fun readLiveData() {
        val e = elm ?: return
        viewModelScope.launch {
            _ui.update { it.copy(reading = true, error = null) }
            try {
                val rpm = e.readEngineRpm()
                val temp = e.readCoolantTemp()
                _ui.update { it.copy(reading = false, rpm = rpm, coolantTemp = temp) }
            } catch (ex: Exception) {
                _ui.update { it.copy(reading = false, error = ex.message) }
            }
        }
    }

    fun disconnect() {
        transport?.close()
        transport = null
        elm = null
        _ui.update {
            it.copy(
                connection = ConnectionState.Disconnected,
                connectedName = null,
                rpm = null,
                coolantTemp = null,
            )
        }
    }

    override fun onCleared() {
        transport?.close()
    }
}
