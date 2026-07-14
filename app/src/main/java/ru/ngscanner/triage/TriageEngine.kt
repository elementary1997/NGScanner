package ru.ngscanner.triage

import ru.ngscanner.agent.DiagnosticVerdict
import ru.ngscanner.agent.Evidence
import ru.ngscanner.agent.EvidenceKind
import ru.ngscanner.agent.VerdictCause
import ru.ngscanner.obd.ObdPid
import ru.ngscanner.report.ReportStatus
import java.util.Locale

/** Код неисправности с расшифровкой из локальной базы. */
data class TriageCode(val code: String, val description: String)

/** Всё, что удалось снять с машины, — вход движка. */
data class TriageFacts(
    val activeCodes: List<TriageCode> = emptyList(),
    val pendingCodes: List<TriageCode> = emptyList(),
    val permanentCodes: List<TriageCode> = emptyList(),
    val metrics: Map<ObdPid, Double> = emptyMap(),
    /** Мониторы, не прошедшие самопроверку (важно перед ТО и после сброса кодов). */
    val readinessIncomplete: List<String> = emptyList(),
) {
    val engineRunning: Boolean get() = (metrics[ObdPid.RPM] ?: 0.0) > ENGINE_ON_RPM
    val hasAnyData: Boolean get() = metrics.isNotEmpty() || activeCodes.isNotEmpty() ||
        pendingCodes.isNotEmpty() || permanentCodes.isNotEmpty()

    companion object {
        const val ENGINE_ON_RPM = 500.0
    }
}

/**
 * Офлайн-триаж: детерминированный движок правил поверх реальных данных машины.
 *
 * Зачем он, если есть LLM:
 * - **Работает без сети и без ключа** — гаражи без интернета, а базовый вердикт
 *   «можно ли ехать» не должен зависеть от платного API.
 * - **Второе мнение.** Правила нельзя уговорить: если движок видит перегрев, а модель
 *   сказала «можно ехать», расхождение показывается пользователю ([crossCheck]).
 * - **Заземление по построению.** Движок читает только то, что реально пришло с
 *   адаптера, поэтому каждое основание — [EvidenceKind.CONFIRMED]. Выдумать он не может.
 *
 * Пороги берутся из [ObdPid] (warn/crit), а не изобретаются здесь: единый источник.
 * Правила курируемые, а не механический обход всех порогов — раскрутка до 6500 об/мин
 * это не неисправность, а перегрев это неисправность.
 */
object TriageEngine {

    /** Строит структурный вердикт по данным машины. Тот же тип, что и у LLM-пути. */
    fun analyze(facts: TriageFacts): DiagnosticVerdict {
        if (!facts.hasAnyData) {
            return DiagnosticVerdict(
                severity = ReportStatus.NEED_DATA,
                summary = "Данных с машины нет — нечего анализировать.",
                checks = listOf("Подключите адаптер и повторите чтение."),
            )
        }

        val causes = mutableListOf<VerdictCause>()
        val checks = mutableListOf<String>()
        var severity = ReportStatus.OK

        fun raise(to: String) {
            severity = maxSeverity(severity, to)
        }

        // --- Перегрев: самое опасное, что видно по OBD. ---
        facts.metrics[ObdPid.COOLANT]?.let { t ->
            val crit = ObdPid.COOLANT.critHigh ?: Double.MAX_VALUE
            val warn = ObdPid.COOLANT.warnHigh ?: Double.MAX_VALUE
            if (t >= crit) {
                raise(ReportStatus.CRITICAL)
                causes += VerdictCause(
                    "Перегрев двигателя — ехать нельзя",
                    confidence = 95,
                    evidence = listOf(confirmed(ObdPid.COOLANT, t, "критический порог ${fmt(crit)} °C")),
                )
                checks += "Немедленно заглушить двигатель и дать остыть. Проверить уровень ОЖ, " +
                    "термостат, помпу, вентилятор и радиатор."
            } else if (t >= warn) {
                raise(ReportStatus.WARNING)
                causes += VerdictCause(
                    "Температура выше нормы — риск перегрева",
                    confidence = 75,
                    evidence = listOf(confirmed(ObdPid.COOLANT, t, "норма до ${fmt(warn)} °C")),
                )
                checks += "Проверить уровень ОЖ, работу вентилятора и термостат."
            }
        }

        // --- Бортовая сеть: судим только на заведённом двигателе. ---
        if (facts.engineRunning) {
            facts.metrics[ObdPid.VOLTAGE]?.let { v ->
                val critLow = ObdPid.VOLTAGE.critLow ?: 0.0
                val warnLow = ObdPid.VOLTAGE.warnLow ?: 0.0
                val warnHigh = ObdPid.VOLTAGE.warnHigh ?: Double.MAX_VALUE
                val critHigh = ObdPid.VOLTAGE.critHigh ?: Double.MAX_VALUE
                when {
                    v <= critLow -> {
                        raise(ReportStatus.CRITICAL)
                        causes += VerdictCause(
                            "Генератор не заряжает — машина скоро встанет",
                            confidence = 90,
                            evidence = listOf(confirmed(ObdPid.VOLTAGE, v, "на заведённом должно быть 13,5–14,5 В")),
                        )
                        checks += "Проверить ремень генератора, щётки и реле-регулятор."
                    }
                    v <= warnLow -> {
                        raise(ReportStatus.WARNING)
                        causes += VerdictCause(
                            "Слабая зарядка бортовой сети",
                            confidence = 70,
                            evidence = listOf(confirmed(ObdPid.VOLTAGE, v, "норма на заведённом 13,5–14,5 В")),
                        )
                        checks += "Проверить натяжение ремня и клеммы АКБ."
                    }
                    v >= critHigh -> {
                        raise(ReportStatus.WARNING)
                        causes += VerdictCause(
                            "Перезаряд — опасно для АКБ и электроники",
                            confidence = 85,
                            evidence = listOf(confirmed(ObdPid.VOLTAGE, v, "выше ${fmt(critHigh)} В")),
                        )
                        checks += "Проверить реле-регулятор напряжения."
                    }
                    v >= warnHigh -> {
                        raise(ReportStatus.WARNING)
                        causes += VerdictCause(
                            "Напряжение выше нормы",
                            confidence = 60,
                            evidence = listOf(confirmed(ObdPid.VOLTAGE, v, "норма 13,5–14,5 В")),
                        )
                        checks += "Проверить реле-регулятор напряжения."
                    }
                }
            }
        }

        // --- Топливные коррекции: главный индикатор смеси. ---
        val trim = fuelTrim(facts)
        if (trim != null) {
            val (pid, value) = trim
            val warnHigh = pid.warnHigh ?: 10.0
            val critHigh = pid.critHigh ?: 25.0
            val strong = value >= critHigh || value <= -critHigh
            if (value >= warnHigh) {
                raise(ReportStatus.WARNING)
                causes += VerdictCause(
                    if (strong) "Сильно бедная смесь" else "Бедная смесь",
                    confidence = if (strong) 85 else 65,
                    evidence = listOf(confirmed(pid, value, "норма ±${fmt(warnHigh)} %")),
                )
                checks += "Искать подсос воздуха (впуск, вакуумные шланги, прокладка дросселя): " +
                    "если бедно сильнее на холостых и выравнивается с оборотами — это подсос."
                checks += "Если бедно равномерно или хуже под нагрузкой — проверить подачу топлива " +
                    "(насос, фильтр, форсунки) и показания ДМРВ."
            } else if (value <= -warnHigh) {
                raise(ReportStatus.WARNING)
                causes += VerdictCause(
                    if (strong) "Сильно богатая смесь" else "Богатая смесь",
                    confidence = if (strong) 85 else 65,
                    evidence = listOf(confirmed(pid, value, "норма ±${fmt(warnHigh)} %")),
                )
                checks += "Проверить льющие форсунки, давление топлива, завышающий ДМРВ и EGR."
            }
        }

        // --- Коды неисправностей. ---
        val leanRich = causes.any { it.title.contains("смесь", ignoreCase = true) }
        facts.activeCodes.forEach { c ->
            causes += causeForCode(c, leanRich, checks)
            raise(ReportStatus.WARNING)
        }

        // Постоянные коды не стираются сбросом — значит ЭБУ не убедился в ремонте.
        if (facts.permanentCodes.isNotEmpty()) {
            raise(ReportStatus.WARNING)
            causes += VerdictCause(
                "Проблема не устранена: есть постоянные коды",
                confidence = 90,
                evidence = facts.permanentCodes.map {
                    Evidence("${it.code} — ${it.description} (постоянный код, Mode 0A)", EvidenceKind.CONFIRMED)
                },
            )
            checks += "Постоянные коды гаснут сами, только когда ЭБУ убедится в ремонте — сбросом их не убрать."
        }

        // Неподтверждённые коды — ранний сигнал, но не диагноз.
        if (facts.pendingCodes.isNotEmpty() && facts.activeCodes.isEmpty()) {
            raise(ReportStatus.WARNING)
            causes += VerdictCause(
                "Зарождающаяся неисправность (неподтверждённые коды)",
                confidence = 55,
                evidence = facts.pendingCodes.map {
                    Evidence("${it.code} — ${it.description} (ожидающий код, Mode 07)", EvidenceKind.CONFIRMED)
                },
            )
            checks += "Проехать ещё цикл и перечитать коды: подтвердится — станет активным."
        }

        // Готовность мониторов — не неисправность, но важно перед ТО.
        if (facts.readinessIncomplete.isNotEmpty()) {
            checks += "Мониторы не прошли самопроверку (${facts.readinessIncomplete.joinToString(", ")}) — " +
                "перед техосмотром проехать цикл готовности."
        }

        val summary = summarize(severity, causes)
        return DiagnosticVerdict(
            severity = severity,
            summary = summary,
            causes = causes.sortedByDescending { it.confidence },
            checks = checks.distinct(),
            diy = diyHint(severity),
        )
    }

    /**
     * Сравнивает вердикт модели с вердиктом движка. Возвращает предупреждение, если
     * модель мягче бортовых данных, — «можно ехать» при перегреве недопустимо.
     * `null` — расхождения нет.
     */
    fun crossCheck(llm: DiagnosticVerdict, local: DiagnosticVerdict): String? {
        if (rank(local.severity) <= rank(llm.severity)) return null
        val worst = local.causes.maxByOrNull { it.confidence }?.title ?: "отклонение в данных"
        return "Бортовые данные строже вердикта ассистента: по ним «${local.severity}» ($worst). " +
            "Доверять стоит данным — перепроверьте прежде, чем ехать."
    }

    /** Берём самую «говорящую» коррекцию: долгосрочная важнее мгновенной. */
    private fun fuelTrim(facts: TriageFacts): Pair<ObdPid, Double>? {
        facts.metrics[ObdPid.LTFT]?.let { return ObdPid.LTFT to it }
        facts.metrics[ObdPid.STFT]?.let { return ObdPid.STFT to it }
        return null
    }

    /** Курируемое правило по коду; для неизвестных — причина из расшифровки базы. */
    private fun causeForCode(c: TriageCode, leanOrRich: Boolean, checks: MutableList<String>): VerdictCause {
        val code = c.code.uppercase()
        val ev = listOf(Evidence("${c.code} — ${c.description} (код с адаптера)", EvidenceKind.CONFIRMED))
        val cylinder = MISFIRE_CYL.find(code)?.groupValues?.getOrNull(1)?.toIntOrNull()

        return when {
            // Пропуски в конкретном цилиндре — есть точный тест перестановкой.
            cylinder != null && cylinder > 0 -> {
                checks += "Переставить катушку (или свечу) с $cylinder-го цилиндра на соседний и " +
                    "перечитать коды: код уйдёт за деталью — виновата она; останется на $cylinder-м — " +
                    "смотреть форсунку, проводку и компрессию."
                VerdictCause(
                    "Пропуски воспламенения в $cylinder-м цилиндре" +
                        if (leanOrRich) " (но сначала разберитесь со смесью)" else "",
                    confidence = if (leanOrRich) 60 else 85,
                    evidence = ev,
                )
            }
            // Случайные пропуски — причина общая, не «одна катушка».
            code == "P0300" -> {
                checks += "Случайные пропуски — искать общее: качество и давление топлива, крупный " +
                    "подсос воздуха, слабое зажигание."
                VerdictCause("Случайные пропуски воспламенения", confidence = 75, evidence = ev)
            }
            code in LEAN_CODES -> VerdictCause("Бедная смесь (подтверждено кодом)", confidence = 85, evidence = ev)
            code in RICH_CODES -> VerdictCause("Богатая смесь (подтверждено кодом)", confidence = 85, evidence = ev)
            code in CAT_CODES -> {
                checks += "Перед заменой катализатора проверить датчики кислорода и устранить " +
                    "пропуски/переобогащение — иначе новый катализатор умрёт так же."
                VerdictCause("Эффективность катализатора ниже порога", confidence = 70, evidence = ev)
            }
            else -> VerdictCause(c.description.ifBlank { "Неисправность по коду ${c.code}" }, confidence = 70, evidence = ev)
        }
    }

    private fun summarize(severity: String, causes: List<VerdictCause>): String {
        val top = causes.maxByOrNull { it.confidence }?.title
        return when (severity) {
            ReportStatus.CRITICAL -> "${top ?: "Критическая неисправность"}. Ехать нельзя."
            ReportStatus.WARNING -> "${top ?: "Есть неисправность"}. Доехать до сервиса можно, откладывать нельзя."
            ReportStatus.OK -> "Активных неисправностей нет, параметры в норме — можно ездить."
            else -> "Данных недостаточно для вывода."
        }
    }

    private fun diyHint(severity: String): String = when (severity) {
        ReportStatus.CRITICAL -> "В сервис. Своими силами — только заглушить двигатель и не ехать."
        ReportStatus.WARNING -> "Проверки из списка можно сделать самому; замену узлов — по результату."
        ReportStatus.OK -> "Ничего делать не нужно."
        else -> "Сначала соберите данные."
    }

    /** Значение параметра как основание — по построению подтверждено данными. */
    private fun confirmed(pid: ObdPid, value: Double, note: String): Evidence =
        Evidence("${pid.label} ${fmt(value)} ${pid.unit} — $note", EvidenceKind.CONFIRMED)

    private fun fmt(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.US, "%.1f", v)

    private fun rank(severity: String): Int = when (severity) {
        ReportStatus.CRITICAL -> 3
        ReportStatus.WARNING -> 2
        ReportStatus.OK -> 1
        else -> 0 // НУЖНЫ ДАННЫЕ — не «мягче» и не «строже», просто нет вывода
    }

    private fun maxSeverity(a: String, b: String): String = if (rank(b) > rank(a)) b else a

    /** P0301…P0312 — пропуски в цилиндре 1…12 (номер цилиндра — в группе). */
    private val MISFIRE_CYL = Regex("^P03(0[1-9]|1[0-2])$")
    private val LEAN_CODES = setOf("P0171", "P0174")
    private val RICH_CODES = setOf("P0172", "P0175")
    private val CAT_CODES = setOf("P0420", "P0430")
}
