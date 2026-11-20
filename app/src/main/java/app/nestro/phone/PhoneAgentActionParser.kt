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
                "search_web" -> {
                    val query = item.getString("query").trim()
                    require(query.isNotBlank() && query.length <= 240) { "网页搜索关键词无效" }
                    val encoded = java.net.URLEncoder.encode(query, Charsets.UTF_8.name())
                    if (PhoneAgentRiskPolicy.containsSensitiveValue(query) || PhoneAgentRiskPolicy.isSensitiveField(query)) {
                        PhoneAgentExecutor.Action.ConfirmSensitive("搜索内容包含个人敏感信息，请先移除敏感内容再继续检索。")
                    } else {
                        PhoneAgentExecutor.Action.OpenUrl("https://html.duckduckgo.com/html/?q=$encoded")
                    }
                }
                "open_url" -> {
                    val url = item.getString("url").trim()
                    val uri = runCatching { URI(url) }.getOrElse { error("模型生成的网址无效") }
                    require((uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) && !uri.host.isNullOrBlank() && uri.userInfo == null) { "模型生成了不安全的网址" }
                    if (PhoneAgentRiskPolicy.containsSensitiveValue(url)) {
                        PhoneAgentExecutor.Action.ConfirmSensitive("网页地址包含个人敏感信息，已暂停打开。")
                    } else PhoneAgentExecutor.Action.OpenUrl(url)
                }
                "launch_app" -> {
                    val packageName = item.getString("package").trim()
                    require(packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) { "应用包名无效" }
                    PhoneAgentExecutor.Action.LaunchApp(packageName)
                }
                "click" -> {
                    val label = item.getString("text").trim()
                    require(label.isNotBlank() && label.length <= 80) { "点击目标无效" }
                    if (PhoneAgentRiskPolicy.containsSensitiveValue(label)) {
                        PhoneAgentExecutor.Action.ConfirmSensitive("页面目标包含个人敏感信息，已暂停该操作。")
                    } else if (PhoneAgentRiskPolicy.requiresUserHandoffForClick(label)) {
                        PhoneAgentExecutor.Action.ConfirmSensitive("页面出现高风险操作“$label”，请核对页面后由你本人处理。")
                    } else {
                        PhoneAgentExecutor.Action.ClickText(label, item.optString("expectedAfter").takeIf { it.isNotBlank() && it != "null" })
                    }
                }
                "enter_text" -> {
                    val label = item.getString("label").trim()
                    val value = item.getString("value")
                    require(label.isNotBlank() && label.length <= 80 && value.length <= 1000) { "输入字段或内容无效" }
                    if (PhoneAgentRiskPolicy.isSensitiveField(label) || PhoneAgentRiskPolicy.containsSensitiveValue(value)) {
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
                    if (PhoneAgentRiskPolicy.containsSensitiveValue(text)) {
                        PhoneAgentExecutor.Action.ConfirmSensitive("页面核验目标包含个人敏感信息，已暂停并交由你处理。")
                    } else PhoneAgentExecutor.Action.VerifyText(text)
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
        .let(PhoneAgentRiskPolicy::redactSensitiveText)


}
