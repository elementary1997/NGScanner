package ru.ngscanner.garage

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Марка автомобиля из офлайн-справочника (`assets/vehicles.json`).
 *
 * Справочник — подсказка для UX при добавлении машины в «Гараж», а не
 * источник диагностических данных.
 */
@Serializable
data class VehicleMake(
    val make: String,
    val ru: String? = null,
    val models: List<VehicleModel>,
)

/**
 * Модель (с необязательным поколением и годами выпуска) внутри марки.
 *
 * `yearTo == null` означает, что модель всё ещё в производстве.
 */
@Serializable
data class VehicleModel(
    val model: String,
    val ru: String? = null,
    val generation: String? = null,
    val yearFrom: Int,
    val yearTo: Int? = null,
    val engines: List<String> = emptyList(),
)

/**
 * Плоская подсказка для выпадающего списка: одна строка = марка + модель
 * (+ поколение и, при выборе, конкретный двигатель).
 */
data class VehicleSuggestion(
    val make: String,
    val model: String,
    val makeRu: String? = null,
    val modelRu: String? = null,
    val generation: String? = null,
    val yearFrom: Int,
    val yearTo: Int? = null,
    val engine: String? = null,
    val engines: List<String> = emptyList(),
) {
    /** Читабельный заголовок на русском, например «Шкода Октавия A7». */
    val title: String
        get() = buildString {
            append(makeRu ?: make)
            append(' ')
            append(modelRu ?: model)
            if (!generation.isNullOrBlank()) {
                append(' ')
                append(generation)
            }
        }

    /** Период выпуска, например «2010–2015» или «2010–н.в.». */
    val years: String
        get() = "$yearFrom–${yearTo?.toString() ?: "н.в."}"
}

/**
 * Офлайн-каталог марок и моделей с живым поиском.
 *
 * Данные читаются один раз из `assets/vehicles.json` и кэшируются в памяти
 * процесса. Ввод-вывод и парсинг выполняются на [Dispatchers.IO].
 */
object VehicleCatalog {

    private val json = Json { ignoreUnknownKeys = true }

    /** Кэш загруженного справочника; заполняется при первом обращении. */
    @Volatile
    private var cache: List<VehicleMake>? = null

    /**
     * Загружает справочник (один раз) и возвращает список марок.
     *
     * Повторные вызовы отдают кэш без обращения к диску.
     */
    suspend fun load(context: Context): List<VehicleMake> {
        cache?.let { return it }
        return withContext(Dispatchers.IO) {
            // Двойная проверка: пока ждали IO-поток, кэш мог заполнить другой вызов.
            cache?.let { return@withContext it }
            val appContext = context.applicationContext
            val text = appContext.assets.open(ASSET_NAME).use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            }
            val makes = json.decodeFromString<List<VehicleMake>>(text)
            cache = makes
            makes
        }
    }

    /**
     * Ищет подсказки по строке [query].
     *
     * Запрос разбивается на токены по пробелам (в нижнем регистре); каждый
     * токен должен встретиться в строке «make model generation». Пустой
     * запрос возвращает первые [limit] моделей. Ранжирование простое:
     * совпадение с начала слова важнее совпадения в середине.
     */
    suspend fun search(
        context: Context,
        query: String,
        limit: Int = 20,
    ): List<VehicleSuggestion> {
        val makes = load(context)
        val all = flatten(makes)

        val tokens = query.lowercase().trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) {
            return all.take(limit)
        }

        val scored = ArrayList<Pair<VehicleSuggestion, Int>>()
        for (suggestion in all) {
            val haystack = suggestion.searchText
            val score = scoreOrNull(haystack, tokens) ?: continue
            scored.add(suggestion to score)
        }
        // Стабильная сортировка: сначала по убыванию релевантности, порядок
        // внутри одинаковых очков сохраняется из исходного справочника.
        scored.sortByDescending { it.second }
        return scored.take(limit).map { it.first }
    }

    /** Разворачивает марки в плоский список подсказок «одна модель = одна строка». */
    private fun flatten(makes: List<VehicleMake>): List<VehicleSuggestion> {
        val result = ArrayList<VehicleSuggestion>()
        for (make in makes) {
            for (model in make.models) {
                result.add(
                    VehicleSuggestion(
                        make = make.make,
                        model = model.model,
                        makeRu = make.ru,
                        modelRu = model.ru,
                        generation = model.generation,
                        yearFrom = model.yearFrom,
                        yearTo = model.yearTo,
                        engines = model.engines,
                    ),
                )
            }
        }
        return result
    }

    /**
     * Считает релевантность: возвращает `null`, если хотя бы один токен не
     * найден. Иначе за каждый токен, стоящий в начале слова, начисляется
     * больше очков, чем за совпадение в середине.
     */
    private fun scoreOrNull(haystack: String, tokens: List<String>): Int? {
        var score = 0
        for (token in tokens) {
            val index = haystack.indexOf(token)
            if (index < 0) return null
            val atWordStart = index == 0 || haystack[index - 1] == ' '
            score += if (atWordStart) 2 else 1
        }
        return score
    }

    /**
     * Строка для поиска — латинские и русские названия марки и модели плюс
     * поколение, в нижнем регистре. Даёт совпадение независимо от языка ввода.
     */
    private val VehicleSuggestion.searchText: String
        get() = buildString {
            append(make.lowercase()).append(' ')
            makeRu?.let { append(it.lowercase()).append(' ') }
            append(model.lowercase()).append(' ')
            modelRu?.let { append(it.lowercase()).append(' ') }
            if (!generation.isNullOrBlank()) append(generation.lowercase())
        }

    private const val ASSET_NAME = "vehicles.json"
    private val WHITESPACE = Regex("\\s+")
}
