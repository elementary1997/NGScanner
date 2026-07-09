package ru.ngscanner.garage

import android.content.Context
import java.util.UUID
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Репозиторий гаража — хранит [Garage] в [android.content.SharedPreferences]
 * в виде одной JSON-строки.
 *
 * Все методы-мутаторы синхронно персистят изменения и возвращают новый
 * снимок [Garage]. Класс не потокобезопасен: предполагается вызов из одного
 * потока (обычно главного или через единый диспетчер данных).
 */
class GarageRepository(context: android.content.Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Читает и десериализует гараж из хранилища.
     * При отсутствии данных или ошибке разбора возвращает пустой [Garage].
     */
    fun load(): Garage {
        val raw = prefs.getString(KEY_GARAGE, null) ?: return Garage()
        return runCatching { json.decodeFromString<Garage>(raw) }.getOrElse { Garage() }
    }

    /**
     * Добавляет новую машину или заменяет существующую с тем же id.
     * Если это первая машина в гараже — делает её активной.
     */
    fun upsertCar(car: Car): Garage {
        val current = load()
        val exists = current.cars.any { it.id == car.id }
        val cars = if (exists) {
            current.cars.map { if (it.id == car.id) car else it }
        } else {
            current.cars + car
        }
        val activeCarId = current.activeCarId ?: car.id
        return persist(current.copy(cars = cars, activeCarId = activeCarId))
    }

    /**
     * Удаляет машину по [carId]. Если удалили активную — активной становится
     * первая из оставшихся машин (или null, если гараж опустел).
     */
    fun deleteCar(carId: String): Garage {
        val current = load()
        val cars = current.cars.filterNot { it.id == carId }
        val activeCarId = if (current.activeCarId == carId) {
            cars.firstOrNull()?.id
        } else {
            current.activeCarId
        }
        return persist(current.copy(cars = cars, activeCarId = activeCarId))
    }

    /**
     * Делает машину с [carId] активной. Если такой машины нет,
     * activeCarId не меняется.
     */
    fun setActive(carId: String): Garage {
        val current = load()
        if (current.cars.none { it.id == carId }) return current
        return persist(current.copy(activeCarId = carId))
    }

    /**
     * Добавляет [entry] в начало журнала машины с [carId].
     */
    fun addEntry(carId: String, entry: LogEntry): Garage =
        updateCar(carId) { car -> car.copy(log = listOf(entry) + car.log) }

    /**
     * Общий помощник: применяет [transform] к машине с [carId] и персистит
     * результат. Если машина не найдена — гараж не меняется.
     */
    fun updateCar(carId: String, transform: (Car) -> Car): Garage {
        val current = load()
        if (current.cars.none { it.id == carId }) return current
        val cars = current.cars.map { if (it.id == carId) transform(it) else it }
        return persist(current.copy(cars = cars))
    }

    /** Сохраняет [garage] в хранилище и возвращает его же для удобства цепочек. */
    private fun persist(garage: Garage): Garage {
        prefs.edit().putString(KEY_GARAGE, json.encodeToString(garage)).apply()
        return garage
    }

    companion object {
        /** Имя файла SharedPreferences. */
        const val PREFS_NAME = "ngscanner_garage"

        /** Ключ, под которым лежит JSON гаража. */
        const val KEY_GARAGE = "garage"

        /** Генерирует новый id для машины. */
        fun newCarId(): String = UUID.randomUUID().toString()

        /** Генерирует новый id для записи журнала. */
        fun newEntryId(): String = UUID.randomUUID().toString()
    }
}
