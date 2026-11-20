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
}
