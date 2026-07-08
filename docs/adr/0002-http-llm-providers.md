# ADR 0002. LLM-провайдеры через HTTP вместо Anthropic SDK

- Статус: принято
- Дата: 2026-07-09

## Контекст

Нужны два провайдера за интерфейсом `LlmProvider`: Anthropic Claude (Messages
API) и Cloud.ru (OpenAI-совместимый). Для Claude есть официальный Anthropic Java
SDK, но при tool-use round-trip из Kotlin он громоздок (union-типы блоков,
builder'ы, конвертация `JsonValue`) и тянет Apache `httpclient5`, который дал
конфликт дублирующихся `META-INF/DEPENDENCIES` при упаковке APK (пришлось
добавлять `packaging`-блок).

## Решение

Оба провайдера реализованы поверх **OkHttp + kotlinx.serialization** как тонкие
HTTP-клиенты. Claude — через Anthropic Messages API (`x-api-key`,
`tool_use`/`tool_result`); Cloud.ru — через OpenAI-совместимый
`/chat/completions` (`Bearer`, `tools`/`tool_calls`). Зависимость
`anthropic-java` удалена.

## Последствия

- Единый подход и полный контроль над форматом tool use у обоих провайдеров.
- Легче APK, нет конфликта `META-INF` из-за `httpclient5`.
- Обновления Anthropic SDK не подхватываются автоматически: при изменении
  Messages API HTTP-слой правится вручную (для стабильного API приемлемо).
