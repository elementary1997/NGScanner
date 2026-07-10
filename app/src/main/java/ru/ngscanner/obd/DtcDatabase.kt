package ru.ngscanner.obd

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Офлайн-словарь диагностических кодов неисправностей (DTC, SAE J2012).
 *
 * Коды и их русские расшифровки хранятся в `assets/dtc.json` как плоский
 * JSON-объект «код → описание» (например `"P0301"` → «Пропуски воспламенения
 * в цилиндре 1»). Файл читается один раз и кэшируется в памяти процесса;
 * ввод-вывод и парсинг выполняются на [Dispatchers.IO].
 *
 * В словаре — только generic-коды (P0xxx, U0xxx, распространённые P2xxx) плюс
 * P1000 (статус готовности мониторов); mfr-специфичные P1xxx, а также B/C-коды
 * пока не входят. Незнакомый код возвращает `null`: выдумывать расшифровку нельзя
 * (см. AGENTS.md, раздел «Границы и безопасность»).
 */
object DtcDatabase {

    private val json = Json { ignoreUnknownKeys = true }

    /** Кэш словаря; заполняется при первом обращении. */
    @Volatile
    private var cache: Map<String, String>? = null

    /**
     * Загружает словарь DTC (один раз) и возвращает карту «код → описание».
     *
     * Повторные вызовы отдают кэш без обращения к диску.
     */
    suspend fun load(context: Context): Map<String, String> {
        cache?.let { return it }
        return withContext(Dispatchers.IO) {
            // Двойная проверка: пока ждали IO-поток, кэш мог заполнить другой вызов.
            cache?.let { return@withContext it }
            val appContext = context.applicationContext
            val text = appContext.assets.open(ASSET_NAME).use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            }
            val map = json.decodeFromString<Map<String, String>>(text)
            cache = map
            map
        }
    }

    /**
     * Возвращает русское описание кода [code] или `null`, если кода нет в
     * словаре.
     *
     * Регистр и обрамляющие пробелы во входной строке не важны: « p0301 »
     * найдёт «P0301».
     */
    suspend fun describe(context: Context, code: String): String? =
        describeIn(load(context), code)

    /**
     * Чистый поиск кода в готовой карте (тестируется без Context). Регистр и
     * обрамляющие пробелы не важны. Незнакомый код → `null` (не выдумываем).
     */
    internal fun describeIn(map: Map<String, String>, code: String): String? {
        val normalized = code.trim().uppercase()
        if (normalized.isEmpty()) return null
        return map[normalized]
    }

    private const val ASSET_NAME = "dtc.json"
}
