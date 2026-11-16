package app.nestro.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.ByteArrayOutputStream
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
        val text = KnowledgeStore.extractPdfText(context, bytes)
        assertTrue(text.contains("Nestro knowledge base PDF extraction"))
    }

    @Test
    fun rejectsPdfWithoutExtractableText() {
        val bytes = createPdf(null)
        val failure = runCatching { KnowledgeStore.extractPdfText(context, bytes) }.exceptionOrNull()
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
                KnowledgeStore.extractPdfText(context, output.toByteArray())
            }.exceptionOrNull()
            assertTrue(failure?.message?.contains("300 页") == true)
        } finally {
            document.close()
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
