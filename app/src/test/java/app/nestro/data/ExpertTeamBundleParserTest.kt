package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExpertTeamBundleParserTest {
    @Test fun teamExportImportPreservesMemberRoles() {
        val members=listOf(ExpertTeamMemberBundle("研究员","检索事实"),ExpertTeamMemberBundle("审校员","检查逻辑"))
        val parsed=ExpertTeamBundleParser.parse(ExpertTeamBundleParser.exportJson("研究小组","先研究再审校",members))
        assertEquals("研究小组",parsed.name)
        assertEquals(members,parsed.members)
    }
    @Test fun rejectsTeamWithTooFewMembers() {
        assertThrows(IllegalArgumentException::class.java) {
            ExpertTeamBundleParser.exportJson("小组","分工",listOf(ExpertTeamMemberBundle("研究员","检索事实")))
        }
    }
    @Test fun rejectsDuplicateMemberNames() {
        val raw="""{"format":"nestro-expert-team","name":"小组","description":"分工","members":[{"name":"研究员","prompt":"a"},{"name":"研究员","prompt":"b"}]}"""
        assertThrows(IllegalArgumentException::class.java) { ExpertTeamBundleParser.parse(raw) }
    }
}
