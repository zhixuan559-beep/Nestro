package app.nestro.coworkbench

/**
 * Bounded intra-file taint heuristic. It follows simple local assignments from
 * common external-input APIs to sensitive sinks. It is not inter-procedural,
 * type-aware, or a substitute for a compiler/static-analysis engine.
 */
object CoworkbenchDataFlowAnalyzer {
    private const val MAX_LINES = 2_000
    private const val MAX_TAINTED_VARIABLES = 200

    private val sources = listOf(
        Regex("""\b(?:getStringExtra|getCharSequenceExtra|getQueryParameter|getParameter|getParameterValues|getHeader|getDataString|getTextBeforeCursor|getPrimaryClip|readLine|nextLine|readText|readBytes|getInputStream)\s*\(""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:TextFieldValue|Editable)\b|\.text\s*(?:\.toString\s*\(\))?"""),
        Regex("""\bSystem\.(?:getenv|getProperty)\s*\(""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:Intent|Bundle|Uri|JSONObject|JSONArray|SharedPreferences)\b[^;=]*\.(?:getString|getStringExtra|getQueryParameter|optString)\s*\(""")
    )

    private val sinks = listOf(
        Triple(Regex("""(?:Runtime\.getRuntime\(\)\.exec|ProcessBuilder)\s*\(""", RegexOption.IGNORE_CASE),
            "命令执行", "外部输入可能流入命令执行入口；必须使用固定参数和严格白名单，禁止拼接 Shell 命令"),
        Triple(Regex("""\.(?:rawQuery|execSQL)\s*\(""", RegexOption.IGNORE_CASE),
            "SQL 查询", "外部输入可能流入 SQL 执行入口；使用参数化查询，标识符必须独立白名单校验"),
        Triple(Regex("""\.(?:loadUrl|loadDataWithBaseURL)\s*\(""", RegexOption.IGNORE_CASE),
            "WebView 导航", "外部输入可能控制 WebView 导航或内容；校验协议、主机和可信来源"),
        Triple(Regex("""\b(?:File|FileInputStream|FileOutputStream|Paths\.get|Path\.of)\s*\(|\bFiles\.(?:read|write|newInputStream|newOutputStream)\s*\(""", RegexOption.IGNORE_CASE),
            "文件路径", "外部输入可能控制文件路径或文件读写目标；执行 canonical path containment 检查并拒绝符号链接"),
        Triple(Regex("""(?:Request\.Builder|\.url)\s*\(""", RegexOption.IGNORE_CASE),
            "网络请求地址", "外部输入可能控制请求 URL；限制 HTTPS、主机白名单、端口和重定向目标")
    )

    private val assignment = Regex(
        """^\s*(?:(?:val|var)\s+|(?:(?:final\s+)?(?:String|CharSequence|Object|Uri|URI|File|Path)\s+))?([A-Za-z_][A-Za-z0-9_]*)(?:\s*:\s*[A-Za-z_][A-Za-z0-9_?.]*(?:<[^>]+>)?\s*)?=\s*(.+)$"""
    )
    private val identifier = Regex("""\b[A-Za-z_][A-Za-z0-9_]*\b""")
    private val commentLine = Regex("""^\s*(?://|/\*|\*|#)""")

    fun analyze(path: String, source: String): List<CoworkbenchFinding> {
        if (source.isBlank()) return emptyList()
        val tainted = linkedMapOf<String, Int>()
        val findings = mutableListOf<CoworkbenchFinding>()
        source.lineSequence().take(MAX_LINES).forEachIndexed { index, raw ->
            val line = raw.take(4_000)
            if (commentLine.containsMatchIn(line)) return@forEachIndexed
            val match = assignment.find(line)
            if (match != null && tainted.size < MAX_TAINTED_VARIABLES) {
                val variable = match.groupValues[1]
                val expression = match.groupValues[2]
                val inherited = identifier.findAll(expression).map { it.value }.firstOrNull { it in tainted }
                if (sources.any { it.containsMatchIn(expression) }) {
                    tainted[variable] = index + 1
                } else if (inherited != null) {
                    tainted[variable] = tainted[inherited] ?: index + 1
                }
            }

            val referenced = identifier.findAll(line).map { it.value }.filter { it in tainted }.toSet()
            val directSource = sources.any { it.containsMatchIn(line) }
            if (referenced.isEmpty() && !directSource) return@forEachIndexed

            sinks.forEach { (pattern, category, message) ->
                if (pattern.containsMatchIn(line)) {
                    val sinkEvidence = line.trim().take(240)
                    val sourceLine = referenced.mapNotNull { tainted[it] }.minOrNull()
                    findings += CoworkbenchFinding(
                        category = "潜在数据流：$category",
                        level = CoworkbenchFindingLevel.HIGH,
                        path = path,
                        line = index + 1,
                        evidence = sinkEvidence,
                        message = message + (sourceLine?.let { "（本文件内输入来源约第 ${it} 行）" }
                            ?: "（同一语句检测到外部输入与敏感入口）") +
                            "；此结果为启发式线索，需人工确认实际数据流"
                    )
                }
            }
        }
        return findings.distinctBy { listOf(it.category, it.path, it.line, it.evidence) }
    }
}
