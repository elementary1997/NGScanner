package ru.ngscanner.obd

/**
 * Сборка полезной нагрузки из ответов ELM327 (ISO 15765-2, ISO-TP).
 *
 * Различаем два независимых явления:
 *  - **многокадровость одного ЭБУ** — ответ длиннее одного CAN-кадра (7 байт)
 *    приходит несколькими кадрами и его нужно СКЛЕИТЬ;
 *  - **ответ нескольких ЭБУ** — на CAN с выключенными заголовками разные модули
 *    отвечают отдельными строками, и их НЕЛЬЗЯ сливать в один поток (иначе
 *    хвост одного сообщения склеивается с началом другого и рождает фантомные
 *    коды/значения).
 *
 * Поэтому базовый метод — [messages]: он возвращает СПИСОК отдельных сообщений
 * прикладного уровня (по одному на логический ответ), уже собрав многокадровые.
 *
 * Форматы вывода ELM327 (зависят от `AT CAF` и `AT H`):
 *  - **CAF ON (по умолчанию), заголовки off** — адаптер сам собирает ISO-TP и
 *    печатает общую длину (`014`), затем сегменты `0:…`, `1:…` с чистыми данными;
 *  - **CAF OFF** — сырые кадры: First Frame (`1LLL…`) и Consecutive (`2N…`);
 *  - **однокадровые ответы** — обычные hex-байты, по строке на ЭБУ.
 */
object IsoTp {

    /**
     * Отдельные сообщения прикладного уровня (по одному на ответивший ЭБУ),
     * с уже собранными многокадровыми ISO-TP ответами. Каждый элемент —
     * «чистый» hex (верхний регистр), начиная с байта Mode+PID.
     *
     * [headerHexLen] > 0 — включены заголовки ELM327 (ATH1) и каждая строка
     * начинается с CAN-ID указанной длины (11-bit → 3, 29-bit → 8). Тогда кадры
     * сначала группируются по ЭБУ и только потом собираются ISO-TP — это
     * единственный надёжный способ не слить ответы разных модулей в фантомы.
     */
    fun messages(raw: String, headerHexLen: Int = 0): List<String> {
        val lines = raw.split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals("OK", ignoreCase = true) && it != ">" }
        if (lines.isEmpty()) return emptyList()

        // --- Заголовки включены: группируем кадры по CAN-ID (ЭБУ), затем ISO-TP. ---
        if (headerHexLen > 0) {
            val hexLines = lines.map { hexOnly(it) }.filter { it.isNotEmpty() }
            // Проверяем, что строки действительно похожи на кадры с заголовком:
            // длина > заголовок+PCI и данные после заголовка байт-выровнены. Иначе
            // (клон проигнорировал ATH1) — падаем в бесзаголовочный разбор ниже.
            val headered = hexLines.isNotEmpty() && hexLines.all {
                it.length > headerHexLen + 2 && (it.length - headerHexLen) % 2 == 0
            }
            if (headered) return messagesByHeader(hexLines, headerHexLen)
        }

        // --- Авто-форматирование (CAF): «NNN» (длина) + строки «i:данные». ---
        // Это один логический ответ (ELM собрал ISO-TP сам).
        if (lines.any { it.contains(':') }) {
            val segments = lines.mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon < 0) return@mapNotNull null
                val idx = line.substring(0, colon).trim().toIntOrNull(16) ?: return@mapNotNull null
                idx to hexOnly(line.substring(colon + 1))
            }.sortedBy { it.first }
            if (segments.isEmpty()) return emptyList()
            val joined = segments.joinToString("") { it.second }
            val declared = lines.firstOrNull { !it.contains(':') }?.trim()?.toIntOrNull(16)
            return listOfNotNull(trimTo(joined, declared).ifEmpty { null })
        }

        val frames = lines.map { hexOnly(it) }.filter { it.length >= 2 }
        if (frames.isEmpty()) return emptyList()

        // --- Сырой многокадровый ISO-TP одного ЭБУ: First Frame + Consecutive. ---
        if (frames.first().startsWith("1") && frames.size > 1 && frames.first().length >= 4) {
            val declared = frames.first().substring(1, 4).toIntOrNull(16)
            val sb = StringBuilder(frames.first().substring(4))
            for (cf in frames.drop(1)) {
                if (cf.startsWith("2") && cf.length >= 2) sb.append(cf.substring(2)) else sb.append(cf)
            }
            return listOfNotNull(trimTo(sb.toString(), declared).ifEmpty { null })
        }

        // --- Одиночный ISO-TP Single Frame (одна строка «0L…»). ---
        if (frames.size == 1 && frames.first().startsWith("0")) {
            val f = frames.first()
            val declared = f.substring(1, 2).toIntOrNull(16)
            return listOfNotNull(trimTo(f.substring(2), declared).ifEmpty { null })
        }

        // --- Иначе: каждая строка — отдельное сообщение (несколько ЭБУ или
        //     несколько однокадровых ответов). Их НЕ сливаем. ---
        return frames
    }

    /** Полезная нагрузка первого сообщения — для одиночных ответов (например VIN). */
    fun reassemble(raw: String): String? = messages(raw).firstOrNull()

    /** Группирует кадры по заголовку (ЭБУ) и собирает ISO-TP каждого независимо. */
    private fun messagesByHeader(hexLines: List<String>, headerLen: Int): List<String> {
        val byEcu = LinkedHashMap<String, MutableList<String>>()
        for (line in hexLines) {
            val ecu = line.substring(0, headerLen)
            byEcu.getOrPut(ecu) { mutableListOf() }.add(line.substring(headerLen))
        }
        return byEcu.values.mapNotNull { reassembleEcuFrames(it) }
    }

    /**
     * Собирает кадры одного ЭБУ (заголовок уже снят, PCI присутствует) в чистые
     * данные прикладного уровня. Single Frame — длина в младшем нибле PCI;
     * First+Consecutive — 12-битная длина, у CF снимается 1 байт PCI.
     */
    private fun reassembleEcuFrames(frames: List<String>): String? {
        if (frames.isEmpty()) return null
        val first = frames.first()
        if (first.length < 2) return null
        val pci = first.substring(0, 2).toIntOrNull(16) ?: return null
        return when (pci and 0xF0) {
            0x00 -> { // Single Frame
                val len = pci and 0x0F
                trimTo(first.substring(2), len).ifEmpty { null }
            }
            0x10 -> { // First Frame + Consecutive Frames
                if (first.length < 4) return null
                val hi = first.substring(2, 4).toIntOrNull(16) ?: return null
                val len = ((pci and 0x0F) shl 8) or hi
                val sb = StringBuilder(first.substring(4)) // после 2 байт PCI FF
                for (cf in frames.drop(1)) {
                    val cfPci = cf.take(2).toIntOrNull(16)
                    if (cf.length >= 2 && cfPci != null && (cfPci and 0xF0) == 0x20) {
                        sb.append(cf.substring(2)) // отбрасываем 1 байт PCI CF
                    } else {
                        sb.append(cf)
                    }
                }
                trimTo(sb.toString(), len).ifEmpty { null }
            }
            else -> first.ifEmpty { null } // не ISO-TP PCI — как есть
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
