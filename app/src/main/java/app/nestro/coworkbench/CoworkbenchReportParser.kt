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

object CoworkbenchReportParser {
    private const val MAX_REPORT_CHARS = 1_000_000
    private const val MAX_COMPONENTS = 5_000

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
                        if (ref.optString("referenceType").contains("purl", true)) {
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
            }
            val builder = factory.newDocumentBuilder()
            val document = builder.parse(InputSource(StringReader(content)))
            val root = document.documentElement ?: return null
            fun count(attribute: String): Int = root.getAttribute(attribute).toIntOrNull()?.coerceAtLeast(0) ?: 0
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
            CoworkbenchTestReport(path, total, (total - failures - errors - skipped).coerceAtLeast(0),
                failures, errors, skipped)
        }.getOrNull()
    }
}
