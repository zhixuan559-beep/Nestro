package app.nestro.phone

import app.nestro.data.PhoneAgentTaskStore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PhoneAgentActionParserTest {
    @Test
    fun paymentButtonBecomesUserHandoff() {
        val plan = PhoneAgentActionParser.parse(
            """{"done":false,"actions":[{"type":"click","text":"确认支付"}]}"""
        )
        assertTrue(plan.actions.single() is PhoneAgentExecutor.Action.ConfirmSensitive)
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
    fun webSearchBecomesEncodedHttpsSearchUrl() {
        val plan = PhoneAgentActionParser.parse(
            """{"done":false,"actions":[{"type":"search_web","query":"成都 咖啡 到手价"}]}"""
        )
        val action = plan.actions.single() as PhoneAgentExecutor.Action.OpenUrl
        assertTrue(action.url.startsWith("https://html.duckduckgo.com/html/?q="))
        assertTrue(action.url.contains("%"))
    }

    @Test
    fun searchWebCanUseAlternatePublicSearchEngines() {
        val plan = PhoneAgentActionParser.parse(
            """{"done":false,"actions":[{"type":"search_web","query":"coffee delivery fees","engine":"google"}]}"""
        )
        val action = plan.actions.single() as PhoneAgentExecutor.Action.OpenUrl
        assertTrue(action.url.startsWith("https://www.google.com/search?q="))
    }

    @Test
    fun rejectsUnknownSearchEngine() {
        try {
            PhoneAgentActionParser.parse(
                """{"done":false,"actions":[{"type":"search_web","query":"coffee","engine":"unknown"}]}"""
            )
            fail("unknown search engine must be rejected")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun sensitiveSearchTermsAndFieldLabelsBecomeUserHandoff() {
        val search = PhoneAgentActionParser.parse(
            """{"done":false,"actions":[{"type":"search_web","query":"phone number: +1 202 555 0123"}]}"""
        )
        val field = PhoneAgentActionParser.parse(
            """{"done":false,"actions":[{"type":"enter_text","label":"test@example.com","value":"ordinary"}]}"""
        )
        assertTrue(search.actions.single() is PhoneAgentExecutor.Action.ConfirmSensitive)
        assertTrue(field.actions.single() is PhoneAgentExecutor.Action.ConfirmSensitive)
    }

    @Test
    fun sensitivePostClickEvidenceBecomesUserHandoff() {
        val plan = PhoneAgentActionParser.parse(
            """{"done":false,"actions":[{"type":"click","text":"继续","expectedAfter":"test@example.com"}]}"""
        )
        assertTrue(plan.actions.single() is PhoneAgentExecutor.Action.ConfirmSensitive)
    }

    @Test
    fun completedPlanCannotAlsoRequestNewActions() {
        try {
            PhoneAgentActionParser.parse(
                """{"done":true,"completionEvidence":"已完成","actions":[{"type":"wait","millis":1}]}"""
            )
            fail("a completed plan must not include executable actions")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun rejectsMissingOrWrongTypedActionEnvelope() {
        listOf(
            """{"done":false}""",
            """{"done":"false","actions":[]}""",
            """{"done":false,"actions":"wait"}"""
        ).forEach { raw ->
            try {
                PhoneAgentActionParser.parse(raw)
                fail("invalid action envelope should be rejected")
            } catch (_: IllegalArgumentException) {
            }
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
