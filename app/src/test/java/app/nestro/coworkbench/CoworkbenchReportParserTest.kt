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
}
