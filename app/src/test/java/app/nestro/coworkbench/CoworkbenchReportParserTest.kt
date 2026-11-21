package app.nestro.coworkbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoworkbenchReportParserTest {
    @Test
    fun parsesCycloneDxComponentsAndLicenses() {
        val report = """{
            "bomFormat":"CycloneDX",
            "components":[
                {"group":"org.example","name":"core","version":"1.2.3","purl":"pkg:maven/org.example/core@1.2.3",
                 "licenses":[{"license":{"id":"Apache-2.0"}}]}
            ]
        }"""
        val components = CoworkbenchReportParser.parseSbom("reports/bom.json", report)
        assertEquals(1, components.size)
        assertEquals("org.example:core", components.single().name)
        assertEquals("1.2.3", components.single().version)
        assertEquals("Apache-2.0", components.single().license)
    }

    @Test
    fun parsesSpdxPackagesAndPurls() {
        val report = """{
            "spdxVersion":"SPDX-2.3",
            "packages":[
                {"name":"sample","versionInfo":"4.5","licenseDeclared":"MIT",
                 "externalRefs":[{"referenceType":"purl","referenceLocator":"pkg:maven/org.example/sample@4.5"}]}
            ]
        }"""
        val components = CoworkbenchReportParser.parseSbom("sbom.spdx.json", report)
        assertEquals(1, components.size)
        assertEquals("pkg:maven/org.example/sample@4.5", components.single().packageUrl)
        assertEquals("MIT", components.single().license)
    }

    @Test
    fun parsesJunitTotalsAndRejectsExternalEntityDocuments() {
        val xml = """<testsuites tests="8" failures="2" errors="1" skipped="1">
            <testsuite name="unit"/>
        </testsuites>"""
        val report = CoworkbenchReportParser.parseJunitXml("TEST-results.xml", xml)
        assertEquals(8, report?.total)
        assertEquals(4, report?.passed)
        assertEquals(2, report?.failures)
        assertEquals(1, report?.errors)
        assertEquals(1, report?.skipped)

        val xxe = """<!DOCTYPE foo [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <testsuite tests="1"><testcase name="&x;"/></testsuite>"""
        assertNull(CoworkbenchReportParser.parseJunitXml("xxe.xml", xxe))
    }

    @Test
    fun boundsAndIgnoresUnknownReports() {
        assertTrue(CoworkbenchReportParser.parseSbom("report.json", "{}").isEmpty())
        assertTrue(CoworkbenchReportParser.parseSbom("report.json", "x".repeat(1_000_001)).isEmpty())
        assertFalse(CoworkbenchReportParser.parseJunitXml("report.txt", "<testsuite tests=\"1\"/>") != null)
    }
    @Test
    fun failedTestReportPreventsSuccessfulValidationClassification() {
        val result = CoworkbenchValidationResult(
            runId = 7L,
            status = "completed",
            conclusion = "success",
            url = "https://github.com/example/repo/actions/runs/7",
            createdAt = "2026-11-22T00:00:00Z",
            testReports = listOf(CoworkbenchTestReport("TEST-sample.xml", 3, 2, 1, 0, 0))
        )
        assertFalse(result.successful)
        assertEquals(1, result.reportedTestFailures)
    }

    @Test
    fun parsesResolvedGradleDependencyTreeAndVersionConflicts() {
        val report = """debugRuntimeClasspath - Runtime classpath of source set 'main'.
+--- org.example:alpha:1.0
|    \--- org.example:transitive:2.0
\--- org.example:beta:1.2 -> 1.3
"""
        val dependencies = CoworkbenchReportParser.parseGradleDependencyReport("gradle-dependencies.txt", report)
        assertEquals(3, dependencies.size)
        assertEquals("debugRuntimeClasspath", dependencies.first().configuration)
        assertEquals("org.example:alpha:1.0", listOf(
            dependencies[0].group, dependencies[0].artifact, dependencies[0].resolvedVersion
        ).joinToString(":"))
        assertEquals(1, dependencies[1].depth)
        assertEquals("org.example:alpha:1.0", dependencies[1].parent)
        assertEquals("1.2", dependencies[2].requestedVersion)
        assertEquals("1.3", dependencies[2].resolvedVersion)
    }

    @Test
    fun validationRequiresCompleteJUnitReportsAndIntactArtifacts() {
        val base = CoworkbenchValidationResult(
            runId = 8L,
            status = "completed",
            conclusion = "success",
            url = "https://github.com/example/repo/actions/runs/8",
            createdAt = "2026-11-22T00:00:00Z"
        )
        assertFalse(base.successful)
        val withTests = base.copy(
            testReports = listOf(CoworkbenchTestReport("TEST-sample.xml", 2, 2, 0, 0, 0))
        )
        assertTrue(withTests.successful)
        val truncated = withTests.copy(
            artifacts = listOf(CoworkbenchArtifactReport(
                1L, "reports", 300_000L,
                listOf(CoworkbenchArtifactFile("TEST-sample.xml", "<testsuites/>", truncated = true))
            ))
        )
        assertFalse(truncated.successful)
        val failedDownload = withTests.copy(
            artifacts = listOf(CoworkbenchArtifactReport(2L, "reports", 100L, emptyList(), "download failed"))
        )
        assertFalse(failedDownload.successful)
    }

}
