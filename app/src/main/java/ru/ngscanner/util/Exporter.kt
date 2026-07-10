package ru.ngscanner.util

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.ngscanner.obd.ObdPid
import ru.ngscanner.trips.Trip
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Экспорт ответа агента: копирование делается на месте, а этот объект собирает
 * PDF из текста и открывает системный «Поделиться» для текста или PDF.
 */
object Exporter {

    // Сериализует сборку PDF: два тапа подряд не пишут в один файл одновременно.
    private val exportMutex = Mutex()

    /** Делится обычным текстом через системный диалог. */
    fun shareText(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Поделиться ответом"))
    }

    /**
     * Собирает CSV записанной поездки/события: строка — синхронный снимок, колонки —
     * время (с от начала) и значения параметров. Разделитель `;` и точка в числах —
     * открывается и в Excel, и в Google Sheets. Тяжёлую запись вызывать не на Main.
     */
    fun buildTripCsv(context: Context, trip: Trip): Uri {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "trip_${trip.id}.csv")
        // Заголовки — человекочитаемые метки с единицами; ключ данных — имя PID.
        val cols = trip.pids.map { name ->
            val pid = runCatching { ObdPid.valueOf(name) }.getOrNull()
            name to (pid?.let { "${it.label}, ${it.unit}" } ?: name)
        }
        val sb = StringBuilder()
        sb.append("Время, с;").append(cols.joinToString(";") { it.second }).append('\n')
        for (s in trip.samples) {
            sb.append(String.format(Locale.US, "%.1f", (s.t - trip.startMs) / 1000.0))
            for ((name, _) in cols) {
                sb.append(';')
                s.v[name]?.let { sb.append(String.format(Locale.US, "%.2f", it)) }
            }
            sb.append('\n')
        }
        file.writeText(sb.toString())
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** Открывает «Поделиться» для готового CSV. */
    fun shareCsv(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Экспорт поездки (CSV)")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    /**
     * Пишет .md во временную папку `cacheDir/shared` (та же, что и PDF, с чисткой по
     * возрасту) и возвращает content-URI через FileProvider. Тяжёлую запись — не на
     * главном потоке.
     */
    fun buildMarkdownFile(context: Context, name: String, markdown: String): Uri {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val now = System.currentTimeMillis()
        // Чистим устаревшие экспорты (по возрасту), не трогая свежие/открытый share.
        dir.listFiles()?.forEach { f ->
            if (f.extension == "md" && now - f.lastModified() > CACHE_TTL_MS) runCatching { f.delete() }
        }
        val safe = name.replace(Regex("[^A-Za-zА-Яа-яЁё0-9_.-]+"), "_").trim('_').take(60).ifBlank { "report" }
        val file = File(dir, "$safe.md")
        file.writeText(markdown)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** Открывает «Поделиться» для готового Markdown-файла. */
    fun shareMarkdown(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Сохранить или отправить Markdown")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    /** Открывает «Поделиться/сохранить» для готового PDF (startActivity — с главного потока). */
    fun sharePdf(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // NEW_TASK: вызывается с application-контекста (из ViewModel), не из Activity.
        val chooser = Intent.createChooser(intent, "Сохранить или отправить PDF")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    /**
     * Рендерит текст в PDF (A4, перенос по словам, многостраничный) и возвращает
     * content-URI через FileProvider. Тяжёлую отрисовку вызывать не на главном потоке.
     */
    suspend fun buildPdf(context: Context, title: String, rawText: String, fileTag: Long): Uri = exportMutex.withLock {
        val text = stripMarkdown(rawText)
        val pageWidth = 595 // A4 @ 72dpi
        val pageHeight = 842
        val margin = 42f
        val bodyPaint = Paint().apply { color = Color.BLACK; textSize = 12f; isAntiAlias = true }
        val titlePaint = Paint().apply { color = Color.BLACK; textSize = 16f; isFakeBoldText = true; isAntiAlias = true }
        val maxWidth = pageWidth - 2 * margin
        val lineHeight = 18f

        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "ngscanner_$fileTag.pdf")
        // Чистим только устаревшие экспорты (по возрасту), не трогая записываемый и
        // свежие файлы — иначе синхронный снос всей папки рвёт открытый пользователем share.
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { f ->
            if (f.name != file.name && now - f.lastModified() > CACHE_TTL_MS) runCatching { f.delete() }
        }

        val pdf = PdfDocument()
        try {
            var pageNo = 1
            var page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNo).create())
            var canvas = page.canvas
            var y = margin + 16f
            canvas.drawText(title.take(80), margin, y, titlePaint)
            y += 26f

            for (line in wrap(text, bodyPaint, maxWidth)) {
                if (y > pageHeight - margin) {
                    pdf.finishPage(page)
                    pageNo++
                    page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNo).create())
                    canvas = page.canvas
                    y = margin + 16f
                }
                canvas.drawText(line, margin, y, bodyPaint)
                y += lineHeight
            }
            pdf.finishPage(page)
            FileOutputStream(file).use { pdf.writeTo(it) }
        } finally {
            // Даже при сбое I/O освобождаем нативную память документа/страниц.
            pdf.close()
        }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** Перенос по словам под ширину страницы; длинные слова жёстко режутся. */
    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val out = ArrayList<String>()
        for (paragraph in text.split("\n")) {
            if (paragraph.isBlank()) {
                out.add("")
                continue
            }
            var line = StringBuilder()
            for (word in paragraph.split(" ").filter { it.isNotEmpty() }) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (paint.measureText(candidate) <= maxWidth) {
                    line = StringBuilder(candidate)
                } else {
                    if (line.isNotEmpty()) {
                        out.add(line.toString())
                        line = StringBuilder()
                    }
                    var w = word
                    while (paint.measureText(w) > maxWidth && w.length > 1) {
                        var i = 1
                        while (i < w.length && paint.measureText(w.substring(0, i + 1)) <= maxWidth) i++
                        out.add(w.substring(0, i))
                        w = w.substring(i)
                    }
                    line = StringBuilder(w)
                }
            }
            out.add(line.toString())
        }
        return out
    }

    /**
     * Убирает основную markdown-разметку, чтобы в PDF был чистый текст. Таблицы
     * сплющиваются вызывающей стороной (flattenMarkdownTables) до передачи сюда.
     */
    private fun stripMarkdown(s: String): String = s
        // [текст](url) → «текст (url)»; ссылки-картинки ![alt](url) → «alt (url)».
        .replace(Regex("!?\\[([^\\]]*)\\]\\(([^)]+)\\)"), "$1 ($2)")
        .replace("**", "")
        .replace("`", "")
        .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
        .replace(Regex("(?m)^\\s{0,3}>\\s?"), "")
        // Маркеры списков -/*/+ → «• »; нумерацию «1.» оставляем как есть (читается).
        .replace(Regex("(?m)^(\\s{0,3})[-*+]\\s+"), "$1• ")

    private const val CACHE_TTL_MS = 3_600_000L // час: свежие экспорты (открытый share) не сносим
}
