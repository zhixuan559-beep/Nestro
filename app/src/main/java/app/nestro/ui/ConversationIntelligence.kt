package app.nestro.ui

import org.json.JSONObject

internal fun isCreationIntent(prompt: String, target: String): Boolean {
    val text = prompt.trim()
    val hasCreationVerb = listOf("创建", "新建", "设计", "生成", "做一个", "帮我做").any { text.contains(it) }
    val hasNegation = listOf("不要", "不需要", "取消", "别创建").any { text.contains(it) }
    return text.contains(target) && hasCreationVerb && !hasNegation
}

internal fun parseGeneratedDefinition(raw: String): Pair<String, String> {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    require(start >= 0 && end > start) { "模型没有返回有效 JSON" }
    val json = JSONObject(raw.substring(start, end + 1))
    val name = json.optString("name").trim()
    val prompt = json.optString("prompt").trim()
    require(name.isNotBlank() && name.length <= 40) { "能力名称为空或过长" }
    require(prompt.length in 80..8000) { "能力指令长度不符合要求" }
    return name to prompt
}
