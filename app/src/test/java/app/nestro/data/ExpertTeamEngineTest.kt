package app.nestro.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ExpertTeamEngineTest {
    private val members=listOf(
        ExpertTeamMemberTask("研究员","核实事实与证据"),
        ExpertTeamMemberTask("审校员","检查逻辑与风险"),
        ExpertTeamMemberTask("策略师","提出执行策略")
    )

    @Test fun runsMembersConcurrentlyAndReturnsIndividualResults()=runBlocking {
        val events=mutableListOf<Pair<String,String>>()
        val result=ExpertTeamEngine.run("分析组","研究、审校、制定策略","分析这个问题",members,
            onMember={ name,status -> synchronized(events) { events += name to status } }) { member,_ ->
            delay(30)
            "${member.name} 的意见"
        }
        assertEquals(3,result.successfulMembers)
        assertEquals(0,result.failedMembers)
        assertEquals(3,result.members.size)
        assertTrue(result.toContext().contains("【研究员】"))
        assertEquals(3,events.count { it.second=="success" })
    }

    @Test fun isolatesMemberFailureAndKeepsPartialResults()=runBlocking {
        val result=ExpertTeamEngine.run("分析组","分工明确","分析问题",members,maxRetries=0) { member,_ ->
            if(member.name=="审校员") error("模拟接口错误")
            "${member.name}完成"
        }
        assertEquals(2,result.successfulMembers)
        assertEquals(1,result.failedMembers)
        assertTrue(result.isUsable)
        assertEquals("failed",result.members.first { it.name=="审校员" }.status)
    }

    @Test fun retriesFailedMemberWithinBoundedAttempts()=runBlocking {
        var calls=0
        val result=ExpertTeamEngine.run("分析组","分工","分析问题",members.take(2),maxRetries=1) { member,_ ->
            if(member.name=="研究员" && ++calls==1) error("临时失败")
            "${member.name}完成"
        }
        assertEquals(2,result.successfulMembers)
        assertEquals(2,result.members.first { it.name=="研究员" }.attempts)
    }

    @Test fun timesOutOnlyTheSlowMember()=runBlocking {
        val result=ExpertTeamEngine.run("分析组","分工","分析问题",members.take(2),maxRetries=0,timeoutMillis=5000) { member,_ ->
            if(member.name=="研究员") delay(6000)
            "${member.name}完成"
        }
        assertEquals(1,result.successfulMembers)
        assertEquals("failed",result.members.first { it.name=="研究员" }.status)
        assertTrue(result.members.first { it.name=="研究员" }.error.contains("超时"))
    }

    @Test fun builtInExpertsHaveUsableDefaultRoles() {
        listOf("文案官","选题狐","代码佬").forEach { name ->
            assertTrue(ExpertTeamDefaults.rolePrompt(name).isNotBlank())
        }
        assertEquals("",ExpertTeamDefaults.rolePrompt("未知专家"))
    }

    @Test fun rejectsInvalidTeamSizeAndMissingRoles()=runBlocking {
        try {
            ExpertTeamEngine.run("分析组","分工","任务",members.take(1)) { _,_ -> "x" }
            fail("expected invalid team size")
        } catch(_:IllegalArgumentException) {}
        try {
            ExpertTeamEngine.run("分析组","分工","任务",listOf(members[0],ExpertTeamMemberTask("空角色",""))) { _,_ -> "x" }
            fail("expected missing role")
        } catch(_:IllegalArgumentException) {}
    }

    @Test fun stopSignalPreventsRetryAndCancelsRemainingTeamWork() = runBlocking {
        var calls=0
        try {
            ExpertTeamEngine.run("分析组","分工","分析问题",members.take(2),maxRetries=1,shouldStop={true}) { _,_ ->
                calls++
                "不应执行"
            }
            fail("expected cancellation")
        } catch(_: kotlinx.coroutines.CancellationException) {}
        assertEquals(0,calls)
    }

    @Test fun historyCodecRoundTripsAndCapsRecords() {
        val record=ExpertTeamRunResult("分析组","任务",10,20,listOf(
            ExpertTeamMemberRun("研究员","success",1,5,"结论"),
            ExpertTeamMemberRun("审校员","failed",2,10,error="超时")
        ))
        val decoded=ExpertTeamRunHistoryCodec.decode(ExpertTeamRunHistoryCodec.encode(listOf(record)))
        assertEquals(record,decoded.single())
        assertEquals(30,ExpertTeamRunHistoryCodec.decode(ExpertTeamRunHistoryCodec.encode(List(35){record})).size)
    }

    @Test fun collaborativeRunInvokesOnlyDelegatedExperts() = runBlocking {
        val calls = mutableListOf<String>()
        val result = ExpertTeamEngine.runCollaborative(
            teamName = "分析组",
            teamDescription = "按需协作",
            task = "研究这个问题",
            members = members,
            maxDelegations = 2,
            onMember = { _, _ -> }
        ) { member, prompt ->
            calls += member.name
            if (member.name == "研究员") {
                assertTrue(prompt.contains("[[DELEGATE:"))
                "初步研究结论 [[DELEGATE:审校员 | 检查研究结论中的逻辑漏洞]]"
            } else {
                assertTrue(prompt.contains("研究员交接"))
                "审校完成"
            }
        }
        assertEquals(listOf("研究员", "审校员"), calls)
        assertEquals(2, result.successfulMembers)
        assertEquals("初步研究结论", result.members.first().output)
        assertTrue(result.toContext().contains("审校完成"))
    }

    @Test fun collaborativeRunDoesNotLoadUnusedExperts() = runBlocking {
        val calls = mutableListOf<String>()
        val result = ExpertTeamEngine.runCollaborative(
            teamName = "分析组",
            teamDescription = "按需协作",
            task = "研究这个问题",
            members = members
        ) { member, _ ->
            calls += member.name
            "本专家已完成"
        }
        assertEquals(1, calls.size)
        assertEquals(1, result.successfulMembers)
    }
    @Test fun plansPrimaryAndRelevantSecondaryExpertsWithinBound() {
        val members = listOf(
            ExpertTeamMemberTask("代码佬", "负责技术开发、接口和软件实现"),
            ExpertTeamMemberTask("文案官", "负责内容写作与表达"),
            ExpertTeamMemberTask("选题狐", "负责研究、选题和用户洞察"),
            ExpertTeamMemberTask("空角色", "")
        )
        val plan = ExpertTeamEngine.planInitialTeam("分析安卓接口开发方案", members)
        assertEquals("代码佬", plan.first().name)
        assertTrue(plan.size <= 3)
        assertTrue(plan.none { it.name == "空角色" })
    }

}
