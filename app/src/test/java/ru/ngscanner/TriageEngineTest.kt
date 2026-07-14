package ru.ngscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.ngscanner.agent.DiagnosticVerdict
import ru.ngscanner.agent.EvidenceKind
import ru.ngscanner.obd.ObdPid
import ru.ngscanner.report.ReportStatus
import ru.ngscanner.triage.TriageCode
import ru.ngscanner.triage.TriageEngine
import ru.ngscanner.triage.TriageFacts

/** Офлайн-движок правил: детерминированный триаж по реальным данным машины. */
class TriageEngineTest {

    private val idle = mapOf(ObdPid.RPM to 820.0, ObdPid.COOLANT to 90.0, ObdPid.VOLTAGE to 14.1)

    @Test
    fun overheatIsCritical() {
        val v = TriageEngine.analyze(
            TriageFacts(metrics = idle + (ObdPid.COOLANT to 118.0)),
        )
        assertEquals(ReportStatus.CRITICAL, v.severity)
        assertTrue(v.summary.contains("нельзя"))
        assertTrue(v.causes.first().title.contains("Перегрев"))
        // Заземление по построению: движок читает только реальные данные.
        assertTrue(v.causes.first().evidence.all { it.kind == EvidenceKind.CONFIRMED })
        assertTrue(v.causes.first().grounded)
    }

    @Test
    fun mildOverheatIsWarning() {
        val v = TriageEngine.analyze(TriageFacts(metrics = idle + (ObdPid.COOLANT to 110.0)))
        assertEquals(ReportStatus.WARNING, v.severity)
    }

    @Test
    fun alternatorNotChargingIsCritical() {
        // Двигатель работает (RPM > 500), напряжение ниже критического порога.
        val v = TriageEngine.analyze(TriageFacts(metrics = idle + (ObdPid.VOLTAGE to 11.5)))
        assertEquals(ReportStatus.CRITICAL, v.severity)
        assertTrue(v.causes.any { it.title.contains("Генератор") })
    }

    @Test
    fun lowVoltageOnStoppedEngineIsNotJudged() {
        // Двигатель заглушен — 11.5 В на АКБ это нормально, не диагноз.
        val v = TriageEngine.analyze(
            TriageFacts(metrics = mapOf(ObdPid.RPM to 0.0, ObdPid.VOLTAGE to 11.5)),
        )
        assertFalse(v.causes.any { it.title.contains("Генератор") })
    }

    @Test
    fun leanMixtureFromFuelTrim() {
        val v = TriageEngine.analyze(TriageFacts(metrics = idle + (ObdPid.LTFT to 18.0)))
        assertEquals(ReportStatus.WARNING, v.severity)
        val cause = v.causes.first { it.title.contains("едная") }
        assertTrue(cause.grounded)
        // Эвристика подсоса должна попасть в проверки.
        assertTrue(v.checks.any { it.contains("подсос", ignoreCase = true) })
    }

    @Test
    fun strongRichMixtureHasHigherConfidence() {
        val mild = TriageEngine.analyze(TriageFacts(metrics = idle + (ObdPid.LTFT to -12.0)))
        val strong = TriageEngine.analyze(TriageFacts(metrics = idle + (ObdPid.LTFT to -30.0)))
        val mildC = mild.causes.first { it.title.contains("огатая") }
        val strongC = strong.causes.first { it.title.contains("огатая") }
        assertTrue(strongC.confidence > mildC.confidence)
        assertTrue(strongC.title.contains("Сильно"))
    }

    @Test
    fun misfireCodeNamesCylinderAndSuggestsSwap() {
        val v = TriageEngine.analyze(
            TriageFacts(
                activeCodes = listOf(TriageCode("P0303", "пропуски воспламенения, цилиндр 3")),
                metrics = idle,
            ),
        )
        assertEquals(ReportStatus.WARNING, v.severity)
        assertTrue(v.causes.any { it.title.contains("3-м цилиндре") })
        // Ключевой тест: не «поменяй катушку», а перестановка для проверки.
        assertTrue(v.checks.any { it.contains("Переставить") })
    }

    @Test
    fun misfireCylinderTenParsed() {
        val v = TriageEngine.analyze(
            TriageFacts(activeCodes = listOf(TriageCode("P0310", "пропуски, цилиндр 10")), metrics = idle),
        )
        assertTrue(v.causes.any { it.title.contains("10-м цилиндре") })
    }

    @Test
    fun misfireWithLeanMixtureDefersToMixture() {
        // Эвристика: пропуски + бедная смесь → причина скорее в смеси, а не в свече.
        val v = TriageEngine.analyze(
            TriageFacts(
                activeCodes = listOf(TriageCode("P0301", "пропуски, цилиндр 1")),
                metrics = idle + (ObdPid.LTFT to 20.0),
            ),
        )
        val misfire = v.causes.first { it.title.contains("цилиндре") }
        val mixture = v.causes.first { it.title.contains("едная") }
        assertTrue("смесь должна быть выше пропусков", mixture.confidence > misfire.confidence)
    }

    @Test
    fun permanentCodesMeanNotRepaired() {
        val v = TriageEngine.analyze(
            TriageFacts(permanentCodes = listOf(TriageCode("P0420", "катализатор")), metrics = idle),
        )
        assertEquals(ReportStatus.WARNING, v.severity)
        assertTrue(v.causes.any { it.title.contains("не устранена") })
    }

    @Test
    fun catalystCodeWarnsBeforeReplacing() {
        val v = TriageEngine.analyze(
            TriageFacts(activeCodes = listOf(TriageCode("P0420", "эффективность катализатора")), metrics = idle),
        )
        assertTrue(v.checks.any { it.contains("Перед заменой катализатора") })
    }

    @Test
    fun cleanCarIsOk() {
        val v = TriageEngine.analyze(TriageFacts(metrics = idle))
        assertEquals(ReportStatus.OK, v.severity)
        assertTrue(v.causes.isEmpty())
        assertTrue(v.summary.contains("можно ездить"))
    }

    @Test
    fun noDataMeansNoVerdict() {
        val v = TriageEngine.analyze(TriageFacts())
        assertEquals(ReportStatus.NEED_DATA, v.severity)
    }

    @Test
    fun readinessIncompleteAddsCheckWithoutRaisingSeverity() {
        val v = TriageEngine.analyze(
            TriageFacts(metrics = idle, readinessIncomplete = listOf("Катализатор", "EVAP")),
        )
        assertEquals(ReportStatus.OK, v.severity) // не неисправность
        assertTrue(v.checks.any { it.contains("техосмотром") })
    }

    // ---- Второе мнение: движок против модели ----

    @Test
    fun crossCheckFiresWhenModelIsSofterThanData() {
        // Модель сказала «можно ездить», а по данным перегрев.
        val llm = DiagnosticVerdict(severity = ReportStatus.OK, summary = "Всё в порядке, можно ехать")
        val local = TriageEngine.analyze(TriageFacts(metrics = idle + (ObdPid.COOLANT to 120.0)))
        val note = TriageEngine.crossCheck(llm, local)
        assertNotNull(note)
        assertTrue(note!!.contains(ReportStatus.CRITICAL))
    }

    @Test
    fun crossCheckSilentWhenModelIsStricterOrEqual() {
        val local = TriageEngine.analyze(TriageFacts(metrics = idle))
        assertNull(TriageEngine.crossCheck(DiagnosticVerdict(ReportStatus.OK, "норма"), local))
        assertNull(TriageEngine.crossCheck(DiagnosticVerdict(ReportStatus.CRITICAL, "плохо"), local))
    }

    @Test
    fun crossCheckSilentWhenNoLocalData() {
        // Нет данных — движку нечего противопоставить, молчим (а не пугаем).
        val local = TriageEngine.analyze(TriageFacts())
        assertNull(TriageEngine.crossCheck(DiagnosticVerdict(ReportStatus.OK, "норма"), local))
    }
}
