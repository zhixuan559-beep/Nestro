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

data class CoworkbenchChangeImpact(
    val findings: List<CoworkbenchFinding>,
    val permissionsAdded: List<String>,
    val permissionsRemoved: List<String>,
    val dependenciesAdded: List<String>,
    val dependenciesRemoved: List<String>
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
        var visited = 0
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
            if (visited++ >= MAX_FILES * 4 || scanned >= MAX_FILES || totalBytes >= MAX_TOTAL_BYTES) {
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
            if (relative.lowercase().endsWith(".toml")) {
                dependencyTokens(relative, text).forEach { declaration ->
                    dependencies += "$relative:$declaration"
                    if (declaration.contains("+") || declaration.contains("SNAPSHOT", true) ||
                        declaration.contains("latest.", true)) {
                        findings += finding("依赖版本", CoworkbenchFindingLevel.WARNING, relative, null,
                            declaration, "版本目录包含动态/快照依赖信息，构建结果可能不可复现")
                    }
                }
            }
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

    fun analyzeProposalsWithBaseline(
        repository: File,
        proposals: List<CoworkbenchProposal>
    ): CoworkbenchChangeImpact {
        val root = repository.canonicalFile
        val enriched = proposals.map { proposal ->
            val relative = CoworkbenchPolicy.safePath(proposal.path)
            val candidate = File(root, relative)
            var ancestor: File? = candidate
            while (ancestor != null && ancestor.toPath().startsWith(root.toPath())) {
                require(!java.nio.file.Files.isSymbolicLink(ancestor.toPath())) {
                    "补丁目标路径包含符号链接，拒绝静态基线读取：$relative"
                }
                ancestor = ancestor.parentFile
            }
            val beforeFile = candidate.canonicalFile.takeIf {
                it.toPath().startsWith(root.toPath()) && it.isFile
            }
            require(beforeFile == null || beforeFile.length() <= MAX_FILE_BYTES) {
                "补丁基线文件超过静态分析读取上限：$relative"
            }
            val before = beforeFile?.readText(Charsets.UTF_8)
            require(before == null || '\u0000' !in before) {
                "补丁基线文件包含二进制内容：$relative"
            }
            proposal to before
        }
        val findings = mutableListOf<CoworkbenchFinding>()
        val dependenciesAdded = linkedSetOf<String>()
        val dependenciesRemoved = linkedSetOf<String>()
        val permissionsAdded = linkedSetOf<String>()
        val permissionsRemoved = linkedSetOf<String>()
        enriched.forEach { (proposal, before) ->
            val path = CoworkbenchPolicy.safePath(proposal.path)
            val after = proposal.content
            if (path.lowercase().endsWith("androidmanifest.xml")) {
                val oldPermissions = manifestPermissions(before.orEmpty())
                val newPermissions = manifestPermissions(after.orEmpty())
                permissionsAdded += newPermissions - oldPermissions
                permissionsRemoved += oldPermissions - newPermissions
                (newPermissions - oldPermissions).forEach { permission ->
                    val high = permission.substringAfterLast('.').matches(
                        Regex("READ_SMS|SEND_SMS|RECEIVE_SMS|CALL_PHONE|READ_CONTACTS|ACCESS_FINE_LOCATION|ACCESS_BACKGROUND_LOCATION|RECORD_AUDIO|CAMERA")
                    )
                    findings += finding("新增 Manifest 权限", if (high) CoworkbenchFindingLevel.HIGH else CoworkbenchFindingLevel.WARNING,
                        path, null, permission, "本次补丁新增权限；核对必要性、授权、隐私披露与数据用途")
                }
                (exportedComponents(after.orEmpty()) - exportedComponents(before.orEmpty())).forEach { component ->
                    findings += finding("新增导出组件", CoworkbenchFindingLevel.HIGH, path, null, component,
                        "本次补丁新增对外组件；核验输入校验、权限保护和身份校验")
                }
                if (!containsTrueAttribute(before.orEmpty(), "debuggable") && containsTrueAttribute(after.orEmpty(), "debuggable")) {
                    findings += finding("启用调试模式", CoworkbenchFindingLevel.HIGH, path, null, "android:debuggable=true",
                        "本次补丁启用了调试模式，发布构建必须阻止该配置")
                }
            }
            if (isBuildConfiguration(path)) {
                val oldDeps = dependencyTokens(path, before.orEmpty())
                val newDeps = dependencyTokens(path, after.orEmpty())
                dependenciesAdded += newDeps - oldDeps
                dependenciesRemoved += oldDeps - newDeps
                (newDeps - oldDeps).forEach { dependency ->
                    val dynamic = dependency.contains("+") || dependency.contains("SNAPSHOT", true) || dependency.contains("latest.", true)
                    findings += finding("新增构建依赖", if (dynamic) CoworkbenchFindingLevel.HIGH else CoworkbenchFindingLevel.WARNING,
                        path, null, dependency, "新增依赖需核验来源、版本、许可证和传递依赖" +
                            if (dynamic) "；当前使用动态/快照版本" else "")
                }
                (oldDeps - newDeps).forEach { dependency ->
                    findings += finding("移除构建依赖", CoworkbenchFindingLevel.INFO, path, null, dependency,
                        "确认移除后没有遗留引用和运行时缺失")
                }
            }
            if (after != null) inspect(path, after, findings, linkedSetOf())
        }
        return CoworkbenchChangeImpact(
            findings.distinctBy { listOf(it.category, it.path, it.evidence, it.message) }
                .sortedWith(compareByDescending<CoworkbenchFinding> { it.level.ordinal }.thenBy { it.path }),
            permissionsAdded.sorted(), permissionsRemoved.sorted(),
            dependenciesAdded.sorted(), dependenciesRemoved.sorted()
        )
    }

    private fun manifestPermissions(text: String): Set<String> =
        Regex("""<uses-permission(?:-sdk-\d+)?\b[^>]*android:name\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(text).map { it.groupValues[1] }.toSet()

    private fun exportedComponents(text: String): Set<String> =
        Regex("""<(activity|activity-alias|service|receiver|provider)\b([^>]*)>""", RegexOption.IGNORE_CASE)
            .findAll(text).filter { match ->
                Regex("""android:exported\s*=\s*["']true["']""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(match.groupValues[2])
            }.map { it.groupValues[1] + ":" + Regex("""android:name\s*=\s*["']([^"']+)["']""")
                .find(match.groupValues[2])?.groupValues?.get(1).orEmpty() }.toSet()

    private fun containsTrueAttribute(text: String, attribute: String): Boolean =
        Regex("""android:$attribute\s*=\s*["']true["']""", RegexOption.IGNORE_CASE).containsMatchIn(text)

    private fun isBuildConfiguration(path: String): Boolean {
        val name = path.substringAfterLast('/').lowercase()
        return name.endsWith(".gradle") || name.endsWith(".gradle.kts") ||
            name == "libs.versions.toml" || name == "gradle.properties"
    }

    private fun dependencyTokens(path: String, text: String): Set<String> {
        val result = linkedSetOf<String>()
        val lower = path.lowercase()
        if (lower.endsWith(".toml")) {
            fun quoted(value: String): String? =
                Regex("""^[\s]*["']([^"']+)["'][\s]*$""").matchEntire(value)?.groupValues?.get(1)
            fun recordLibrary(alias: String, fields: Map<String, String>) {
                val module = fields["module"]?.let(::quoted)
                val group = fields["group"]?.let(::quoted)
                val name = fields["name"]?.let(::quoted)
                val coordinate = module ?: if (!group.isNullOrBlank() && !name.isNullOrBlank()) "$group:$name" else null
                val version = fields["version"]?.let(::quoted)
                    ?: fields["version.ref"]?.let(::quoted)?.let { "version-ref:$it" }
                if (coordinate != null) result += "catalog:$alias:$coordinate" + (version?.let { ":$it" } ?: "")
            }

            // Inline Gradle version-catalog entries.
            Regex("""(?m)^\s*([A-Za-z0-9_.-]+)\s*=\s*\{([^}]*)}""").findAll(text).forEach { match ->
                val fields = Regex("""([A-Za-z0-9_.-]+)\s*=\s*("[^"]*"|'[^']*')""")
                    .findAll(match.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2] }
                recordLibrary(match.groupValues[1], fields)
            }

            // Dotted TOML tables such as [libraries.androidx-core].
            var section: String? = null
            val tableFields = linkedMapOf<String, String>()
            fun flushTable() {
                val current = section ?: return
                if (current.startsWith("libraries.")) {
                    recordLibrary(current.removePrefix("libraries."), tableFields)
                }
                tableFields.clear()
            }
            text.lineSequence().forEach { line ->
                val header = Regex("""^\s*\[([^]]+)]\s*(?:#.*)?$""").find(line)
                if (header != null) {
                    flushTable()
                    section = header.groupValues[1].trim()
                } else if (section?.startsWith("libraries.") == true) {
                    val field = Regex("""^\s*([A-Za-z0-9_.-]+)\s*=\s*(.+?)\s*(?:#.*)?$""").find(line)
                    if (field != null) tableFields[field.groupValues[1]] = field.groupValues[2]
                }
            }
            flushTable()

            // Version aliases and plugin aliases are useful change evidence too.
            Regex("""(?m)^\s*([A-Za-z0-9_.-]+)\s*=\s*["']([^"']+)["']""").findAll(text).forEach { match ->
                result += "catalog-value:" + match.groupValues[1] + ":" + match.groupValues[2]
            }
        } else {
            Regex("""\b(?:implementation|api|runtimeOnly|compileOnly|kapt|annotationProcessor|testImplementation|androidTestImplementation|classpath)\s*\(?\s*["']([^"']+)["']""")
                .findAll(text).forEach { result += it.groupValues[1] }
            Regex("""\b(?:id|alias)\s*\(?\s*["']([^"']+)["']\s*\)?\s*(?:version\s*["']([^"']+)["'])?""")
                .findAll(text).forEach { result += "plugin:" + it.groupValues[1] + ":" + it.groupValues[2] }
            Regex("""\blibs(?:\.[A-Za-z0-9_-]+){1,8}\b""")
                .findAll(text).forEach { result += "catalog-alias:" + it.value }
        }
        return result
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

            if (lowerPath.startsWith(".github/workflows/") &&
                Regex("""\bpull_request_target\b""").containsMatchIn(line)) {
                findings += finding("CI 触发边界", CoworkbenchFindingLevel.HIGH, path, number, line,
                    "Workflow 使用 pull_request_target；必须核验是否执行不可信 PR 代码及是否暴露写权限/密钥")
            }
            if (lowerPath.startsWith(".github/workflows/")) {
                Regex("""\buses\s*:\s*[^@\s]+@([^\s#]+)""").find(line)?.let { match ->
                    val version = match.groupValues[1]
                    if (!Regex("^[0-9a-fA-F]{40}$").matches(version)) {
                        findings += finding("CI Action 未固定摘要", CoworkbenchFindingLevel.WARNING, path, number, match.value,
                            "第三方 Action 使用可变标签/分支；优先固定到完整 40 位提交 SHA")
                    }
                }
            }
            if (Regex("""\bcurl\b[^|]*\|\s*(?:sudo\s+)?(?:sh|bash)\b|\bwget\b[^|]*\|\s*(?:sudo\s+)?(?:sh|bash)\b""",
                    RegexOption.IGNORE_CASE).containsMatchIn(line)) {
                findings += finding("远程脚本执行", CoworkbenchFindingLevel.HIGH, path, number, line,
                    "检测到下载内容直接交给 Shell 执行；核验来源、校验和并避免管道直执行")
            }
            if (Regex("""\bdebuggable\s*=\s*true\b|isDebuggable\s*=\s*true\b""", RegexOption.IGNORE_CASE).containsMatchIn(line) &&
                (lowerPath.endsWith(".gradle") || lowerPath.endsWith(".gradle.kts"))) {
                findings += finding("构建调试配置", CoworkbenchFindingLevel.HIGH, path, number, line,
                    "构建脚本可能启用 debuggable；确认仅限 debug variant，release 必须关闭")
            }
        }
    }

    private fun finding(
        category: String,
        level: CoworkbenchFindingLevel,
        path: String,
        line: Int?,
        evidence: String,
        message: String
    ) = CoworkbenchFinding(category, level, path, line, evidence, message)
}
