package ru.ngscanner

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.ngscanner.agent.ObdToolExecutor
import ru.ngscanner.llm.ToolCall
import ru.ngscanner.obd.Elm327

class ObdToolExecutorTest {

    private fun executor(elm: Elm327, allow: Boolean) =
        ObdToolExecutor(elm, allowClearDtcs = { allow })

    @Test
    fun clearDtcsBlockedByDefault() = runBlocking {
        val elm = Elm327(FakeObdTransport(mapOf("04" to "44 00")))
        val result = executor(elm, allow = false).execute(ToolCall("1", "clear_dtcs", "{}"))
        assertTrue(result.content.contains("Отклонено"))
    }

    @Test
    fun clearDtcsRunsWhenApproved() = runBlocking {
        val elm = Elm327(FakeObdTransport(mapOf("04" to "44 00")))
        val result = executor(elm, allow = true).execute(ToolCall("2", "clear_dtcs", "{}"))
        assertTrue(result.content.contains("сброшены"))
    }

    @Test
    fun readDtcsReportsBusErrorNotHealthy() = runBlocking {
        val elm = Elm327(FakeObdTransport(mapOf("03" to "UNABLE TO CONNECT")))
        val result = executor(elm, allow = false).execute(ToolCall("3", "read_dtcs", "{}"))
        assertTrue(result.content.contains("НЕТ СВЯЗИ"))
    }

    @Test
    fun readDtcsDecodesCodes() = runBlocking {
        // Легаси-ответ с кодом P0133
        val elm = Elm327(FakeObdTransport(mapOf("03" to "43 01 33")))
        val result = executor(elm, allow = false).execute(ToolCall("4", "read_dtcs", "{}"))
        assertTrue(result.content.contains("P0133"))
    }
}
