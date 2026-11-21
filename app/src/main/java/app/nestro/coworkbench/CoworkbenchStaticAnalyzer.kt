package app.nestro.coworkbench

import java.io.File

enum class CoworkbenchFindingLevel { INFO, WARNING, HIGH }

data class CoworkbenchFinding(
    val category: String,
    val level: CoworkbenchFindingLevel,
    val path: String,
    val line: Int?,
    val evidence: String,
    val message: String
)

data class CoworkbenchAnalysisReport(
    val scannedFiles: Int,
    val skippedFiles: Int,
    val dependencyDeclarations: List<String>,
    val findings: List<CoworkbenchFinding>,
    val truncated: Boolean
)

/**
 * Bounded, read-only repository inspection. This is a source/configuration
 * heuristic, not a compiler, resolved dependency graph, or security proof.
 */
object CoworkbenchStaticAnalyzer {
    private const val MAX_FILES = 4_000
    private const val MAX_FILE_BYTES = 1_000_000L
    private const val MAX_TOTAL_BYTES = 24_000_000L
    private val extensions = setOf("kt", "kts", "java", "xml", "gradle", "toml", "properties", "yml", "yaml", "sh", "json")

    fun analyze(repository: File): CoworkbenchAnalysisReport {
        val root = repository.canonicalFile
        require(root.isDirectory) { "目标仓库目录不存在" }
        val findings = mutableListOf<CoworkbenchFinding>()
        val dependencies = linkedSetOf<String>()
        var scanned = 0
        var skipped = 0
        var totalBytes = 0L
        var truncated = false

        val files = root.walkTopDown()
            .onEnter { dir ->
                if (dir == root) true else {
                    val name = dir.name
                    name !in setOf(".git", ".gradle", "build", "node_modules", ".idea", ".nestro", "dist", "out") &&
                        !java.nio.file.Files.isSymbolicLink(dir.toPath())
                }
            }
            .filter { file ->
                file.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath()) &&
                    file.extension.lowercase() in extensions
            }

        for (file in files) {
            if (scanned >= MAX_FILES || totalBytes >= MAX_TOTAL_BYTES) {
                truncated = true
                break
            }
            val size = runCatching { file.length() }.getOrDefault(Long.MAX_VALUE)
            if (size !in 1..MAX_FILE_BYTES || totalBytes + size > MAX_TOTAL_BYTES) {
                skipped++
                continue
            }
            val canonical = runCatching { file.canonicalFile }.getOrNull()
            if (canonical == null || !canonical.toPath().startsWith(root.toPath())) {
                skipped++
                continue
            }
            val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            if (text == null || '\u0000' in text) {
                skipped++
                continue
            }
            scanned++
            totalBytes += size
            val relative = root.toPath().relativize(canonical.toPath()).toString().replace(File.separatorChar, '/')
            inspect(relative, text, findings, dependencies)
        }

        return CoworkbenchAnalysisReport(
            scannedFiles = scanned,
            skippedFiles = skipped,
            dependencyDeclarations = dependencies.toList().sorted(),
            findings = findings.distinctBy { listOf(it.category, it.path, it.line, it.evidence) }
                .sortedWith(compareByDescending<CoworkbenchFinding> { it.level.ordinal }.thenBy { it.path }.thenBy { it.line ?: 0 }),
            truncated = truncated
        )
    }

    private fun inspect(
        path: String,
        text: String,
        findings: MutableList<CoworkbenchFinding>,
        dependencies: MutableSet<String>
    ) {
        val lowerPath = path.lowercase()
        val lines = text.lines()
        lines.forEachIndexed { index, raw ->
            val line = raw.take(500)
            val number = index + 1

            if (lowerPath.endsWith(".gradle") || lowerPath.endsWith(".gradle.kts")) {
                Regex("""\b(?:implementation|api|runtimeOnly|compileOnly|kapt|annotationProcessor|testImplementation|androidTestImplementation)\s*\(?\s*["']([^"']+)["']""")
                    .findAll(line).forEach { match ->
                        val declaration = match.groupValues[1]
                        dependencies += "$path:$number:$declaration"
                        if (declaration.contains("+") || declaration.contains("SNAPSHOT", true) || declaration.contains("latest.", true)) {
                            findings += finding("依赖版本", CoworkbenchFindingLevel.WARNING, path, number, declaration,
                                "发现动态或快照依赖版本，构建结果可能不可复现")
                        }
                    }
                if (Regex("""maven\s*\{[^}]*https?://""", RegexOption.IGNORE_CASE).containsMatchIn(line)) {
                    findings += finding("依赖仓库", CoworkbenchFindingLevel.WARNING, path, number, line,
                        "发现自定义 HTTP/Maven 仓库声明，需核验来源、TLS 与供应链可信度")
                }
            }

            if (lowerPath.endsWith("androidmanifest.xml")) {
                Regex("""<uses-permission[^>]*android:name\s*=\s*["']([^"']+)""").findAll(line).forEach { match ->
                    val permission = match.groupValues[1]
                    val level = if (permission.substringAfterLast('.').matches(Regex("READ_SMS|SEND_SMS|RECEIVE_SMS|CALL_PHONE|READ_CONTACTS|ACCESS_FINE_LOCATION|ACCESS_BACKGROUND_LOCATION|RECORD_AUDIO|CAMERA"))) CoworkbenchFindingLevel.HIGH else CoworkbenchFindingLevel.WARNING
                    findings += finding("Manifest 权限", level, path, number, permission,
                        "新增或声明 Android 权限；需核对功能必要性、运行时授权与数据用途")
                }
                if (Regex("""android:exported\s*=\s*["']true["']""").containsMatchIn(line)) {
                    findings += finding("导出组件", CoworkbenchFindingLevel.WARNING, path, number, line,
                        "组件对其他应用可见；核验 intent-filter、权限保护和输入校验")
                }
                if (Regex("""android:debuggable\s*=\s*["']true["']""").containsMatchIn(line)) {
                    findings += finding("调试配置", CoworkbenchFindingLevel.HIGH, path, number, line,
                        "Manifest 显式启用 debuggable，发布构建必须确认已关闭")
                }
                if (Regex("""android:allowBackup\s*=\s*["']true["']""").containsMatchIn(line)) {
                    findings += finding("数据备份", CoworkbenchFindingLevel.WARNING, path, number, line,
                        "应用数据允许系统备份；核对敏感数据是否通过 backup rules 排除")
                }
            }

            val checks = listOf(
                Triple(Regex("""ProcessBuilder|Runtime\.getRuntime\(\)\.exec|/bin/(?:sh|bash)|powershell""", RegexOption.IGNORE_CASE), "命令执行", CoworkbenchFindingLevel.HIGH to "发现进程或 Shell 执行入口；核验输入来源与命令注入风险"),
                Triple(Regex("""http://""", RegexOption.IGNORE_CASE), "明文 HTTP", CoworkbenchFindingLevel.WARNING to "发现明文 HTTP 地址；确认是否可改为 HTTPS，禁止传输敏感数据"),
                Triple(Regex("""TrustAllCerts|ALLOW_ALL_HOSTNAME_VERIFIER|HostnameVerifier\s*\{\s*_,\s*_->\s*true""", RegexOption.IGNORE_CASE), "TLS 校验", CoworkbenchFindingLevel.HIGH to "可能存在跳过 TLS 证书或主机名校验的实现"),
                Triple(Regex("""addJavascriptInterface|setJavaScriptEnabled\(true\)"""), "WebView", CoworkbenchFindingLevel.WARNING to "发现 WebView JavaScript/桥接入口；核验可信来源与桥接方法暴露"),
                Triple(Regex("""SmsManager|TelephonyManager|READ_SMS|SEND_SMS|CALL_PHONE"""), "敏感设备 API", CoworkbenchFindingLevel.HIGH to "发现短信/电话相关 API 或权限引用；核验用途与最小权限"),
                Triple(Regex("""AccessibilityService|performGlobalAction"""), "无障碍能力", CoworkbenchFindingLevel.WARNING to "发现无障碍服务或全局动作；核验用户主动启用、用途披露与动作边界"),
                Triple(Regex("""apiKey\s*[:=]\s*["'][A-Za-z0-9_\-]{20,}["']|BEGIN (?:RSA |EC )?PRIVATE KEY"""), "疑似硬编码凭据", CoworkbenchFindingLevel.HIGH to "发现疑似硬编码密钥或私钥材料；立即确认是否为真实凭据")
            )
            checks.forEach { (regex, category, pair) ->
                if (regex.containsMatchIn(line)) findings += finding(category, pair.first, path, number, line, pair.second)
            }

            if ((lowerPath.startsWith(".github/workflows/") || lowerPath.endsWith(".yml") || lowerPath.endsWith(".yaml")) &&
                Regex("""permissions\s*:\s*write-all""", RegexOption.IGNORE_CASE).containsMatchIn(line)) {
                findings += finding("CI 权限", CoworkbenchFindingLevel.HIGH, path, number, line,
                    "Workflow 使用 write-all；应改为逐项最小权限")
            }
        }
    }

    private fun finding(
        category: String,
        level: CoworkbenchFindingLevel,
        path: String,
        line: Int,
        evidence: String,
        message: String
    ) = CoworkbenchFinding(category, level, path, line, evidence, message)
}
