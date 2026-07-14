package ru.ngscanner.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Насколько утверждение модели подтверждается реальными данными с адаптера. */
@Serializable
enum class EvidenceKind {
    /** Ссылается на код или значение, которые реально пришли с адаптера. */
    CONFIRMED,

    /** Ссылается на КОД, которого в ответах адаптера не было — модель его выдумала. */
    CONTRADICTED,

    /** Рассуждение или слова владельца — проверить нечем (не ложь, но и не факт). */
    UNVERIFIED,
}

/** Одно основание причины + вердикт проверки (проверяем МЫ, а не модель о себе). */
@Serializable
data class Evidence(val text: String, val kind: EvidenceKind)

/** Вероятная причина: чем выше [confidence], тем увереннее модель. */
@Serializable
data class VerdictCause(
    val title: String,
    val confidence: Int = 0,
    val evidence: List<Evidence> = emptyList(),
) {
    /** Причина не опирается ни на один подтверждённый факт с адаптера. */
    val grounded: Boolean get() = evidence.any { it.kind == EvidenceKind.CONFIRMED }
}

/**
 * Структурный вердикт диагностики — не свободный текст, а данные.
 *
 * Свободный текст нельзя ни проверить, ни показать честно: непонятно, на чём стоит
 * вывод. Здесь каждая причина несёт свои основания, и приложение само сверяет их с
 * тем, что реально пришло с адаптера ([VerdictGrounding]). Галлюцинация «можно
 * ехать» — главный риск продукта, и видимое заземление — защита от неё.
 */
@Serializable
data class DiagnosticVerdict(
    val severity: String,
    val summary: String,
    val causes: List<VerdictCause> = emptyList(),
    val checks: List<String> = emptyList(),
    val diy: String = "",
    /**
     * Предупреждение, если детерминированный движок правил (второе мнение по бортовым
     * данным) оказался строже модели. Правила нельзя уговорить: «можно ехать» при
     * перегреве не должно проходить незаметно.
     */
    val crossCheck: String? = null,
) {
    /** Модель сослалась на код, которого адаптер не отдавал, — красный флаг. */
    val hasContradiction: Boolean
        get() = causes.any { c -> c.evidence.any { it.kind == EvidenceKind.CONTRADICTED } }

    /** Ни одна причина не подтверждена данными — вывод висит в воздухе. */
    val ungrounded: Boolean
        get() = causes.isNotEmpty() && causes.none { it.grounded }

    /** Текст для истории модели и для экспорта/копирования (структура → плоский текст). */
    fun toPlainText(): String = buildString {
        append("[СТАТУС: ").append(severity).append("] ").append(summary)
        if (causes.isNotEmpty()) {
            append("\n\nВероятные причины:")
            causes.forEach { c ->
                append("\n• ").append(c.title)
                if (c.confidence > 0) append(" (").append(c.confidence).append("%)")
                c.evidence.forEach { e -> append("\n    — ").append(e.text) }
            }
        }
        if (checks.isNotEmpty()) {
            append("\n\nЧто проверить:")
            checks.forEach { append("\n• ").append(it) }
        }
        if (diy.isNotBlank()) append("\n\nСвоими силами или в сервис: ").append(diy)
    }
}

/** Сырой ответ модели (аргументы инструмента `submit_verdict`) — до проверки заземления. */
@Serializable
internal data class VerdictJson(
    val severity: String = "",
    val summary: String = "",
    val causes: List<CauseJson> = emptyList(),
    val checks: List<String> = emptyList(),
    val diy: String = "",
)

@Serializable
internal data class CauseJson(
    val title: String = "",
    val confidence: Int = 0,
    val evidence: List<String> = emptyList(),
)

/**
 * Сверка оснований модели с тем, что реально пришло с адаптера.
 *
 * Принцип: **проверяем мы, а не модель о себе.** Модель отдаёт основания строками, а
 * приложение ищет их в сырых ответах инструментов. Если модель сослалась на код,
 * которого не было, — это видно ([EvidenceKind.CONTRADICTED]), и вывод не выдаётся
 * за факт.
 */
object VerdictGrounding {

    /** Коды OBD-II: P/B/C/U + 4 hex-символа (первый — 0..3). */
    private val DTC_RE = Regex("\\b([PBCU][0-3][0-9A-F]{3})\\b", RegexOption.IGNORE_CASE)

    /** Числа с необязательным знаком и дробной частью: «+18», «-12,5», «92.4». */
    private val NUM_RE = Regex("[-+]?\\d+(?:[.,]\\d+)?")

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Коды, которые адаптер реально отдал (из всех ответов инструментов). */
    fun observedCodes(toolOutputs: List<String>): Set<String> =
        toolOutputs.flatMap { out -> DTC_RE.findAll(out).map { it.value.uppercase() } }.toSet()

    /** Числа, встречавшиеся в ответах адаптера, — для сверки процентов/градусов и т.п. */
    fun observedNumbers(toolOutputs: List<String>): Set<String> =
        toolOutputs.flatMap { out -> NUM_RE.findAll(out).map { normalizeNum(it.value) } }.toSet()

    private fun normalizeNum(s: String): String =
        s.replace(',', '.').removePrefix("+").trimStart('0').ifEmpty { "0" }
            .let { if (it.startsWith(".")) "0$it" else it }
            .removeSuffix(".0")

    /**
     * Классифицирует основание:
     * - назван код, которого адаптер не отдавал → [EvidenceKind.CONTRADICTED] (выдумка);
     * - назван код, который отдавал, → [EvidenceKind.CONFIRMED];
     * - названо число, встречавшееся в ответах адаптера, → [EvidenceKind.CONFIRMED];
     * - иначе → [EvidenceKind.UNVERIFIED] (рассуждение, слова владельца).
     *
     * Ошибаемся в сторону UNVERIFIED, а не CONFIRMED: лучше не похвалить верный вывод,
     * чем выдать выдумку за подтверждённый факт.
     */
    fun classify(text: String, codes: Set<String>, numbers: Set<String>): EvidenceKind {
        val cited = DTC_RE.findAll(text).map { it.value.uppercase() }.toList()
        if (cited.isNotEmpty()) {
            return if (cited.any { it !in codes }) EvidenceKind.CONTRADICTED else EvidenceKind.CONFIRMED
        }
        val nums = NUM_RE.findAll(text).map { normalizeNum(it.value) }.filter { it != "0" }.toList()
        if (nums.isNotEmpty() && nums.any { it in numbers }) return EvidenceKind.CONFIRMED
        return EvidenceKind.UNVERIFIED
    }

    /**
     * Разбирает аргументы `submit_verdict` и проставляет заземление по [toolOutputs].
     * `null` — модель прислала мусор вместо JSON (тогда остаётся текстовый путь).
     */
    fun parse(argumentsJson: String, toolOutputs: List<String>): DiagnosticVerdict? {
        val dto = runCatching { JSON.decodeFromString<VerdictJson>(argumentsJson) }.getOrNull() ?: return null
        if (dto.summary.isBlank() && dto.causes.isEmpty()) return null
        val codes = observedCodes(toolOutputs)
        val numbers = observedNumbers(toolOutputs)
        return DiagnosticVerdict(
            severity = ru.ngscanner.report.ReportStatus.normalize(dto.severity),
            summary = dto.summary.trim(),
            causes = dto.causes
                .filter { it.title.isNotBlank() }
                .map { c ->
                    VerdictCause(
                        title = c.title.trim(),
                        confidence = c.confidence.coerceIn(0, 100),
                        evidence = c.evidence
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .map { Evidence(it, classify(it, codes, numbers)) },
                    )
                },
            checks = dto.checks.map { it.trim() }.filter { it.isNotBlank() },
            diy = dto.diy.trim(),
        )
    }
}
