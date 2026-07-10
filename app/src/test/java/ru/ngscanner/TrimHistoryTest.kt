package ru.ngscanner

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.ngscanner.llm.LlmMessage
import ru.ngscanner.llm.Role
import ru.ngscanner.ui.MainViewModel

/**
 * Обрезка истории для модели — тонкое правило парности tool_use/tool_result.
 * Регрессия здесь ломает КАЖДЫЙ длинный диалог с инструментами (провайдер → 400),
 * поэтому логика вынесена в чистую [MainViewModel.trimHistory] и покрыта тестами.
 */
class TrimHistoryTest {

    private fun user(i: Int) = LlmMessage(Role.USER, content = "u$i")
    private fun asst(i: Int) = LlmMessage(Role.ASSISTANT, content = "a$i")
    private fun tool(i: Int) = LlmMessage(Role.TOOL, content = "t$i")

    @Test
    fun shorterThanLimitReturnedAsIs() {
        val h = listOf(user(1), asst(1), user(2))
        assertEquals(h, MainViewModel.trimHistory(h, maxMsgs = 40))
    }

    @Test
    fun trimsForwardToFirstUserInWindow() {
        // 6 сообщений, окно 3: срез с index 3 (assistant) сдвигается вперёд до USER.
        val h = listOf(user(1), asst(1), tool(1), asst(2), user(2), asst(3))
        val trimmed = MainViewModel.trimHistory(h, maxMsgs = 3)
        assertEquals(Role.USER, trimmed.first().role)
        assertEquals("u2", trimmed.first().content)
    }

    @Test
    fun expandsBackWhenNoUserInTailWindow() {
        // Хвостовое окно без USER → расширяем назад до последнего USER (иначе начали
        // бы с tool_result, что даёт 400 у провайдера).
        val h = listOf(user(1), asst(1), tool(1), asst(2), tool(2))
        val trimmed = MainViewModel.trimHistory(h, maxMsgs = 2)
        assertEquals(Role.USER, trimmed.first().role)
        assertEquals(h, trimmed)
    }

    @Test
    fun returnsWholeWhenNoUserAtAll() {
        val h = listOf(asst(1), tool(1), asst(2), tool(2))
        assertEquals(h, MainViewModel.trimHistory(h, maxMsgs = 2))
    }
}
