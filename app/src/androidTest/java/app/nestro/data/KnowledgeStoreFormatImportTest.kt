package app.nestro.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KnowledgeStoreFormatImportTest {
    @Test fun xmlImportExtractsTextWithoutMarkup() {
        val xml = """<root><title>知识库</title><body><![CDATA[本地检索]]></body></root>"""
        val text = KnowledgeStore.extractXmlText(xml.toByteArray())
        assertTrue(text.contains("知识库"))
        assertTrue(text.contains("本地检索"))
        assertFalse(text.contains("<root>"))
    }

    @Test fun docxImportExtractsParagraphText() {
        val xml = """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>项目文档正文</w:t></w:r></w:p></w:body></w:document>"""
        val text = KnowledgeStore.extractOfficeText("docx", zip(mapOf("word/document.xml" to xml)))
        assertTrue(text.contains("项目文档正文"))
    }

    @Test fun xlsxImportExtractsSharedStringsAndCellValues() {
        val shared = """<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>收入</t></si></sst>"""
        val sheet = """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row><c r="A1" t="s"><v>0</v></c><c r="B1"><v>128</v></c></row></sheetData></worksheet>"""
        val text = KnowledgeStore.extractOfficeText(
            "xlsx",
            zip(mapOf("xl/sharedStrings.xml" to shared, "xl/worksheets/sheet1.xml" to sheet))
        )
        assertTrue(text.contains("收入"))
        assertTrue(text.contains("128"))
    }

    @Test fun pptxImportExtractsSlideText() {
        val slide = """<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>演示文稿内容</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>"""
        val text = KnowledgeStore.extractOfficeText("pptx", zip(mapOf("ppt/slides/slide1.xml" to slide)))
        assertTrue(text.contains("演示文稿内容"))
    }

    @Test fun corruptOfficeArchiveIsRejected() {
        val result = runCatching { KnowledgeStore.extractOfficeText("docx", "not a zip".toByteArray()) }
        assertTrue(result.isFailure)
    }

    private fun zip(entries: Map<String, String>): ByteArray =
        ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { archive ->
                entries.forEach { (name, content) ->
                    archive.putNextEntry(ZipEntry(name))
                    archive.write(content.toByteArray())
                    archive.closeEntry()
                }
            }
            output.toByteArray()
        }
}
