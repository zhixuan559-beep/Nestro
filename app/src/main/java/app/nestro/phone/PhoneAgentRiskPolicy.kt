package app.nestro.phone

/**
 * One conservative risk policy shared by model-plan parsing and the Android
 * execution adapter. A model cannot downgrade a risky action by changing its
 * description or action type.
 */
object PhoneAgentRiskPolicy {
    private val commitActions = listOf(
        "支付", "付款", "转账", "下单", "提交订单", "立即下单", "立即购买",
        "确认购买", "确认支付", "确认提交", "最终提交", "预约提交",
        "确认预约", "立即预约", "确认预订", "立即预订", "删除账号",
        "删除账户", "删除", "发布", "发送", "分享", "上传", "退款",
        "提现", "充值"
    )

    private val sensitiveLabels = listOf(
        "密码", "验证码", "短信码", "动态码", "支付码", "银行卡", "身份证",
        "安全码", "cvv", "pin", "手机号", "手机号码", "电话", "邮箱",
        "电子邮件", "地址", "联系人", "收货人", "姓名", "账号", "账户"
    )

    private val email = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
    private val phone = Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)")
    private val card = Regex("(?<!\\d)\\d{16,19}(?!\\d)")
    private val verificationCode = Regex("(?<!\\d)\\d{6}(?!\\d)")
    private val nationalId = Regex("(?<![0-9Xx])[0-9]{17}[0-9Xx](?![0-9Xx])")

    fun requiresUserHandoffForClick(label: String): Boolean {
        val normalized = normalize(label)
        if (normalized.isBlank()) return false
        if (normalized in setOf("购买", "预约", "预订", "确认", "提交")) return true
        return commitActions.any { phrase -> normalized.contains(normalize(phrase)) }
    }

    fun isSensitiveField(label: String): Boolean {
        val normalized = normalize(label)
        return normalized.isNotBlank() && sensitiveLabels.any { normalized.contains(normalize(it)) }
    }

    fun containsSensitiveValue(value: String): Boolean =
        email.containsMatchIn(value) ||
            phone.containsMatchIn(value) ||
            card.containsMatchIn(value) ||
            verificationCode.containsMatchIn(value) ||
            nationalId.containsMatchIn(value)

    private fun normalize(value: String): String =
        value.trim().lowercase().replace(Regex("[\\s：:，,。.!！?？、_-]+"), "")
}
