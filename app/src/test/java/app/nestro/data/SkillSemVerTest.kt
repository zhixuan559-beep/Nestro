package app.nestro.data

import org.junit.Assert.*
import org.junit.Test

class SkillSemVerTest {
    @Test fun comparesNumericCoreAndIgnoresBuildMetadata() {
        assertTrue(SkillSemVer.compare("1.10.0","1.9.9")>0)
        assertEquals(0,SkillSemVer.compare("2.1.0+build.7","2.1.0+build.8"))
        assertTrue(SkillSemVer.compare("1.999999999999999999999.0","1.10.0")>0)
        assertFalse(runCatching { SkillSemVer.compare("1.0.0+build..x","1.0.0") }.isSuccess)
    }
    @Test fun followsPrereleasePrecedence() {
        assertTrue(SkillSemVer.compare("1.0.0-alpha","1.0.0")<0)
        assertTrue(SkillSemVer.compare("1.0.0-alpha.2","1.0.0-alpha.10")<0)
        assertTrue(SkillSemVer.compare("1.0.0-rc.2","1.0.0-rc.1")>0)
    }
    @Test fun supportsComparatorCaretTildeAndOrRanges() {
        assertTrue(SkillSemVer.matches("1.8.2",">=1.7.0 <2.0.0"))
        assertFalse(SkillSemVer.matches("2.0.0",">=1.7.0 <2.0.0"))
        assertTrue(SkillSemVer.matches("0.2.8","^0.2.3"))
        assertFalse(SkillSemVer.matches("0.3.0","^0.2.3"))
        assertTrue(SkillSemVer.matches("1.4.9","~1.4.2"))
        assertFalse(SkillSemVer.matches("1.5.0","~1.4.2"))
        assertTrue(SkillSemVer.matches("3.0.0","^1.0.0 || ^3.0.0"))
        assertTrue(SkillSemVer.matches("2.0.0","^1.0.0 || *"))
        assertFalse(SkillSemVer.matches("1.2.0-beta.1","*"))
        assertFalse(SkillSemVer.matches("1.2.0-beta.1",">=1.0.0 <2.0.0"))
        assertTrue(SkillSemVer.matches("1.2.0-beta.2",">=1.2.0-beta.1 <2.0.0"))
    }
    @Test fun rejectsInvalidSemVerAndLeadingZeroPrerelease() {
        assertFalse(runCatching { SkillSemVer.compare("01.0.0","1.0.0") }.isSuccess)
        assertFalse(runCatching { SkillSemVer.compare("1.0.0-01","1.0.0") }.isSuccess)
    }

    @Test fun comparesLongNumericPrereleaseIdentifiersWithoutOverflow() {
        assertTrue(SkillSemVer.compare("1.0.0-100000000000000000000", "1.0.0-99999999999999999999") > 0)
        assertTrue(SkillSemVer.compare("1.0.0-99999999999999999999", "1.0.0-0abc") < 0)
    }
}
