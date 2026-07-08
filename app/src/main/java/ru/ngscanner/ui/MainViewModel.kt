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
import ru.ngscanner.agent.AgentEvent
import ru.ngscanner.agent.DiagnosticAgent
import ru.ngscanner.agent.ObdToolExecutor
import ru.ngscanner.bluetooth.BluetoothController
import ru.ngscanner.llm.ClaudeProvider
import ru.ngscanner.llm.CloudRuProvider
import ru.ngscanner.llm.LlmMessage
import ru.ngscanner.llm.LlmProvider
import ru.ngscanner.llm.ProviderId
import ru.ngscanner.obd.Elm327
import ru.ngscanner.settings.AppSettings
import ru.ngscanner.transport.ClassicSppTransport

enum class ConnectionState { Disconnected, Connecting, Connected }

data class DeviceUi(val name: String, val address: String)

enum class ChatRole { USER, ASSISTANT, TOOL, SYSTEM }

data class ChatMessage(val role: ChatRole, val text: String)

data class UiState(
    // подключение к адаптеру
    val btEnabled: Boolean = false,
    val devices: List<DeviceUi> = emptyList(),
    val connection: ConnectionState = ConnectionState.Disconnected,
    val connectedName: String? = null,
    val reading: Boolean = false,
    val rpm: Int? = null,
    val coolantTemp: Int? = null,
    val error: String? = null,
    // диагностика / чат с LLM
    val chat: List<ChatMessage> = emptyList(),
    val diagnosing: Boolean = false,
    // настройки провайдера
    val provider: ProviderId = ProviderId.CLAUDE,
    val model: String = AppSettings.DEFAULT_MODEL,
    val hasKey: Boolean = false,
)

/**
 * Состояние экрана: подключение к адаптеру, чтение живых данных и чат-диагностика
 * через выбранный LLM-провайдер. Не зависит от Compose.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val controller = BluetoothController(app)
    private val settings = AppSettings(app)

    private var transport: ClassicSppTransport? = null
    private var elm: Elm327? = null
    private var llmHistory: List<LlmMessage> = emptyList()

    private val _ui = MutableStateFlow(
        UiState(
            provider = settings.provider,
            model = settings.model,
            hasKey = settings.apiKey(settings.provider).isNotBlank(),
        ),
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    // ---- Подключение к адаптеру ----

    @SuppressLint("MissingPermission")
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
                    it.copy(connection = ConnectionState.Connected, connectedName = device.name ?: address)
                }
            } catch (ex: Exception) {
                transport?.close()
                transport = null
                elm = null
                _ui.update { it.copy(connection = ConnectionState.Disconnected, error = ex.message) }
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
            it.copy(connection = ConnectionState.Disconnected, connectedName = null, rpm = null, coolantTemp = null)
        }
    }

    // ---- Чат-диагностика ----

    fun sendMessage(text: String) {
        if (text.isBlank() || _ui.value.diagnosing) return
        val key = settings.apiKey(_ui.value.provider)
        if (key.isBlank()) {
            appendChat(ChatMessage(ChatRole.SYSTEM, "Укажите API-ключ в настройках."))
            return
        }
        appendChat(ChatMessage(ChatRole.USER, text))
        _ui.update { it.copy(diagnosing = true) }

        val provider = buildProvider(_ui.value.provider, key)
        val agent = DiagnosticAgent(provider, _ui.value.model, ObdToolExecutor(elm))

        viewModelScope.launch {
            try {
                llmHistory = agent.run(text, llmHistory) { event ->
                    val msg = when (event) {
                        is AgentEvent.Assistant -> ChatMessage(ChatRole.ASSISTANT, event.text)
                        is AgentEvent.ToolCall -> ChatMessage(ChatRole.TOOL, "🔧 ${event.name}")
                    }
                    appendChat(msg)
                }
            } catch (ex: Exception) {
                appendChat(ChatMessage(ChatRole.SYSTEM, "Ошибка: ${ex.message}"))
            } finally {
                _ui.update { it.copy(diagnosing = false) }
            }
        }
    }

    fun clearChat() {
        llmHistory = emptyList()
        _ui.update { it.copy(chat = emptyList()) }
    }

    private fun appendChat(message: ChatMessage) {
        _ui.update { it.copy(chat = it.chat + message) }
    }

    private fun buildProvider(id: ProviderId, key: String): LlmProvider = when (id) {
        ProviderId.CLAUDE -> ClaudeProvider(key)
        ProviderId.CLOUD_RU -> CloudRuProvider(key)
    }

    // ---- Настройки ----

    fun setProvider(p: ProviderId) {
        settings.provider = p
        _ui.update { it.copy(provider = p, model = settings.model, hasKey = settings.apiKey(p).isNotBlank()) }
    }

    fun setModel(m: String) {
        settings.model = m
        _ui.update { it.copy(model = m) }
    }

    fun setApiKey(key: String) {
        settings.setApiKey(_ui.value.provider, key)
        _ui.update { it.copy(hasKey = key.isNotBlank()) }
    }

    override fun onCleared() {
        transport?.close()
    }
}
