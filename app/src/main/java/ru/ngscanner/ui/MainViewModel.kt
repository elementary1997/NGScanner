package ru.ngscanner.ui

import android.annotation.SuppressLint
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
import ru.ngscanner.llm.ToolCall
import ru.ngscanner.llm.LlmException
import ru.ngscanner.obd.DtcDatabase
import ru.ngscanner.obd.Elm327
import ru.ngscanner.obd.ObdPid
import ru.ngscanner.service.ObdForegroundService
import ru.ngscanner.settings.AppSettings
import ru.ngscanner.settings.ChatRepository
import ru.ngscanner.settings.FavoritesRepository
import ru.ngscanner.settings.SessionSummary
import ru.ngscanner.transport.ClassicSppTransport

enum class ConnectionState { Disconnected, Connecting, Connected }

@kotlinx.serialization.Serializable
data class DeviceUi(val name: String, val address: String)

/** Точка истории параметра: значение и момент снятия (мс, System.currentTimeMillis). */
data class MetricSample(val tMs: Long, val value: Double)

@kotlinx.serialization.Serializable
enum class ChatRole { USER, ASSISTANT, TOOL, SYSTEM }

@kotlinx.serialization.Serializable
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
    val favorites: List<DeviceUi> = emptyList(),
    val scanning: Boolean = false,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val connectedName: String? = null,
    val metrics: Map<ObdPid, Double> = emptyMap(),
    // история значений с временными метками для графиков (последние точки на параметр)
    val history: Map<ObdPid, List<MetricSample>> = emptyMap(),
    val error: String? = null,
    // диагностика / чат с LLM
    val chat: List<ChatMessage> = emptyList(),
    val diagnosing: Boolean = false,
    // архив последних сессий диагностики (для восстановления)
    val sessions: List<SessionSummary> = emptyList(),
    // настройки провайдера
    val provider: ProviderId = ProviderId.CLAUDE,
    val model: String = AppSettings.DEFAULT_MODEL,
    val hasKey: Boolean = false,
    val apiKey: String = "",
    val testing: Boolean = false,
    val testStatus: TestStatus? = null,
    val availableModels: List<LlmModel> = emptyList(),
    // ключи хранятся в зашифрованном виде (false — редкий фолбэк на plaintext)
    val keysEncrypted: Boolean = true,
    // гараж
    val garage: Garage = Garage(),
    val carSuggestions: List<VehicleSuggestion> = emptyList(),
    val vinDecoding: Boolean = false,
    val vinResult: VinInfo? = null,
    val vinError: String? = null,
    // модельные нормы параметров для активной машины: pidCmd -> текст нормы
    val modelNorms: Map<String, String> = emptyMap(),
    val normLoadingPid: String? = null,
    // ожидается подтверждение сброса кодов (Mode 04)
    val clearDtcsPending: Boolean = false,
)

/** Данные, подгружаемые с диска при старте (в фоне) и вливаемые в [UiState]. */
private class LoadedState(
    val provider: ProviderId,
    val model: String,
    val hasKey: Boolean,
    val apiKey: String,
    val garage: Garage,
    val modelNorms: Map<String, String>,
    val chat: List<ChatMessage>,
    val sessions: List<SessionSummary>,
    val keysEncrypted: Boolean,
    val favorites: List<DeviceUi>,
    val history: List<LlmMessage>,
)

/**
 * Состояние экрана: подключение к адаптеру, периодический опрос параметров и
 * чат-диагностика через выбранный LLM-провайдер. Не зависит от Compose.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val controller = BluetoothController(app)
    // Ленивая инициализация: создание EncryptedSharedPreferences (Tink/keystore) и
    // разовая миграция тяжёлые — первый доступ идёт из фоновой init-корутины (IO).
    private val settings by lazy { AppSettings(app) }
    private val garageRepo = GarageRepository(app)
    private val normsRepo = ModelNormsRepository(app)
    private val chatRepo = ChatRepository(app)
    private val favoritesRepo = FavoritesRepository(app)

    private var transport: ClassicSppTransport? = null
    private var elm: Elm327? = null
    private var pollJob: Job? = null
    private var diagnoseJob: Job? = null
    private var searchJob: Job? = null
    private var clearConfirm: CompletableDeferred<Boolean>? = null
    private var supportedPids: Set<String> = emptySet()
    private var lastAddress: String? = null
    private var reconnecting = false
    private var llmHistory: List<LlmMessage> = emptyList()

    // Стартуем с пустого состояния и подгружаем сохранённые данные (JSON гаража/чата,
    // расшифровка ключей) в фоне — чтение с диска не должно блокировать главный поток.
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val garage = garageRepo.load()
                val provider = settings.provider
                val key = settings.apiKey(provider)
                LoadedState(
                    provider = provider,
                    model = settings.model,
                    hasKey = key.isNotBlank(),
                    apiKey = key,
                    garage = garage,
                    modelNorms = garage.activeCar?.let { c -> normsRepo.normsFor(c.id) } ?: emptyMap(),
                    chat = chatRepo.loadChat(),
                    sessions = chatRepo.sessions(),
                    keysEncrypted = settings.encrypted,
                    favorites = favoritesRepo.load(),
                    history = chatRepo.loadHistory(),
                )
            }
            if (llmHistory.isEmpty()) llmHistory = loaded.history
            // Вливаем сохранённые данные ТОЛЬКО в поля, которых пользователь ещё не
            // трогал (значение = стартовому пустому). Иначе действие, совершённое за
            // десятки мс загрузки (ввод ключа, тап по симптому, toggleFavorite,
            // addCar), было бы молча откачено снимком с диска.
            val empty = UiState()
            _ui.update { cur ->
                cur.copy(
                    provider = if (cur.provider == empty.provider) loaded.provider else cur.provider,
                    model = if (cur.model == empty.model) loaded.model else cur.model,
                    hasKey = if (cur.apiKey == empty.apiKey) loaded.hasKey else cur.hasKey,
                    apiKey = if (cur.apiKey == empty.apiKey) loaded.apiKey else cur.apiKey,
                    garage = if (cur.garage == empty.garage) loaded.garage else cur.garage,
                    modelNorms = if (cur.modelNorms == empty.modelNorms) loaded.modelNorms else cur.modelNorms,
                    chat = if (cur.chat == empty.chat) loaded.chat else cur.chat,
                    sessions = if (cur.sessions == empty.sessions) loaded.sessions else cur.sessions,
                    keysEncrypted = loaded.keysEncrypted, // не редактируется пользователем
                    favorites = if (cur.favorites == empty.favorites) loaded.favorites else cur.favorites,
                )
            }
        }
    }

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

    /** Добавляет/убирает адаптер из избранного (быстрое подключение на «Приборах»). */
    fun toggleFavorite(device: DeviceUi) {
        // Запись на диск — вне лямбды update{} (она может повториться на CAS-ретрае, а
        // toggle не идемпотентна) и на IO.
        viewModelScope.launch {
            val updated = withContext(Dispatchers.IO) { favoritesRepo.toggle(device) }
            _ui.update { it.copy(favorites = updated) }
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        val device = controller.deviceByAddress(address) ?: return
        controller.cancelDiscovery()
        _ui.update { it.copy(connection = ConnectionState.Connecting, error = null, scanning = false) }
        viewModelScope.launch {
            var fresh: ClassicSppTransport? = null
            try {
                val t = ClassicSppTransport(device)
                t.connect()
                fresh = t
                val e = Elm327(t)
                e.initialize()
                supportedPids = runCatching { e.readSupportedPids() }.getOrDefault(emptySet())
                transport = t
                elm = e
                lastAddress = address
                ObdForegroundService.start(getApplication(), device.name)
                _ui.update {
                    it.copy(connection = ConnectionState.Connected, connectedName = device.name ?: address)
                }
                startPolling()
            } catch (ex: Exception) {
                // Закрываем именно свежий сокет: если init упал уже после connect(),
                // дешёвый клон держит RFCOMM занятым и следующие подключения падают.
                fresh?.close()
                transport = null
                elm = null
                _ui.update { it.copy(connection = ConnectionState.Disconnected, error = ex.message) }
            }
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        // Опрашиваем только PID, которые авто реально поддерживает (по маскам 0100/0120/0140);
        // если определить не удалось — все, как фолбэк.
        val pids = ObdPid.entries.filter { supportedPids.isEmpty() || it.cmd in supportedPids }
        pollJob = viewModelScope.launch {
            var emptyStreak = 0
            var lastDataAt = System.currentTimeMillis()
            while (isActive) {
                val e = elm ?: break
                val values = LinkedHashMap<ObdPid, Double>()
                for (pid in pids) {
                    val v = runCatching { e.read(pid) }.getOrNull()
                    if (v != null) values[pid] = v
                }
                if (values.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    _ui.update { state ->
                        val history = state.history.toMutableMap()
                        values.forEach { (pid, v) ->
                            history[pid] = ((history[pid] ?: emptyList()) + MetricSample(now, v)).takeLast(HISTORY_SIZE)
                        }
                        // Мержим, а не заменяем: промах одного PID (норма для ELM327)
                        // не должен гасить остальные приборы в «—» до следующего цикла.
                        state.copy(metrics = state.metrics + values, history = history)
                    }
                    emptyStreak = 0
                    lastDataAt = now
                } else {
                    emptyStreak++
                }
                // Обрыв связи с адаптером — уходим в авто-переподключение.
                if (transport?.isConnected == false) {
                    attemptReconnect()
                    break
                }
                // Защита АКБ: ЭБУ давно не отвечает (зажигание выкл / забытый адаптер) — отключаемся.
                if (System.currentTimeMillis() - lastDataAt > BATTERY_GUARD_MS) {
                    autoDisconnectForBattery()
                    break
                }
                // Адаптивный интервал: пока данных нет — опрашиваем реже (шина и батарея).
                delay(if (emptyStreak >= IDLE_THRESHOLD) POLL_IDLE_MS else POLL_INTERVAL_MS)
            }
        }
    }

    private fun autoDisconnectForBattery() {
        disconnect()
        _ui.update {
            it.copy(
                error = "Адаптер отключён для защиты аккумулятора: ЭБУ долго не отвечал. " +
                    "Отсоедините ELM327 от разъёма OBD, если он не используется — иначе он посадит АКБ.",
            )
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun disconnect() {
        reconnecting = false
        lastAddress = null
        stopPolling()
        transport?.close()
        transport = null
        elm = null
        ObdForegroundService.stop(getApplication())
        _ui.update {
            it.copy(
                connection = ConnectionState.Disconnected,
                connectedName = null,
                metrics = emptyMap(),
                history = emptyMap(),
            )
        }
    }

    /** Пытается восстановить связь после обрыва с экспоненциальным бэкоффом. */
    @SuppressLint("MissingPermission")
    private fun attemptReconnect() {
        val address = lastAddress ?: return
        if (reconnecting) return
        reconnecting = true
        _ui.update { it.copy(connection = ConnectionState.Connecting, error = null) }
        viewModelScope.launch {
            var delayMs = RECONNECT_BASE_MS
            repeat(RECONNECT_ATTEMPTS) {
                delay(delayMs)
                if (!reconnecting) return@launch
                val device = controller.deviceByAddress(address)
                var fresh: ClassicSppTransport? = null
                val ok = device != null && runCatching {
                    transport?.close()
                    val t = ClassicSppTransport(device)
                    t.connect()
                    fresh = t
                    val e = Elm327(t)
                    e.initialize()
                    supportedPids = runCatching { e.readSupportedPids() }.getOrDefault(emptySet())
                    transport = t
                    elm = e
                    true
                }.getOrElse {
                    // Сбой init/чтения после connect() — закрываем свежий сокет, иначе
                    // занятый канал гарантированно проваливает остальные попытки.
                    fresh?.close()
                    false
                }
                if (ok) {
                    reconnecting = false
                    _ui.update {
                        it.copy(
                            connection = ConnectionState.Connected,
                            connectedName = device?.name ?: address,
                            error = null,
                        )
                    }
                    startPolling()
                    return@launch
                }
                delayMs = (delayMs * 2).coerceAtMost(RECONNECT_MAX_MS)
            }
            reconnecting = false
            transport = null
            elm = null
            ObdForegroundService.stop(getApplication())
            _ui.update {
                it.copy(
                    connection = ConnectionState.Disconnected,
                    connectedName = null,
                    metrics = emptyMap(),
                    error = "Связь с адаптером потеряна, переподключение не удалось. Проверьте адаптер и подключитесь заново.",
                )
            }
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

        val executor = ObdToolExecutor(
            elm,
            allowClearDtcs = { requestClearConfirm() },
            saveNote = { note -> addSystemLogEntry(note) },
            describeDtc = { code -> DtcDatabase.describe(getApplication(), code) },
        )
        val agent = DiagnosticAgent(buildProvider(_ui.value.provider, key), _ui.value.model, executor)
        // На время диалога опрос приборов ставим на паузу: half-duplex сокет ELM327
        // не должен обслуживать опрос и инструменты агента одновременно.
        val wasPolling = pollJob?.isActive == true
        stopPolling()
        diagnoseJob = viewModelScope.launch {
            try {
                llmHistory = trimLlmHistory(agent.run(text, images, llmHistory, carContext(), adapterConnected = elm != null) { event ->
                    val msg = when (event) {
                        is AgentEvent.Assistant -> ChatMessage(ChatRole.ASSISTANT, event.text)
                        is AgentEvent.ToolCall -> ChatMessage(ChatRole.TOOL, toolStatusText(event.name))
                    }
                    appendChat(msg)
                })
            } catch (ex: CancellationException) {
                appendChat(ChatMessage(ChatRole.SYSTEM, "Диагностика прервана."))
                throw ex
            } catch (ex: Exception) {
                appendChat(ChatMessage(ChatRole.SYSTEM, errorMessage(ex)))
            } finally {
                clearConfirm?.complete(false)
                clearConfirm = null
                _ui.update { it.copy(diagnosing = false, clearDtcsPending = false) }
                chatRepo.save(_ui.value.chat, llmHistory)
                if (wasPolling && elm != null) startPolling()
            }
        }
    }

    /** Прерывает текущую диагностику (в т.ч. зациклившийся на лимите шагов агент). */
    fun cancelDiagnosis() {
        diagnoseJob?.cancel()
    }

    /**
     * Офлайн-диагностика без ИИ и интернета: читает коды с адаптера, расшифровывает
     * по бортовой базе DTC и отмечает параметры вне нормы. Работает там, где нет сети.
     */
    fun localDiagnose() {
        val adapter = elm
        if (adapter == null) {
            appendChat(ChatMessage(ChatRole.SYSTEM, "Сначала подключите адаптер на вкладке «Приборы»."))
            return
        }
        if (_ui.value.diagnosing) return
        appendChat(ChatMessage(ChatRole.USER, "🔧 Локальная диагностика (без интернета)"))
        _ui.update { it.copy(diagnosing = true) }
        val wasPolling = pollJob?.isActive == true
        stopPolling()
        diagnoseJob = viewModelScope.launch {
            try {
                val executor = ObdToolExecutor(
                    adapter,
                    describeDtc = { code -> DtcDatabase.describe(getApplication(), code) },
                )
                val active = executor.execute(ToolCall("l1", "read_dtcs", "{}")).content
                val pending = executor.execute(ToolCall("l2", "read_pending_dtcs", "{}")).content
                appendChat(ChatMessage(ChatRole.ASSISTANT, buildLocalReport(active, pending, _ui.value.metrics)))
            } catch (ex: Exception) {
                appendChat(ChatMessage(ChatRole.SYSTEM, "Ошибка локальной диагностики: ${ex.message}"))
            } finally {
                _ui.update { it.copy(diagnosing = false) }
                chatRepo.save(_ui.value.chat, llmHistory)
                if (wasPolling && elm != null) startPolling()
            }
        }
    }

    private fun buildLocalReport(active: String, pending: String, metrics: Map<ObdPid, Double>): String {
        val sb = StringBuilder("**Локальная диагностика** — по бортовой базе, без ИИ\n\n")
        sb.append("**Коды неисправностей**\n").append(active).append("\n\n")
        if (!pending.contains("не обнаружены")) sb.append(pending).append("\n\n")

        val abnormal = metrics.filter { (pid, v) ->
            (pid.critHigh != null && v >= pid.critHigh) || (pid.warnHigh != null && v >= pid.warnHigh) ||
                (pid.critLow != null && v <= pid.critLow) || (pid.warnLow != null && v <= pid.warnLow)
        }
        if (abnormal.isNotEmpty()) {
            sb.append("**Параметры вне нормы**\n")
            abnormal.forEach { (pid, v) ->
                val value = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)
                sb.append("• ${pid.label}: $value ${pid.unit} (норма ${pid.norm})\n")
            }
            sb.append('\n')
        }
        sb.append("_Это офлайн-вывод по кодам и параметрам. Для развёрнутого разбора причин " +
            "включите интернет и запустите диагностику через ИИ._")
        return sb.toString()
    }

    /** Показывает диалог подтверждения сброса кодов и ждёт ответа пользователя. */
    private suspend fun requestClearConfirm(): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        clearConfirm = deferred
        _ui.update { it.copy(clearDtcsPending = true) }
        return deferred.await()
    }

    fun confirmClearDtcs(approved: Boolean) {
        _ui.update { it.copy(clearDtcsPending = false) }
        clearConfirm?.complete(approved)
        clearConfirm = null
    }

    /**
     * Ограничивает историю для модели, чтобы она не росла без предела (стоимость
     * и переполнение контекста). Отрезаем старое, но начинаем с хода пользователя
     * — иначе разрыв пары tool_use/tool_result вызовет 400 у провайдера.
     */
    private fun trimLlmHistory(history: List<LlmMessage>): List<LlmMessage> {
        if (history.size <= MAX_HISTORY_MSGS) return history
        var start = history.size - MAX_HISTORY_MSGS
        while (start < history.size && history[start].role != Role.USER) start++
        if (start < history.size) return history.drop(start)
        // В хвостовом окне нет хода пользователя — расширяем назад до последнего USER,
        // чтобы не начать с tool_result (провайдер вернёт 400). Нет USER вовсе —
        // отдаём историю целиком, не takeLast с разорванной парой tool_use/tool_result.
        val lastUser = history.indexOfLast { it.role == Role.USER }
        return if (lastUser >= 0) history.drop(lastUser) else history
    }

    private fun errorMessage(ex: Throwable): String =
        (ex as? LlmException ?: LlmException.from(ex)).userMessage()

    /** Начинает новый диалог: текущий (непустой) уходит в архив последних сессий. */
    fun clearChat() {
        val summaries = chatRepo.archive(
            _ui.value.chat,
            llmHistory,
            id = java.util.UUID.randomUUID().toString(),
            dateIso = java.time.LocalDate.now().toString(),
        )
        llmHistory = emptyList()
        chatRepo.clear()
        _ui.update { it.copy(chat = emptyList(), sessions = summaries) }
    }

    /** Восстанавливает архивную сессию как активный диалог (убирая её из архива). */
    fun restoreSession(id: String) {
        if (_ui.value.diagnosing) return
        val session = chatRepo.session(id) ?: return
        // Текущий непустой диалог не теряем — сначала архивируем его.
        if (_ui.value.chat.isNotEmpty()) {
            chatRepo.archive(
                _ui.value.chat,
                llmHistory,
                id = java.util.UUID.randomUUID().toString(),
                dateIso = java.time.LocalDate.now().toString(),
            )
        }
        val remaining = chatRepo.removeSession(id)
        llmHistory = session.history
        chatRepo.save(session.chat, session.history)
        _ui.update { it.copy(chat = session.chat, sessions = remaining) }
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
        searchJob?.cancel()
        if (query.isBlank()) {
            _ui.update { it.copy(carSuggestions = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS) // не ищем на каждое нажатие — ждём паузу ввода
            // Разбор каталога из assets — не на главном потоке.
            val suggestions = withContext(Dispatchers.IO) {
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

    /** Удаляет запись [entryId] из журнала машины [carId]. */
    fun deleteLogEntry(carId: String, entryId: String) {
        _ui.update { it.copy(garage = garageRepo.deleteEntry(carId, entryId)) }
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
                // Не показываем сырое английское тело ошибки — только понятный текст.
                _ui.update {
                    it.copy(testing = false, testStatus = TestStatus.Error(errorMessage(ex)))
                }
            }
        }
    }

    override fun onCleared() {
        stopPolling()
        controller.cancelDiscovery()
        transport?.close()
        ObdForegroundService.stop(getApplication())
    }

    companion object {
        private const val POLL_INTERVAL_MS = 1500L
        private const val POLL_IDLE_MS = 5000L
        private const val IDLE_THRESHOLD = 4
        private const val BATTERY_GUARD_MS = 5 * 60 * 1000L
        private const val RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_BASE_MS = 2000L
        private const val RECONNECT_MAX_MS = 16000L
        private const val HISTORY_SIZE = 240
        private const val MAX_HISTORY_MSGS = 40
        private const val SEARCH_DEBOUNCE_MS = 250L
    }
}
