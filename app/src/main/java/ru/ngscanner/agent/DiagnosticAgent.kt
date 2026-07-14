package ru.ngscanner.agent

import ru.ngscanner.llm.LlmImage
import ru.ngscanner.llm.LlmMessage
import ru.ngscanner.llm.LlmProvider
import ru.ngscanner.llm.LlmRequest
import ru.ngscanner.llm.LlmResponse
import ru.ngscanner.llm.Role
import ru.ngscanner.llm.ToolResult

/**
 * Диагностический агентный цикл.
 *
 * Прогоняет запрос пользователя через выбранный [LlmProvider] с каталогом
 * инструментов [ObdTools], исполняет запрошенные вызовы через [ObdToolExecutor]
 * и возвращает результаты модели, пока та не сформирует финальный вердикт.
 * События ([AgentEvent]) стримятся в UI по мере работы.
 */
class DiagnosticAgent(
    private val provider: LlmProvider,
    private val model: String,
    private val executor: ObdToolExecutor,
) {
    suspend fun run(
        userMessage: String,
        images: List<LlmImage>,
        history: List<LlmMessage>,
        carContext: String? = null,
        adapterConnected: Boolean = true,
        connectionInfo: String? = null,
        onEvent: (AgentEvent) -> Unit,
    ): List<LlmMessage> {
        val messages = history.toMutableList()
        messages.add(LlmMessage(Role.USER, content = userMessage, images = images))
        // Системный промпт зависит от того, подключён ли адаптер: без него агент
        // отвечает на общие вопросы и не упирается в OBD-инструменты. Инфо о связи
        // (протокол, отвечает ли ЭБУ) заземляет агента, чтобы он не выдумывал причины.
        val system = buildString {
            append(SYSTEM_PROMPT)
            append("\n\n")
            append(if (adapterConnected) ADAPTER_ONLINE_NOTE else ADAPTER_OFFLINE_NOTE)
            // Эвристики нужны только когда есть живые данные — при офлайн Q&A не раздуваем промпт.
            if (adapterConnected) append("\n\n").append(DIAGNOSTIC_HEURISTICS)
            if (!connectionInfo.isNullOrBlank()) append("\n\n").append(connectionInfo)
            if (!carContext.isNullOrBlank()) append("\n\n").append(carContext)
        }

        // Сырые ответы инструментов — источник истины для проверки заземления вердикта:
        // на что модель сослалась, того адаптер мог и не отдавать.
        val toolOutputs = mutableListOf<String>()

        var steps = 0
        while (steps++ < MAX_STEPS) {
            val response = provider.send(LlmRequest(model, system, messages, ObdTools.all))
            response.usage?.let { onEvent(AgentEvent.Usage(it.prompt, it.completion)) }
            when (response) {
                is LlmResponse.Final -> {
                    messages.add(LlmMessage(Role.ASSISTANT, content = response.text))
                    onEvent(AgentEvent.Assistant(response.text))
                    return messages
                }
                is LlmResponse.ToolUse -> {
                    response.text?.takeIf { it.isNotBlank() }?.let { onEvent(AgentEvent.Assistant(it)) }
                    messages.add(
                        LlmMessage(Role.ASSISTANT, content = response.text, toolCalls = response.calls),
                    )
                    // Вердикт — не обращение к железу: исполнителю его не отдаём, разбираем сами.
                    val verdictCall = response.calls.firstOrNull { it.name == SUBMIT_VERDICT }
                    val results = response.calls.map { call ->
                        if (call.name == SUBMIT_VERDICT) {
                            ToolResult(call.id, "Вердикт принят и показан пользователю.")
                        } else {
                            onEvent(AgentEvent.ToolCall(call.name))
                            executor.execute(call).also { toolOutputs.add(it.content) }
                        }
                    }
                    messages.add(LlmMessage(Role.TOOL, toolResults = results))

                    if (verdictCall != null) {
                        val verdict = VerdictGrounding.parse(verdictCall.argumentsJson, toolOutputs)
                        if (verdict != null) {
                            // История обязана заканчиваться ходом ассистента, а не tool_result:
                            // иначе следующий запрос к Anthropic — некорректная последовательность.
                            val text = verdict.toPlainText()
                            messages.add(LlmMessage(Role.ASSISTANT, content = text))
                            onEvent(AgentEvent.Verdict(verdict))
                            return messages
                        }
                        // Мусор вместо JSON — не роняем диалог: пусть модель договорит текстом.
                    }
                }
            }
        }
        // Достигнут лимит шагов: даём модели финальный ход БЕЗ инструментов, чтобы она
        // подвела вердикт по уже собранным данным. Иначе история заканчивалась бы на
        // tool_result без ответного assistant — для Anthropic это некорректная
        // последовательность (риск 400 на следующем запросе), а пользователь так и не
        // получал бы структурный вердикт.
        val finalText = runCatching {
            val closing = provider.send(LlmRequest(model, system, messages, emptyList()))
            closing.usage?.let { u -> onEvent(AgentEvent.Usage(u.prompt, u.completion)) }
            when (closing) {
                is LlmResponse.Final -> closing.text
                is LlmResponse.ToolUse -> closing.text ?: ""
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "Достигнут лимит шагов диагностики. Однозначный вердикт по собранным данным " +
            "не сформирован — уточни симптом или повтори диагностику."
        messages.add(LlmMessage(Role.ASSISTANT, content = finalText))
        onEvent(AgentEvent.Assistant(finalText))
        return messages
    }

    companion object {
        const val MAX_STEPS = 12

        /** Терминальный инструмент: не идёт в железо, а завершает диалог вердиктом. */
        const val SUBMIT_VERDICT = "submit_verdict"

        const val SYSTEM_PROMPT: String =
            "Ты — опытный автомеханик-диагност. У тебя есть инструменты для чтения данных " +
                "автомобиля через OBD-II адаптер ELM327.\n\n" +
                "Порядок работы: пойми жалобу; при подключённом адаптере СНАЧАЛА вызови full_scan — " +
                "он одним махом соберёт коды всех типов, готовность мониторов, freeze frame и ключевые " +
                "живые параметры. Анализируй СВЯЗКУ данных (код + freeze frame + топливные коррекции + " +
                "параметры), а не один код в отрыве. Точечные инструменты (read_live_data, monitor_pid, " +
                "read_freeze_frame) — для доуточнения гипотезы после full_scan.\n\n" +
                "ГЛАВНОЕ ПРАВИЛО — заземление. Опирайся ТОЛЬКО на реальные данные из инструментов и " +
                "контекст этой машины. Не выдумывай коды, значения и причины. Если данных для вывода " +
                "не хватает — честно скажи, что нужно измерить, и НЕ придумывай правдоподобный " +
                "диагноз. Уверенно-неверный вывод про безопасность хуже честного «нужны данные».\n\n" +
                "ИТОГОВЫЙ ВЕРДИКТ — ТОЛЬКО через инструмент submit_verdict, не свободным текстом. " +
                "Когда готов поставить диагноз, вызови submit_verdict:\n" +
                "• severity — КРИТИЧНО (ехать нельзя: перегрев, падение давления масла, детонация, " +
                "сильные пропуски с риском катализатора) / ВНИМАНИЕ (можно аккуратно доехать до " +
                "сервиса) / НОРМА (можно ездить и наблюдать) / НУЖНЫ ДАННЫЕ (данных не хватает).\n" +
                "• summary — одна короткая строка: что вероятнее и можно ли ехать.\n" +
                "• causes — причины по убыванию вероятности; у КАЖДОЙ в evidence перечисли конкретные " +
                "коды и значения ИЗ ОТВЕТОВ ИНСТРУМЕНТОВ, на которых она стоит. Приложение сверит их " +
                "с реальными данными адаптера и покажет пользователю, что подтверждено. Ссылка на " +
                "код, которого не было в ответах, будет помечена как выдумка — не делай так.\n" +
                "• checks — что проверить ДО замены деталей (не «поменяй катушку», а «проверь X; если " +
                "Y — тогда катушка»).\n" +
                "• diy — своими силами или в сервис и насколько серьёзна работа.\n" +
                "Severity ставь по реальным данным, не на всякий случай. При НУЖНЫ ДАННЫЕ перечисли в " +
                "checks, что именно снять или измерить.\n\n" +
                "Постоянные коды (read_permanent_dtcs) не стираются сбросом, пока ЭБУ не убедится в " +
                "ремонте — если есть, проблема не устранена. Готовность мониторов (read_readiness) — " +
                "перед техосмотром и после сброса кодов.\n\n" +
                "Инструмент clear_dtcs вызывай только по явной просьбе пользователя; он требует " +
                "отдельного подтверждения в приложении.\n\n" +
                "Сформулировав вердикт или заметив важный факт по машине — сохрани кратко через " +
                "save_to_logbook, чтобы учесть при будущих диагностиках. Не дублируй записи из " +
                "контекста автомобиля.\n\n" +
                "Форматирование: экран узкий. НЕ используй Markdown-таблицы — их не прочитать. Данные " +
                "давай списком или строками «Параметр: значение». Заголовки держи короткими."

        /** Ситуативная вставка, когда адаптер подключён и инструменты доступны. */
        const val ADAPTER_ONLINE_NOTE: String =
            "Адаптер ELM327 подключён — инструменты чтения данных доступны. Если пользователь " +
                "описал симптом и не просил иного, начни с full_scan."

        /**
         * Выверенные эвристики автодиагностики — вставляются в промпт при подключённом
         * адаптере, чтобы дисциплинировать рассуждение (особенно у слабых моделей) и
         * связывать параметры в причину. Применять ТОЛЬКО с реальными данными, не вслепую.
         */
        const val DIAGNOSTIC_HEURISTICS: String =
            "ЭВРИСТИКИ (применяй с реальными данными из инструментов, не наугад):\n" +
                "• Топливные коррекции STFT/LTFT стабильно от +10% = бедная смесь: подсос воздуха, " +
                "слабая подача топлива (насос/фильтр/форсунки) или заниженный MAF. Если бедно СИЛЬНЕЕ " +
                "на холостых и выравнивается с оборотами → подсос воздуха; если равномерно/хуже под " +
                "нагрузкой → подача топлива или MAF.\n" +
                "• STFT/LTFT стабильно от −10% = богатая смесь: льющая форсунка, высокое давление " +
                "топлива, завышающий MAF, застрявший открытым EGR.\n" +
                "• Пропуски по КОНКРЕТНОМУ цилиндру (P0301…P030x): предложи переставить катушку/свечу " +
                "на другой цилиндр — уйдёт код за деталью → она виновна; останется → форсунка/" +
                "компрессия/проводка этого цилиндра. P0300 (случайные) → общее: топливо (давление/" +
                "качество), крупный подсос, слабое зажигание.\n" +
                "• Пропуски + высокий LTFT (бедно) → причина скорее в смеси (подсос/подача), а не в свече.\n" +
                "• Перегрев при РАБОТАЮЩЕМ вентиляторе → термостат/помпа/воздушная пробка/забитый " +
                "радиатор; при НЕработающем на горячую → датчик/реле/мотор вентилятора.\n" +
                "• Напряжение бортсети на оборотах <13.0 В → слабый генератор/регулятор/ремень/" +
                "окисленные клеммы; >15 В → неисправный регулятор; проседает под нагрузкой → генератор/АКБ.\n" +
                "• Плавают обороты ХХ + высокий STFT → подсос воздуха либо грязный дроссель/РХХ.\n" +
                "• Лямбда «застряла» (не колеблется) → мёртвый датчик кислорода или стабильный перекос смеси.\n" +
                "• Есть ПОСТОЯННЫЕ коды (Mode 0A) → проблема не устранена, ЭБУ не подтвердил ремонт; " +
                "сбросом они не убираются.\n" +
                "Всегда сверяй с freeze frame (при каких оборотах/темп/нагрузке возникло) и с нормой " +
                "для этой машины."

        /** Ситуативная вставка, когда адаптер не подключён — общий режим Q&A. */
        const val ADAPTER_OFFLINE_NOTE: String =
            "Адаптер ELM327 сейчас НЕ подключён. Отвечай на общие вопросы об устройстве, " +
                "эксплуатации, обслуживании и ремонте автомобиля напрямую, своими знаниями — не " +
                "вызывай OBD-инструменты и не требуй подключать адаптер. Предложи подключить " +
                "адаптер на вкладке «Приборы» только если для точного ответа действительно нужны " +
                "коды или живые параметры именно этой машины."
    }
}

/** События агента для отображения в чате и учёта расхода. */
sealed interface AgentEvent {
    data class Assistant(val text: String) : AgentEvent
    data class ToolCall(val name: String) : AgentEvent

    /** Структурный вердикт с проверенным заземлением — вместо свободного текста. */
    data class Verdict(val verdict: DiagnosticVerdict) : AgentEvent

    /** Расход токенов за один ответ модели. */
    data class Usage(val prompt: Int, val completion: Int) : AgentEvent
}
