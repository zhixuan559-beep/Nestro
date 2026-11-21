package app.nestro.coworkbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoworkbenchDataFlowAnalyzerTest {
    @Test
    fun followsExternalInputAcrossSimpleAssignmentsIntoCommandSink() {
        val source = """
            val external = intent.getStringExtra("command")
            val forwarded = external
            // Runtime.getRuntime().exec(external) is only a comment.
            Runtime.getRuntime().exec(forwarded)
        """.trimIndent()

        val findings = CoworkbenchDataFlowAnalyzer.analyze("src/Main.kt", source)
        assertEquals(1, findings.size)
        assertEquals("潜在数据流：命令执行", findings.single().category)
        assertEquals(4, findings.single().line)
        assertTrue(findings.single().message.contains("输入来源约第 1 行"))
    }

    @Test
    fun detectsDirectInputToSqlAndWebViewSinks() {
        val source = """
            database.rawQuery(uri.getQueryParameter("sql"), null)
            webView.loadUrl(intent.getStringExtra("url") ?: "")
        """.trimIndent()

        val findings = CoworkbenchDataFlowAnalyzer.analyze("src/Screen.kt", source)
        assertTrue(findings.any { it.category == "潜在数据流：SQL 查询" })
        assertTrue(findings.any { it.category == "潜在数据流：WebView 导航" })
        val javaSource = """
            String suppliedPath = intent.getStringExtra("path");
            new File(suppliedPath);
        """.trimIndent()
        assertTrue(CoworkbenchDataFlowAnalyzer.analyze("src/Legacy.java", javaSource)
            .any { it.category == "潜在数据流：文件路径" })
        val typedKotlinSource = """
            val supplied: String = intent.getStringExtra("path") ?: ""
            val forwarded: String = supplied
            Files.readString(Path.of(forwarded))
        """.trimIndent()
        assertTrue(CoworkbenchDataFlowAnalyzer.analyze("src/Typed.kt", typedKotlinSource)
            .any { it.category == "潜在数据流：文件路径" })
    }

    @Test
    fun ignoresUnrelatedCodeAndBoundsTheScan() {
        assertTrue(CoworkbenchDataFlowAnalyzer.analyze("src/Safe.kt", "val label = \"hello\"").isEmpty())
        val findings = CoworkbenchDataFlowAnalyzer.analyze("src/Large.kt",
            (1..2_100).joinToString("\n") { "val label$it = \"value\"" })
        assertTrue(findings.isEmpty())
    }
}
