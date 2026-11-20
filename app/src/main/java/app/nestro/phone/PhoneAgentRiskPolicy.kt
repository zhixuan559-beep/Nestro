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
        "删除账户", "删除", "发布", "发送", "分享", "上传", "申请退款", "确认退款", "提交退款",
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

    fun containsSensitiveValue(value: String): Boolean {
        val decoded = runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
        return email.containsMatchIn(decoded) ||
            phone.containsMatchIn(decoded) ||
            card.containsMatchIn(decoded) ||
            nationalId.containsMatchIn(decoded)
    }

    fun redactSensitiveText(value: String): String = value
        .replace(email, "[邮箱已隐藏]")
        .replace(phone, "[手机号已隐藏]")
        .replace(card, "[疑似卡号已隐藏]")
        .replace(verificationCode, "[六位数字已隐藏]")
        .replace(nationalId, "[身份证号已隐藏]")
        .replace(Regex("(收货地址|详细地址|家庭地址|住址|地址)[:：]\\s*[^，,；;。]+"), "$1：[地址已隐藏]")
        .replace(Regex("(收货人|联系人|姓名)[:：]\\s*[^，,；;。]+"), "$1：[姓名已隐藏]")

    private fun normalize(value: String): String =
        value.trim().lowercase().replace(Regex("[\\s：:，,。.!！?？、_-]+"), "")
}
