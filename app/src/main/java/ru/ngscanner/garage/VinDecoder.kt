package ru.ngscanner.garage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Результат разбора VIN: то, что удалось извлечь из декодера. */
data class VinInfo(
    val make: String,
    val model: String,
    val year: Int? = null,
    val engine: String? = null,
    val fuel: String? = null,
)

/**
 * Декодер VIN через бесплатный сервис NHTSA vPIC (без ключа, US-ориентирован,
 * но покрывает большинство мировых брендов). Возвращает марку/модель/год и,
 * если есть, объём двигателя и тип топлива. Требует интернет.
 */
object VinDecoder {

    // callTimeout ограничивает весь вызов — иначе спиннер распознавания VIN
    // висит бесконечно на «капризной» сети.
    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun decode(vin: String): VinInfo? = withContext(Dispatchers.IO) {
        val clean = vin.trim().uppercase()
        if (clean.length !in 11..17) return@withContext null
        // Регион VIN «X» — Россия/СНГ. Такие авто американский сервис почти не знает,
        // поэтому определяем марку офлайн по WMI и не обращаемся за границу.
        if (clean.startsWith("X")) {
            wmiMake(clean)?.let { return@withContext VinInfo(it, model = "", year = yearFromVin(clean)) }
        }
        // Иномарки — онлайн-сервис (полные данные: марка, модель, год).
        val online = runCatching { decodeOnline(clean) }.getOrNull()
        if (online != null) return@withContext online
        // Резерв по WMI (в т.ч. если сети нет).
        val make = wmiMake(clean) ?: return@withContext null
        VinInfo(make = make, model = "", year = yearFromVin(clean))
    }

    private fun decodeOnline(clean: String): VinInfo? {
        val url = "https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVinValues/$clean?format=json"
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        return client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val row = json.parseToJsonElement(body).jsonObject["Results"]
                ?.jsonArray?.firstOrNull()?.jsonObject ?: return null

            fun field(name: String): String? =
                row[name]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }

            // Без марки от сервиса считаем, что он не знает VIN (частый случай для РФ).
            val make = field("Make")?.let(::titleCase) ?: return null
            val model = field("Model")?.let(::titleCase).orEmpty()
            val year = field("ModelYear")?.toIntOrNull() ?: yearFromVin(clean)
            val displacement = field("DisplacementL")?.let { l ->
                runCatching { "%.1f".format(l.toDouble()) }.getOrNull()?.let { "$it л" }
            }
            VinInfo(make, model, year, displacement, field("FuelTypePrimary"))
        }
    }

    /** Марка по WMI (первые 3 символа VIN) — офлайн-таблица, упор на рынок РФ. */
    private fun wmiMake(vin: String): String? {
        if (vin.length < 3) return null
        return WMI[vin.substring(0, 3)]
    }

    private val WMI = mapOf(
        // Россия и СНГ
        "XTA" to "Lada", "XTB" to "Lada", "X9L" to "Lada",
        "XTT" to "УАЗ", "XTH" to "ГАЗ", "XTC" to "КамАЗ",
        "X4X" to "BMW", "X7L" to "Renault", "XW8" to "Volkswagen",
        "XW7" to "Toyota", "XWE" to "Hyundai", "XWH" to "Hyundai",
        "XWB" to "Ravon", "XUF" to "Chevrolet", "XUU" to "Chevrolet",
        "Z94" to "Hyundai", "Z8N" to "Nissan", "Z6F" to "Ford", "Z8T" to "SsangYong",
        // Мировые (резерв, если сервис недоступен)
        "WVW" to "Volkswagen", "WV1" to "Volkswagen", "WV2" to "Volkswagen",
        "WAU" to "Audi", "TRU" to "Audi", "WBA" to "BMW", "WBS" to "BMW",
        "WDB" to "Mercedes-Benz", "WDD" to "Mercedes-Benz", "W1K" to "Mercedes-Benz",
        "W0L" to "Opel", "VF1" to "Renault", "VF3" to "Peugeot", "VF7" to "Citroen",
        "ZFA" to "Fiat", "TMB" to "Skoda", "TMP" to "Skoda", "VSS" to "Seat",
        "JMB" to "Mitsubishi", "JHM" to "Honda", "JN1" to "Nissan", "JN8" to "Nissan",
        "JTD" to "Toyota", "JT1" to "Toyota", "JT2" to "Toyota", "JF1" to "Subaru",
        "KMH" to "Hyundai", "KNA" to "Kia", "KNB" to "Kia", "KL1" to "Chevrolet",
        "SAL" to "Land Rover", "SAJ" to "Jaguar", "YV1" to "Volvo", "YS3" to "Saab",
        // Китайские марки (активно продаются в РФ)
        "LVT" to "Chery", "LVV" to "Chery", "LVS" to "Chery",
        "L6T" to "Geely", "LB3" to "Geely", "LB2" to "Geely",
        "LGW" to "Haval", "LGX" to "BYD", "LC0" to "BYD",
        "LS5" to "Changan", "LS4" to "Changan", "LJ1" to "JAC",
        "LFV" to "FAW", "LMG" to "GAC", "LFP" to "Lifan",
    )

    /**
     * Год модели по 10-й позиции VIN (резерв, если сервис не вернул ModelYear).
     *
     * Код 10-й позиции повторяется каждые 30 лет (A = 1980 и 2010, 1 = 2001 и
     * 2031). Циклы различаем по 7-й позиции: у легковых цифра → 1980–2009,
     * буква → 2010–2039. Без этого буквы завышают год на 30 лет (W → 2028
     * вместо 1998 для типичного X-VIN из РФ/СНГ).
     */
    private fun yearFromVin(vin: String): Int? {
        if (vin.length < 10) return null
        val code = vin[9]
        val secondCycle = vin.length > 6 && vin[6].isLetter()
        val letters = "ABCDEFGHJKLMNPRSTVWXY" // без I,O,Q,U,Z
        letters.indexOf(code).takeIf { it >= 0 }?.let { return (if (secondCycle) 2010 else 1980) + it }
        "123456789".indexOf(code).takeIf { it >= 0 }?.let { return (if (secondCycle) 2031 else 2001) + it }
        return null
    }

    /** «CHEVROLET» → «Chevrolet», «LAND ROVER» → «Land Rover». */
    private fun titleCase(s: String): String =
        s.lowercase().split(' ').joinToString(" ") { w ->
            w.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
}
