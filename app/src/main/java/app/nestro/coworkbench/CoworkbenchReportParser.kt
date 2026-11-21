package app.nestro.coworkbench

import org.json.JSONObject
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

data class CoworkbenchSbomComponent(
    val name: String,
    val version: String?,
    val packageUrl: String?,
    val license: String?
)

data class CoworkbenchTestReport(
    val path: String,
    val total: Int,
    val passed: Int,
    val failures: Int,
    val errors: Int,
    val skipped: Int
)

data class CoworkbenchResolvedDependency(
    val configuration: String,
    val group: String?,
    val artifact: String,
    val requestedVersion: String?,
    val resolvedVersion: String?,
    val depth: Int,
    val parent: String?,
    val notation: String
)

object CoworkbenchReportParser {
    private const val MAX_REPORT_CHARS = 1_000_000
    private const val MAX_COMPONENTS = 5_000

    fun isCompleteCoworkbenchSbom(path: String, content: String): Boolean {
        if (content.length > MAX_REPORT_CHARS || !path.lowercase().endsWith("bom.cdx.json")) return false
        val json = runCatching { JSONObject(content) }.getOrNull() ?: return false
        if (json.optString("bomFormat") != "CycloneDX" || json.optString("specVersion") != "1.5") return false
        val metadata = json.optJSONObject("metadata") ?: return false
        if (metadata.optJSONObject("component")?.optString("name") != "Nestro") return false
        val properties = metadata.optJSONArray("properties") ?: return false
        val source = (0 until properties.length()).mapNotNull { properties.optJSONObject(it) }
            .firstOrNull { it.optString("name") == "nestro:sbom:source" }
            ?.optString("value").orEmpty()
        if (!source.contains("debugRuntimeClasspath") || !source.contains("releaseRuntimeClasspath")) return false
        val components = json.optJSONArray("components") ?: return false
        if (components.length() == 0 || components.length() > MAX_COMPONENTS) return false
        val refs = mutableSetOf<String>()
        for (index in 0 until components.length()) {
            val item = components.optJSONObject(index) ?: return false
            val purl = item.optString("purl")
            if (item.optString("type") != "library" || purl.isBlank() ||
                !purl.startsWith("pkg:maven/") || item.optString("bom-ref") != purl ||
                item.optString("group").isBlank() || item.optString("name").isBlank() ||
                item.optString("version").isBlank() || !refs.add(purl)
            ) return false
        }
        return true
    }

    fun parseSbom(path: String, content: String): List<CoworkbenchSbomComponent> {
        if (content.length > MAX_REPORT_CHARS || !path.lowercase().endsWith(".json")) return emptyList()
        val json = runCatching { JSONObject(content) }.getOrNull() ?: return emptyList()
        val result = mutableListOf<CoworkbenchSbomComponent>()
        when {
            json.optString("bomFormat") == "CycloneDX" -> {
                fun collect(rows: org.json.JSONArray?, depth: Int) {
                    if (rows == null || depth > 12 || result.size >= MAX_COMPONENTS) return
                    for (index in 0 until minOf(rows.length(), MAX_COMPONENTS - result.size)) {
                        val item = rows.optJSONObject(index) ?: continue
                        val name = item.optString("name").takeIf { it.isNotBlank() } ?: continue
                        val licenses = item.optJSONArray("licenses")
                        val license = licenses?.optJSONObject(0)?.let { row ->
                            row.optJSONObject("license")?.optString("id")?.takeIf { it.isNotBlank() }
                                ?: row.optJSONObject("license")?.optString("name")?.takeIf { it.isNotBlank() }
                                ?: row.optString("expression").takeIf { it.isNotBlank() }
                        }
                        result += CoworkbenchSbomComponent(
                            item.optString("group").takeIf { it.isNotBlank() }?.let { "$it:$name" } ?: name,
                            item.optString("version").takeIf { it.isNotBlank() },
                            item.optString("purl").takeIf { it.isNotBlank() },
                            license
                        )
                        collect(item.optJSONArray("components"), depth + 1)
                    }
                }
                collect(json.optJSONArray("components"), 0)
            }
            json.optString("spdxVersion").startsWith("SPDX-") -> {
                val rows = json.optJSONArray("packages") ?: return emptyList()
                for (index in 0 until minOf(rows.length(), MAX_COMPONENTS)) {
                    val item = rows.optJSONObject(index) ?: continue
                    val name = item.optString("name").takeIf { it.isNotBlank() } ?: continue
                    val refs = item.optJSONArray("externalRefs")
                    var purl: String? = null
                    if (refs != null) for (refIndex in 0 until refs.length()) {
                        val ref = refs.optJSONObject(refIndex) ?: continue
                        if (ref.optString("referenceType").contains("purl", true) ||
                            ref.optString("referenceLocator").startsWith("pkg:")) {
                            purl = ref.optString("referenceLocator").takeIf { it.isNotBlank() }
                            if (purl != null) break
                        }
                    }
                    result += CoworkbenchSbomComponent(
                        name,
                        item.optString("versionInfo").takeIf { it.isNotBlank() },
                        purl,
                        item.optString("licenseDeclared").takeIf { it.isNotBlank() && it != "NOASSERTION" }
                            ?: item.optString("licenseConcluded").takeIf { it.isNotBlank() && it != "NOASSERTION" }
                    )
                }
            }
        }
        return result.distinctBy { listOf(it.name, it.version, it.packageUrl) }.take(MAX_COMPONENTS)
    }

    fun parseGradleDependencyReport(path: String, content: String): List<CoworkbenchResolvedDependency> {
        if (content.length > MAX_REPORT_CHARS ||
            !path.lowercase().endsWith(".txt") && !path.lowercase().endsWith(".log")) return emptyList()
        val result = mutableListOf<CoworkbenchResolvedDependency>()
        val parents = mutableMapOf<Int, String>()
        var configuration = ""
        val coordinate = Regex("""^([^\s:]+):([^\s:]+):([^\s]+)(?:\s+->\s+([^\s]+))?.*$""")
        content.lineSequence().forEach { raw ->
            if (result.size >= 10_000) return@forEach
            val line = raw.take(2_000)
            val marker = Regex("""(?:\+---|\\---|---)\s+(.+)$""").find(line)
            if (marker == null) {
                if (line.contains(" - ") && !line.trimStart().startsWith("|")) {
                    configuration = line.trim().substringBefore(" - ").take(160)
                    parents.clear()
                }
                return@forEach
            }
            val depth = line.substring(0, marker.range.first).count { it == '|' }
            val notation = marker.groupValues[1].trim()
            val match = coordinate.find(notation)
            if (match != null) {
                val group = match.groupValues[1]
                val artifact = match.groupValues[2]
                val requested = match.groupValues[3].takeIf { it.isNotBlank() }
                val resolved = match.groupValues[4].takeIf { it.isNotBlank() } ?: requested
                val key = "$group:$artifact:$resolved"
                val parent = parents.filterKeys { it < depth }.maxByOrNull { it.key }?.value
                result += CoworkbenchResolvedDependency(configuration, group, artifact, requested, resolved,
                    depth, parent, notation)
                parents.keys.filter { it >= depth }.toList().forEach { parents.remove(it) }
                parents[depth] = key
            } else if (notation.startsWith("project ")) {
                val artifact = notation.substringAfter("project ").substringBefore(" ").take(160)
                val parent = parents.filterKeys { it < depth }.maxByOrNull { it.key }?.value
                result += CoworkbenchResolvedDependency(configuration, null, artifact, null, null,
                    depth, parent, notation)
                parents.keys.filter { it >= depth }.toList().forEach { parents.remove(it) }
                parents[depth] = artifact
            }
        }
        return result
    }

    fun parseJunitXml(path: String, content: String): CoworkbenchTestReport? {
        if (content.length > MAX_REPORT_CHARS || !path.lowercase().endsWith(".xml")) return null
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                isXIncludeAware = false
                isExpandEntityReferences = false
                runCatching { setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "") }
                runCatching { setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "") }
            }
            val builder = factory.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> InputSource(StringReader("")) }
            }
            val document = builder.parse(InputSource(StringReader(content)))
            val root = document.documentElement ?: return null
            if (root.tagName !in setOf("testsuite", "testsuites")) return null
            fun count(attribute: String): Int {
                if (!root.hasAttribute(attribute)) return 0
                val parsed = root.getAttribute(attribute).toIntOrNull()
                    ?: error("JUnit 报告计数不是整数：$attribute")
                require(parsed >= 0) { "JUnit 报告计数不能为负数：$attribute" }
                return parsed
            }
            val hasRootCounts = root.hasAttribute("tests")
            val total: Int
            val failures: Int
            val errors: Int
            val skipped: Int
            if (hasRootCounts) {
                total = count("tests")
                failures = count("failures")
                errors = count("errors")
                skipped = count("skipped")
            } else {
                val suites = root.getElementsByTagName("testsuite")
                total = (0 until suites.length()).sumOf { (suites.item(it) as org.w3c.dom.Element).getAttribute("tests").toIntOrNull()?.coerceAtLeast(0) ?: 0 }
                failures = (0 until suites.length()).sumOf { (suites.item(it) as org.w3c.dom.Element).getAttribute("failures").toIntOrNull()?.coerceAtLeast(0) ?: 0 }
                errors = (0 until suites.length()).sumOf { (suites.item(it) as org.w3c.dom.Element).getAttribute("errors").toIntOrNull()?.coerceAtLeast(0) ?: 0 }
                skipped = (0 until suites.length()).sumOf { (suites.item(it) as org.w3c.dom.Element).getAttribute("skipped").toIntOrNull()?.coerceAtLeast(0) ?: 0 }
            }
            require(total >= 0 && failures + errors + skipped <= total) { "JUnit 测试报告计数不一致" }
            CoworkbenchTestReport(path, total, total - failures - errors - skipped,
                failures, errors, skipped)
        }.getOrNull()
    }
}
