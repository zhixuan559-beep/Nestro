package app.nestro.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KnowledgePdfImportTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun extractsTextFromPdfForKnowledgeIndexing() {
        val bytes = createPdf("Nestro knowledge base PDF extraction")
        val text = runBlocking { KnowledgeStore.extractPdfText(context, bytes) }
        assertTrue(text.contains("Nestro knowledge base PDF extraction"))
    }

    @Test
    fun recognizesTextFromScannedPdfOnDevice() {
        val text = runBlocking { KnowledgeStore.extractPdfText(context, createScannedPdf("NESTRO 知识库测试")) }
        assertTrue("OCR 未识别出预期内容，实际结果：<$text>", text.contains("知识库测试"))
    }

    @Test
    fun rejectsPdfWithoutExtractableText() {
        val bytes = createPdf(null)
        val failure = runCatching { runBlocking { KnowledgeStore.extractPdfText(context, bytes) } }.exceptionOrNull()
        assertTrue(failure?.message?.contains("扫描图片版") == true)
    }

    @Test
    fun rejectsPdfAbovePageLimit() {
        val document = PDDocument()
        try {
            repeat(301) { document.addPage(PDPage()) }
            val output = ByteArrayOutputStream()
            document.save(output)
            val failure = runCatching {
                runBlocking { KnowledgeStore.extractPdfText(context, output.toByteArray()) }
            }.exceptionOrNull()
            assertTrue(failure?.message?.contains("300 页") == true)
        } finally {
            document.close()
        }
    }

    private fun createScannedPdf(text: String): ByteArray {
        val bitmap = Bitmap.createBitmap(1200, 500, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 96f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                drawText(text, 50f, 260f, paint)
            }
            val document = PDDocument()
            try {
                val page = PDPage()
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.drawImage(LosslessFactory.createFromImage(document, bitmap), 50f, 450f, 500f, 208f)
                }
                val output = ByteArrayOutputStream()
                document.save(output)
                return output.toByteArray()
            } finally {
                document.close()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun createPdf(text: String?): ByteArray {
        val document = PDDocument()
        try {
            val page = PDPage()
            document.addPage(page)
            if (text != null) {
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font.HELVETICA, 12f)
                    stream.newLineAtOffset(50f, 700f)
                    stream.showText(text)
                    stream.endText()
                }
            }
            val output = ByteArrayOutputStream()
            document.save(output)
            return output.toByteArray()
        } finally {
            document.close()
        }
    }
}
