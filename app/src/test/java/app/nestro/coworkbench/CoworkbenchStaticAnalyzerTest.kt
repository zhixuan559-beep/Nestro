package app.nestro.coworkbench

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoworkbenchStaticAnalyzerTest {
    @Test
    fun scansManifestPermissionsDependenciesAndSensitiveApis() {
        val root = Files.createTempDirectory("nestro-coworkbench-analysis").toFile()
        try {
            root.resolve("app/src/main/AndroidManifest.xml").apply {
                parentFile.mkdirs()
                writeText(
                    """<manifest>
                        <uses-permission android:name="android.permission.CAMERA" />
                        <application android:allowBackup="true">
                            <activity android:exported="true" />
                        </application>
                    </manifest>""".trimIndent()
                )
            }
            root.resolve("app/build.gradle.kts").writeText(
                """dependencies {
                    implementation("org.example:demo:1.+")
                }"""
            )
            root.resolve("app/src/main/Example.kt").apply {
                parentFile.mkdirs()
                writeText("Runtime.getRuntime().exec(\"sh\")")
            }

            val report = CoworkbenchStaticAnalyzer.analyze(root)
            assertTrue(report.scannedFiles >= 3)
            assertTrue(report.dependencyDeclarations.any { it.contains("org.example:demo:1.+") })
            assertTrue(report.findings.any { it.category == "Manifest 权限" })
            assertTrue(report.findings.any { it.category == "导出组件" })
            assertTrue(report.findings.any { it.category == "命令执行" && it.level == CoworkbenchFindingLevel.HIGH })
            assertTrue(report.findings.any { it.category == "依赖版本" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun skipsBuildAndGitDirectoriesAndReportsBoundedScan() {
        val root = Files.createTempDirectory("nestro-coworkbench-bounds").toFile()
        try {
            root.resolve(".git/config").apply { parentFile.mkdirs(); writeText("http://ignored.example") }
            root.resolve("build/generated/Generated.kt").apply { parentFile.mkdirs(); writeText("http://ignored.example") }
            root.resolve("src/main/Allowed.kt").apply { parentFile.mkdirs(); writeText("https://example.com") }

            val report = CoworkbenchStaticAnalyzer.analyze(root)
            assertEquals(1, report.scannedFiles)
            assertFalse(report.findings.any { it.evidence.contains("ignored.example") })
            assertFalse(report.truncated)
        } finally {
            root.deleteRecursively()
        }
    }
}
