package ru.ngscanner.report

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Четыре метки статуса протокола — те же, что агент ставит в чате (плашка «можно ли
 * ехать»). Отдельного enum намеренно нет: метки хранятся строкой, чтобы старые
 * протоколы не ломались при изменении набора.
 */
object ReportStatus {
    const val CRITICAL = "КРИТИЧНО"
    const val WARNING = "ВНИМАНИЕ"
    const val OK = "НОРМА"
    const val NEED_DATA = "НУЖНЫ ДАННЫЕ"

    /** Нормализует произвольную строку модели (мусор, англ.) к одной из четырёх меток. */
    fun normalize(raw: String?): String {
        val s = raw?.trim()?.uppercase() ?: return NEED_DATA
        return when {
            s.startsWith("КРИТ") || "CRIT" in s || "DANGER" in s -> CRITICAL
            s.startsWith("ВНИМ") || "WARN" in s || "CAUTION" in s -> WARNING
            s.startsWith("НОРМ") || s == "OK" || "NORMAL" in s || "GOOD" in s -> OK
            else -> NEED_DATA
        }
    }
}

/** Извлекает метку `[СТАТУС: X]` из свободного текста; нет метки/мусор → НУЖНЫ ДАННЫЕ. */
fun parseStatusLabel(text: String): String {
    val m = Regex("\\[СТАТУС:\\s*([^\\]]+)]").find(text) ?: return ReportStatus.NEED_DATA
    return ReportStatus.normalize(m.groupValues[1])
}

/**
 * Протокол диагностики: сжатая моделью выжимка переписки с агентом (жалоба, статус,
 * вердикт, находки/причины/рекомендации) + снимок паспорта машины на момент генерации.
 * При провале парсинга JSON сырой ответ кладётся в [rawBody] — протокол всё равно
 * сохраняется и открывается.
 */
@Serializable
data class DiagnosticReport(
    val id: String,
    val carId: String,
    val dateIso: String,
    val title: String,
    val carTitle: String,
    val carSpec: String = "",
    val mileageKm: Int? = null,
    val vin: String? = null,
    val complaint: String = "",
    val statusLabel: String = ReportStatus.NEED_DATA,
    val verdict: String = "",
    val findings: List<String> = emptyList(),
    val causes: List<String> = emptyList(),
    val recommendations: List<String> = emptyList(),
    val model: String = "",
    val provider: String = "",
    val rawBody: String? = null,
    val schemaVersion: Int = 1,
)

/** Лёгкая карточка для списка (полный отчёт грузится по запросу). */
@Serializable
data class ReportMeta(
    val id: String,
    val carId: String,
    val dateIso: String,
    val title: String,
    val statusLabel: String,
)

fun DiagnosticReport.toMeta(): ReportMeta = ReportMeta(id, carId, dateIso, title, statusLabel)

/** Детерминированный рендер протокола в Markdown (для просмотра, PDF и .md-экспорта). */
fun DiagnosticReport.toMarkdown(): String {
    val sb = StringBuilder()
    sb.append("# Протокол диагностики\n\n")
    sb.append("**Машина:** ").append(carTitle).append('\n')
    if (carSpec.isNotBlank()) sb.append("**Двигатель / год:** ").append(carSpec).append('\n')
    mileageKm?.let { sb.append("**Пробег:** ").append(it).append(" км\n") }
    vin?.takeIf { it.isNotBlank() }?.let { sb.append("**VIN:** ").append(it).append('\n') }
    sb.append("**Дата:** ").append(dateIso).append('\n')
    val by = listOf(provider, model).filter { it.isNotBlank() }.joinToString(" · ")
    if (by.isNotBlank()) sb.append("**Ассистент:** ").append(by).append('\n')
    sb.append('\n')
    if (complaint.isNotBlank()) sb.append("**Жалоба:** ").append(complaint).append("\n\n")
    sb.append("[СТАТУС: ").append(statusLabel).append("] ").append(verdict.ifBlank { "—" }).append("\n\n")
    if (rawBody != null) {
        sb.append(rawBody.trim()).append('\n')
        return sb.toString()
    }
    appendSection(sb, "Находки", findings)
    appendSection(sb, "Вероятные причины", causes)
    appendSection(sb, "Что проверить и рекомендации", recommendations)
    return sb.toString().trimEnd() + "\n"
}

private fun appendSection(sb: StringBuilder, title: String, items: List<String>) {
    if (items.isEmpty()) return
    sb.append("## ").append(title).append('\n')
    items.forEach { sb.append("- ").append(it).append('\n') }
    sb.append('\n')
}

/** DTO строгого JSON-ответа модели. Все поля с дефолтами — слабые модели опускают часть. */
@Serializable
internal data class ReportJson(
    val complaint: String = "",
    val statusLabel: String = "",
    val verdict: String = "",
    val findings: List<String> = emptyList(),
    val causes: List<String> = emptyList(),
    val recommendations: List<String> = emptyList(),
)

private val REPORT_JSON = Json { ignoreUnknownKeys = true }

/**
 * Собирает транскрипт для модели: только реплики владельца и диагноста с ролевыми
 * префиксами; берётся хвост, обрезанный по лимиту символов (свежий разбор дороже
 * старого). [turns] — пары (реплика владельца?, текст); TOOL/SYSTEM отфильтрованы
 * вызывающей стороной.
 */
fun buildTranscript(turns: List<Pair<Boolean, String>>, limit: Int = 12_000): String {
    val full = turns
        .filter { it.second.isNotBlank() }
        .joinToString("\n") { (fromUser, text) -> (if (fromUser) "Владелец: " else "Диагност: ") + text.trim() }
    return if (full.length <= limit) full else full.takeLast(limit)
}

/**
 * Разбирает ответ модели в протокол. Успех — строгий JSON (возможно в ```-ограждении
 * или с преамбулой) → поля из [ReportJson]. Провал — фолбэк: сырой текст в [rawBody],
 * статус из метки `[СТАТУС]`, вердикт — первая непустая строка.
 */
fun parseReport(
    text: String,
    id: String,
    carId: String,
    dateIso: String,
    title: String,
    carTitle: String,
    carSpec: String,
    mileageKm: Int?,
    vin: String?,
    provider: String,
    model: String,
): DiagnosticReport {
    val base = DiagnosticReport(
        id = id, carId = carId, dateIso = dateIso, title = title,
        carTitle = carTitle, carSpec = carSpec, mileageKm = mileageKm, vin = vin,
        provider = provider, model = model,
    )
    val dto = runCatching { REPORT_JSON.decodeFromString<ReportJson>(extractJsonObject(text)) }.getOrNull()
    val hasContent = dto != null && (
        dto.verdict.isNotBlank() || dto.complaint.isNotBlank() ||
            dto.findings.isNotEmpty() || dto.causes.isNotEmpty() || dto.recommendations.isNotEmpty()
        )
    return if (hasContent) {
        base.copy(
            complaint = dto.complaint.trim(),
            statusLabel = ReportStatus.normalize(dto.statusLabel),
            verdict = dto.verdict.trim(),
            findings = dto.findings.map { it.trim() }.filter { it.isNotBlank() },
            causes = dto.causes.map { it.trim() }.filter { it.isNotBlank() },
            recommendations = dto.recommendations.map { it.trim() }.filter { it.isNotBlank() },
        )
    } else {
        base.copy(
            statusLabel = parseStatusLabel(text),
            verdict = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }?.take(160) ?: "",
            rawBody = text.trim(),
        )
    }
}

/** Снимает ```json-ограждение и берёт подстроку от первой `{` до последней `}`. */
private fun extractJsonObject(text: String): String {
    val noFence = text.replace(Regex("```(?:json)?", RegexOption.IGNORE_CASE), "").trim()
    val start = noFence.indexOf('{')
    val end = noFence.lastIndexOf('}')
    return if (start >= 0 && end > start) noFence.substring(start, end + 1) else noFence
}
