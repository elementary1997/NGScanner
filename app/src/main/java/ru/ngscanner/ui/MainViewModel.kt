package ru.ngscanner.ui

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.util.Log
import android.widget.Toast
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
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.ngscanner.agent.AgentEvent
import ru.ngscanner.agent.DiagnosticAgent
import ru.ngscanner.agent.DiagnosticVerdict
import ru.ngscanner.agent.ObdToolExecutor
import ru.ngscanner.bluetooth.BluetoothController
import ru.ngscanner.garage.Car
import ru.ngscanner.garage.Garage
import ru.ngscanner.garage.GarageRepository
import ru.ngscanner.garage.LogEntry
import ru.ngscanner.garage.MaintenanceCalc
import ru.ngscanner.garage.MaintenanceInterval
import ru.ngscanner.garage.MaintenanceItem
import ru.ngscanner.garage.MaintenanceRepository
import ru.ngscanner.garage.ModelNormsRepository
import ru.ngscanner.service.MaintenanceNotifier
import java.time.LocalDate
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
import ru.ngscanner.obd.CustomPid
import ru.ngscanner.obd.CustomPidRepository
import ru.ngscanner.obd.DtcDatabase
import ru.ngscanner.obd.Elm327
import ru.ngscanner.obd.FuelCalc
import ru.ngscanner.obd.ObdParser
import ru.ngscanner.obd.ObdPid
import ru.ngscanner.obd.ObdProtocol
import ru.ngscanner.perf.PerfCalc
import ru.ngscanner.perf.PerfKind
import ru.ngscanner.perf.PerfPhase
import ru.ngscanner.perf.PerfRepository
import ru.ngscanner.perf.PerfRun
import ru.ngscanner.perf.PerfState
import ru.ngscanner.report.DiagnosticReport
import ru.ngscanner.report.ReportMeta
import ru.ngscanner.report.ReportRepository
import ru.ngscanner.report.buildTranscript
import ru.ngscanner.report.parseReport
import ru.ngscanner.report.toMarkdown
import ru.ngscanner.service.ObdForegroundService
import ru.ngscanner.settings.AppSettings
import ru.ngscanner.settings.ChatRepository
import ru.ngscanner.settings.FavoritesRepository
import ru.ngscanner.settings.ModelPrice
import ru.ngscanner.settings.ModelUsage
import ru.ngscanner.settings.SessionSummary
import ru.ngscanner.settings.UsageRepository
import ru.ngscanner.update.AppUpdater
import ru.ngscanner.update.UpdateInfo
import ru.ngscanner.transport.BleTransport
import ru.ngscanner.transport.ClassicSppTransport
import ru.ngscanner.transport.ObdTransport
import ru.ngscanner.trips.Trip
import ru.ngscanner.trips.TripKind
import ru.ngscanner.trips.TripMeta
import ru.ngscanner.trips.TripRepository
import ru.ngscanner.trips.TripSample
import ru.ngscanner.util.Exporter

enum class ConnectionState { Disconnected, Connecting, Connected }

/** Категория диагностического кода: активный (Mode 03), pending (07), permanent (0A). */
enum class DtcCategory { ACTIVE, PENDING, PERMANENT }

/** Диагностический код с расшифровкой из локальной базы и категорией. */
data class DtcItem(val code: String, val description: String?, val category: DtcCategory)

/** Одна строка снимка условий (freeze frame): подпись, значение, единицы. */
data class FreezeFrameParam(val label: String, val value: Double, val unit: String)

/**
 * Снимок условий в момент фиксации кода (Mode 02, кадр 00). По OBD-II снимок один и
 * привязан к ОДНОМУ коду [dtcCode] (Mode 02 PID 02). Пустой [params] — снимка нет.
 */
data class FreezeFrame(val dtcCode: String?, val params: List<FreezeFrameParam>)

@kotlinx.serialization.Serializable
data class DeviceUi(val name: String, val address: String)

/** Точка истории параметра: значение и момент снятия (мс, System.currentTimeMillis). */
data class MetricSample(val tMs: Long, val value: Double)

@kotlinx.serialization.Serializable
enum class ChatRole { USER, ASSISTANT, TOOL, SYSTEM }

/**
 * Сообщение чата. [verdict] непуст, когда агент завершил диагностику структурным
 * вердиктом — тогда UI рисует карточку с заземлением вместо простого текста.
 * Значение по умолчанию сохраняет совместимость с уже сохранёнными диалогами.
 */
@kotlinx.serialization.Serializable
data class ChatMessage(
    val role: ChatRole,
    val text: String,
    val verdict: DiagnosticVerdict? = null,
)

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
    // PID, которые реально поддерживает подключённый ЭБУ (пусто = показывать все)
    val supportedPids: Set<String> = emptySet(),
    // отвечает ли ЭБУ на запросы (данные идут) — отдельно от статуса адаптера
    val ecuResponding: Boolean = false,
    // определённый протокол связи (CAN 500k / ISO 14230 KWP…); null — ещё не определён
    val ecuProtocol: String? = null,
    // Желаемый протокол шины (AUTO = автоопределение адаптером) и результаты зонда.
    val obdProtocol: ObdProtocol = ObdProtocol.AUTO,
    val protocolProbing: Boolean = false,
    val protocolProbeStep: ObdProtocol? = null,
    val protocolProbeResult: List<ObdProtocol>? = null,
    // история значений с временными метками для графиков (последние точки на параметр)
    val history: Map<ObdPid, List<MetricSample>> = emptyMap(),
    // чтение кодов неисправностей (Mode 03/07/0A) для экрана «Коды»
    val dtcReading: Boolean = false,
    val dtcChecked: Boolean = false,
    val dtcItems: List<DtcItem> = emptyList(),
    val dtcError: String? = null,
    // Не-блокирующее предупреждение: часть режимов упала с ошибкой шины, но коды из
    // других режимов прочитаны и показаны (в отличие от dtcError, который прячет коды).
    val dtcWarning: String? = null,
    // Снимок условий (freeze frame) на экране кодов — по кнопке.
    val freezeFrame: FreezeFrame? = null,
    val freezeFrameReading: Boolean = false,
    val freezeFrameError: String? = null,
    // запись поездки: идёт ли, сохранённые поездки/события, выбор параметров панели графиков
    val recording: Boolean = false,
    val tripMetas: List<TripMeta> = emptyList(),
    // выбранные для панели графиков PID (пусто = показывать все с историей)
    val graphPids: List<String> = emptyList(),
    // Выбор и порядок PID для дашборда (пустой = дефолтная раскладка).
    val dashboardPids: List<String> = emptyList(),
    // Расход топлива: мгновенный (л/100км при движении, л/ч на месте) и за текущую поездку.
    val instantLper100: Double? = null,
    val instantLperH: Double = 0.0,
    val tripFuelLiters: Double = 0.0,
    val tripDistanceKm: Double = 0.0,
    val tripAvgLper100: Double? = null,
    val fuelPrice: Double = 0.0,
    val error: String? = null,
    // диагностика / чат с LLM
    val chat: List<ChatMessage> = emptyList(),
    val diagnosing: Boolean = false,
    // текущий шаг агента (Проверяю соединение / Читаю коды…) — сменяется на месте,
    // а не копится строками в чате; null → показываем общий «Диагностирую…»
    val agentStatus: String? = null,
    // прикреплённое к вводу фото (в ViewModel — переживает поворот экрана)
    val pendingImage: LlmImage? = null,
    // архив последних сессий диагностики (для восстановления)
    val sessions: List<SessionSummary> = emptyList(),
    // настройки провайдера
    val provider: ProviderId = ProviderId.CLAUDE,
    val model: String = AppSettings.DEFAULT_MODEL,
    // Только признак наличия ключа. Сам ключ НЕ держим в наблюдаемом состоянии
    // (логи/скриншоты/дампы StateFlow не должны его раскрывать) — путь запроса
    // читает ключ напрямую из настроек, поле ввода сеет разово (см. currentApiKey).
    val hasKey: Boolean = false,
    val testing: Boolean = false,
    val testStatus: TestStatus? = null,
    val availableModels: List<LlmModel> = emptyList(),
    // ключи хранятся в зашифрованном виде (false — редкий фолбэк на plaintext)
    val keysEncrypted: Boolean = true,
    // --- Настройки поведения ---
    val batteryGuard: Boolean = true,
    val keepScreenOn: Boolean = true,
    val updateCheck: Boolean = true,
    val pollIntervalMs: Long = AppSettings.DEFAULT_POLL_MS,
    // --- Обновление приложения ---
    val appVersion: String = "",
    val updateInfo: UpdateInfo? = null, // непусто, если найдена более новая версия
    val updateChecking: Boolean = false,
    val updateStatus: String? = null, // человекочитаемый статус проверки/загрузки
    // расход токенов: за сессию приложения (сумма) и помодельно по провайдеру + цены
    val sessionTokens: Int = 0,
    val modelUsage: List<ModelUsage> = emptyList(),
    val modelPrices: Map<String, ModelPrice> = emptyMap(),
    // гараж
    val garage: Garage = Garage(),
    val carSuggestions: List<VehicleSuggestion> = emptyList(),
    val vinDecoding: Boolean = false,
    val vinResult: VinInfo? = null,
    val vinError: String? = null,
    // Модели марки из каталога — подсказка, когда VIN дал марку, но не модель.
    val vinModelOptions: List<VehicleSuggestion> = emptyList(),
    // VIN, считанный прямо с ЭБУ (Mode 09): подставляется в поле формы добавления по VIN.
    val vinScanning: Boolean = false,
    val ecuVin: String? = null,
    // модельные нормы параметров для активной машины: pidCmd -> текст нормы
    val modelNorms: Map<String, String> = emptyMap(),
    // Вычисленные позиции ТО активной машины (интервал + остаток + срочность).
    val carMaintenance: List<MaintenanceItem> = emptyList(),
    // Протоколы диагностики (все машины; в UI фильтруются по car.id).
    val reports: List<ReportMeta> = emptyList(),
    val reportGenerating: Boolean = false,
    val reportError: String? = null,
    // Перф-замеры: текущий (kind непусто = замер открыт) и сохранённые заезды.
    val perfKind: PerfKind? = null,
    val perfState: PerfState? = null,
    val perfRuns: List<PerfRun> = emptyList(),
    // Заводские PID пользователя и их последние значения (id -> значение).
    val customPids: List<CustomPid> = emptyList(),
    val customValues: Map<String, Double> = emptyMap(),
    val normLoadingPid: String? = null,
    // ожидается подтверждение сброса кодов (Mode 04)
    val clearDtcsPending: Boolean = false,
)

/** Данные, подгружаемые с диска при старте (в фоне) и вливаемые в [UiState]. */
private class LoadedState(
    val provider: ProviderId,
    val model: String,
    val hasKey: Boolean,
    val garage: Garage,
    val modelNorms: Map<String, String>,
    val maintenance: List<MaintenanceItem>,
    val chat: List<ChatMessage>,
    val sessions: List<SessionSummary>,
    val keysEncrypted: Boolean,
    val favorites: List<DeviceUi>,
    val modelUsage: List<ModelUsage>,
    val modelPrices: Map<String, ModelPrice>,
    val history: List<LlmMessage>,
    val graphPids: List<String>,
    val dashboardPids: List<String>,
    val tripMetas: List<TripMeta>,
    val batteryGuard: Boolean,
    val keepScreenOn: Boolean,
    val updateCheck: Boolean,
    val pollIntervalMs: Long,
    val appVersion: String,
    val fuelPrice: Double,
    val reports: List<ReportMeta>,
    val perfRuns: List<PerfRun>,
    val customPids: List<CustomPid>,
    val obdProtocol: ObdProtocol,
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
    private val maintenanceRepo = MaintenanceRepository(app)
    private val chatRepo = ChatRepository(app)
    private val favoritesRepo = FavoritesRepository(app)
    private val usageRepo = UsageRepository(app)
    private val tripRepo = TripRepository(app)
    private val reportRepo = ReportRepository(app)
    private val perfRepo = PerfRepository(app)
    private val customPidRepo = CustomPidRepository(app)

    // Запись поездки: буфер синхронных снимков и её старт; чёрный ящик — детект
    // перехода параметров в критическую зону для сохранения окна вокруг события.
    private val tripBuffer = ArrayList<TripSample>()
    private var tripStartMs = 0L

    // Инкрементальный интеграл расхода и дистанции за поездку — источник истины (в
    // отличие от tripBuffer, который прореживается и занизил бы результат).
    private var accFuelLiters = 0.0
    private var accDistanceKm = 0.0
    private var lastFuelMs: Long? = null
    private val recordedPids = LinkedHashSet<String>()
    private var prevCritical = emptySet<ObdPid>()

    private var transport: ObdTransport? = null
    private var elm: Elm327? = null
    private var pollJob: Job? = null
    private var diagnoseJob: Job? = null
    private var searchJob: Job? = null
    private var perfJob: Job? = null
    private val favoritesMutex = Mutex() // упорядочивает запись избранного на диск
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
                    garage = garage,
                    modelNorms = garage.activeCar?.let { c -> normsRepo.normsFor(c.id) } ?: emptyMap(),
                    maintenance = garage.activeCar?.let { c ->
                        MaintenanceCalc.computeAll(maintenanceRepo.intervalsFor(c.id), c.mileageKm, LocalDate.now())
                    } ?: emptyList(),
                    chat = chatRepo.loadChat(),
                    sessions = chatRepo.sessions(),
                    keysEncrypted = settings.encrypted,
                    favorites = favoritesRepo.load(),
                    modelUsage = usageRepo.models(provider),
                    modelPrices = usageRepo.prices(),
                    history = chatRepo.loadHistory(),
                    graphPids = settings.graphPids,
                    dashboardPids = settings.dashboardPids,
                    tripMetas = tripRepo.metas(),
                    batteryGuard = settings.batteryGuard,
                    keepScreenOn = settings.keepScreenOn,
                    updateCheck = settings.updateCheck,
                    pollIntervalMs = settings.pollIntervalMs,
                    appVersion = AppUpdater.currentVersion(app),
                    fuelPrice = settings.fuelPrice,
                    reports = reportRepo.metas(),
                    perfRuns = perfRepo.runs(),
                    customPids = customPidRepo.all(),
                    obdProtocol = ObdProtocol.fromCode(settings.obdProtocol),
                )
            }
            if (llmHistory.isEmpty()) llmHistory = loaded.history
            // Вливаем сохранённые данные ТОЛЬКО в поля, которых пользователь ещё не
            // трогал (значение = стартовому пустому). Иначе действие, совершённое за
            // десятки мс загрузки (ввод ключа, тап по симптому, toggleFavorite,
            // addCar), было бы молча откачено снимком с диска.
            val empty = UiState()
            _ui.update { cur ->
                // Парные поля вливаем группой, иначе можно подтянуть устаревшее
                // companion-значение (modelNorms старой машины; hasKey для чужого ключа).
                // hasKey заменяет прежний признак «пользователь трогал ключ»: если ключ
                // уже установлен в состоянии (сохранён за время загрузки) — не откатываем.
                val loadProvider = cur.provider == empty.provider && !cur.hasKey
                val loadGarage = cur.garage == empty.garage
                cur.copy(
                    provider = if (loadProvider) loaded.provider else cur.provider,
                    // Модель можно сменить независимо (setModel без смены провайдера) —
                    // отдельный гард, чтобы ранний выбор не затёрся снимком с диска.
                    model = if (cur.model == empty.model) loaded.model else cur.model,
                    hasKey = if (loadProvider) loaded.hasKey else cur.hasKey,
                    modelUsage = if (loadProvider) loaded.modelUsage else cur.modelUsage,
                    modelPrices = if (cur.modelPrices == empty.modelPrices) loaded.modelPrices else cur.modelPrices,
                    garage = if (loadGarage) loaded.garage else cur.garage,
                    modelNorms = if (loadGarage) loaded.modelNorms else cur.modelNorms,
                    carMaintenance = if (loadGarage) loaded.maintenance else cur.carMaintenance,
                    chat = if (cur.chat == empty.chat) loaded.chat else cur.chat,
                    sessions = if (cur.sessions == empty.sessions) loaded.sessions else cur.sessions,
                    keysEncrypted = loaded.keysEncrypted, // не редактируется пользователем
                    favorites = if (cur.favorites == empty.favorites) loaded.favorites else cur.favorites,
                    graphPids = if (cur.graphPids == empty.graphPids) loaded.graphPids else cur.graphPids,
                    dashboardPids = if (cur.dashboardPids == empty.dashboardPids) loaded.dashboardPids else cur.dashboardPids,
                    tripMetas = if (cur.tripMetas == empty.tripMetas) loaded.tripMetas else cur.tripMetas,
                    reports = if (cur.reports == empty.reports) loaded.reports else cur.reports,
                    perfRuns = if (cur.perfRuns == empty.perfRuns) loaded.perfRuns else cur.perfRuns,
                    customPids = if (cur.customPids == empty.customPids) loaded.customPids else cur.customPids,
                    obdProtocol = if (cur.obdProtocol == empty.obdProtocol) loaded.obdProtocol else cur.obdProtocol,
                    // Настройки поведения и версия: тумблеры редко трогают за время загрузки,
                    // а сеттеры пишут и на диск, и в состояние — вливаем значения с диска.
                    batteryGuard = loaded.batteryGuard,
                    keepScreenOn = loaded.keepScreenOn,
                    updateCheck = loaded.updateCheck,
                    pollIntervalMs = loaded.pollIntervalMs,
                    appVersion = loaded.appVersion,
                    fuelPrice = loaded.fuelPrice,
                )
            }
            // Проверка новой версии при запуске (если включена в настройках).
            if (loaded.updateCheck) checkForUpdate(manual = false)
            // Напоминание о ТО при запуске (если есть просроченные/скорые).
            checkMaintenanceAndNotify()
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
        // Атомарно считаем новый список из состояния (CAS updateAndGet — двойной тап не
        // теряется), затем персистим результат на IO. Источник истины — состояние, а не
        // диск, поэтому нет гонки load→modify→save.
        val updated = _ui.updateAndGet { st ->
            val favs = if (st.favorites.any { it.address == device.address }) {
                st.favorites.filterNot { it.address == device.address }
            } else {
                st.favorites + device
            }
            st.copy(favorites = favs)
        }.favorites
        // Персист под Mutex — запись строго в порядке тапов (без гонки диска и состояния).
        viewModelScope.launch {
            favoritesMutex.withLock { withContext(Dispatchers.IO) { favoritesRepo.save(updated) } }
        }
    }

    /**
     * Выбирает транспорт по типу устройства — ровно та точка расширения, ради которой
     * заведён [ObdTransport] (ADR 0001): BLE-адаптеры (Vgate iCar Pro BLE, OBDLink CX,
     * клоны на HM-10) не поднимают RFCOMM, а классические не говорят по GATT. Для
     * DUAL предпочитаем классику: SPP надёжнее и быстрее GATT.
     */
    @SuppressLint("MissingPermission")
    private fun buildTransport(device: BluetoothDevice): ObdTransport {
        val isLeOnly = runCatching { device.type == BluetoothDevice.DEVICE_TYPE_LE }.getOrDefault(false)
        return if (isLeOnly) BleTransport(getApplication(), device) else ClassicSppTransport(device)
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        val device = controller.deviceByAddress(address) ?: return
        controller.cancelDiscovery()
        _ui.update { it.copy(connection = ConnectionState.Connecting, error = null, scanning = false) }
        viewModelScope.launch {
            var fresh: ObdTransport? = null
            try {
                val t = buildTransport(device)
                t.connect()
                fresh = t
                val e = Elm327(t, ObdProtocol.fromCode(settings.obdProtocol))
                e.initialize()
                supportedPids = runCatching { e.readSupportedPids() }.getOrDefault(emptySet())
                transport = t
                elm = e
                lastAddress = address
                ObdForegroundService.start(getApplication(), device.name)
                _ui.update {
                    it.copy(
                        connection = ConnectionState.Connected,
                        connectedName = device.name ?: address,
                        // Показываем только поддерживаемые PID (+ напряжение через ATRV).
                        // Пусто = определить не удалось, тогда дашборд покажет все.
                        supportedPids = if (supportedPids.isEmpty()) emptySet() else supportedPids + ObdPid.VOLTAGE.cmd,
                        ecuResponding = false,
                        ecuProtocol = null,
                    )
                }
                startTripRecording()
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
                // Данные ОТ ЭБУ (реальные OBD-ответы) — по ним судим, отвечает ли ЭБУ.
                val ecuValues = LinkedHashMap<ObdPid, Double>()
                for (pid in pids) {
                    runCatching { e.read(pid) }.getOrNull()?.let { ecuValues[pid] = it }
                }
                // Заводские PID пользователя (мода 21/22): отдельная карта — они не входят
                // в каталог ObdPid и не участвуют в графиках/поездках/чёрном ящике.
                val custom = LinkedHashMap<String, Double>()
                for (cp in _ui.value.customPids) {
                    runCatching { e.readCustom(cp) }.getOrNull()?.let { custom[cp.id] = it }
                }
                // Напряжение — командой адаптера ATRV; работает и БЕЗ ЭБУ, поэтому в
                // признак «ЭБУ отвечает» не входит, но на дашборд/в историю попадает.
                val merged = LinkedHashMap(ecuValues)
                runCatching { e.readAdapterVoltage() }.getOrNull()?.let { merged[ObdPid.VOLTAGE] = it }
                if (custom.isNotEmpty()) _ui.update { it.copy(customValues = it.customValues + custom) }
                val now = System.currentTimeMillis()
                if (merged.isNotEmpty()) {
                    _ui.update { state ->
                        val history = state.history.toMutableMap()
                        merged.forEach { (pid, v) ->
                            history[pid] = ((history[pid] ?: emptyList()) + MetricSample(now, v)).takeLast(HISTORY_SIZE)
                        }
                        // Мержим, а не заменяем: промах одного PID (норма для ELM327)
                        // не должен гасить остальные приборы в «—» до следующего цикла.
                        state.copy(metrics = state.metrics + merged, history = history)
                    }
                    recordTripSample(now, merged)
                    checkBlackBox(now, merged)
                }
                if (ecuValues.isNotEmpty()) {
                    // ЭБУ на связи: статус + определяем протокол один раз, когда заговорил.
                    val proto = if (_ui.value.ecuProtocol == null) runCatching { e.protocolName() }.getOrNull() else null
                    _ui.update { it.copy(ecuResponding = true, ecuProtocol = it.ecuProtocol ?: proto) }
                    emptyStreak = 0
                    lastDataAt = now
                } else {
                    emptyStreak++
                    // Несколько пустых циклов подряд — ЭБУ молчит (зажигание выкл / шина).
                    if (emptyStreak >= 2 && _ui.value.ecuResponding) _ui.update { it.copy(ecuResponding = false) }
                    // Один раз пробуем перезапустить автоопределение протокола: ELM-клон
                    // мог «залипнуть» на неудачном автопоиске и упорно отдавать NO DATA.
                    if (emptyStreak == PROTOCOL_RETRY_STREAK && transport?.isConnected == true) {
                        runCatching { e.resetProtocol() }
                    }
                }
                // Обрыв связи с адаптером — уходим в авто-переподключение.
                if (transport?.isConnected == false) {
                    attemptReconnect()
                    break
                }
                // Защита АКБ: ЭБУ давно не отвечает (зажигание выкл / забытый адаптер) —
                // отключаемся, если защита включена в настройках (можно выключить для
                // длительного мониторинга на заведённом двигателе).
                if (settings.batteryGuard && System.currentTimeMillis() - lastDataAt > BATTERY_GUARD_MS) {
                    autoDisconnectForBattery()
                    break
                }
                // Адаптивный интервал: пока данных нет — опрашиваем реже (шина и батарея).
                // Базовый интервал берём из настроек (экономный/обычный/быстрый).
                delay(if (emptyStreak >= IDLE_THRESHOLD) POLL_IDLE_MS else settings.pollIntervalMs)
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

    // ---- Запись поездок и «чёрный ящик» ----

    /** Начинает запись новой поездки (при подключении). Реконнект её не сбрасывает. */
    private fun startTripRecording() {
        tripBuffer.clear()
        recordedPids.clear()
        tripStartMs = System.currentTimeMillis()
        accFuelLiters = 0.0
        accDistanceKm = 0.0
        lastFuelMs = null
        prevCritical = emptySet()
        _ui.update { it.copy(recording = true) }
    }

    /** Добавляет снимок в буфер поездки; при переполнении прореживает вдвое. */
    private fun recordTripSample(now: Long, values: Map<ObdPid, Double>) {
        if (!_ui.value.recording) return
        // Расход: инкрементально интегрируем из MAF + скорость (+λ) по фактическому dt.
        val maf = values[ObdPid.MAF]
        val speed = values[ObdPid.SPEED]
        val lambda = values[ObdPid.O2_LAMBDA]
        lastFuelMs?.let { prev ->
            val (df, dd) = FuelCalc.step(maf, speed, (now - prev) / 1000.0, lambda)
            accFuelLiters += df
            accDistanceKm += dd
        }
        lastFuelMs = now
        val instant = if (maf != null && speed != null) FuelCalc.instantLper100(maf, speed, lambda) else null
        _ui.update {
            it.copy(
                instantLper100 = instant,
                instantLperH = maf?.let { m -> FuelCalc.litersPerHour(m, lambda) } ?: 0.0,
                tripFuelLiters = accFuelLiters,
                tripDistanceKm = accDistanceKm,
                tripAvgLper100 = FuelCalc.avgLper100(accFuelLiters, accDistanceKm),
            )
        }
        values.keys.forEach { recordedPids.add(it.name) }
        tripBuffer.add(TripSample(now, values.mapKeys { it.key.name }))
        if (tripBuffer.size > MAX_TRIP_SAMPLES) {
            val thinned = tripBuffer.filterIndexed { i, _ -> i % 2 == 0 }
            tripBuffer.clear(); tripBuffer.addAll(thinned)
        }
    }

    /** Сохраняет поездку при отключении, если она достаточно длинная. */
    private fun finishTripRecording() {
        val trip = takeTripSnapshot() ?: return
        viewModelScope.launch {
            val metas = withContext(Dispatchers.IO) { tripRepo.save(trip) }
            _ui.update { it.copy(tripMetas = metas) }
        }
    }

    /**
     * Снимает накопленную поездку для сохранения: опустошает буфер и сбрасывает флаг
     * записи, возвращает готовый [Trip] или `null` (запись не шла / поездка коротка).
     * Отделено от персиста, чтобы onCleared мог сохранить синхронно, когда
     * viewModelScope уже отменяется.
     */
    private fun takeTripSnapshot(): Trip? {
        if (!_ui.value.recording) return null
        val samples = ArrayList(tripBuffer)
        val start = tripStartMs
        val pids = recordedPids.toList()
        val carTitle = _ui.value.garage.activeCar?.title
        tripBuffer.clear()
        recordedPids.clear()
        _ui.update { it.copy(recording = false) }
        if (samples.size < MIN_TRIP_SAMPLES) return null // слишком короткая — не сохраняем
        return Trip(
            id = TripRepository.newId(), kind = TripKind.TRIP,
            startMs = start, endMs = samples.last().t,
            carTitle = carTitle, pids = pids, samples = samples,
            fuelLiters = accFuelLiters, distanceKm = accDistanceKm,
        )
    }

    /** Детект перехода параметра в критическую зону → сохранение окна события. */
    private fun checkBlackBox(now: Long, values: Map<ObdPid, Double>) {
        val critical = values.filterKeys { true }.filter { (pid, v) -> isCritical(pid, v) }.keys
        val fresh = critical - prevCritical // только переходы норма→критично
        prevCritical = critical
        fresh.forEach { pid -> scheduleEventSave(pid, now) }
    }

    private fun isCritical(pid: ObdPid, v: Double): Boolean =
        (pid.critHigh != null && v >= pid.critHigh) || (pid.critLow != null && v <= pid.critLow)

    /** Дособирает «хвост» после аномалии и вырезает окно [t−30с…t+30с] в событие. */
    private fun scheduleEventSave(pid: ObdPid, triggerMs: Long) {
        val carTitle = _ui.value.garage.activeCar?.title
        viewModelScope.launch {
            delay(EVENT_POST_MS)
            val window = tripBuffer.filter { it.t in (triggerMs - EVENT_PRE_MS)..(triggerMs + EVENT_POST_MS) }
            if (window.size < 3) return@launch
            val metas = withContext(Dispatchers.IO) {
                tripRepo.save(
                    Trip(
                        id = TripRepository.newId(), kind = TripKind.EVENT,
                        startMs = window.first().t, endMs = window.last().t,
                        carTitle = carTitle, trigger = "${pid.label} — критично",
                        pids = recordedPids.toList(), samples = window,
                    ),
                )
            }
            _ui.update { it.copy(tripMetas = metas) }
        }
    }

    /** Обновляет список записей (для экрана «Поездки и события»). */
    fun refreshTrips() {
        viewModelScope.launch {
            val metas = withContext(Dispatchers.IO) { tripRepo.metas() }
            _ui.update { it.copy(tripMetas = metas) }
        }
    }

    fun deleteTrip(id: String) {
        viewModelScope.launch {
            val metas = withContext(Dispatchers.IO) { tripRepo.delete(id) }
            _ui.update { it.copy(tripMetas = metas) }
        }
    }

    /** Полная запись для просмотра графиков поездки. */
    suspend fun loadTrip(id: String): Trip? = withContext(Dispatchers.IO) { tripRepo.load(id) }

    /** Экспортирует запись в CSV и открывает системный «Поделиться». */
    fun exportTripCsv(id: String) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val uri = withContext(Dispatchers.IO) {
                val trip = tripRepo.load(id) ?: return@withContext null
                runCatching { Exporter.buildTripCsv(app, trip) }.getOrElse { Log.w(TAG, "Экспорт CSV не удался", it); null }
            }
            if (uri != null) Exporter.shareCsv(app, uri)
            else Toast.makeText(app, "Не удалось экспортировать поездку", Toast.LENGTH_SHORT).show()
        }
    }

    /** Настройка набора параметров для панели графиков (пустой список = показывать все). */
    fun setGraphPids(pids: List<String>) {
        settings.graphPids = pids
        _ui.update { it.copy(graphPids = pids) }
    }

    /** Набор и порядок PID для дашборда (пустой список = дефолтная раскладка). */
    fun setDashboardPids(pids: List<String>) {
        settings.dashboardPids = pids
        _ui.update { it.copy(dashboardPids = pids) }
    }

    /** Цена топлива, ₽/л (для оценки стоимости поездок; 0 = не задана). */
    fun setFuelPrice(price: Double) {
        settings.fuelPrice = price
        _ui.update { it.copy(fuelPrice = price) }
    }

    // ---- Настройки поведения ----

    fun setBatteryGuard(enabled: Boolean) {
        settings.batteryGuard = enabled
        _ui.update { it.copy(batteryGuard = enabled) }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        settings.keepScreenOn = enabled
        _ui.update { it.copy(keepScreenOn = enabled) }
    }

    fun setUpdateCheck(enabled: Boolean) {
        settings.updateCheck = enabled
        _ui.update { it.copy(updateCheck = enabled) }
    }

    fun setPollIntervalMs(ms: Long) {
        settings.pollIntervalMs = ms
        _ui.update { it.copy(pollIntervalMs = ms) }
    }

    // ---- Обновление приложения ----

    /**
     * Проверяет наличие новой версии. [manual] — запущено кнопкой (показываем статус
     * «актуальная версия»/ошибку). При автопроверке на старте, если версия новее,
     * кладём в состояние и шлём системное уведомление.
     */
    fun checkForUpdate(manual: Boolean) {
        if (_ui.value.updateChecking) return
        _ui.update { it.copy(updateChecking = true, updateStatus = if (manual) "Проверяю обновления…" else it.updateStatus) }
        viewModelScope.launch {
            val info = runCatching { AppUpdater.checkLatest(getApplication()) }.getOrNull()
            _ui.update {
                when {
                    info != null && info.newer ->
                        it.copy(updateChecking = false, updateInfo = info, updateStatus = null)
                    manual && info != null ->
                        it.copy(updateChecking = false, updateInfo = null, updateStatus = "Установлена актуальная версия.")
                    manual ->
                        it.copy(updateChecking = false, updateStatus = "Не удалось проверить обновления (нет сети или релизов).")
                    else -> it.copy(updateChecking = false)
                }
            }
            if (info != null && info.newer && !manual) {
                AppUpdater.notifyAvailable(getApplication(), info)
            }
        }
    }

    /** Качает найденное обновление и запускает системный установщик. */
    fun installUpdate() {
        val info = _ui.value.updateInfo ?: return
        _ui.update { it.copy(updateStatus = "Загружаю обновление…") }
        viewModelScope.launch {
            val ok = runCatching { AppUpdater.downloadAndInstall(getApplication(), info) }.isSuccess
            _ui.update { it.copy(updateStatus = if (ok) "Открываю установщик…" else "Не удалось скачать обновление.") }
        }
    }

    fun dismissUpdateStatus() {
        _ui.update { it.copy(updateStatus = null) }
    }

    fun disconnect() {
        reconnecting = false
        // Агент-диагност захватил ссылку на elm в момент запроса — без отмены он
        // продолжил бы дёргать инструменты через закрытый сокет и жечь токены API.
        diagnoseJob?.cancel()
        // Замер тоже держит elm в тесном цикле — иначе продолжит читать закрытый сокет.
        perfJob?.cancel()
        perfJob = null
        lastAddress = null
        stopPolling()
        finishTripRecording() // сохраняем поездку до сброса состояния
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
                supportedPids = emptySet(),
                ecuResponding = false,
                ecuProtocol = null,
                perfKind = null,
                perfState = null,
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
                var fresh: ObdTransport? = null
                val ok = device != null && runCatching {
                    transport?.close()
                    val t = buildTransport(device)
                    t.connect()
                    fresh = t
                    val e = Elm327(t, ObdProtocol.fromCode(settings.obdProtocol))
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
            finishTripRecording() // связь потеряна окончательно — сохраняем накопленную поездку
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
        _ui.update { it.copy(diagnosing = true, pendingImage = null, agentStatus = null) }

        // Фиксируем провайдера и модель запроса: пока идёт диалог, их смена в UI
        // заблокирована, но расход всё равно пишем на ту модель, что дала ответ.
        val reqProvider = _ui.value.provider
        val reqModel = _ui.value.model

        val executor = ObdToolExecutor(
            elm,
            allowClearDtcs = { requestClearConfirm() },
            saveNote = { note -> addSystemLogEntry(note) },
            describeDtc = { code -> DtcDatabase.describe(getApplication(), code) },
        )
        val agent = DiagnosticAgent(buildProvider(reqProvider, key), reqModel, executor)
        // На время диалога опрос приборов ставим на паузу: half-duplex сокет ELM327
        // не должен обслуживать опрос и инструменты агента одновременно.
        val wasPolling = pollJob?.isActive == true
        stopPolling()
        diagnoseJob = viewModelScope.launch {
            try {
                llmHistory = trimLlmHistory(agent.run(text, images, llmHistory, carContext(), adapterConnected = elm != null, connectionInfo = connectionInfo()) { event ->
                    when (event) {
                        // Ответ модели остаётся в истории; сбрасываем статус — дальше она «думает».
                        is AgentEvent.Assistant -> {
                            appendChat(ChatMessage(ChatRole.ASSISTANT, event.text))
                            _ui.update { it.copy(agentStatus = null) }
                        }
                        // Шаг инструмента — не строка в чате, а сменяющийся статус под спиннером.
                        is AgentEvent.ToolCall -> _ui.update { it.copy(agentStatus = toolStatusText(event.name)) }
                        // Структурный вердикт: текст кладём для истории/экспорта, а UI
                        // рисует по verdict карточку с проверенным заземлением.
                        is AgentEvent.Verdict -> {
                            appendChat(
                                ChatMessage(
                                    ChatRole.ASSISTANT,
                                    event.verdict.toPlainText(),
                                    verdict = event.verdict,
                                ),
                            )
                            _ui.update { it.copy(agentStatus = null) }
                        }
                        is AgentEvent.Usage -> recordUsage(reqProvider, reqModel, event.prompt, event.completion)
                    }
                })
            } catch (ex: CancellationException) {
                appendChat(ChatMessage(ChatRole.SYSTEM, "Диагностика прервана."))
                throw ex
            } catch (ex: Exception) {
                appendChat(ChatMessage(ChatRole.SYSTEM, errorMessage(ex)))
            } finally {
                clearConfirm?.complete(false)
                clearConfirm = null
                _ui.update { it.copy(diagnosing = false, clearDtcsPending = false, agentStatus = null) }
                chatRepo.save(_ui.value.chat, llmHistory)
                if (wasPolling && elm != null) startPolling()
            }
        }
    }

    /** Прикрепляет закодированное фото к вводу (хранится в состоянии до отправки). */
    fun attachImage(image: LlmImage) {
        _ui.update { it.copy(pendingImage = image) }
    }

    /** Убирает прикреплённое фото. */
    fun clearPendingImage() {
        _ui.update { it.copy(pendingImage = null) }
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
        if (_ui.value.diagnosing || _ui.value.vinScanning) return // не пересекаемся со сканом VIN на half-duplex шине
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
                val permanent = executor.execute(ToolCall("l3", "read_permanent_dtcs", "{}")).content
                val readiness = executor.execute(ToolCall("l4", "read_readiness", "{}")).content
                appendChat(ChatMessage(ChatRole.ASSISTANT, buildLocalReport(active, pending, permanent, readiness, _ui.value.metrics)))
            } catch (ex: Exception) {
                appendChat(ChatMessage(ChatRole.SYSTEM, "Ошибка локальной диагностики: ${ex.message}"))
            } finally {
                _ui.update { it.copy(diagnosing = false) }
                chatRepo.save(_ui.value.chat, llmHistory)
                if (wasPolling && elm != null) startPolling()
            }
        }
    }

    private fun buildLocalReport(
        active: String,
        pending: String,
        permanent: String,
        readiness: String,
        metrics: Map<ObdPid, Double>,
    ): String {
        val sb = StringBuilder("**Локальная диагностика** — по бортовой базе, без ИИ\n\n")
        sb.append("**Коды неисправностей**\n").append(active).append("\n\n")
        if (!pending.contains("не обнаружены")) sb.append(pending).append("\n\n")
        // Постоянные коды показываем только если они реально есть (не стёрты сбросом).
        if (!permanent.contains("не обнаружены") && !permanent.contains("NO DATA") &&
            !permanent.contains("НЕТ СВЯЗИ")) {
            sb.append(permanent).append("\n\n")
        }
        // Готовность мониторов — важна перед ТО и после сброса кодов.
        if (readiness.startsWith("Готовность")) sb.append(readiness).append("\n\n")

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

    // ---- Экран кодов неисправностей ----

    /** Читает коды всех типов (Mode 03/07/0A), расшифровывает по локальной базе. */
    fun readDtcCodes() {
        val e = elm ?: run {
            _ui.update { it.copy(dtcError = "Адаптер не подключён — подключитесь на вкладке «Приборы».", dtcChecked = true) }
            return
        }
        if (_ui.value.dtcReading || _ui.value.diagnosing || _ui.value.vinScanning) return
        runDtcOp { readAllDtc(e) }
    }

    /** Сброс кодов (Mode 04) из экрана «Коды» — вызывать только после подтверждения в UI. */
    fun clearDtcCodes() {
        val e = elm ?: return
        if (_ui.value.dtcReading || _ui.value.diagnosing) return
        runDtcOp { withContext(Dispatchers.IO) { e.command("04") }; readAllDtc(e) }
    }

    /**
     * Читает снимок условий (freeze frame, Mode 02 кадр 00) на экране кодов — по кнопке.
     * Логика чтения та же, что у агента (ObdToolExecutor.readFreezeFrame): снимок один,
     * привязан к одному DTC. Пауза опроса на время чтения (half-duplex шина).
     */
    fun readFreezeFrame() {
        val e = elm ?: return
        if (_ui.value.freezeFrameReading || _ui.value.dtcReading || _ui.value.diagnosing) return
        _ui.update { it.copy(freezeFrameReading = true, freezeFrameError = null) }
        val wasPolling = pollJob?.isActive == true
        stopPolling()
        viewModelScope.launch {
            try {
                val (frame, err) = withContext(Dispatchers.IO) {
                    val pids = listOf(
                        ObdPid.RPM, ObdPid.COOLANT, ObdPid.SPEED, ObdPid.ENGINE_LOAD,
                        ObdPid.THROTTLE, ObdPid.STFT, ObdPid.MAF,
                    )
                    val params = mutableListOf<FreezeFrameParam>()
                    for (p in pids) {
                        val suffix = p.cmd.removePrefix("01")
                        // 00 — номер сохранённого кадра (единственный снимок).
                        val data = ObdParser.freezeFrameBytes(e.command("02${suffix}00"), suffix)
                        val value = data?.let { runCatching { p.decode(it) }.getOrNull() }
                        if (value != null) params.add(FreezeFrameParam(p.label, value, p.unit))
                    }
                    val dtcCode = ObdParser.freezeFrameDtc(e.command("020200"))
                    if (params.isEmpty()) {
                        null to "Снимок недоступен: нет сохранённого freeze frame (или активных кодов)."
                    } else {
                        FreezeFrame(dtcCode, params) to null
                    }
                }
                _ui.update { it.copy(freezeFrame = frame, freezeFrameReading = false, freezeFrameError = err) }
            } catch (ex: Exception) {
                _ui.update { it.copy(freezeFrameReading = false, freezeFrameError = "Ошибка чтения снимка: ${ex.message}") }
            } finally {
                if (wasPolling && elm != null) startPolling()
            }
        }
    }

    /** Общая обвязка операций с кодами: пауза опроса, флаги, восстановление. */
    private fun runDtcOp(op: suspend () -> Pair<List<DtcItem>, List<String>>) {
        // Снимок сбрасываем: после Mode 04 ЭБУ его стирает, при перечитывании старый неактуален.
        _ui.update { it.copy(dtcReading = true, dtcError = null, dtcWarning = null, freezeFrame = null, freezeFrameError = null) }
        val wasPolling = pollJob?.isActive == true
        stopPolling()
        viewModelScope.launch {
            try {
                val (items, failedModes) = op()
                // Жёсткая ошибка — только когда ничего не прочитано И была ошибка шины.
                // Если часть режимов упала, но коды из других есть — показываем коды
                // и НЕ-блокирующее предупреждение, чтобы не потерять, например, факт
                // «постоянные коды прочитать не удалось».
                val hardError = failedModes.isNotEmpty() && items.isEmpty()
                _ui.update {
                    it.copy(
                        dtcReading = false, dtcChecked = true, dtcItems = items,
                        dtcError = if (hardError) {
                            "Нет связи с ЭБУ (ошибка шины). Проверьте зажигание и разъём OBD."
                        } else {
                            null
                        },
                        dtcWarning = if (!hardError && failedModes.isNotEmpty()) {
                            "Часть кодов прочитать не удалось (ошибка шины): ${failedModes.joinToString(", ")}. " +
                                "Показаны только успешно прочитанные — отсутствие остальных не значит, что там пусто."
                        } else {
                            null
                        },
                    )
                }
            } catch (ex: Exception) {
                _ui.update { it.copy(dtcReading = false, dtcChecked = true, dtcError = "Ошибка: ${ex.message}") }
            } finally {
                if (wasPolling && elm != null) startPolling()
            }
        }
    }

    /** Читает коды всех трёх режимов; второй элемент — список режимов с ошибкой шины. */
    private suspend fun readAllDtc(e: Elm327): Pair<List<DtcItem>, List<String>> = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val isCan = e.isCan()
        val hl = e.headerHexLen()
        val out = mutableListOf<DtcItem>()
        val failed = mutableListOf<String>()
        suspend fun read(cmd: String, header: String, cat: DtcCategory, label: String) {
            when (val r = ObdParser.parseDtcs(e.command(cmd), isCan, hl, header)) {
                is ObdParser.DtcResult.Ok -> r.codes.forEach { out.add(DtcItem(it, DtcDatabase.describe(app, it), cat)) }
                ObdParser.DtcResult.BusError -> failed.add(label)
                else -> {}
            }
        }
        read("03", "43", DtcCategory.ACTIVE, "активные")
        read("07", "47", DtcCategory.PENDING, "неподтверждённые")
        read("0A", "4A", DtcCategory.PERMANENT, "постоянные")
        out.toList() to failed.toList()
    }

    /** Отправляет агенту запрос разобрать причины и ремонт по конкретному коду. */
    fun explainDtc(item: DtcItem) {
        val desc = item.description?.let { " ($it)" } ?: ""
        sendMessage(
            "Диагностический код ${item.code}$desc обнаружен на моей машине. Объясни простыми " +
                "словами вероятные причины, как проверить и как устранить.",
        )
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
    private fun trimLlmHistory(history: List<LlmMessage>): List<LlmMessage> =
        trimHistory(history, MAX_HISTORY_MSGS)

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

    /**
     * Учитывает израсходованные токены помодельно: сессия (сумма) + расход модели.
     * Провайдер и модель передаём явно — те, что дали ответ (могли смениться в UI,
     * пока шёл запрос), а список moded обновляем только если это активный провайдер.
     */
    private fun recordUsage(provider: ProviderId, model: String, prompt: Int, completion: Int) {
        val tokens = prompt + completion
        if (tokens <= 0) return
        val updated = usageRepo.add(provider, model, prompt, completion)
        _ui.update {
            it.copy(
                sessionTokens = it.sessionTokens + tokens,
                modelUsage = if (provider == it.provider) updated else it.modelUsage,
            )
        }
    }

    /** Собирает PDF из ответа и открывает «Поделиться»; ошибку I/O показываем тостом. */
    fun exportMessagePdf(text: String) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val uri = withContext(Dispatchers.IO) {
                runCatching { Exporter.buildPdf(app, "Ответ NG Scanner", text, text.hashCode().toLong()) }
                    .getOrElse { Log.w(TAG, "Экспорт PDF не удался", it); null }
            }
            if (uri != null) Exporter.sharePdf(app, uri)
            else Toast.makeText(app, "Не удалось создать PDF", Toast.LENGTH_SHORT).show()
        }
    }

    /** Сбрасывает помодельный счётчик расхода текущего провайдера. */
    fun resetUsage() {
        if (_ui.value.diagnosing) return // идёт запрос — иначе строка воскреснет следующим usage-тиком
        usageRepo.reset(_ui.value.provider)
        _ui.update { it.copy(sessionTokens = 0, modelUsage = emptyList()) }
    }

    /** Задаёт цены модели (₽ за 1 млн входных/выходных токенов) для оценки суммы расхода. */
    fun setModelPrice(model: String, input: Double, output: Double) {
        _ui.update { it.copy(modelPrices = usageRepo.setPrice(model, input, output)) }
    }

    /** Убирает конкретную модель из учёта расходов текущего провайдера. */
    fun removeModelUsage(model: String) {
        if (_ui.value.diagnosing) return // идёт запрос — удалённая модель вернулась бы следующим тиком
        val updated = usageRepo.removeModel(_ui.value.provider, model)
        _ui.update { it.copy(modelUsage = updated) }
    }

    private fun buildProvider(id: ProviderId, key: String): LlmProvider = when (id) {
        ProviderId.CLAUDE -> ClaudeProvider(key)
        ProviderId.CLOUD_RU -> CloudRuProvider(key)
    }

    /** Контекст активной машины для агента: паспорт + последние записи бортжурнала. */
    /**
     * Состояние связи для промпта агента: протокол и отвечает ли ЭБУ. Заземляет
     * модель — чтобы при NO DATA она не советовала «переключить на CAN» (автовыбор
     * уже включён), а сначала проверила зажигание.
     */
    private fun connectionInfo(): String? {
        if (elm == null) return null
        val proto = _ui.value.ecuProtocol?.let { "протокол $it" } ?: "протокол ещё определяется"
        return if (_ui.value.ecuResponding) {
            "Связь с автомобилем: адаптер подключён, $proto, ЭБУ отвечает — живые данные читаются."
        } else {
            "Связь с автомобилем: адаптер подключён ($proto), но ЭБУ СЕЙЧАС не отвечает на OBD-запросы " +
                "(данные и коды не читаются, NO DATA). ВАЖНО: автоопределение протокола включено (ATSP0), " +
                "протокол менять НЕ нужно и советовать это НЕ надо. Самая вероятная причина — зажигание не в " +
                "положении ON или двигатель заглушён; попроси пользователя включить зажигание/завести мотор и " +
                "повторить, прежде чем делать любые выводы о неисправностях."
        }
    }

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
        // Дедуп по VIN: повторный скан того же авто не должен плодить вторую пустую
        // запись (иначе активной станет она, а бортжурнал/история осядут в первой).
        val vin = car.vin?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        val existing = vin?.let { v -> _ui.value.garage.cars.firstOrNull { it.vin?.trim()?.uppercase() == v } }
        val toSave = if (existing != null) car.copy(id = existing.id, log = existing.log) else car
        garageRepo.upsertCar(toSave)
        val garage = garageRepo.setActive(toSave.id)
        _ui.update {
            it.copy(
                garage = garage,
                carSuggestions = emptyList(),
                vinResult = null,
                vinError = null,
                modelNorms = normsRepo.normsFor(toSave.id),
            )
        }
        recomputeMaintenance()
    }

    /** Декодирует VIN (офлайн для РФ, vPIC для иномарок); результат кладёт в [UiState.vinResult]. */
    fun decodeVin(vin: String) {
        if (vin.isBlank() || _ui.value.vinDecoding) return
        _ui.update { it.copy(vinDecoding = true, vinError = null, vinResult = null, vinModelOptions = emptyList()) }
        viewModelScope.launch {
            val info = runCatching { VinDecoder.decode(vin) }.getOrNull()
            if (info == null) {
                _ui.update { it.copy(vinDecoding = false, vinError = "Не удалось распознать VIN. Проверьте номер или введите вручную.") }
                return@launch
            }
            // Модель не определилась (частый случай для РФ-VIN) — подсказываем модели
            // этой марки из офлайн-каталога, чтобы пользователь выбрал, а не печатал.
            val options = if (info.model.isBlank()) {
                runCatching { VehicleCatalog.modelsForMake(getApplication(), info.make) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            _ui.update { it.copy(vinDecoding = false, vinResult = info, vinModelOptions = options) }
        }
    }

    /**
     * Читает VIN прямо с ЭБУ (Mode 09 PID 02) и, если удалось, сразу декодирует его,
     * заполняя форму добавления машины. Опрос приборов на это время приостанавливаем:
     * ELM327 half-duplex, параллельные обращения смешивают кадры.
     */
    fun readVinFromEcu() {
        val adapter = elm
        if (adapter == null) {
            _ui.update { it.copy(vinError = "Адаптер не подключён — подключитесь на вкладке «Приборы».") }
            return
        }
        if (_ui.value.vinScanning || _ui.value.diagnosing) return
        _ui.update { it.copy(vinScanning = true, vinError = null, ecuVin = null) }
        val wasPolling = pollJob?.isActive == true
        stopPolling()
        viewModelScope.launch {
            try {
                val raw = withContext(Dispatchers.IO) { adapter.command("0902") }
                val vin = ObdParser.parseVin(raw, adapter.headerHexLen())
                if (vin != null) {
                    _ui.update { it.copy(vinScanning = false, ecuVin = vin) }
                    decodeVin(vin) // сразу распознаём марку/модель/год
                } else {
                    // NO DATA = ЭБУ не поддерживает Mode 09 (частое на ЭБУ Нивы/ВАЗ и
                    // других российских). Это не сбой — просто вводим VIN вручную.
                    val noData = raw.uppercase().let { "NO DATA" in it || "NODATA" in it }
                    _ui.update {
                        it.copy(
                            vinScanning = false,
                            vinError = if (noData) {
                                "Этот ЭБУ не отдаёт VIN по OBD (Mode 09 не поддерживается — обычное дело для ВАЗ/Нивы). Введите VIN вручную."
                            } else {
                                "Не удалось прочитать VIN (ответ: ${raw.trim().take(40)}). Введите вручную."
                            },
                        )
                    }
                }
            } catch (ex: Exception) {
                _ui.update { it.copy(vinScanning = false, vinError = "Ошибка чтения VIN: ${ex.message}") }
            } finally {
                if (wasPolling && elm != null) startPolling()
            }
        }
    }

    fun clearVin() {
        _ui.update { it.copy(vinResult = null, vinError = null, vinDecoding = false, ecuVin = null) }
    }

    fun clearSuggestions() {
        _ui.update { it.copy(carSuggestions = emptyList()) }
    }

    fun setActiveCar(carId: String) {
        _ui.update { it.copy(garage = garageRepo.setActive(carId), modelNorms = normsRepo.normsFor(carId)) }
        recomputeMaintenance()
    }

    fun deleteCar(carId: String) {
        val garage = garageRepo.deleteCar(carId)
        // Чистим нормы, интервалы ТО и протоколы удалённой машины — иначе осиротеют.
        normsRepo.clearFor(carId)
        maintenanceRepo.clearFor(carId)
        reportRepo.deleteForCar(carId)
        _ui.update {
            it.copy(
                garage = garage,
                modelNorms = garage.activeCar?.let { c -> normsRepo.normsFor(c.id) } ?: emptyMap(),
                carMaintenance = garage.activeCar?.let { c ->
                    MaintenanceCalc.computeAll(maintenanceRepo.intervalsFor(c.id), c.mileageKm, LocalDate.now())
                } ?: emptyList(),
                reports = reportRepo.metas(),
            )
        }
    }

    // ---- Обслуживание (ТО) ----

    fun setMaintenanceInterval(interval: MaintenanceInterval) {
        val car = _ui.value.garage.activeCar ?: return
        maintenanceRepo.upsert(car.id, interval)
        recomputeMaintenance()
        checkMaintenanceAndNotify()
    }

    fun deleteMaintenanceInterval(id: String) {
        val car = _ui.value.garage.activeCar ?: return
        maintenanceRepo.delete(car.id, id)
        recomputeMaintenance()
    }

    /** Отметить ТО выполненным: база отсчёта = текущий пробег + сегодня. */
    fun markServiced(id: String) {
        val car = _ui.value.garage.activeCar ?: return
        maintenanceRepo.markServiced(car.id, id, car.mileageKm, LocalDate.now().toString())
        recomputeMaintenance()
    }

    /** Ручное обновление пробега активной машины (одометра по OBD нет). */
    fun updateActiveCarMileage(km: Int) {
        val car = _ui.value.garage.activeCar ?: return
        val garage = garageRepo.updateCar(car.id) { it.copy(mileageKm = km) }
        _ui.update { it.copy(garage = garage) }
        recomputeMaintenance()
        checkMaintenanceAndNotify()
    }

    private fun recomputeMaintenance() {
        val car = _ui.value.garage.activeCar
        val items = if (car != null) {
            MaintenanceCalc.computeAll(maintenanceRepo.intervalsFor(car.id), car.mileageKm, LocalDate.now())
        } else {
            emptyList()
        }
        _ui.update { it.copy(carMaintenance = items) }
    }

    private fun checkMaintenanceAndNotify() {
        val car = _ui.value.garage.activeCar ?: return
        MaintenanceNotifier.notify(getApplication(), car.title, _ui.value.carMaintenance)
    }

    // ---- Протоколы диагностики ----

    /**
     * Формирует протокол по переписке с агентом: единый ход в ВЫБРАННЫЙ провайдер/модель
     * (как [fetchNorm], без инструментов), модель сжимает разбор в строгий JSON; при провале
     * парсинга сохраняется сырой текст. Провайдер/модель/ключ фиксируются до запуска.
     */
    fun generateReport(carId: String) {
        if (_ui.value.diagnosing || _ui.value.reportGenerating) return
        val reqProvider = _ui.value.provider
        val reqModel = _ui.value.model
        val key = settings.apiKey(reqProvider)
        if (key.isBlank()) {
            _ui.update { it.copy(reportError = "Укажите API-ключ в настройках.") }
            return
        }
        val car = _ui.value.garage.cars.firstOrNull { it.id == carId } ?: return
        val turns = _ui.value.chat.mapNotNull { m ->
            when (m.role) {
                ChatRole.USER -> true to m.text
                ChatRole.ASSISTANT -> false to m.text
                else -> null
            }
        }
        val transcript = buildTranscript(turns)
        if (transcript.isBlank()) {
            _ui.update { it.copy(reportError = "Нет переписки для протокола — сначала проведите диагностику в чате.") }
            return
        }
        val passport = buildString {
            append("Машина: ").append(car.title)
            car.spec.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
            append('\n')
            car.mileageKm?.let { append("Пробег: ").append(it).append(" км\n") }
            car.vin?.takeIf { it.isNotBlank() }?.let { append("VIN: ").append(it).append('\n') }
        }
        val prompt = passport + "\nПереписка:\n" + transcript
        _ui.update { it.copy(reportGenerating = true, reportError = null) }
        viewModelScope.launch {
            try {
                val provider = buildProvider(reqProvider, key)
                val resp = provider.send(
                    LlmRequest(reqModel, REPORT_SYSTEM, listOf(LlmMessage(Role.USER, content = prompt)), emptyList()),
                )
                resp.usage?.let { recordUsage(reqProvider, reqModel, it.prompt, it.completion) }
                val text = when (resp) {
                    is LlmResponse.Final -> resp.text
                    is LlmResponse.ToolUse -> resp.text ?: ""
                }
                val date = LocalDate.now().toString()
                val report = parseReport(
                    text = text,
                    id = ReportRepository.newId(),
                    carId = car.id,
                    dateIso = date,
                    title = "Протокол ${car.title} · $date",
                    carTitle = car.title,
                    carSpec = car.spec,
                    mileageKm = car.mileageKm,
                    vin = car.vin,
                    provider = provider.displayName,
                    model = reqModel,
                )
                val metas = withContext(Dispatchers.IO) { reportRepo.save(report) }
                _ui.update { it.copy(reports = metas, reportGenerating = false) }
            } catch (ex: CancellationException) {
                _ui.update { it.copy(reportGenerating = false) }
                throw ex
            } catch (ex: Exception) {
                _ui.update { it.copy(reportGenerating = false, reportError = errorMessage(ex)) }
            }
        }
    }

    fun renameReport(id: String, title: String) {
        viewModelScope.launch {
            val metas = withContext(Dispatchers.IO) { reportRepo.rename(id, title) }
            _ui.update { it.copy(reports = metas) }
        }
    }

    fun deleteReport(id: String) {
        viewModelScope.launch {
            val metas = withContext(Dispatchers.IO) { reportRepo.delete(id) }
            _ui.update { it.copy(reports = metas) }
        }
    }

    /** Полный протокол для экрана просмотра (meta списка достаточно только для карточки). */
    suspend fun loadReport(id: String): DiagnosticReport? =
        withContext(Dispatchers.IO) { reportRepo.load(id) }

    /** Экспорт протокола в .md и системный «Поделиться»; ошибку I/O показываем тостом. */
    fun shareReportMd(id: String) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val report = withContext(Dispatchers.IO) { reportRepo.load(id) } ?: return@launch
            val uri = withContext(Dispatchers.IO) {
                runCatching { Exporter.buildMarkdownFile(app, report.title, report.toMarkdown()) }
                    .getOrElse { Log.w(TAG, "Экспорт MD не удался", it); null }
            }
            if (uri != null) Exporter.shareMarkdown(app, uri)
            else Toast.makeText(app, "Не удалось создать файл", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Протокол шины OBD ----

    /**
     * Меняет протокол связи. Настройка запоминается и применяется при следующем
     * подключении; если адаптер уже на связи — переключаем на лету и сразу проверяем,
     * отвечает ли ЭБУ (иначе пользователь узнал бы об ошибке только после реконнекта).
     */
    fun setObdProtocol(p: ObdProtocol) {
        settings.obdProtocol = p.code
        _ui.update { it.copy(obdProtocol = p, protocolProbeResult = null) }
        val e = elm ?: return
        val wasPolling = pollJob?.isActive == true
        stopPolling()
        viewModelScope.launch {
            val ok = runCatching { e.setProtocol(p) }.getOrDefault(false)
            val name = runCatching { e.protocolName() }.getOrNull()
            _ui.update {
                it.copy(
                    ecuResponding = ok,
                    ecuProtocol = name,
                    error = if (ok) null else "На протоколе «${p.label}» ЭБУ не отвечает. " +
                        "Попробуйте «Определить протокол» или верните автоопределение.",
                )
            }
            if (wasPolling && elm != null) startPolling()
        }
    }

    /**
     * Перебирает протоколы и показывает, на каких ЭБУ реально ответил, — прямой ответ на
     * «почему не подключается». Опрос на это время останавливается: зонд дёргает шину.
     */
    fun probeProtocols() {
        val e = elm ?: run {
            _ui.update { it.copy(error = "Сначала подключите адаптер.") }
            return
        }
        if (_ui.value.protocolProbing) return
        val wasPolling = pollJob?.isActive == true
        stopPolling()
        _ui.update { it.copy(protocolProbing = true, protocolProbeResult = null, protocolProbeStep = null) }
        viewModelScope.launch {
            val found = runCatching {
                e.probeProtocols { p -> _ui.update { it.copy(protocolProbeStep = p) } }
            }.getOrDefault(emptyList())
            _ui.update {
                it.copy(protocolProbing = false, protocolProbeStep = null, protocolProbeResult = found)
            }
            if (wasPolling && elm != null) startPolling()
        }
    }

    // ---- Заводские PID пользователя ----

    /** Добавляет/меняет заводской PID. Невалидную команду не сохраняем. */
    fun setCustomPid(pid: CustomPid) {
        if (!pid.isValid() || pid.name.isBlank()) return
        _ui.update { it.copy(customPids = customPidRepo.upsert(pid)) }
    }

    fun deleteCustomPid(id: String) {
        _ui.update {
            it.copy(customPids = customPidRepo.delete(id), customValues = it.customValues - id)
        }
    }

    // ---- Перф-замеры (разгон / торможение / ¼ мили) ----

    /**
     * Открывает замер и запускает быстрый опрос ТОЛЬКО скорости (010D). Обычный опрос
     * приборов на это время останавливается: half-duplex ELM327 не может обслуживать
     * весь дашборд и при этом отдавать скорость достаточно часто для секундомера.
     */
    fun startPerf(kind: PerfKind) {
        val e = elm ?: run {
            _ui.update { it.copy(error = "Сначала подключите адаптер.") }
            return
        }
        perfJob?.cancel()
        val calc = PerfCalc(kind)
        _ui.update { it.copy(perfKind = kind, perfState = PerfState(PerfPhase.ARMING)) }
        val wasPolling = pollJob?.isActive == true
        stopPolling()
        perfJob = viewModelScope.launch {
            try {
                while (isActive) {
                    val v = runCatching { e.read(ObdPid.SPEED) }.getOrNull()
                    if (v == null) {
                        // Промах чтения — не сэмпл: подмешивать «дырку» в таймер нельзя.
                        delay(PERF_RETRY_MS)
                        continue
                    }
                    val state = calc.sample(System.currentTimeMillis(), v)
                    _ui.update { it.copy(perfState = state) }
                    if (state.phase == PerfPhase.DONE) {
                        state.resultSec?.let { sec -> savePerfRun(kind, sec, state.trapSpeedKmh) }
                        break
                    }
                    // Без delay: гоним опрос так быстро, как отвечает адаптер (~10–20 Гц).
                }
            } finally {
                if (wasPolling && elm != null) startPolling()
            }
        }
    }

    /** Перезапускает текущий замер с нуля (кнопка «Ещё раз»). */
    fun restartPerf() {
        _ui.value.perfKind?.let { startPerf(it) }
    }

    /** Закрывает замер и возвращает обычный опрос приборов. */
    fun stopPerf() {
        perfJob?.cancel()
        perfJob = null
        _ui.update { it.copy(perfKind = null, perfState = null) }
        if (elm != null) startPolling()
    }

    fun deletePerfRun(id: String) {
        _ui.update { it.copy(perfRuns = perfRepo.delete(id)) }
    }

    private fun savePerfRun(kind: PerfKind, seconds: Double, trapSpeedKmh: Double?) {
        val run = PerfRun(
            id = PerfRepository.newId(),
            kind = kind,
            dateIso = LocalDate.now().toString(),
            seconds = seconds,
            trapSpeedKmh = trapSpeedKmh,
            carTitle = _ui.value.garage.activeCar?.title.orEmpty(),
        )
        _ui.update { it.copy(perfRuns = perfRepo.add(run)) }
    }

    /** Экспорт протокола в PDF и системный «Поделиться». */
    fun shareReportPdf(id: String) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val report = withContext(Dispatchers.IO) { reportRepo.load(id) } ?: return@launch
            val uri = withContext(Dispatchers.IO) {
                runCatching { Exporter.buildPdf(app, report.title, report.toMarkdown(), report.id.hashCode().toLong()) }
                    .getOrElse { Log.w(TAG, "Экспорт PDF не удался", it); null }
            }
            if (uri != null) Exporter.sharePdf(app, uri)
            else Toast.makeText(app, "Не удалось создать PDF", Toast.LENGTH_SHORT).show()
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
        val reqProvider = _ui.value.provider
        val reqModel = _ui.value.model
        val provider = buildProvider(reqProvider, key)
        val system = "Ты — автомобильный справочник. Ответь ОДНОЙ короткой строкой: нормальный " +
            "диапазон значения параметра для указанной машины, с единицами измерения. Без пояснений " +
            "и вводных слов. Пример правильного ответа: 780–840 об/мин."
        val spec = car.spec.takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""
        val userMsg = "Автомобиль: ${car.title}$spec. Параметр: ${pid.label} (${pid.unit}). " +
            "Общая норма: ${pid.norm}. Укажи типичную норму именно для этой машины."
        val response = provider.send(
            LlmRequest(reqModel, system, listOf(LlmMessage(Role.USER, content = userMsg)), emptyList()),
        )
        response.usage?.let { recordUsage(reqProvider, reqModel, it.prompt, it.completion) }
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
        "full_scan" -> "Собираю полный снимок автомобиля…"
        "read_dtcs" -> "Читаю коды неисправностей…"
        "read_permanent_dtcs" -> "Проверяю постоянные коды…"
        "read_readiness" -> "Проверяю готовность мониторов…"
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
        if (_ui.value.diagnosing) return // не меняем провайдера, пока идёт запрос — расход и ключ должны совпадать
        settings.provider = p
        _ui.update {
            it.copy(
                provider = p,
                model = settings.model,
                hasKey = settings.apiKey(p).isNotBlank(),
                testStatus = null,
                availableModels = emptyList(),
                modelUsage = usageRepo.models(p),
            )
        }
    }

    fun setModel(m: String) {
        if (_ui.value.diagnosing) return // не меняем модель на лету — расход учитываем по ответившей
        settings.model = m
        _ui.update { it.copy(model = m) }
    }

    /** Проверяет ключ запросом списка моделей; при успехе сохраняет ключ и модели. */
    /**
     * Текущий API-ключ провайдера — для одноразового засева поля ввода в настройках.
     * Ключ не держим в наблюдаемом состоянии; читаем из настроек по требованию.
     */
    fun currentApiKey(): String = settings.apiKey(_ui.value.provider)

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
        // Последний шанс сохранить незаписанную поездку. viewModelScope уже отменяется,
        // поэтому сохраняем синхронно — это teardown, IO короткий и разовый.
        takeTripSnapshot()?.let { trip ->
            runCatching { kotlinx.coroutines.runBlocking(Dispatchers.IO) { tripRepo.save(trip) } }
        }
        transport?.close()
        ObdForegroundService.stop(getApplication())
    }

    companion object {
        /**
         * Чистая функция обрезки истории для модели (тестируется отдельно). Отрезает
         * старое, но начинает с хода пользователя — иначе разрыв пары
         * tool_use/tool_result вызовет 400 у провайдера. Если в хвостовом окне нет
         * USER — расширяет назад до последнего USER; если USER нет вовсе — отдаёт
         * историю целиком (не takeLast с разорванной парой tool_use/tool_result).
         */
        internal fun trimHistory(history: List<LlmMessage>, maxMsgs: Int): List<LlmMessage> {
            if (history.size <= maxMsgs) return history
            var start = history.size - maxMsgs
            while (start < history.size && history[start].role != Role.USER) start++
            if (start < history.size) return history.drop(start)
            val lastUser = history.indexOfLast { it.role == Role.USER }
            return if (lastUser >= 0) history.drop(lastUser) else history
        }

        // Системный промпт протокола: единый ход, строгий JSON, заземление на переписку.
        private const val REPORT_SYSTEM =
            "Ты составляешь протокол автодиагностики из переписки владельца с ассистентом-диагностом. " +
                "Верни ТОЛЬКО валидный JSON-объект без markdown-ограждений с полями: " +
                "complaint (строка — жалоба владельца), " +
                "statusLabel (РОВНО одно из: КРИТИЧНО | ВНИМАНИЕ | НОРМА | НУЖНЫ ДАННЫЕ), " +
                "verdict (одна короткая строка — что вероятнее и можно ли ехать), " +
                "findings (массив строк — реальные коды/параметры ИЗ переписки), " +
                "causes (массив строк — вероятные причины), " +
                "recommendations (массив строк — что проверить или сделать). " +
                "ЗАЗЕМЛЕНИЕ: бери факты ТОЛЬКО из переписки, НЕ выдумывай коды, значения и причины; " +
                "если вердикт не выведен — statusLabel=НУЖНЫ ДАННЫЕ."

        private const val TAG = "MainViewModel"
        private const val PERF_RETRY_MS = 50L // пауза после промаха чтения скорости в замере
        private const val POLL_INTERVAL_MS = 1500L
        private const val POLL_IDLE_MS = 5000L
        private const val IDLE_THRESHOLD = 4
        private const val PROTOCOL_RETRY_STREAK = 4 // пустых циклов до попытки перезапуска протокола
        private const val BATTERY_GUARD_MS = 5 * 60 * 1000L
        private const val RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_BASE_MS = 2000L
        private const val RECONNECT_MAX_MS = 16000L
        private const val HISTORY_SIZE = 240
        private const val MAX_HISTORY_MSGS = 40
        private const val SEARCH_DEBOUNCE_MS = 250L
        // Запись поездок: потолок точек в памяти (при переполнении — прореживание),
        // минимум для сохранения и окно «чёрного ящика» вокруг аномалии.
        private const val MAX_TRIP_SAMPLES = 4000
        private const val MIN_TRIP_SAMPLES = 10
        private const val EVENT_PRE_MS = 30_000L
        private const val EVENT_POST_MS = 30_000L
    }
}
