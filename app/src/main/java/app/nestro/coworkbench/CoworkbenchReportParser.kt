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
        val application = metadata.optJSONObject("component") ?: return false
        if (application.optString("name") != "Nestro" || application.optString("version").isBlank()) return false
        val properties = metadata.optJSONArray("properties") ?: return false
        val metadataProperties = (0 until properties.length()).mapNotNull { properties.optJSONObject(it) }
            .associate { it.optString("name") to it.optString("value") }
        val expectedConfigurations = setOf("debugRuntimeClasspath", "releaseRuntimeClasspath")
        if (metadataProperties["nestro:sbom:configuration:debugRuntimeClasspath"] != "resolved" ||
            metadataProperties["nestro:sbom:configuration:releaseRuntimeClasspath"] != "resolved"
        ) return false
        val source = metadataProperties["nestro:sbom:source"].orEmpty()
        if (!source.contains("debugRuntimeClasspath") || !source.contains("releaseRuntimeClasspath")) return false
        val components = json.optJSONArray("components") ?: return false
        if (components.length() == 0 || components.length() > MAX_COMPONENTS) return false
        val refs = mutableSetOf<String>()
        val coveredConfigurations = mutableSetOf<String>()
        for (index in 0 until components.length()) {
            val item = components.optJSONObject(index) ?: return false
            val purl = item.optString("purl")
            val group = item.optString("group")
            val name = item.optString("name")
            val version = item.optString("version")
            val expectedPurl = "pkg:maven/" + group + "/" + name + "@" + version
            if (item.optString("type") != "library" || purl.isBlank() ||
                !purl.startsWith("pkg:maven/") || purl != expectedPurl || item.optString("bom-ref") != purl ||
                group.isBlank() || name.isBlank() || version.isBlank() || !refs.add(purl)
            ) return false
            val componentProperties = item.optJSONArray("properties") ?: return false
            val configurations = (0 until componentProperties.length()).mapNotNull { propertyIndex ->
                componentProperties.optJSONObject(propertyIndex)?.takeIf {
                    it.optString("name") == "nestro:gradle:configurations"
                }?.optString("value")
            }.flatMap { it.split(",") }.map { it.trim() }.filter { it in expectedConfigurations }.toSet()
            if (configurations.isEmpty()) return false
            coveredConfigurations += configurations
        }
        return coveredConfigurations.containsAll(expectedConfigurations)
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
                runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
                runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
            }
            val builder = factory.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> InputSource(StringReader("")) }
            }
            val document = builder.parse(InputSource(StringReader(content)))
            val root = document.documentElement ?: return null
            if (root.tagName !in setOf("testsuite", "testsuites")) return null

            fun checkedCount(value: String, label: String): Int {
                val parsed = value.toLongOrNull() ?: error("JUnit 报告计数不是整数：$label")
                require(parsed in 0..Int.MAX_VALUE.toLong()) { "JUnit 报告计数越界或为负数：$label" }
                return parsed.toInt()
            }
            fun rootCount(attribute: String): Int =
                if (root.hasAttribute(attribute)) checkedCount(root.getAttribute(attribute), attribute) else 0
            fun suiteCount(element: org.w3c.dom.Element, attribute: String): Long {
                if (!element.hasAttribute(attribute)) return 0L
                return checkedCount(element.getAttribute(attribute), "testsuite.$attribute").toLong()
            }

            // Validate every nested suite even when the root provides aggregate counters.
            // Otherwise a malformed child (including a negative value) could be hidden by valid root totals.
            val suites = root.getElementsByTagName("testsuite")
            for (index in 0 until suites.length) {
                val suite = suites.item(index) as org.w3c.dom.Element
                listOf("tests", "failures", "errors", "skipped").forEach { suiteCount(suite, it) }
            }

            val total: Int
            val failures: Int
            val errors: Int
            val skipped: Int
            if (root.hasAttribute("tests")) {
                total = rootCount("tests")
                failures = rootCount("failures")
                errors = rootCount("errors")
                skipped = rootCount("skipped")
            } else {
                fun aggregate(attribute: String): Int {
                    val value = (0 until suites.length).sumOf {
                        suiteCount(suites.item(it) as org.w3c.dom.Element, attribute)
                    }
                    require(value <= Int.MAX_VALUE.toLong()) { "JUnit 子测试套件计数总和越界：$attribute" }
                    return value.toInt()
                }
                total = aggregate("tests")
                failures = aggregate("failures")
                errors = aggregate("errors")
                skipped = aggregate("skipped")
            }
            require(failures.toLong() + errors + skipped <= total) { "JUnit 测试报告计数不一致" }
            CoworkbenchTestReport(path, total, total - failures - errors - skipped,
                failures, errors, skipped)
        }.getOrNull()
    }

}
