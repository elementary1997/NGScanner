@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package ru.ngscanner.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Garage
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Numbers
import ru.ngscanner.garage.Car
import ru.ngscanner.garage.GarageRepository
import ru.ngscanner.garage.LogEntry
import ru.ngscanner.garage.VehicleSuggestion
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.ngscanner.ui.ConnectionState
import ru.ngscanner.ui.UiState
import ru.ngscanner.ui.MainViewModel

private sealed interface GarageNav {
    data object List : GarageNav
    data class Detail(val carId: String) : GarageNav
    data object AddSearch : GarageNav
    data class AddForm(val suggestion: VehicleSuggestion) : GarageNav
    data object AddVin : GarageNav
}

/** Saver для стека навигации Гаража — переживает поворот и переключение вкладок. */
private val GarageNavSaver = Saver<GarageNav, String>(
    save = { nav ->
        when (nav) {
            is GarageNav.List -> "L"
            is GarageNav.AddSearch -> "S"
            is GarageNav.AddVin -> "V"
            is GarageNav.Detail -> "D:${nav.carId}"
            is GarageNav.AddForm -> "F:" + Json.encodeToString(nav.suggestion)
        }
    },
    restore = { s ->
        when {
            s == "S" -> GarageNav.AddSearch
            s == "V" -> GarageNav.AddVin
            s.startsWith("D:") -> GarageNav.Detail(s.removePrefix("D:"))
            s.startsWith("F:") -> runCatching {
                GarageNav.AddForm(Json.decodeFromString<VehicleSuggestion>(s.removePrefix("F:")))
            }.getOrDefault(GarageNav.List)
            else -> GarageNav.List
        }
    },
)

@Composable
internal fun GarageTab(ui: UiState, vm: MainViewModel) {
    var nav by rememberSaveable(stateSaver = GarageNavSaver) { mutableStateOf<GarageNav>(GarageNav.List) }
    // «Назад» на вложенных экранах Гаража возвращает на шаг назад, а не выходит из
    // приложения. На корневом списке обработчик выключен — тогда «назад» ловит
    // MainScreen и уводит на вкладку «Приборы».
    BackHandler(enabled = nav !is GarageNav.List) {
        nav = when (nav) {
            is GarageNav.AddForm -> GarageNav.AddSearch
            is GarageNav.AddSearch -> { vm.clearSuggestions(); GarageNav.List }
            is GarageNav.AddVin -> { vm.clearVin(); GarageNav.List }
            else -> GarageNav.List
        }
    }
    when (val n = nav) {
        is GarageNav.List -> CarListScreen(
            garage = ui.garage,
            onOpen = { nav = GarageNav.Detail(it) },
            onAddSearch = { vm.clearSuggestions(); nav = GarageNav.AddSearch },
            onAddVin = { vm.clearVin(); nav = GarageNav.AddVin },
        )
        is GarageNav.Detail -> {
            val car = ui.garage.cars.firstOrNull { it.id == n.carId }
            if (car == null) {
                LaunchedEffect(Unit) { nav = GarageNav.List }
            } else {
                CarDetailScreen(
                    car = car,
                    isActive = ui.garage.activeCarId == car.id,
                    onBack = { nav = GarageNav.List },
                    onMakeActive = { vm.setActiveCar(car.id) },
                    onDelete = { vm.deleteCar(car.id); nav = GarageNav.List },
                    onAddEntry = { text, km -> vm.addLogEntry(text, km) },
                    onDeleteEntry = { entryId -> vm.deleteLogEntry(car.id, entryId) },
                )
            }
        }
        is GarageNav.AddSearch -> AddBySearchScreen(
            suggestions = ui.carSuggestions,
            onQuery = { vm.searchCars(it) },
            onBack = { vm.clearSuggestions(); nav = GarageNav.List },
            onPick = { nav = GarageNav.AddForm(it) },
        )
        is GarageNav.AddForm -> AddCarForm(
            suggestion = n.suggestion,
            onBack = { nav = GarageNav.AddSearch },
            onSave = { car -> vm.addCar(car); nav = GarageNav.Detail(car.id) },
        )
        is GarageNav.AddVin -> AddByVinScreen(
            ui = ui,
            onDecode = { vm.decodeVin(it) },
            onScanVin = { vm.readVinFromEcu() },
            onBack = { vm.clearVin(); nav = GarageNav.List },
            onSave = { car -> vm.addCar(car); nav = GarageNav.Detail(car.id) },
        )
    }
}

@Composable
private fun CarListScreen(
    garage: ru.ngscanner.garage.Garage,
    onOpen: (String) -> Unit,
    onAddSearch: () -> Unit,
    onAddVin: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        if (garage.cars.isEmpty()) {
            EmptyGarageHint()
        } else {
            GarageSectionLabel("Мои машины")
            garage.cars.forEach { car ->
                CarCard(car, isActive = garage.activeCarId == car.id) { onOpen(car.id) }
            }
        }
        Spacer(Modifier.height(4.dp))
        GarageSectionLabel("Добавить машину")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onAddSearch,
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Rounded.Search, null, Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text("Справочник")
            }
            OutlinedButton(
                onClick = onAddVin,
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Rounded.Numbers, null, Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text("По VIN")
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun CarCard(car: Car, isActive: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    ElevatedCard(onClick = onClick, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)).background(cs.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.DirectionsCar, null, Modifier.size(25.dp), tint = cs.primary)
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(car.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (isActive) {
                        Spacer(Modifier.width(8.dp))
                        ActiveBadge()
                    }
                }
                if (car.spec.isNotBlank()) {
                    Text(
                        car.spec,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = cs.onSurfaceVariant,
                    )
                }
                if (car.log.isNotEmpty()) {
                    Text(
                        "${car.log.size} ${plural(car.log.size, "запись", "записи", "записей")} в журнале",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun ActiveBadge() {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(999.dp), color = cs.primary.copy(alpha = 0.14f)) {
        Text(
            "АКТИВНА",
            Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = cs.primary,
        )
    }
}

@Composable
private fun GarageTopBar(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", tint = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.width(4.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun CarDetailScreen(
    car: Car,
    isActive: Boolean,
    onBack: () -> Unit,
    onMakeActive: () -> Unit,
    onDelete: () -> Unit,
    onAddEntry: (String, Int?) -> Unit,
    onDeleteEntry: (String) -> Unit,
) {
    var showEntry by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        GarageTopBar(car.title, onBack)
        CarSpecCard(car, isActive, onMakeActive, onDelete = { showDelete = true })
        CtxNote()
        LogbookSection(car.log, onAdd = { showEntry = true }, onDeleteEntry = onDeleteEntry)
        Spacer(Modifier.height(16.dp))
    }
    if (showEntry) {
        AddEntryDialog(
            onDismiss = { showEntry = false },
            onSave = { text, km -> onAddEntry(text, km); showEntry = false },
        )
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            icon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Убрать «${car.title}»?") },
            text = { Text("Машина и весь её бортжурнал будут удалены без возможности восстановления.") },
            confirmButton = {
                TextButton(onClick = { showDelete = false; onDelete() }) {
                    Text("Убрать", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun CarSpecCard(car: Car, isActive: Boolean, onMakeActive: () -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            SpecRow("Марка / модель", car.title)
            car.generation?.takeIf { it.isNotBlank() }?.let { SpecRow("Поколение", it) }
            SpecRow("Год выпуска", car.year?.toString() ?: "—")
            SpecRow("Двигатель", car.engine ?: "—")
            SpecRow("Пробег", car.mileageKm?.let { "$it км" } ?: "—")
            car.vin?.takeIf { it.isNotBlank() }?.let { SpecRow("VIN", it) }
            car.fuel?.takeIf { it.isNotBlank() }?.let { SpecRow("Топливо", it) }
            HorizontalDivider(color = cs.outline.copy(alpha = 0.6f), modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth()) {
                if (!isActive) {
                    TextButton(onClick = onMakeActive, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Сделать активной")
                    }
                } else {
                    Row(
                        Modifier.weight(1f).padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(16.dp), tint = cs.primary)
                        Spacer(Modifier.width(7.dp))
                        Text("Активная машина", color = cs.primary, style = MaterialTheme.typography.labelLarge)
                    }
                }
                TextButton(onClick = onDelete) {
                    Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text("Убрать", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun SpecRow(label: String, value: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = cs.onSurface,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.padding(vertical = 12.dp, horizontal = 14.dp)) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 0.8.sp,
            color = cs.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = cs.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun CtxNote() {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cs.primary.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, cs.primary.copy(alpha = 0.16f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Info, null, Modifier.size(18.dp), tint = cs.primary)
            Spacer(Modifier.width(11.dp))
            Text(
                "Паспорт машины и последние записи бортжурнала передаются агенту — он не станет " +
                    "предлагать то, что вы уже сделали, и сам дописывает сюда свои выводы.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurface,
                lineHeight = 18.sp,
            )
        }
    }
}

@Composable
private fun LogbookSection(log: List<LogEntry>, onAdd: () -> Unit, onDeleteEntry: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "БОРТЖУРНАЛ",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.6.sp,
                color = cs.onSurfaceVariant,
            )
            TextButton(onClick = onAdd) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Запись")
            }
        }
        if (log.isEmpty()) {
            Text(
                "Записей пока нет. Пишите, что сделали руками (заменил ДМРВ, почистил дроссель) — " +
                    "агент учтёт это при диагностике.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
        } else {
            log.forEach { LogEntryRow(it, onDelete = { onDeleteEntry(it.id) }) }
        }
    }
}

@Composable
private fun LogEntryRow(e: LogEntry, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var confirm by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth()) {
        Box(
            Modifier.padding(top = 5.dp).size(9.dp).clip(CircleShape)
                .background(if (e.bySystem) cs.tertiary else cs.primary),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    e.dateIso,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = cs.onSurfaceVariant,
                )
                e.mileageKm?.let {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "$it км",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(e.text, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
            if (e.bySystem) {
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(12.dp), tint = cs.tertiary)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "запись ассистента",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = cs.tertiary,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        IconButton(onClick = { confirm = true }) {
            Icon(Icons.Rounded.DeleteOutline, "Удалить запись", Modifier.size(18.dp), tint = cs.onSurfaceVariant)
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            icon = { Icon(Icons.Rounded.DeleteOutline, null, tint = cs.error) },
            title = { Text("Удалить запись?") },
            text = { Text("Запись бортжурнала будет удалена без возможности восстановления.") },
            confirmButton = {
                TextButton(onClick = { confirm = false; onDelete() }) {
                    Text("Удалить", color = cs.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun AddBySearchScreen(
    suggestions: List<VehicleSuggestion>,
    onQuery: (String) -> Unit,
    onBack: () -> Unit,
    onPick: (VehicleSuggestion) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GarageTopBar("Из справочника", onBack)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; onQuery(it) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Марка, модель… (напр. Лада Веста)") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
        )
        if (query.isNotBlank() && suggestions.isEmpty()) {
            Text(
                "Ничего не найдено. Попробуйте иначе — например «Solaris» или «Тойота Королла».",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
        }
        suggestions.forEach { s ->
            ElevatedCard(onClick = { onPick(s) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            s.years,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = cs.primary)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun AddCarForm(suggestion: VehicleSuggestion, onBack: () -> Unit, onSave: (Car) -> Unit) {
    val currentYear = remember { java.time.LocalDate.now().year }
    val yearTo = suggestion.yearTo ?: currentYear
    val years = remember(suggestion) { (yearTo downTo suggestion.yearFrom).map { it.toString() } }
    var year by rememberSaveable { mutableStateOf(years.firstOrNull() ?: yearTo.toString()) }
    var engine by rememberSaveable { mutableStateOf(suggestion.engines.firstOrNull() ?: "") }
    var mileage by rememberSaveable { mutableStateOf("") }
    var vin by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GarageTopBar("Новая машина", onBack)
        Text(suggestion.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        SimpleDropdown("Год выпуска", years, year) { year = it }
        if (suggestion.engines.isNotEmpty()) {
            SimpleDropdown("Двигатель", suggestion.engines, engine) { engine = it }
        } else {
            OutlinedTextField(
                value = engine,
                onValueChange = { engine = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Двигатель") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
        }
        OutlinedTextField(
            value = mileage,
            onValueChange = { v -> mileage = v.filter { it.isDigit() } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Пробег, км") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(14.dp),
        )
        OutlinedTextField(
            value = vin,
            onValueChange = { vin = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(17) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("VIN (необязательно)") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
        )
        Button(
            onClick = {
                onSave(
                    Car(
                        id = GarageRepository.newCarId(),
                        make = suggestion.makeRu ?: suggestion.make,
                        model = suggestion.modelRu ?: suggestion.model,
                        generation = suggestion.generation,
                        engine = engine.ifBlank { null },
                        year = year.toIntOrNull(),
                        vin = vin.ifBlank { null },
                        mileageKm = mileage.toIntOrNull(),
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Rounded.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("Добавить в гараж")
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun AddByVinScreen(
    ui: UiState,
    onDecode: (String) -> Unit,
    onScanVin: () -> Unit,
    onBack: () -> Unit,
    onSave: (Car) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var vin by rememberSaveable { mutableStateOf("") }
    var mileage by rememberSaveable { mutableStateOf("") }
    val info = ui.vinResult
    val connected = ui.connection == ConnectionState.Connected
    // VIN, считанный с ЭБУ, подставляем в поле ввода (декодирование запускается само).
    LaunchedEffect(ui.ecuVin) { ui.ecuVin?.let { vin = it } }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GarageTopBar("Добавить по VIN", onBack)
        Text(
            "Введите VIN вручную или считайте его прямо с авто по OBD (нужен подключённый " +
                "адаптер). Определим марку, модель, год и двигатель автоматически. VIN " +
                "отправляется в сервис NHTSA (США); для авто рынка РФ марка определится офлайн.",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
        )
        // Чтение VIN с ЭБУ — быстрый путь без ручного ввода 17 символов.
        OutlinedButton(
            onClick = onScanVin,
            enabled = connected && !ui.vinScanning && !ui.vinDecoding,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            if (ui.vinScanning) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Rounded.DirectionsCar, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (connected) "Считать VIN с авто" else "Считать с авто (нет адаптера)")
            }
        }
        OutlinedTextField(
            value = vin,
            onValueChange = { vin = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(17) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("VIN") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
        )
        Button(
            onClick = { onDecode(vin) },
            enabled = vin.length in 11..17 && !ui.vinDecoding,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            if (ui.vinDecoding) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = cs.onPrimary)
            } else {
                Icon(Icons.Rounded.Search, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Распознать")
            }
        }
        ui.vinError?.let {
            Text("✗ $it", style = MaterialTheme.typography.bodyMedium, color = cs.error)
        }
        if (info != null) {
            // Поля редактируемые: сервис мог определить не всё (частая ситуация для авто РФ) —
            // пользователь проверяет и дописывает.
            var make by rememberSaveable(info) { mutableStateOf(info.make) }
            var model by rememberSaveable(info) { mutableStateOf(info.model) }
            var year by rememberSaveable(info) { mutableStateOf(info.year?.toString() ?: "") }
            var engine by rememberSaveable(info) { mutableStateOf(info.engine ?: "") }

            Text(
                if (info.model.isBlank()) {
                    "Определена марка и год. Допишите модель и двигатель."
                } else {
                    "Проверьте данные и при необходимости поправьте."
                },
                style = MaterialTheme.typography.labelLarge,
                color = cs.primary,
            )
            OutlinedTextField(
                value = make,
                onValueChange = { make = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Марка") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Модель") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
            OutlinedTextField(
                value = year,
                onValueChange = { v -> year = v.filter { it.isDigit() }.take(4) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Год выпуска") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(14.dp),
                // Для офлайн-распознанных VIN (марка по WMI, без сервиса) год по
                // 10-й позиции неоднозначен — предупреждаем, что он ориентировочный.
                supportingText = if (info.model.isBlank()) {
                    { Text("Определён ориентировочно по VIN — проверьте") }
                } else {
                    null
                },
            )
            OutlinedTextField(
                value = engine,
                onValueChange = { engine = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Двигатель") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
            OutlinedTextField(
                value = mileage,
                onValueChange = { v -> mileage = v.filter { it.isDigit() } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Пробег, км (необязательно)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(14.dp),
            )
            Button(
                onClick = {
                    onSave(
                        Car(
                            id = GarageRepository.newCarId(),
                            make = make.trim(),
                            model = model.trim(),
                            engine = engine.ifBlank { null },
                            year = year.toIntOrNull(),
                            vin = vin.ifBlank { null },
                            fuel = info.fuel,
                            mileageKm = mileage.toIntOrNull(),
                        ),
                    )
                },
                enabled = make.isNotBlank() && model.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Rounded.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Добавить в гараж")
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SimpleDropdown(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(text = { Text(opt) }, onClick = { onSelect(opt); expanded = false })
            }
        }
    }
}

/** Русское склонение существительного после числа: 1 запись, 2 записи, 5 записей. */
private fun plural(n: Int, one: String, few: String, many: String): String {
    val mod10 = n % 10
    val mod100 = n % 100
    return when {
        mod10 == 1 && mod100 != 11 -> one
        mod10 in 2..4 && mod100 !in 12..14 -> few
        else -> many
    }
}

@Composable
private fun EmptyGarageHint() {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Garage, null, Modifier.size(52.dp), tint = cs.primary)
        Text("Гараж пуст", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Добавьте автомобиль ниже — агент будет диагностировать именно вашу машину " +
                "с учётом её паспорта и истории работ.",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun GarageSectionLabel(title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        Spacer(Modifier.width(10.dp))
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.6.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AddEntryDialog(onDismiss: () -> Unit, onSave: (String, Int?) -> Unit) {
    var text by remember { mutableStateOf("") }
    var km by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Запись в бортжурнал") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Что сделали или заметили") },
                    minLines = 2,
                )
                OutlinedTextField(
                    value = km,
                    onValueChange = { v -> km = v.filter { it.isDigit() } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Пробег, км (необязательно)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text, km.toIntOrNull()) }, enabled = text.isNotBlank()) {
                Text("Сохранить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
