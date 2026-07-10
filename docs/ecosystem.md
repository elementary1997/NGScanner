# Инструментарий для автодиагностики и автоэлектрики

Каталог MCP-серверов, open-source библиотек и ресурсов для сборки AI-инструментария
под автоэлектрику и диагностику автомобилей.

> Источник: deep-research (5 фаз, 103 агента, адверсариальная верификация).
> Данные актуальны на **2026-07-09**. Ниша молодая — перепроверяйте зрелость проектов.
> Полный сырой отчёт с цитатами и голосами верификации: [`research-raw.json`](./research-raw.json).

**Легенда доверия:**
- ✅ — утверждение прошло адверсариальную верификацию (high confidence)
- ⚠️ — найдено в поиске, но **не** проверено; перепроверьте перед использованием

---

## Короткий вывод

**Готовой «кнопки» нет.** Экосистема MCP под авто — прототипы с единицами звёзд и коммитов.
Зато **строительные блоки зрелые**. Реалистичный путь: тонкая собственная MCP-обёртка над
`python-OBD` (или SocketCAN через `can327`) + локальные базы DTC. Как отправная точка — готовый
пример под Claude `petrpatek/obd2-mcp-server` (~250 строк).

---

## 1. Готовые MCP-серверы

| Проект | Что делает | Зрелость | Лицензия |
|---|---|---|---|
| ⚠️ [petrpatek/obd2-mcp-server](https://github.com/petrpatek/obd2-mcp-server) | **Ближе всего к запросу.** Соединяет Claude с OBD-II через дешёвый ($15) Bluetooth ELM327 (Vgate vLinker). Read-only чтение DTC и live-сенсоров с объяснением простым языком. Встроенная база ~1937 Ford-specific DTC + generic SAE | из поиска, не верифицирован | проверить |
| ✅ [castlebbs/Embedded-MCP-ELM327](https://github.com/castlebbs/Embedded-MCP-ELM327) | Хакатонный PoC: MCP-сервер **зашит в прошивку** WiFi-донгла (чип WinnerMicro W600). Инструменты: `status`, `send_elm327_command` (pass-through AT/PID: чтение/сброс DTC, RPM, скорость), `get_elm327_history` | PoC, дек. 2025, ~2 коммита, 4★ | MIT |
| ✅ [farzadnadiri/MCP-CAN](https://github.com/farzadnadiri/MCP-CAN) | Декодирование CAN через DBC/cantools для LLM по SSE (порт 6278). Инструменты: `read_can_frames`, `decode_can_frame`, `filter_frames`, `monitor_signal`, `dbc_info`. **По умолчанию симулятор виртуальной шины, без железа**; реальный доступ опционально через SocketCAN | v0.1.0, 9★, «educational only» | MIT |

⚠️ **Оговорки:**
- Заявление, что Embedded-MCP-ELM327 подключается к Claude Code как remote HTTP MCP «из коробки»,
  **опровергнуто верификацией (0-3)** — совместимость проверять руками.
- `petrpatek` проверен только по сниппету, не адверсариально.

---

## 2. Строительные блоки для своей обёртки (основа)

| Проект | Роль | Зрелость | Лицензия |
|---|---|---|---|
| ✅ [python-OBD](https://github.com/brendan-w/python-OBD) ([docs](https://python-obd.readthedocs.io/)) | **Кандидат №1 на обёртку.** Телеметрия в реальном времени + чтение DTC (Mode 03) с описаниями. Заточен под дешёвые ELM327, работает на Raspberry Pi | v0.7.3 (апр. 2025), 1.3k★, статус Alpha | **GPL-2.0** ⚠️ copyleft |
| ✅ [opendbc](https://github.com/commaai/opendbc) (comma.ai) | Python API к машине по CAN: чтение состояния + управление актуаторами. ~70+ DBC-файлов (Tesla, Toyota, Ford…) + библиотека парсинга CAN. DTC/OBD-II **нет** | v0.3.1 (апр. 2026), 3.3k★, живой | MIT |
| ✅ [can327](https://docs.kernel.org/networking/device_drivers/can/can327.html) (ядро Linux ≥6.0) | Превращает ELM327-адаптер в полноценный **SocketCAN**-интерфейс — работают `candump`, `python-can`, `cantools` без спец-библиотек. Документация ядра: для OBD-II запрос/ответ и пассивного мониторинга «более чем достаточно» | mainline с 6.0 (2022) | GPL (ядро) |
| ✅ [ELM327-emulator](https://github.com/Ircama/ELM327-emulator) (Ircama) | **Разработка без машины.** Эмулирует несколько ECU, поддерживает stateful UDS/ISO-TP/KWP2000, тестируется с python-OBD | v3.0.4 (фев. 2026), 654★, живой | **CC-BY-NC-SA** ⚠️ только некоммерч. |
| (косв.) python-can + cantools | Базовый слой SocketCAN + декодирование DBC — фундамент под MCP-CAN и всю CAN-экосистему | зрелые | — |

**Смысл:** python-OBD (диагностика через ELM327) и связка can327 + python-can + cantools (сырой CAN)
закрывают почти всё. ELM327-emulator — разработка/тесты без реальной машины.

---

## 3. Открытые базы DTC-кодов

| Проект | Объём/формат | Лицензия |
|---|---|---|
| ✅ [Wal33D/dtc-database](https://github.com/Wal33D/dtc-database) | Локальный SQLite (~3.1 МБ) + обёртки Python/Java/Android/TS. Python-обёртка на чистом stdlib, **ноль зависимостей** — тривиально в MCP | MIT (⚠️ рекламные «28 220 кодов» **опровергнуты**, реальный охват меньше) |
| ✅ [mytrile/obd-trouble-codes](https://github.com/mytrile/obd-trouble-codes) | 3071 код (P/B/C/U) в JSON/CSV/**SQLite**. Берите CSV или SQLite (JSON битый) | MIT |
| ✅ [misspellted/DTC-Database](https://github.com/misspellted/DTC-Database) | Крупнейший дамп: **6665** кодов в JSON+XML | ⚠️ **лицензии нет** — статус неясен |
| ✅ [OBDb](https://github.com/OBDb) ([community](https://obdb.community)) | Активный: **741 репозиторий** по маркам/моделям с машиночитаемыми signalsets/v3 (PID, скалирование) прямо из git. DTC-покрытие неравномерно | CC BY-SA 4.0 |
| ⚠️ [carapi.app](https://carapi.app/features/obd-code-api) | Hosted REST API расшифровки кодов (free tier) — если нужен онлайн вместо локального дампа | коммерческий |
| ⚠️ [fabiovila/OBDIICodes](https://github.com/fabiovila/OBDIICodes) | Дамп в JSON + C-header | — |

**Рекомендация:** Wal33D (готовая SQLite + Python-обёртка) как основной локальный источник +
OBDb для PID/скалирования по конкретным моделям.

---

## 4. Автоэлектрика: схемы, harness, расчёты ⚠️

Самый **слабо проверенный** блок — эти находки не прошли верификацию (отброшены по бюджету).
Даю как лиды, перепроверяйте:

- ⚠️ [charm.li](https://charm.li/) (Operation CHARM) — бесплатный архив OEM сервис-мануалов с
  электросхемами для 50 000+ авто (1982–2013). Отдают всю БД (~700 ГБ) и код сайта открыто →
  **можно self-host и индексировать как reference-слой для агента**. Преемник «LEMON» до ~2025.
  Лучший кандидат под базу знаний по схемам.
- ⚠️ [WireViz](https://github.com/wireviz/WireViz) (GPL-3.0, Python) — генерит SVG/PNG схемы жгутов
  из YAML + автоматический BOM. Идеально под агента: Claude пишет YAML → WireViz рендерит схему.
  Легко в MCP-tool или скилл.
- ⚠️ [BBB Industries](https://www.bbbind.com/tsb-wiring-diagrams/) — бесплатная база OE-схем + TSB
  по всем маркам (регистрация email). API нет — доступ через WebFetch/scraping.

**Пробел:** готовых калькуляторов сечений проводов и номиналов предохранителей не найдено —
вероятно, реализовать самому (формулы простые: по току, длине, допустимому падению напряжения).

---

## 5. Готовые AI-ассистенты для механиков ⚠️

Не прошло верификацию, но прямо релевантно:

- ⚠️ [speed785/open-mechanic](https://github.com/speed785/open-mechanic) — open-source «AI-механик»:
  OBD-II USB + **Claude API** = диагноз на естественном языке и пошаговые repair-гайды. Поллинг
  сенсоров, чтение/декодирование DTC, лог сессий в SQLite, структурированный JSON. Кроссплатформенный.
  Хороший референс архитектуры.
- ⚠️ [bitvea.com блог](https://bitvea.com/en/blog/claude-mcp-car-diagnostics-obd2) — практический разбор
  «Claude + OBD-II + BLE + MCP за вечер» на живом Ford Focus CC 2010. Доказательство, что подход
  работает на реальной машине.

---

## 6. Расшифровка VIN (марка/модель/год/двигатель)

> Раздел добавлен вручную (2026-07-09) и выверен по исходникам и эталонным
> тестам библиотек, а не из того deep-research прогона, — легенда ✅ здесь
> означает «проверено по коду/тесту». Решение зафиксировано в
> [ADR-0003](adr/0003-vin-decoding-offline-first.md).

| Проект | Что даёт | Офлайн | Лицензия |
|---|---|---|---|
| ✅ [idlesign/vininfo](https://github.com/idlesign/vininfo) | Детальный разбор VDS: **знает АвтоВАЗ** (`XTAGFK330JY144213` → Vesta, двиг. 21179, Ижевск), а также Renault, Nissan, Opel, Ford Australia. Проверка контрольной цифры | да | **BSD-3-Clause** (нужна атрибуция) |
| ✅ [NHTSA vPIC](https://vpic.nhtsa.dot.gov/api/) | Публичный REST `DecodeVinValues`, без ключа. Хорош по иномаркам, **российские VIN почти не знает** | нет (сеть) | US Gov (public domain) |
| ⚠️ [kabirnayeem99/kVinInfo](https://github.com/kabirnayeem99/kVinInfo) | Kotlin Multiplatform: базовый разбор WMI/год/checksum + опц. вызов NHTSA. Общая WMI-база, **специфики ВАЗ нет** | да (базово) | MIT |
| ⚠️ Автокод / SpectrumData / avtoapi | Платные РФ-агрегаторы: полная комплектация + **история/ДТП/ограничения/пробег**. Ключ, деньги (отчёты «от 17 ₽»), B2B-договор, часто async | нет (сеть) | коммерческий |

**Что используется в проекте:** офлайн-первый разбор в
[`garage/VinDecoder.kt`](../app/src/main/java/ru/ngscanner/garage/VinDecoder.kt):
марка по WMI-таблице, детали ВАЗ по VDS (таблицы **портированы из vininfo**,
BSD-3), год по 10-й позиции; иномарки — резерв через vPIC. Платный API и
kVinInfo сознательно не подключены (см. ADR-0003).

⚠️ **Лицензионная заметка:** таблицы ВАЗ в `VinDecoder` — производная от vininfo
(BSD-3), требуется сохранение копирайта/атрибуции (оформлено в комментарии кода).

---

## Рекомендуемая сборка

1. **Ядро диагностики:** форкнуть/изучить `petrpatek/obd2-mcp-server`, либо своя тонкая MCP-обёртка
   над `python-OBD`. Для сырого CAN — `can327` (SocketCAN) + `python-can`/`cantools` + DBC из `opendbc`.
2. **Разработка без машины:** `ELM327-emulator` (помнить про NC-лицензию — только личная разработка).
3. **База кодов:** `Wal33D/dtc-database` (SQLite) + `OBDb` (PID по моделям) как MCP-ресурс или прямые SQL.
4. **Схемы:** self-host `charm.li` как reference-слой + `WireViz` как скилл для документирования проводки.

### ⚠️ Лицензионные ловушки
- **python-OBD — GPL-2.0**: copyleft при распространении обёртки (для личного инструментария ОК).
- **ELM327-emulator — CC-BY-NC-SA**: коммерческое использование запрещено.
- **misspellted/DTC-Database — без лицензии**: юридически рискованно переиспользовать.
- «Чистые» MIT/CC BY-SA: opendbc, MCP-CAN, Embedded-MCP-ELM327, Wal33D, mytrile, OBDb.

### Заметки по среде (WSL2)
- Для `can327` нужно ядро с `CONFIG_CAN_327` — в стоковом WSL2 может не быть (проверьте `modinfo can327`).
- Сквозная беда экосистемы — **поддельные дешёвые ELM327-клоны**: могут не синхронизироваться с
  python-OBD и несовместимы с can327 при прошивке <1.3.

---

## Открытые вопросы (для доисследования)

- Пункт 4 (схемы, даташиты, калькуляторы сечений/предохранителей) — нет верифицированных находок,
  нужен отдельный проход.
- Пункт 5 (зрелые AI-ассистенты для механиков) — ни одного подтверждённого утверждения.
- Реально ли подключить Embedded-MCP-ELM327 к Claude Code как remote HTTP MCP (утверждение опровергнуто).
- Насколько полно в OBDb покрыты именно DTC per-vehicle (в отличие от signalsets).
