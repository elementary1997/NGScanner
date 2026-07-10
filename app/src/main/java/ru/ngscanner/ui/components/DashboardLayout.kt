package ru.ngscanner.ui.components

import ru.ngscanner.obd.ObdPid

/**
 * Логика раскладки дашборда без Compose (чтобы покрыть unit-тестом отдельно).
 *
 * Набор и порядок приборов пользователь задаёт списком имён [ObdPid] (хранится в
 * настройках как CSV). Пустой список = дефолтная раскладка.
 */
object DashboardLayout {

    /** Дефолтная раскладка (как в Dashboard до кастомизации), в порядке отображения. */
    val DEFAULT_ORDER: List<ObdPid> = listOf(
        ObdPid.RPM, ObdPid.COOLANT, ObdPid.ENGINE_LOAD, ObdPid.TIMING, ObdPid.SPEED,
        ObdPid.THROTTLE, ObdPid.MAF, ObdPid.MAP, ObdPid.INTAKE_TEMP,
        ObdPid.STFT, ObdPid.LTFT, ObdPid.O2_LAMBDA, ObdPid.FUEL_LEVEL, ObdPid.VOLTAGE,
    )

    private val byName = ObdPid.entries.associateBy { it.name }

    /**
     * Разрешает сохранённый список имён в упорядоченный список PID: сохраняет порядок
     * пользователя, отбрасывает неизвестные имена и дубликаты, фильтрует по [supported]
     * (если он непуст). Пустой [stored] → [DEFAULT_ORDER].
     */
    fun resolve(stored: List<String>, supported: Set<String>): List<ObdPid> {
        val base = if (stored.isEmpty()) DEFAULT_ORDER else stored.mapNotNull { byName[it] }.distinct()
        return if (supported.isEmpty()) base else base.filter { it.cmd in supported }
    }

    /** Переместить имя вверх в списке (no-op на первой позиции/отсутствии). */
    fun moveUp(list: List<String>, name: String): List<String> {
        val i = list.indexOf(name)
        if (i <= 0) return list
        return list.toMutableList().apply { this[i] = this[i - 1]; this[i - 1] = name }
    }

    /** Переместить имя вниз в списке (no-op на последней позиции/отсутствии). */
    fun moveDown(list: List<String>, name: String): List<String> {
        val i = list.indexOf(name)
        if (i < 0 || i >= list.size - 1) return list
        return list.toMutableList().apply { this[i] = this[i + 1]; this[i + 1] = name }
    }
}
