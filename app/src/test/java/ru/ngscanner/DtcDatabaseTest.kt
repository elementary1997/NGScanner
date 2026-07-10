package ru.ngscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.ngscanner.obd.DtcDatabase

/**
 * Чистый поиск кода в словаре: нормализация ввода и — главное — `null` на
 * незнакомый код (расшифровку выдумывать нельзя, см. AGENTS.md).
 */
class DtcDatabaseTest {

    private val map = mapOf("P0301" to "Пропуски воспламенения в цилиндре 1")

    @Test
    fun normalizesCaseAndSpaces() {
        assertEquals("Пропуски воспламенения в цилиндре 1", DtcDatabase.describeIn(map, " p0301 "))
    }

    @Test
    fun unknownCodeReturnsNullNotInvented() {
        assertNull(DtcDatabase.describeIn(map, "P9999"))
    }

    @Test
    fun blankReturnsNull() {
        assertNull(DtcDatabase.describeIn(map, "   "))
    }
}
