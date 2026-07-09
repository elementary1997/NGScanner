package ru.ngscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.ngscanner.agent.ObdTools
import ru.ngscanner.obd.ObdPid

class ObdPidTest {

    @Test
    fun engineRpmPidIsCorrect() {
        assertEquals("010C", ObdPid.RPM.cmd)
    }

    @Test
    fun toolCatalogExposesClearDtcs() {
        assertTrue(ObdTools.all.any { it.name == "clear_dtcs" })
    }
}
