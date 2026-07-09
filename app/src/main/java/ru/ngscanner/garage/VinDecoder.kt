package ru.ngscanner.garage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

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

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun decode(vin: String): VinInfo? = withContext(Dispatchers.IO) {
        val clean = vin.trim().uppercase()
        if (clean.length !in 11..17) return@withContext null
        // Сначала онлайн-сервис (полные данные для иномарок).
        val online = runCatching { decodeOnline(clean) }.getOrNull()
        if (online != null) return@withContext online
        // Офлайн-резерв: марка по WMI + год из VIN. Покрывает авто рынка РФ и работу без сети.
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
        val wmi = vin.substring(0, 3)
        WMI[wmi]?.let { return it }
        // Для ряда изготовителей значим только код страны+завода из 2 символов.
        return WMI[vin.substring(0, 2)]
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
    )

    /** Год модели по 10-й позиции VIN (резерв, если сервис не вернул ModelYear). */
    private fun yearFromVin(vin: String): Int? {
        if (vin.length < 10) return null
        val code = vin[9]
        // A=2010 … Y=2039, 1=2001 … 9=2009 (буквы I,O,Q,U,Z в VIN не используются).
        val yearMap = buildMap {
            var y = 2010
            for (c in "ABCDEFGHJKLMNPRSTVWXY") { put(c, y); y++ }
            var n = 2001
            for (c in "123456789") { put(c, n); n++ }
        }
        return yearMap[code]
    }

    /** «CHEVROLET» → «Chevrolet», «LAND ROVER» → «Land Rover». */
    private fun titleCase(s: String): String =
        s.lowercase().split(' ').joinToString(" ") { w ->
            w.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
}
