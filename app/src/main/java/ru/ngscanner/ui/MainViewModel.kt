package ru.ngscanner.ui

import android.annotation.SuppressLint
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.ngscanner.agent.AgentEvent
import ru.ngscanner.agent.DiagnosticAgent
import ru.ngscanner.agent.ObdToolExecutor
import ru.ngscanner.bluetooth.BluetoothController
import ru.ngscanner.llm.ClaudeProvider
import ru.ngscanner.llm.CloudRuProvider
import ru.ngscanner.llm.LlmImage
import ru.ngscanner.llm.LlmMessage
import ru.ngscanner.llm.LlmModel
import ru.ngscanner.llm.LlmProvider
import ru.ngscanner.llm.ProviderId
import ru.ngscanner.obd.Elm327
import ru.ngscanner.obd.ObdPid
import ru.ngscanner.settings.AppSettings
import ru.ngscanner.transport.ClassicSppTransport

enum class ConnectionState { Disconnected, Connecting, Connected }

data class DeviceUi(val name: String, val address: String)

enum class ChatRole { USER, ASSISTANT, TOOL, SYSTEM }

data class ChatMessage(val role: ChatRole, val text: String)

sealed interface TestStatus {
    data object Success : TestStatus
    data class Error(val message: String) : TestStatus
}

data class UiState(
    // подключение к адаптеру
    val btEnabled: Boolean = false,
    val devices: List<DeviceUi> = emptyList(),
    val connection: ConnectionState = ConnectionState.Disconnected,
    val connectedName: String? = null,
    val metrics: Map<ObdPid, Double> = emptyMap(),
    val error: String? = null,
    // диагностика / чат с LLM
    val chat: List<ChatMessage> = emptyList(),
    val diagnosing: Boolean = false,
    // настройки провайдера
    val provider: ProviderId = ProviderId.CLAUDE,
    val model: String = AppSettings.DEFAULT_MODEL,
    val hasKey: Boolean = false,
    val apiKey: String = "",
    val testing: Boolean = false,
    val testStatus: TestStatus? = null,
    val availableModels: List<LlmModel> = emptyList(),
)

/**
 * Состояние экрана: подключение к адаптеру, периодический опрос параметров и
 * чат-диагностика через выбранный LLM-провайдер. Не зависит от Compose.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val controller = BluetoothController(app)
    private val settings = AppSettings(app)

    private var transport: ClassicSppTransport? = null
    private var elm: Elm327? = null
    private var pollJob: Job? = null
    private var llmHistory: List<LlmMessage> = emptyList()

    private val _ui = MutableStateFlow(
        UiState(
            provider = settings.provider,
            model = settings.model,
            hasKey = settings.apiKey(settings.provider).isNotBlank(),
            apiKey = settings.apiKey(settings.provider),
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
                startPolling()
            } catch (ex: Exception) {
                transport?.close()
                transport = null
                elm = null
                _ui.update { it.copy(connection = ConnectionState.Disconnected, error = ex.message) }
            }
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                val e = elm ?: break
                val values = LinkedHashMap<ObdPid, Double>()
                for (pid in ObdPid.entries) {
                    val v = runCatching { e.read(pid) }.getOrNull()
                    if (v != null) values[pid] = v
                }
                if (values.isNotEmpty()) _ui.update { it.copy(metrics = values) }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun disconnect() {
        stopPolling()
        transport?.close()
        transport = null
        elm = null
        _ui.update {
            it.copy(connection = ConnectionState.Disconnected, connectedName = null, metrics = emptyMap())
        }
    }

    // ---- Чат-диагностика ----

    fun sendMessage(text: String, images: List<LlmImage> = emptyList()) {
        if ((text.isBlank() && images.isEmpty()) || _ui.value.diagnosing) return
        val key = settings.apiKey(_ui.value.provider)
        if (key.isBlank()) {
            appendChat(ChatMessage(ChatRole.SYSTEM, "Укажите API-ключ в настройках."))
            return
        }
        val shown = if (images.isEmpty()) text else "📷 " + text.ifBlank { "(фото)" }
        appendChat(ChatMessage(ChatRole.USER, shown))
        _ui.update { it.copy(diagnosing = true) }

        val agent = DiagnosticAgent(buildProvider(_ui.value.provider, key), _ui.value.model, ObdToolExecutor(elm))
        viewModelScope.launch {
            try {
                llmHistory = agent.run(text, images, llmHistory) { event ->
                    val msg = when (event) {
                        is AgentEvent.Assistant -> ChatMessage(ChatRole.ASSISTANT, event.text)
                        is AgentEvent.ToolCall -> ChatMessage(ChatRole.TOOL, toolStatusText(event.name))
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

    /** Человекочитаемый статус вместо технического имени инструмента. */
    private fun toolStatusText(tool: String): String = when (tool) {
        "read_dtcs" -> "Читаю коды неисправностей…"
        "read_pending_dtcs" -> "Проверяю неподтверждённые коды…"
        "read_freeze_frame" -> "Смотрю freeze frame…"
        "read_live_data" -> "Считываю параметры двигателя…"
        "read_vehicle_info" -> "Читаю данные автомобиля (VIN)…"
        "list_supported_pids" -> "Определяю доступные параметры…"
        "get_connection_status" -> "Проверяю соединение…"
        "monitor_pid" -> "Наблюдаю за параметром…"
        "clear_dtcs" -> "Сбрасываю коды неисправностей…"
        else -> "Работаю…"
    }

    // ---- Настройки ----

    fun setProvider(p: ProviderId) {
        settings.provider = p
        _ui.update {
            it.copy(
                provider = p,
                model = settings.model,
                hasKey = settings.apiKey(p).isNotBlank(),
                apiKey = settings.apiKey(p),
                testStatus = null,
                availableModels = emptyList(),
            )
        }
    }

    fun setModel(m: String) {
        settings.model = m
        _ui.update { it.copy(model = m) }
    }

    /** Проверяет ключ запросом списка моделей; при успехе сохраняет ключ и модели. */
    fun testConnection(key: String) {
        if (key.isBlank() || _ui.value.testing) return
        settings.setApiKey(_ui.value.provider, key)
        _ui.update { it.copy(testing = true, testStatus = null) }
        viewModelScope.launch {
            try {
                val models = buildProvider(_ui.value.provider, key).availableModels()
                _ui.update { s ->
                    val keepModel = models.any { it.id == s.model }
                    val model = if (keepModel) s.model else models.firstOrNull()?.id ?: s.model
                    settings.model = model
                    s.copy(
                        testing = false,
                        testStatus = TestStatus.Success,
                        hasKey = true,
                        apiKey = key,
                        availableModels = models,
                        model = model,
                    )
                }
            } catch (ex: Exception) {
                _ui.update {
                    it.copy(testing = false, testStatus = TestStatus.Error(ex.message ?: "ошибка подключения"))
                }
            }
        }
    }

    override fun onCleared() {
        stopPolling()
        transport?.close()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 1500L
    }
}
