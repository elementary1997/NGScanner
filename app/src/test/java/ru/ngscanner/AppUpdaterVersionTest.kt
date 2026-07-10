package ru.ngscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.ngscanner.update.AppUpdater

/** Сравнение версий обновления — чтобы не предлагать «обновиться» на ту же/старую. */
class AppUpdaterVersionTest {

    @Test
    fun detectsHigherVersion() {
        assertTrue(AppUpdater.isNewer("0.2.0", "0.1.0"))
        assertTrue(AppUpdater.isNewer("1.0.0", "0.9.9"))
        assertTrue(AppUpdater.isNewer("0.1.1", "0.1.0"))
    }

    @Test
    fun sameOrOlderIsNotNewer() {
        assertFalse(AppUpdater.isNewer("0.1.0", "0.1.0"))
        assertFalse(AppUpdater.isNewer("0.1.0", "0.2.0"))
    }

    @Test
    fun normalizeStripsVPrefix() {
        assertEquals("0.2.0", AppUpdater.normalize("v0.2.0"))
        assertEquals("0.2.0", AppUpdater.normalize(" V0.2.0 "))
    }

    @Test
    fun handlesDifferentSegmentCounts() {
        assertTrue(AppUpdater.isNewer("0.2", "0.1.9"))
        assertFalse(AppUpdater.isNewer("0.1", "0.1.0"))
    }
}
