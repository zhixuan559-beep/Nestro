package app.nestro.data

import app.nestro.phone.PhoneAgentExecutor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class PhoneAgentTaskStoreTest {
    @Test
    fun validatesAndDecodesPersistedActionForExplicitResume() {
        val encoded = JSONObject()
            .put("format", "phone_agent_actions_v1")
            .put("actions", JSONArray().put(
                JSONObject().put("type", "open_url").put("url", "https://example.org")
            ))
            .toString()

        assertEquals(1, PhoneAgentTaskStore.validatePlanJson(encoded))
        val actions = PhoneAgentTaskStore.decodeActions(encoded)
        assertEquals(1, actions.size)
        assertTrue(actions.single() is PhoneAgentExecutor.Action.OpenUrl)
    }

    @Test
    fun validatesAndDecodesPersistedMediaDownloadAction() {
        val url = "https://cdn.example.com/scene.mp4"
        val sourceUrl = "https://example.com/scene-license"
        val encoded = JSONObject()
            .put("format", "phone_agent_actions_v1")
            .put("actions", JSONArray().put(
                JSONObject().put("type", "download_asset").put("url", url).put("name", "scene.mp4").put("source_url", sourceUrl).put("license", "CC0")
            ))
            .toString()
        assertEquals(1, PhoneAgentTaskStore.validatePlanJson(encoded))
        assertEquals(PhoneAgentExecutor.Action.DownloadAsset(url, "scene.mp4", sourceUrl, "CC0"), PhoneAgentTaskStore.decodeActions(encoded).single())
    }

    @Test
    fun rejectsUnknownPersistedActionInsteadOfSilentlySkippingIt() {
        val encoded = JSONObject()
            .put("format", "phone_agent_actions_v1")
            .put("actions", JSONArray().put(JSONObject().put("type", "execute_shell")))
            .toString()

        assertThrows(IllegalStateException::class.java) {
            PhoneAgentTaskStore.validatePlanJson(encoded)
        }
    }

    @Test
    fun rejectsUnsafePersistedUrlBeforeResume() {
        val encoded = JSONObject()
            .put("format", "phone_agent_actions_v1")
            .put("actions", JSONArray().put(
                JSONObject().put("type", "open_url").put("url", "file:///private/data")
            ))
            .toString()

        assertThrows(IllegalArgumentException::class.java) {
            PhoneAgentTaskStore.validatePlanJson(encoded)
        }
    }

    @Test
    fun rejectsPersistedHighRiskClicksAndSensitiveInput() {
        val riskyClick = JSONObject()
            .put("format", "phone_agent_actions_v1")
            .put("actions", JSONArray().put(
                JSONObject().put("type", "click").put("text", "确认支付")
            ))
            .toString()
        val sensitiveInput = JSONObject()
            .put("format", "phone_agent_actions_v1")
            .put("actions", JSONArray().put(
                JSONObject().put("type", "enter_text").put("label", "验证码").put("value", "123456")
            ))
            .toString()

        assertThrows(IllegalArgumentException::class.java) {
            PhoneAgentTaskStore.validatePlanJson(riskyClick)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PhoneAgentTaskStore.validatePlanJson(sensitiveInput)
        }
    }

    @Test
    fun allowsOrdinaryNumericTextAndNonSensitiveConfirmationLabels() {
        val ordinaryNumber = JSONObject()
            .put("format", "phone_agent_actions_v1")
            .put("actions", JSONArray().put(
                JSONObject().put("type", "enter_text").put("label", "订单编号").put("value", "123456")
            ))
            .toString()
        val ordinaryConfirm = JSONObject()
            .put("format", "phone_agent_actions_v1")
            .put("actions", JSONArray().put(
                JSONObject().put("type", "click").put("text", "确认筛选")
            ))
            .toString()

        assertEquals(1, PhoneAgentTaskStore.validatePlanJson(ordinaryNumber))
        assertEquals(1, PhoneAgentTaskStore.validatePlanJson(ordinaryConfirm))
    }

    @Test
    fun restoredActionsUseTheSameSensitiveFieldAndValuePolicyAsNewPlans() {
        val sensitiveLabel = JSONObject()
            .put("format", "phone_agent_actions_v1")
            .put("actions", JSONArray().put(
                JSONObject().put("type", "enter_text").put("label", "Email address").put("value", "ordinary")
            ))
            .toString()
        val bankCardValue = JSONObject()
            .put("format", "phone_agent_actions_v1")
            .put("actions", JSONArray().put(
                JSONObject().put("type", "enter_text").put("label", "备注").put("value", "4111111111111111")
            ))
            .toString()
        val sensitiveExpectedAfter = JSONObject()
            .put("format", "phone_agent_actions_v1")
            .put("actions", JSONArray().put(
                JSONObject().put("type", "click").put("text", "继续").put("expectedAfter", "test@example.com")
            ))
            .toString()

        assertThrows(IllegalArgumentException::class.java) {
            PhoneAgentTaskStore.validatePlanJson(sensitiveLabel)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PhoneAgentTaskStore.validatePlanJson(bankCardValue)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PhoneAgentTaskStore.validatePlanJson(sensitiveExpectedAfter)
        }
    }

    @Test
    fun validatesLegacyPlanRiskAndConfirmationFields() {
        val valid = JSONArray().put(
            JSONObject()
                .put("kind", "REQUEST_USER_CONFIRMATION")
                .put("title", "请用户确认最终提交")
                .put("risk", "HIGH")
                .put("requiresUserConfirmation", true)
        ).toString()
        assertEquals(1, PhoneAgentTaskStore.validatePlanJson(valid))

        val invalid = JSONArray().put(
            JSONObject()
                .put("kind", "REQUEST_USER_CONFIRMATION")
                .put("title", "缺少风险等级")
        ).toString()
        assertThrows(IllegalArgumentException::class.java) {
            PhoneAgentTaskStore.validatePlanJson(invalid)
        }
    }

    @Test
    fun ambiguousInterruptionRequiresExplicitRetryAndNeverLooksRunning() {
        assertEquals(PhoneAgentTaskStore.STATUS_INTERRUPTED, PhoneAgentTaskStore.interruptedStatus(PhoneAgentTaskStore.STATUS_RUNNING, true))
        assertEquals(PhoneAgentTaskStore.STATUS_PAUSED, PhoneAgentTaskStore.interruptedStatus(PhoneAgentTaskStore.STATUS_RUNNING, false))
        assertEquals(PhoneAgentTaskStore.STATUS_COMPLETED, PhoneAgentTaskStore.interruptedStatus(PhoneAgentTaskStore.STATUS_COMPLETED, true))
        assertEquals(PhoneAgentTaskStore.STATUS_INTERRUPTED, PhoneAgentTaskStore.interruptedStatus(PhoneAgentTaskStore.STATUS_INTERRUPTED, false))
    }

    @Test
    fun interruptedTasksHaveAnExplicitUserFacingStatus() {
        assertEquals("中断待核对", PhoneAgentTaskStore.statusLabel(PhoneAgentTaskStore.STATUS_INTERRUPTED))
        assertEquals("已暂停", PhoneAgentTaskStore.statusLabel(PhoneAgentTaskStore.STATUS_PAUSED))
    }

    @Test
    fun coordinateClickRestoresWithPermilleCoordinates() {
        val encoded = JSONObject()
            .put("format", "phone_agent_actions_v1")
            .put("actions", JSONArray().put(
                JSONObject().put("type", "click_at").put("x", 540).put("y", 960).put("expectedAfter", "已加入购物车")
            ))
            .toString()

        assertEquals(1, PhoneAgentTaskStore.validatePlanJson(encoded))
        assertEquals(
            PhoneAgentExecutor.Action.ClickAt(540, 960, "已加入购物车"),
            PhoneAgentTaskStore.decodeActions(encoded).single()
        )
    }

    @Test
    fun coordinateClickRejectsOutOfRangeCoordinatesAndSensitiveEvidence() {
        val outOfRange = JSONObject()
            .put("format", "phone_agent_actions_v1")
            .put("actions", JSONArray().put(
                JSONObject().put("type", "click_at").put("x", 1001).put("y", 960)
            ))
            .toString()
        val sensitiveEvidence = JSONObject()
            .put("format", "phone_agent_actions_v1")
            .put("actions", JSONArray().put(
                JSONObject().put("type", "click_at").put("x", 540).put("y", 960).put("expectedAfter", "test@example.com")
            ))
            .toString()

        assertThrows(IllegalArgumentException::class.java) {
            PhoneAgentTaskStore.validatePlanJson(outOfRange)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PhoneAgentTaskStore.validatePlanJson(sensitiveEvidence)
        }
    }
}
