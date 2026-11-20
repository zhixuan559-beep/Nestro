package app.nestro.phone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAgentRiskPolicyTest {
    @Test
    fun finalCommitActionsRequireUserHandoff() {
        listOf("立即购买", "确认支付", "提交订单", "确认预约", "删除账号", "发布内容", "发送消息", "Pay now", "Place order", "Delete account")
            .forEach { assertTrue("$it must be handed to the user", PhoneAgentRiskPolicy.requiresUserHandoffForClick(it)) }
    }

    @Test
    fun informationalLabelsAreNotMistakenForCommitActions() {
        listOf("购买须知", "预约规则", "订单详情", "查看退款政策", "搜索提交记录", "Purchase history")
            .forEach { assertFalse("$it is informational", PhoneAgentRiskPolicy.requiresUserHandoffForClick(it)) }
    }

    @Test
    fun sensitiveFieldsAreDetectedAcrossCommonLabels() {
        listOf("收货地址", "联系人姓名", "手机号码", "银行卡号", "验证码")
            .forEach { assertTrue("$it is sensitive", PhoneAgentRiskPolicy.isSensitiveField(it)) }
        assertTrue(PhoneAgentRiskPolicy.isSensitiveField("Password"))
        assertTrue(PhoneAgentRiskPolicy.isSensitiveField("Email address"))
        assertFalse(PhoneAgentRiskPolicy.isSensitiveField("商品名称"))
    }

    @Test
    fun sensitiveValuesAreDetectedWithoutStoringThemInPolicyResults() {
        assertTrue(PhoneAgentRiskPolicy.containsSensitiveValue("13812345678"))
        assertTrue(PhoneAgentRiskPolicy.containsSensitiveValue("test@example.com"))
        assertTrue(PhoneAgentRiskPolicy.containsSensitiveValue("test%40example.com"))
        assertTrue(PhoneAgentRiskPolicy.containsSensitiveValue("6222021234567890123"))
        assertFalse(PhoneAgentRiskPolicy.containsSensitiveValue("普通公开商品描述"))
        assertFalse(PhoneAgentRiskPolicy.containsSensitiveValue("123456"))
    }

    @Test
    fun sensitiveValuesAreRedactedBeforeTaskPersistenceOrDisplay() {
        val redacted = PhoneAgentRiskPolicy.redactSensitiveText("联系人：张三，电话：13812345678，邮箱：test@example.com")
        assertFalse(redacted.contains("13812345678"))
        assertFalse(redacted.contains("test@example.com"))
        assertTrue(redacted.contains("[手机号已隐藏]"))
        assertTrue(redacted.contains("[邮箱已隐藏]"))
        val english = PhoneAgentRiskPolicy.redactSensitiveText("Shipping address: 123 Main Street; Full name: Alex")
        assertFalse(english.contains("123 Main Street"))
        assertFalse(english.contains("Alex"))
    }

    @Test
    fun parserHandsSensitiveValuesBackInsteadOfEmbeddingThemInActions() {
        val plan = PhoneAgentActionParser.parse(
            """{"done":false,"actions":[{"type":"enter_text","label":"搜索关键词","value":"13812345678"}]}"""
        )
        assertTrue(plan.actions.single() is PhoneAgentExecutor.Action.ConfirmSensitive)
    }

    @Test
    fun parserAndExecutionPolicyUseTheSameCommitBoundary() {
        val handoff = PhoneAgentActionParser.parse(
            """{"done":false,"actions":[{"type":"click","text":"立即购买"}]}"""
        )
        assertTrue(handoff.actions.single() is PhoneAgentExecutor.Action.ConfirmSensitive)

        val informational = PhoneAgentActionParser.parse(
            """{"done":false,"actions":[{"type":"click","text":"购买须知"}]}"""
        )
        assertTrue(informational.actions.single() is PhoneAgentExecutor.Action.ClickText)
    }
}
