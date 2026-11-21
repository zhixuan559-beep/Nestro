package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ExpertTeamRunHistoryCodecTest {
    @Test fun roundTripPreservesProjectOwnership() {
        val record = ExpertTeamRunResult(
            teamName = "项目团队",
            task = "分析任务",
            startedAt = 10L,
            finishedAt = 20L,
            members = listOf(ExpertTeamMemberRun("研究员", "success", 1, 100L, "结论", "")),
            projectId = "project-a"
        )
        assertEquals("project-a", ExpertTeamRunHistoryCodec.decode(ExpertTeamRunHistoryCodec.encode(listOf(record))).single().projectId)
    }

    @Test fun legacyHistoryWithoutProjectIdBelongsToDefaultProject() {
        val legacy = """[{"teamName":"旧团队","task":"任务","startedAt":10,"finishedAt":20,"members":[{"name":"研究员","status":"success","attempts":1,"elapsedMillis":100,"output":"结论","error":""}]}]"""
        assertEquals("default", ExpertTeamRunHistoryCodec.decode(legacy).single().projectId)
    }
}
