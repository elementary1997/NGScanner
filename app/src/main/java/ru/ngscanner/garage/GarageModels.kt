package ru.ngscanner.garage

import kotlinx.serialization.Serializable

/**
 * Одна запись в журнале обслуживания/диагностики машины.
 *
 * @property id уникальный идентификатор записи (UUID-строка)
 * @property dateIso дата в формате ISO-8601, например "2026-07-09"
 * @property mileageKm пробег на момент записи, км (если известен)
 * @property text текст записи
 * @property bySystem true, если запись создана агентом-диагностом, а не пользователем
 */
@Serializable
data class LogEntry(
    val id: String,
    val dateIso: String,
    val mileageKm: Int? = null,
    val text: String,
    val bySystem: Boolean = false,
)

/**
 * Автомобиль в гараже пользователя.
 *
 * @property id уникальный идентификатор машины (UUID-строка)
 * @property make марка, например "VW"
 * @property model модель, например "Passat"
 * @property generation поколение/кузов, например "B7"
 * @property engine двигатель, например "1.8 TSI"
 * @property year год выпуска
 * @property vin VIN-номер
 * @property fuel тип топлива, например "Бензин"
 * @property mileageKm текущий пробег, км
 * @property log журнал записей (последние — в начале списка)
 */
@Serializable
data class Car(
    val id: String,
    val make: String,
    val model: String,
    val generation: String? = null,
    val engine: String? = null,
    val year: Int? = null,
    val vin: String? = null,
    val fuel: String? = null,
    val mileageKm: Int? = null,
    val log: List<LogEntry> = emptyList(),
) {
    /**
     * Человекочитаемый заголовок машины, например "VW Passat B7".
     * Пустые части опускаются, лишние пробелы схлопываются.
     */
    val title: String
        get() = listOfNotNull(
            make.ifBlank { null },
            model.ifBlank { null },
            generation?.ifBlank { null },
        ).joinToString(" ")

    /**
     * Краткая техническая сводка, например "1.8 TSI · 2013".
     * Собирается из двигателя и года; отсутствующие части опускаются.
     * Если данных нет — возвращается пустая строка.
     */
    val spec: String
        get() = listOfNotNull(
            engine?.ifBlank { null },
            year?.toString(),
        ).joinToString(" · ")
}

/**
 * Гараж пользователя — список машин и указатель на активную.
 *
 * @property cars все машины пользователя
 * @property activeCarId id активной машины (или null, если гараж пуст)
 */
@Serializable
data class Garage(
    val cars: List<Car> = emptyList(),
    val activeCarId: String? = null,
    // Версия схемы хранения: позволяет будущим миграциям распознать старые данные.
    val schemaVersion: Int = 1,
) {
    /**
     * Активная машина, найденная по [activeCarId], либо null,
     * если id не задан или машина отсутствует в списке.
     */
    val activeCar: Car?
        get() = activeCarId?.let { id -> cars.firstOrNull { it.id == id } }
}
