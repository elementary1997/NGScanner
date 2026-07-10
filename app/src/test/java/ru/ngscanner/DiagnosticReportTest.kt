package ru.ngscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.ngscanner.report.DiagnosticReport
import ru.ngscanner.report.ReportStatus
import ru.ngscanner.report.buildTranscript
import ru.ngscanner.report.parseReport
import ru.ngscanner.report.parseStatusLabel
import ru.ngscanner.report.toMarkdown

/** Чистые функции протокола: рендер, парсинг статуса/JSON, фолбэк, сборка транскрипта. */
class DiagnosticReportTest {

    private fun report(
        statusLabel: String = ReportStatus.CRITICAL,
        verdict: String = "Ехать нельзя — риск перегрева",
        findings: List<String> = listOf("P0217 — перегрев двигателя"),
        causes: List<String> = listOf("низкий уровень ОЖ"),
        recommendations: List<String> = listOf("проверить систему охлаждения"),
        rawBody: String? = null,
    ) = DiagnosticReport(
        id = "id1", carId = "car1", dateIso = "2026-07-10", title = "Протокол",
        carTitle = "Lada Vesta", carSpec = "1.6 · 2019", mileageKm = 84000, vin = "XTA...",
        complaint = "загорелся чек", statusLabel = statusLabel, verdict = verdict,
        findings = findings, causes = causes, recommendations = recommendations,
        provider = "Claude", model = "claude", rawBody = rawBody,
    )

    @Test
    fun markdownHasPassportStatusAndSections() {
        val md = report().toMarkdown()
        assertTrue(md.contains("Lada Vesta"))
        assertTrue(md.contains("84000 км"))
        assertTrue(md.contains("[СТАТУС: КРИТИЧНО]"))
        assertTrue(md.contains("## Находки"))
        assertTrue(md.contains("## Вероятные причины"))
        assertTrue(md.contains("## Что проверить и рекомендации"))
        assertTrue(md.contains("- P0217 — перегрев двигателя"))
    }

    @Test
    fun markdownOmitsEmptySections() {
        val md = report(causes = emptyList(), recommendations = emptyList()).toMarkdown()
        assertTrue(md.contains("## Находки"))
        assertFalse(md.contains("## Вероятные причины"))
        assertFalse(md.contains("## Что проверить"))
    }

    @Test
    fun markdownRawBodyReplacesSections() {
        val md = report(rawBody = "Свободный текст без JSON").toMarkdown()
        assertTrue(md.contains("Свободный текст без JSON"))
        assertFalse(md.contains("## Находки"))
    }

    @Test
    fun parseStatusLabelVariants() {
        assertEquals(ReportStatus.WARNING, parseStatusLabel("[СТАТУС: ВНИМАНИЕ] можно доехать"))
        assertEquals(ReportStatus.CRITICAL, parseStatusLabel("бла [СТАТУС: КРИТИЧНО] бла"))
        assertEquals(ReportStatus.NEED_DATA, parseStatusLabel("без метки вовсе"))
        assertEquals(ReportStatus.NEED_DATA, parseStatusLabel("[СТАТУС: абракадабра]"))
    }

    private fun parse(text: String) = parseReport(
        text = text, id = "id1", carId = "car1", dateIso = "2026-07-10", title = "T",
        carTitle = "Lada Vesta", carSpec = "1.6 · 2019", mileageKm = 84000, vin = "XTA",
        provider = "Claude", model = "claude",
    )

    @Test
    fun parseValidJson() {
        val json = """
            {"complaint":"стук","statusLabel":"ВНИМАНИЕ","verdict":"можно доехать до сервиса",
             "findings":["P0300 — пропуски"],"causes":["свечи"],"recommendations":["заменить свечи"]}
        """.trimIndent()
        val r = parse(json)
        assertEquals("id1", r.id)
        assertEquals("car1", r.carId)
        assertEquals("Lada Vesta", r.carTitle)
        assertEquals(ReportStatus.WARNING, r.statusLabel)
        assertEquals("можно доехать до сервиса", r.verdict)
        assertEquals(listOf("P0300 — пропуски"), r.findings)
        assertNull(r.rawBody)
    }

    @Test
    fun parseJsonWrappedInFences() {
        val text = "Вот протокол:\n```json\n{\"verdict\":\"ок\",\"statusLabel\":\"НОРМА\"}\n```\nГотово."
        val r = parse(text)
        assertEquals(ReportStatus.OK, r.statusLabel)
        assertEquals("ок", r.verdict)
        assertNull(r.rawBody)
    }

    @Test
    fun parseInvalidJsonFallsBackToRawBody() {
        val text = "[СТАТУС: КРИТИЧНО] Двигатель перегрет, ехать нельзя."
        val r = parse(text)
        assertNotNull(r.rawBody)
        assertEquals(text, r.rawBody)
        assertEquals(ReportStatus.CRITICAL, r.statusLabel)
        assertTrue(r.verdict.isNotBlank())
    }

    @Test
    fun buildTranscriptFiltersAndLabels() {
        val turns = listOf(
            true to "у меня стук",
            false to "покажите коды",
            true to "",           // пустое выкидываем
            false to "вижу P0300",
        )
        val t = buildTranscript(turns)
        assertTrue(t.contains("Владелец: у меня стук"))
        assertTrue(t.contains("Диагност: покажите коды"))
        assertTrue(t.contains("Диагност: вижу P0300"))
        assertFalse(t.contains("Владелец: \n"))
    }

    @Test
    fun buildTranscriptTakesTailUnderLimit() {
        val turns = (1..100).map { true to "реплика номер $it с текстом" }
        val t = buildTranscript(turns, limit = 200)
        assertTrue(t.length <= 200)
        // Хвост: последняя реплика должна присутствовать, ранние — обрезаться.
        assertTrue(t.contains("номер 100"))
        assertFalse(t.contains("номер 1 "))
    }
}
