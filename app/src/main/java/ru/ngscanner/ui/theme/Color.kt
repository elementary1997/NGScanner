package ru.ngscanner.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Палитра «automotive cockpit» — тёмный графит с бирюзовым (cyan) акцентом.
 * Тема фиксированная (см. [NgScannerTheme]): приборная панель — это «ночной»
 * визуальный мир, поэтому динамические цвета Material You не используются.
 */

// --- Фон и поверхности (холодный графит) ---
val CockpitBg = Color(0xFF0B0E11)            // основной фон
val CockpitSurface = Color(0xFF12171C)       // базовая поверхность
val CockpitCardLow = Color(0xFF171D24)       // карточки (ElevatedCard)
val CockpitCard = Color(0xFF1B222A)          // приподнятые контейнеры, навбар
val CockpitCardHigh = Color(0xFF1F2730)      // шторки, диалоги
val CockpitCardHighest = Color(0xFF263039)   // самый светлый контейнер
val CockpitLine = Color(0xFF28323C)          // hairline-границы
val CockpitVariant = Color(0xFF222C36)        // surfaceVariant (треки, чипы)

// --- Текст ---
val CockpitText = Color(0xFFEAEEF2)          // основной
val CockpitDim = Color(0xFF8A96A2)           // приглушённый (подписи, единицы)

// --- Акцент (бирюза приборов) ---
val CockpitAccent = Color(0xFF34E1CE)
val CockpitAccentInk = Color(0xFF04140F)     // текст на акценте (кнопки)
val CockpitAccentContainer = Color(0xFF123A36)
val CockpitOnAccentContainer = Color(0xFF7DF0E0)

// --- Семантика состояний (общие для приборов и статусов) ---
val StatusGood = Color(0xFF4FE0A0)           // норма
val StatusWarn = Color(0xFFFFB84D)           // внимание (выше/ниже нормы)
val StatusCrit = Color(0xFFFF5A6A)           // критично
val StatusWarnInk = Color(0xFF241700)
val StatusWarnContainer = Color(0xFF3A2A0A)
val StatusCritInk = Color(0xFF2A0409)
val StatusCritContainer = Color(0xFF3A1116)
val StatusOnCritContainer = Color(0xFFFFB3B8)

// --- Дуга gauge: спокойная бирюза → янтарь → «redline» ---
val GaugeCyan = CockpitAccent
val GaugeAmber = StatusWarn
val GaugeRed = StatusCrit
