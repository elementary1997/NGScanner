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
        runCatching {
            val url = "https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVinValues/$clean?format=json"
            val request = Request.Builder().url(url).header("Accept", "application/json").build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string() ?: return@runCatching null
                val row = json.parseToJsonElement(body).jsonObject["Results"]
                    ?.jsonArray?.firstOrNull()?.jsonObject ?: return@runCatching null

                fun field(name: String): String? =
                    row[name]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }

                // Без марки VIN бесполезен; модель и год можем доопределить.
                val make = field("Make")?.let(::titleCase) ?: return@runCatching null
                val model = field("Model")?.let(::titleCase).orEmpty()
                val year = field("ModelYear")?.toIntOrNull() ?: yearFromVin(clean)
                val displacement = field("DisplacementL")?.let { l ->
                    runCatching { "%.1f".format(l.toDouble()) }.getOrNull()?.let { "$it л" }
                }
                VinInfo(make, model, year, displacement, field("FuelTypePrimary"))
            }
        }.getOrNull()
    }

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
