package ru.ngscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.ngscanner.agent.EvidenceKind
import ru.ngscanner.agent.VerdictGrounding
import ru.ngscanner.report.ReportStatus

/**
 * Проверка заземления вердикта — ядро защиты от галлюцинаций: основания модели
 * сверяются с тем, что РЕАЛЬНО пришло с адаптера.
 */
class VerdictGroundingTest {

    private val toolOutputs = listOf(
        "Активные коды:\nP0301 — пропуски воспламенения в цилиндре 1\nP0171 — бедная смесь (банк 1)",
        "Параметры:\nОбороты: 820 об/мин\nТемп. ОЖ: 92 °C\nLTFT: 18.0 %\nSTFT: 3.5 %",
    )

    private fun kind(text: String): EvidenceKind = VerdictGrounding.classify(
        text,
        VerdictGrounding.observedCodes(toolOutputs),
        VerdictGrounding.observedNumbers(toolOutputs),
    )

    @Test
    fun citedCodeThatAdapterReturnedIsConfirmed() {
        assertEquals(EvidenceKind.CONFIRMED, kind("P0301 — пропуски в 1-м цилиндре"))
        assertEquals(EvidenceKind.CONFIRMED, kind("код p0171 (бедная смесь)"))
    }

    @Test
    fun inventedCodeIsContradicted() {
        // Адаптер такого кода не отдавал — модель его выдумала.
        assertEquals(EvidenceKind.CONTRADICTED, kind("P0420 — эффективность катализатора ниже порога"))
        // Смесь реального и выдуманного тоже нельзя выдавать за факт.
        assertEquals(EvidenceKind.CONTRADICTED, kind("P0301 вместе с P0442"))
    }

    @Test
    fun citedValueFromAdapterIsConfirmed() {
        assertEquals(EvidenceKind.CONFIRMED, kind("LTFT +18% на холостых — смесь бедная"))
        assertEquals(EvidenceKind.CONFIRMED, kind("температура ОЖ 92 °C — прогрет"))
    }

    @Test
    fun reasoningWithoutCheckableClaimIsUnverified() {
        assertEquals(EvidenceKind.UNVERIFIED, kind("владелец жалуется на троение на холодную"))
        assertEquals(EvidenceKind.UNVERIFIED, kind("характерно для изношенных свечей"))
    }

    @Test
    fun unseenNumberIsNotConfirmed() {
        // Числа, которого в данных не было, недостаточно для «подтверждено».
        assertEquals(EvidenceKind.UNVERIFIED, kind("LTFT около 45% — сильно бедная"))
    }

    @Test
    fun parseBuildsGroundedVerdict() {
        val args = """
            {"severity":"ВНИМАНИЕ","summary":"Пропуски в 1-м цилиндре, до сервиса доехать можно",
             "causes":[
               {"title":"Катушка зажигания 1-го цилиндра","confidence":70,
                "evidence":["P0301 — пропуски в цилиндре 1","LTFT 18.0% — смесь бедная"]},
               {"title":"Износ свечей","confidence":30,"evidence":["типично для пробега"]}],
             "checks":["Переставить катушку на другой цилиндр"],
             "diy":"Своими силами, работа простая"}
        """.trimIndent()
        val v = VerdictGrounding.parse(args, toolOutputs)
        assertNotNull(v)
        requireNotNull(v)
        assertEquals(ReportStatus.WARNING, v.severity)
        assertEquals(2, v.causes.size)

        val coil = v.causes[0]
        assertEquals(70, coil.confidence)
        assertTrue(coil.grounded)
        assertTrue(coil.evidence.all { it.kind == EvidenceKind.CONFIRMED })

        // Вторая причина — чистое рассуждение, подтверждений нет.
        val plugs = v.causes[1]
        assertFalse(plugs.grounded)
        assertEquals(EvidenceKind.UNVERIFIED, plugs.evidence.single().kind)

        // Хотя бы одна причина заземлена → общий вывод не «в воздухе».
        assertFalse(v.ungrounded)
        assertFalse(v.hasContradiction)
        assertEquals(1, v.checks.size)
    }

    @Test
    fun parseFlagsInventedCode() {
        val args = """
            {"severity":"КРИТИЧНО","summary":"Катализатор разрушен",
             "causes":[{"title":"Катализатор","confidence":90,"evidence":["P0420 — низкая эффективность"]}]}
        """.trimIndent()
        val v = VerdictGrounding.parse(args, toolOutputs)
        requireNotNull(v)
        // Модель сослалась на код, которого адаптер не отдавал — это должно быть видно.
        assertTrue(v.hasContradiction)
        assertTrue(v.ungrounded)
        assertEquals(EvidenceKind.CONTRADICTED, v.causes.single().evidence.single().kind)
    }

    @Test
    fun severityNormalizedFromGarbage() {
        val args = """{"severity":"critical","summary":"перегрев"}"""
        val v = VerdictGrounding.parse(args, toolOutputs)
        requireNotNull(v)
        assertEquals(ReportStatus.CRITICAL, v.severity)
    }

    @Test
    fun garbageArgumentsReturnNull() {
        assertNull(VerdictGrounding.parse("не json вовсе", toolOutputs))
        // Пустой вердикт бесполезен — пусть модель договорит текстом.
        assertNull(VerdictGrounding.parse("""{"severity":"НОРМА"}""", toolOutputs))
    }

    @Test
    fun plainTextKeepsStatusLabelForExport() {
        val args = """
            {"severity":"КРИТИЧНО","summary":"Перегрев — ехать нельзя",
             "causes":[{"title":"Термостат","confidence":60,"evidence":["Темп. ОЖ 92 °C"]}],
             "checks":["Проверить помпу"],"diy":"В сервис"}
        """.trimIndent()
        val v = VerdictGrounding.parse(args, toolOutputs)
        requireNotNull(v)
        val text = v.toPlainText()
        // Метка нужна протоколам (F1) и экспорту — формат совпадает с прежним текстовым.
        assertTrue(text.startsWith("[СТАТУС: КРИТИЧНО]"))
        assertTrue(text.contains("Термостат"))
        assertTrue(text.contains("Проверить помпу"))
    }

    @Test
    fun observedCodesCollectedFromAllOutputs() {
        val codes = VerdictGrounding.observedCodes(toolOutputs)
        assertEquals(setOf("P0301", "P0171"), codes)
    }
}
