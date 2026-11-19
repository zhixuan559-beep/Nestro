package app.nestro.data
import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.File
import java.io.Writer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await

/** Extract embedded text first; OCR only pages without a text layer. */
internal object KnowledgePdfOcr {
    private const val MAX_PAGES = 300
    private const val MAX_TEXT_CHARS = 500_000
    private const val MAX_RENDER_EDGE = 1_800
    private const val MAX_SCALE = 2.0

    fun extract(context: Context, bytes: ByteArray): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        val tempFile = File.createTempFile("nestro-knowledge-", ".pdf", context.cacheDir)
        tempFile.writeBytes(bytes)
        var recognizer: TextRecognizer? = null
        var renderer: PdfRenderer? = null
        var descriptor: ParcelFileDescriptor? = null
        try {
            PDDocument.load(ByteArrayInputStream(bytes)).use { document ->
                require(!document.isEncrypted) { "暂不支持加密 PDF，请先解除密码保护。" }
                require(document.numberOfPages <= MAX_PAGES) { "PDF 超过 300 页，请拆分后导入。" }
                descriptor = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
                renderer = PdfRenderer(descriptor!!)
                val output = StringBuilder()
                for (pageIndex in 0 until document.numberOfPages) {
                    val pageText = StringBuilder()
                    val boundedWriter = object : Writer() {
                        override fun write(buffer: CharArray, offset: Int, length: Int) {
                            require(output.length + pageText.length + length <= MAX_TEXT_CHARS) {
                                "PDF 提取文本超过 50 万字符，请拆分文件后导入。"
                            }
                            pageText.append(buffer, offset, length)
                        }
                        override fun flush() = Unit
                        override fun close() = Unit
                    }
                    PDFTextStripper().apply {
                        sortByPosition = true
                        startPage = pageIndex + 1
                        endPage = pageIndex + 1
                    }.writeText(document, boundedWriter)
                    var text = pageText.toString().trim()
                    if (text.isBlank()) {
                        val page = renderer!!.openPage(pageIndex)
                        val bitmap: Bitmap
                        try {
                            val scale = minOf(MAX_SCALE, MAX_RENDER_EDGE.toDouble() / maxOf(page.width, page.height).coerceAtLeast(1))
                            bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        } finally { page.close() }
                        try {
                            if (recognizer == null) recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                            text = runBlocking { recognizer!!.process(InputImage.fromBitmap(bitmap, 0)).await().text.trim() }
                        } finally { bitmap.recycle() }
                    }
                    if (text.isNotBlank()) {
                        require(output.length + text.length + 1 <= MAX_TEXT_CHARS) { "PDF 提取文本超过 50 万字符，请拆分文件后导入。" }
                        if (output.isNotEmpty()) output.append('\n')
                        output.append(text)
                    }
                }
                val result = output.toString().trim()
                require(result.isNotBlank()) { "扫描图片版 PDF 未识别到可用文字，请检查清晰度或另行提供可复制文本的 PDF。" }
                return result
            }
        } finally {
            recognizer?.close()
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
            tempFile.delete()
        }
    }
}
