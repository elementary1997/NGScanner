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
import ru.ngscanner.garage.Car
import ru.ngscanner.garage.Garage
import ru.ngscanner.garage.GarageRepository
import ru.ngscanner.garage.LogEntry
import ru.ngscanner.garage.ModelNormsRepository
import ru.ngscanner.garage.VehicleCatalog
import ru.ngscanner.garage.VehicleSuggestion
import ru.ngscanner.garage.VinDecoder
import ru.ngscanner.garage.VinInfo
import ru.ngscanner.llm.ClaudeProvider
import ru.ngscanner.llm.LlmRequest
import ru.ngscanner.llm.LlmResponse
import ru.ngscanner.llm.Role
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
    val discovered: List<DeviceUi> = emptyList(),
    val scanning: Boolean = false,
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
    // гараж
    val garage: Garage = Garage(),
    val carSuggestions: List<VehicleSuggestion> = emptyList(),
    val vinDecoding: Boolean = false,
    val vinResult: VinInfo? = null,
    val vinError: String? = null,
    // модельные нормы параметров для активной машины: pidCmd -> текст нормы
    val modelNorms: Map<String, String> = emptyMap(),
    val normLoadingPid: String? = null,
)

/**
 * Состояние экрана: подключение к адаптеру, периодический опрос параметров и
 * чат-диагностика через выбранный LLM-провайдер. Не зависит от Compose.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val controller = BluetoothController(app)
    private val settings = AppSettings(app)
    private val garageRepo = GarageRepository(app)
    private val normsRepo = ModelNormsRepository(app)

    private var transport: ClassicSppTransport? = null
    private var elm: Elm327? = null
    private var pollJob: Job? = null
    private var llmHistory: List<LlmMessage> = emptyList()

    private val _ui = MutableStateFlow(
        garageRepo.load().let { garage ->
            UiState(
                provider = settings.provider,
                model = settings.model,
                hasKey = settings.apiKey(settings.provider).isNotBlank(),
                apiKey = settings.apiKey(settings.provider),
                garage = garage,
                modelNorms = garage.activeCar?.let { c -> normsRepo.normsFor(c.id) } ?: emptyMap(),
            )
        },
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
    fun startScan() {
        if (_ui.value.scanning) return
        _ui.update { it.copy(discovered = emptyList(), scanning = true) }
        controller.startDiscovery(
            onFound = { device ->
                val ui = DeviceUi(name = device.name ?: "Без имени", address = device.address)
                val bonded = _ui.value.devices.map { it.address }.toSet()
                _ui.update { s ->
                    if (s.discovered.any { it.address == ui.address } || ui.address in bonded) {
                        s
                    } else {
                        s.copy(discovered = s.discovered + ui)
                    }
                }
            },
            onFinished = { _ui.update { it.copy(scanning = false) } },
        )
    }

    fun stopScan() {
        controller.cancelDiscovery()
        _ui.update { it.copy(scanning = false) }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        val device = controller.deviceByAddress(address) ?: return
        controller.cancelDiscovery()
        _ui.update { it.copy(connection = ConnectionState.Connecting, error = null, scanning = false) }
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

        val executor = ObdToolExecutor(elm, saveNote = { note -> addSystemLogEntry(note) })
        val agent = DiagnosticAgent(buildProvider(_ui.value.provider, key), _ui.value.model, executor)
        viewModelScope.launch {
            try {
                llmHistory = agent.run(text, images, llmHistory, carContext()) { event ->
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

    /** Контекст активной машины для агента: паспорт + последние записи бортжурнала. */
    private fun carContext(): String? {
        val car = _ui.value.garage.activeCar ?: return null
        return buildString {
            append("Активный автомобиль пользователя: ${car.title}")
            car.spec.takeIf { it.isNotBlank() }?.let { append(", $it") }
            car.mileageKm?.let { append(", пробег ${it} км") }
            car.vin?.takeIf { it.isNotBlank() }?.let { append(", VIN $it") }
            append(".")
            val log = car.log.take(6)
            if (log.isNotEmpty()) {
                append("\nПоследние работы и наблюдения по этой машине:")
                log.forEach { e ->
                    val km = e.mileageKm?.let { " (${it} км)" } ?: ""
                    val who = if (e.bySystem) " [запись ассистента]" else ""
                    append("\n • ${e.dateIso}$km — ${e.text}$who")
                }
                append(
                    "\nУчитывай это: не предлагай проверять узлы, которые владелец уже заменил, " +
                        "первыми — ищи причину в более узком пространстве.",
                )
            }
        }
    }

    // ---- Гараж ----

    fun searchCars(query: String) {
        viewModelScope.launch {
            val suggestions = if (query.isBlank()) {
                emptyList()
            } else {
                runCatching { VehicleCatalog.search(getApplication(), query, limit = 12) }.getOrDefault(emptyList())
            }
            _ui.update { it.copy(carSuggestions = suggestions) }
        }
    }

    /** Сохраняет собранную в форме машину и делает её активной. */
    fun addCar(car: Car) {
        garageRepo.upsertCar(car)
        val garage = garageRepo.setActive(car.id)
        _ui.update {
            it.copy(
                garage = garage,
                carSuggestions = emptyList(),
                vinResult = null,
                vinError = null,
                modelNorms = normsRepo.normsFor(car.id),
            )
        }
    }

    /** Декодирует VIN через vPIC; результат кладёт в [UiState.vinResult]. */
    fun decodeVin(vin: String) {
        if (vin.isBlank() || _ui.value.vinDecoding) return
        _ui.update { it.copy(vinDecoding = true, vinError = null, vinResult = null) }
        viewModelScope.launch {
            val info = runCatching { VinDecoder.decode(vin) }.getOrNull()
            _ui.update {
                if (info != null) {
                    it.copy(vinDecoding = false, vinResult = info)
                } else {
                    it.copy(vinDecoding = false, vinError = "Не удалось распознать VIN. Проверьте номер или введите вручную.")
                }
            }
        }
    }

    fun clearVin() {
        _ui.update { it.copy(vinResult = null, vinError = null, vinDecoding = false) }
    }

    fun clearSuggestions() {
        _ui.update { it.copy(carSuggestions = emptyList()) }
    }

    fun setActiveCar(carId: String) {
        _ui.update { it.copy(garage = garageRepo.setActive(carId), modelNorms = normsRepo.normsFor(carId)) }
    }

    fun deleteCar(carId: String) {
        val garage = garageRepo.deleteCar(carId)
        _ui.update {
            it.copy(
                garage = garage,
                modelNorms = garage.activeCar?.let { c -> normsRepo.normsFor(c.id) } ?: emptyMap(),
            )
        }
    }

    /**
     * Узнаёт у модели норму параметра для активной машины и кэширует её.
     * Если норма уже в кэше или запрос идёт — ничего не делает.
     */
    fun requestNorm(pid: ObdPid) {
        val car = _ui.value.garage.activeCar ?: return
        val cmd = pid.cmd
        if (_ui.value.modelNorms.containsKey(cmd) || _ui.value.normLoadingPid == cmd) return
        val key = settings.apiKey(_ui.value.provider)
        if (key.isBlank()) return
        _ui.update { it.copy(normLoadingPid = cmd) }
        viewModelScope.launch {
            val norm = runCatching { fetchNorm(car, pid, key) }.getOrNull()
            if (norm != null) {
                normsRepo.setNorm(car.id, cmd, norm)
                _ui.update { it.copy(modelNorms = it.modelNorms + (cmd to norm), normLoadingPid = null) }
            } else {
                _ui.update { it.copy(normLoadingPid = null) }
            }
        }
    }

    private suspend fun fetchNorm(car: Car, pid: ObdPid, key: String): String? {
        val provider = buildProvider(_ui.value.provider, key)
        val system = "Ты — автомобильный справочник. Ответь ОДНОЙ короткой строкой: нормальный " +
            "диапазон значения параметра для указанной машины, с единицами измерения. Без пояснений " +
            "и вводных слов. Пример правильного ответа: 780–840 об/мин."
        val spec = car.spec.takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""
        val userMsg = "Автомобиль: ${car.title}$spec. Параметр: ${pid.label} (${pid.unit}). " +
            "Общая норма: ${pid.norm}. Укажи типичную норму именно для этой машины."
        val response = provider.send(
            LlmRequest(_ui.value.model, system, listOf(LlmMessage(Role.USER, content = userMsg)), emptyList()),
        )
        return when (response) {
            is LlmResponse.Final -> response.text.trim().lineSequence().firstOrNull()?.take(80)?.ifBlank { null }
            is LlmResponse.ToolUse -> response.text?.trim()?.take(80)?.ifBlank { null }
        }
    }

    fun addLogEntry(text: String, mileageKm: Int?) {
        val car = _ui.value.garage.activeCar ?: return
        if (text.isBlank()) return
        val entry = LogEntry(
            id = GarageRepository.newEntryId(),
            dateIso = java.time.LocalDate.now().toString(),
            mileageKm = mileageKm,
            text = text.trim(),
        )
        _ui.update { it.copy(garage = garageRepo.addEntry(car.id, entry)) }
    }

    /** Запись, которую агент сам сохраняет в бортжурнал (`bySystem = true`). */
    private fun addSystemLogEntry(text: String): Boolean {
        val car = _ui.value.garage.activeCar ?: return false
        if (text.isBlank()) return false
        val entry = LogEntry(
            id = GarageRepository.newEntryId(),
            dateIso = java.time.LocalDate.now().toString(),
            text = text.trim(),
            bySystem = true,
        )
        _ui.update { it.copy(garage = garageRepo.addEntry(car.id, entry)) }
        return true
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
        controller.cancelDiscovery()
        transport?.close()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 1500L
    }
}
