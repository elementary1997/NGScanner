package ru.ngscanner.obd

/**
 * Сборка полезной нагрузки из многофреймовых ответов ELM327 (ISO 15765-2, ISO-TP).
 *
 * Ответы длиннее одного CAN-кадра (7 байт данных) приходят несколькими кадрами.
 * ELM327 выдаёт их в одном из форматов — в зависимости от `AT CAF` (авто-
 * форматирование) и `AT H` (заголовки):
 *
 *  - **CAF ON (по умолчанию), заголовки off** — адаптер сам собирает ISO-TP и
 *    печатает общую длину сообщения одной строкой (`014`), затем сегменты вида
 *    `0:…`, `1:…`, `2:…` с уже «чистыми» байтами данных (PCI снят);
 *  - **CAF OFF** — приходят сырые кадры ISO-TP: First Frame (`1LLL…`, где LLL —
 *    12-битная длина) и Consecutive Frames (`2N…`, N — счётчик 0..F), из которых
 *    нужно снять байты PCI и склеить данные;
 *  - **однофреймовый ответ** — обычные hex-байты без PCI (Mode 01 и т.п.).
 *
 * Наивная нормализация всего текста в один hex-поток здесь ломается: маркеры
 * сегментов (`0:`, `1:`) и байты длины вклиниваются в данные и сдвигают
 * выравнивание. Поэтому сборку делаем построчно, до нормализации.
 *
 * Возвращает «чистую» последовательность байт прикладного уровня в hex
 * (верхний регистр, начиная с байта Mode+PID) или `null`, если данных нет.
 *
 * Ограничение: при ответе нескольких ЭБУ с выключенными заголовками их кадры
 * неразличимы — поддерживается сборка одного логического сообщения.
 */
object IsoTp {

    fun reassemble(raw: String): String? {
        val lines = raw.split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals("OK", ignoreCase = true) && it != ">" }
        if (lines.isEmpty()) return null

        // --- Формат авто-форматирования: «NNN» (длина) + строки «i:данные». ---
        if (lines.any { it.contains(':') }) {
            val segments = lines.mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon < 0) return@mapNotNull null
                val idx = line.substring(0, colon).trim().toIntOrNull(16) ?: return@mapNotNull null
                idx to hexOnly(line.substring(colon + 1))
            }.sortedBy { it.first }
            if (segments.isEmpty()) return null
            val joined = segments.joinToString("") { it.second }
            // Первая строка без двоеточия — объявленная длина сообщения (в байтах).
            val declared = lines.firstOrNull { !it.contains(':') }?.trim()?.toIntOrNull(16)
            return trimTo(joined, declared).ifEmpty { null }
        }

        // --- Сырой ISO-TP или однофреймовый ответ: смотрим PCI первого байта. ---
        val frames = lines.map { hexOnly(it) }.filter { it.length >= 2 }
        if (frames.isEmpty()) return null
        val first = frames.first()
        return when (first.substring(0, 1)) {
            // First Frame: длина — 12 бит (младший полубайт + следующий байт),
            // далее данные; Consecutive Frames — первый байт PCI (`2N`) снимаем.
            "1" -> {
                val declared = first.substring(1, 4).toIntOrNull(16)
                val sb = StringBuilder(first.substring(4))
                for (cf in frames.drop(1)) {
                    if (cf.length >= 2 && cf[0] == '2') sb.append(cf.substring(2)) else sb.append(cf)
                }
                trimTo(sb.toString(), declared).ifEmpty { null }
            }
            // Single Frame: длина — младший полубайт, далее данные.
            "0" -> {
                val declared = first.substring(1, 2).toIntOrNull(16)
                trimTo(first.substring(2), declared).ifEmpty { null }
            }
            // Обычный ответ без ISO-TP PCI (Mode 01: 41xx…) — просто склеиваем.
            else -> frames.joinToString("").ifEmpty { null }
        }
    }

    /** Обрезает hex до объявленной длины в байтах (если она валидна). */
    private fun trimTo(hex: String, declaredBytes: Int?): String {
        if (declaredBytes == null || declaredBytes <= 0) return hex
        val need = declaredBytes * 2
        return if (need in 1..hex.length) hex.substring(0, need) else hex
    }

    private fun hexOnly(s: String): String = s.uppercase().replace(Regex("[^0-9A-F]"), "")
}
