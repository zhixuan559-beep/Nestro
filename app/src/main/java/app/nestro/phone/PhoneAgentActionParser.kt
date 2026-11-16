package app.nestro.phone

import java.net.URI
import org.json.JSONObject

object PhoneAgentActionParser {
    data class ParsedPlan(
        val done: Boolean,
        val completionEvidence: String,
        val summary: String,
        val actions: List<PhoneAgentExecutor.Action>
    )

    fun parse(raw: String): ParsedPlan {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        require(start >= 0 && end > start) { "模型没有返回有效动作 JSON" }
        val root = JSONObject(raw.substring(start, end + 1))
        val rows = root.optJSONArray("actions") ?: org.json.JSONArray()
        require(rows.length() <= 12) { "单轮动作数量超过安全上限" }
        val actions = (0 until rows.length()).map { index ->
            val item = rows.getJSONObject(index)
            when (item.getString("type")) {
                "open_url" -> {
                    val url = item.getString("url").trim()
                    val uri = runCatching { URI(url) }.getOrElse { error("模型生成的网址无效") }
                    require((uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) && !uri.host.isNullOrBlank() && uri.userInfo == null) { "模型生成了不安全的网址" }
                    PhoneAgentExecutor.Action.OpenUrl(url)
                }
                "launch_app" -> {
                    val packageName = item.getString("package").trim()
                    require(packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) { "应用包名无效" }
                    PhoneAgentExecutor.Action.LaunchApp(packageName)
                }
                "click" -> {
                    val label = item.getString("text").trim()
                    require(label.isNotBlank() && label.length <= 80) { "点击目标无效" }
                    if (isHighRisk(label)) {
                        PhoneAgentExecutor.Action.ConfirmSensitive("页面出现高风险操作“$label”，请核对页面后由你本人处理。")
                    } else {
                        PhoneAgentExecutor.Action.ClickText(label, item.optString("expectedAfter").takeIf { it.isNotBlank() && it != "null" })
                    }
                }
                "enter_text" -> {
                    val label = item.getString("label").trim()
                    val value = item.getString("value")
                    require(label.isNotBlank() && label.length <= 80 && value.length <= 1000) { "输入字段或内容无效" }
                    if (isSensitiveLabel(label) || isSensitiveValue(value)) {
                        PhoneAgentExecutor.Action.ConfirmSensitive("需要填写个人敏感信息字段“$label”，请你本人核对并填写。")
                    } else {
                        PhoneAgentExecutor.Action.EnterText(label, value)
                    }
                }
                "scroll" -> {
                    val direction = item.getString("direction")
                    require(direction == "forward" || direction == "backward") { "滚动方向无效" }
                    PhoneAgentExecutor.Action.Scroll(direction)
                }
                "wait" -> {
                    val millis = item.optLong("millis", 500L)
                    require(millis in 0..10_000) { "等待时间超出限制" }
                    PhoneAgentExecutor.Action.Wait(millis)
                }
                "verify" -> {
                    val text = item.getString("text").trim()
                    require(text.isNotBlank() && text.length <= 100) { "验证目标无效" }
                    PhoneAgentExecutor.Action.VerifyText(text)
                }
                "back" -> PhoneAgentExecutor.Action.Back
                "confirm_sensitive" -> PhoneAgentExecutor.Action.ConfirmSensitive(sanitizeMessage(item.optString("description").ifBlank { "需要你确认当前高风险步骤。" }.take(240)))
                else -> error("不支持的动作类型")
            }
        }
        return ParsedPlan(
            done = root.optBoolean("done", false),
            completionEvidence = root.optString("completionEvidence").take(120),
            summary = sanitizeMessage(root.optString("summary").take(600)),
            actions = actions
        )
    }

    private fun sanitizeMessage(value: String): String = value
        .replace(Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE), "[邮箱已隐藏]")
        .replace(Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)"), "[手机号已隐藏]")
        .replace(Regex("(?<!\\d)\\d{6,19}(?!\\d)"), "[数字信息已隐藏]")

    private fun isHighRisk(value: String): Boolean =
        listOf("付款", "支付", "转账", "购买", "预约", "确认", "立即购买", "确认购买", "提交订单", "立即下单", "确认支付", "删除账号", "确认提交", "最终提交", "预约提交", "提交", "下单", "购买", "预约", "确认", "发送", "发布", "分享", "拨打电话", "打电话", "呼叫")
            .any { value.contains(it, true) }

    private fun isSensitiveLabel(value: String): Boolean =
        listOf("密码", "验证码", "短信码", "支付码", "银行卡", "身份证", "安全码", "cvv", "pin", "手机号", "电话", "邮箱", "地址", "联系人", "收货人", "姓名", "账号")
            .any { value.contains(it, true) }

    private fun isSensitiveValue(value: String): Boolean =
        Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE).containsMatchIn(value) ||
            Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)").containsMatchIn(value) ||
            Regex("(?<!\\d)\\d{16,19}(?!\\d)").containsMatchIn(value) ||
            Regex("(?<!\\d)\\d{6}(?!\\d)").containsMatchIn(value) ||
            Regex("(?<![0-9Xx])[0-9]{17}[0-9Xx](?![0-9Xx])").containsMatchIn(value)
}
