# AGENTS.md

Единый источник инструкций для AI-кодинг-агентов (Claude Code, Cursor, Copilot,
Aider и др.). Файл `CLAUDE.md` — симлинк на этот документ. Правь только этот файл.

## О проекте

**NG Scanner** — Android-приложение для диагностики автомобиля: подключение к
OBD-II адаптеру ELM327 по Bluetooth, чтение данных с ЭБУ и диагностика через LLM
(Anthropic Claude или модели Cloud.ru). Обзорно — см. [README.md](README.md).

> Статус: ранняя разработка. В репозитории каркас; реализация идёт по этапам
> (дорожная карта — в README). Скелеты помечены `TODO(этап N)`.

## Стек и требования

- **Kotlin** + **Jetpack Compose**, Kotlin Coroutines
- **JDK 17**, Android SDK: `compileSdk 35`, `minSdk 26` (Android 8.0)
- **Gradle** (wrapper 8.11.1) с version catalog
- OkHttp, kotlinx.serialization (оба LLM-провайдера работают через HTTP — см. ADR 0002)

Точные версии — **единственный источник** [`gradle/libs.versions.toml`](gradle/libs.versions.toml).
Зависимости добавляются только через этот каталог, не хардкодом в `build.gradle.kts`.

## Команды

```bash
./gradlew assembleDebug        # отладочный APK
./gradlew testDebugUnitTest    # юнит-тесты
./gradlew lintDebug            # статический анализ
```

CI (`.github/workflows/ci.yml`) прогоняет все три на push и PR. Перед пушем
прогоняй их локально. Если локально нет JDK/Android SDK — см. README «Сборка».

## Структура

```
app/src/main/java/ru/ngscanner/
  MainActivity.kt      экран на Compose
  transport/           ObdTransport + ClassicSppTransport, BleTransport
  obd/                 Elm327 (драйвер), ObdPid (каталог PID)
  llm/                 LlmProvider + ClaudeProvider, CloudRuProvider
  agent/               DiagnosticAgent (цикл), ObdTools, ObdToolExecutor
  settings/            хранилище настроек и API-ключей
  ui/                  Compose-экран, тема, ViewModel
```

## Ключевые абстракции — не ломать

- **`ObdTransport`** — канал до адаптера. Новый тип адаптера (USB, Wi-Fi, другой
  BLE) = новая реализация интерфейса. OBD-слой и выше не меняются.
- **`LlmProvider`** — провайдер модели. Новый провайдер = новая реализация.
  Агентный цикл (`DiagnosticAgent`) работает только с интерфейсом и не знает,
  какой провайдер выбран.
- **`ObdTools`** — инструменты модели в нейтральном виде (`ToolSpec`). Каждый
  провайдер сам конвертирует их в свой формат function calling (Anthropic
  `tool_use` / OpenAI `tools`).

Добавляешь фичу — сначала пойми, в какой слой она попадает, и не протаскивай
детали транспорта/провайдера сквозь абстракцию.

## Конвенции

- Пакет `ru.ngscanner`. Kotlin official style, отступ 4 пробела, строка ≤ 120.
- **Комментарии и документация — на русском**; идентификаторы кода — на английском.
- Скелеты помечай `TODO(этап N)` в соответствии с дорожной картой README.

## Границы и безопасность

- **Никогда не зашивай API-ключи** в код, ресурсы или коммиты. В приложении ключи
  хранятся в `EncryptedSharedPreferences`; локально — в `.env` (игнорируется git,
  шаблон — `.env.example`).
- **`clear_dtcs`** (сброс кодов, Mode 04) стирает данные — вызывается только после
  явного подтверждения пользователя в UI. Не обходить этот гейт.
- Не выдумывать коды и значения параметров — только реальные данные с адаптера и
  расшифровка из локальной базы DTC.

## Чего не делать

- Не коммитить `.env`, ключи, `*.keystore`.
- Не менять `.github/workflows/` без явной причины (CI настроен).
- Не перезаписывать этот файл без согласования с владельцем.

## Ссылки

- [README.md](README.md) — обзор и дорожная карта
- [docs/ecosystem.md](docs/ecosystem.md) — open-source библиотеки, базы DTC, лицензии
- [docs/adr/](docs/adr/) — архитектурные решения
