package app.nestro.phone

import app.nestro.data.PhoneAgentTaskStore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PhoneAgentActionParserTest {
    @Test
    fun ambiguousInterruptionRequiresExplicitRetryAndNeverLooksRunning() {
        assertEquals(PhoneAgentTaskStore.STATUS_INTERRUPTED, PhoneAgentTaskStore.interruptedStatus(PhoneAgentTaskStore.STATUS_RUNNING, true))
        assertEquals(PhoneAgentTaskStore.STATUS_PAUSED, PhoneAgentTaskStore.interruptedStatus(PhoneAgentTaskStore.STATUS_RUNNING, false))
        assertEquals(PhoneAgentTaskStore.STATUS_COMPLETED, PhoneAgentTaskStore.interruptedStatus(PhoneAgentTaskStore.STATUS_COMPLETED, true))
        assertEquals(PhoneAgentTaskStore.STATUS_INTERRUPTED, PhoneAgentTaskStore.interruptedStatus(PhoneAgentTaskStore.STATUS_INTERRUPTED, true))
        assertEquals(PhoneAgentTaskStore.STATUS_INTERRUPTED, PhoneAgentTaskStore.interruptedStatus(PhoneAgentTaskStore.STATUS_INTERRUPTED, false))
    }

    @Test
    fun paymentButtonBecomesUserHandoff() {
        val plan = PhoneAgentActionParser.parse(
            """{"done":false,"actions":[{"type":"click","text":"确认支付"}]}"""
        )
        assertTrue(plan.actions.single() is PhoneAgentExecutor.Action.ConfirmSensitive)
    }

    @Test
    fun loginAndAuthorizationButtonsRequireUserHandoff() {
        listOf("登录", "注册", "身份验证", "授权", "允许", "同意", "删除", "注销", "清空", "订阅").forEach { label ->
            val plan = PhoneAgentActionParser.parse(
                """{"done":false,"actions":[{"type":"click","text":"$label"}]}"""
            )
            assertTrue("$label must require user handoff", plan.actions.single() is PhoneAgentExecutor.Action.ConfirmSensitive)
        }
    }

    @Test
    fun sendingMessageButtonBecomesUserHandoff() {
        val plan = PhoneAgentActionParser.parse(
            """{"done":false,"actions":[{"type":"click","text":"发送"}]}"""
        )
        assertTrue(plan.actions.single() is PhoneAgentExecutor.Action.ConfirmSensitive)
    }

    @Test
    fun publishingSharingAndDialingButtonsRequireUserHandoff() {
        listOf("发布", "分享", "拨打电话").forEach { label ->
            val plan = PhoneAgentActionParser.parse(
                """{"done":false,"actions":[{"type":"click","text":"$label"}]}"""
            )
            assertTrue("$label must require user handoff", plan.actions.single() is PhoneAgentExecutor.Action.ConfirmSensitive)
        }
    }

    @Test
    fun personalInformationFieldBecomesUserHandoffWithoutPersistingValue() {
        val plan = PhoneAgentActionParser.parse(
            """{"done":false,"actions":[{"type":"enter_text","label":"收货地址","value":"成都市某某街道 123 号"}]}"""
        )
        val action = plan.actions.single()
        assertTrue(action is PhoneAgentExecutor.Action.ConfirmSensitive)
        assertTrue((action as PhoneAgentExecutor.Action.ConfirmSensitive).description.contains("收货地址"))
    }

    @Test
    fun sensitiveTaskContextIsRedactedBeforeModelTransmission() {
        val raw = "给13812345678发消息，邮箱 test@example.com，验证码123456，地址：成都市锦江区某某路88号"
        val safe = PhoneAgentTaskStore.redactSensitiveText(raw)
        assertTrue(safe.contains("[手机号已隐藏]"))
        assertTrue(safe.contains("[邮箱已隐藏]"))
        assertTrue(safe.contains("[六位数字已隐藏]"))
        assertTrue(safe.contains("[地址已隐藏]"))
        assertTrue(!safe.contains("13812345678"))
        assertTrue(!safe.contains("test@example.com"))
        assertTrue(!safe.contains("某某路88号"))
    }

    @Test
    fun onlyHttpAndHttpsUrlsAreAccepted() {
        val plan = PhoneAgentActionParser.parse(
            """{"done":false,"actions":[{"type":"open_url","url":"https://example.com/search"}]}"""
        )
        assertTrue(plan.actions.single() is PhoneAgentExecutor.Action.OpenUrl)
        try {
            PhoneAgentActionParser.parse(
                """{"done":false,"actions":[{"type":"open_url","url":"file:///etc/passwd"}]}"""
            )
            fail("file URL should be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun actionBatchIsBounded() {
        val rows = (1..13).joinToString(",") { """{"type":"wait","millis":1}""" }
        try {
            PhoneAgentActionParser.parse("""{"done":false,"actions":[$rows]}""")
            fail("more than twelve actions should be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun safeWaitActionIsParsed() {
        val plan = PhoneAgentActionParser.parse(
            """{"done":false,"actions":[{"type":"wait","millis":500}]}"""
        )
        assertEquals(PhoneAgentExecutor.Action.Wait(500), plan.actions.single())
    }

    @Test
    fun validatesPersistedExecutableActionPlan() {
        val plan = """{"format":"phone_agent_actions_v1","actions":[{"type":"wait","millis":500}]}"""
        assertEquals(1, PhoneAgentTaskStore.validatePlanJson(plan))
    }

    @Test
    fun validatesInitialPlannerStepSnapshot() {
        val plan = """[{"kind":"VERIFY_PAGE","title":"核对页面","risk":"LOW","requiresUserConfirmation":false}]"""
        assertEquals(1, PhoneAgentTaskStore.validatePlanJson(plan))
    }

    @Test
    fun allowsEmptyPlanAfterTaskClarification() {
        assertEquals(0, PhoneAgentTaskStore.validatePlanJson("[]"))
    }

    @Test
    fun rejectsUnknownActionInRestoredBackup() {
        try {
            PhoneAgentTaskStore.validatePlanJson("""{"format":"phone_agent_actions_v1","actions":[{"type":"run_shell"}]}""")
            fail("unknown action must be rejected")
        } catch (_: RuntimeException) {
        }
    }

    @Test
    fun rejectsMalformedPlannerStepInRestoredBackup() {
        try {
            PhoneAgentTaskStore.validatePlanJson("""[{"kind":"RUN_CODE","title":"执行代码","risk":"LOW","requiresUserConfirmation":false}]""")
            fail("unknown planner step must be rejected")
        } catch (_: RuntimeException) {
        }
    }
}
