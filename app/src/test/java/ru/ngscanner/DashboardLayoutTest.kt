package ru.ngscanner

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.ngscanner.obd.ObdPid
import ru.ngscanner.ui.components.DashboardLayout

/** Логика раскладки кастомного дашборда: порядок, дедуп, фильтр по поддержке. */
class DashboardLayoutTest {

    @Test
    fun preservesUserOrder() {
        assertEquals(
            listOf(ObdPid.VOLTAGE, ObdPid.RPM),
            DashboardLayout.resolve(listOf("VOLTAGE", "RPM"), emptySet()),
        )
    }

    @Test
    fun emptyStoredReturnsDefault() {
        assertEquals(DashboardLayout.DEFAULT_ORDER, DashboardLayout.resolve(emptyList(), emptySet()))
    }

    @Test
    fun dropsUnknownAndDuplicates() {
        assertEquals(
            listOf(ObdPid.RPM, ObdPid.COOLANT),
            DashboardLayout.resolve(listOf("RPM", "NOPE", "RPM", "COOLANT"), emptySet()),
        )
    }

    @Test
    fun filtersBySupportedWhenNonEmpty() {
        assertEquals(
            listOf(ObdPid.RPM),
            DashboardLayout.resolve(listOf("RPM", "COOLANT"), setOf(ObdPid.RPM.cmd)),
        )
    }

    @Test
    fun noFilterWhenSupportedEmpty() {
        assertEquals(
            listOf(ObdPid.RPM, ObdPid.COOLANT),
            DashboardLayout.resolve(listOf("RPM", "COOLANT"), emptySet()),
        )
    }

    @Test
    fun onlyUnsupportedGivesEmpty() {
        assertEquals(emptyList<ObdPid>(), DashboardLayout.resolve(listOf("RPM"), setOf("XXXX")))
    }

    @Test
    fun moveUpDownWithEdges() {
        assertEquals(listOf("B", "A", "C"), DashboardLayout.moveUp(listOf("A", "B", "C"), "B"))
        assertEquals(listOf("A", "C", "B"), DashboardLayout.moveDown(listOf("A", "B", "C"), "B"))
        assertEquals(listOf("A", "B"), DashboardLayout.moveUp(listOf("A", "B"), "A"))
        assertEquals(listOf("A", "B"), DashboardLayout.moveDown(listOf("A", "B"), "B"))
    }
}
