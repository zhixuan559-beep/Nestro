package app.nestro.data

import org.json.JSONObject

data class ExpertBundle(val name:String,val version:String,val description:String,val prompt:String,val source:String)

object ExpertBundleParser {
    fun parse(raw:String, sourceLabel:String):ExpertBundle {
        require(raw.isNotBlank() && raw.length<=20_000) { "专家文件为空或超过 20 KB" }
        val sourceName=sourceLabel.substringAfterLast('/').take(120).ifBlank { "本地文件" }
        val json=JSONObject(raw)
        val name=json.optString("name").trim()
        val version=json.optString("version","1.0.0").trim()
        val description=json.optString("description","").trim()
        val prompt=json.optString("prompt").trim()
        val source=json.optString("source",sourceName).trim().take(200)
        require(name.isNotBlank() && name.length<=40) { "专家名称无效" }
        require(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+([+-][A-Za-z0-9.-]+)?"))) { "专家版本必须使用语义版本格式" }
        require(description.length<=300) { "专家描述不能超过 300 个字符" }
        require(prompt.isNotBlank() && prompt.length<=8_000) { "专家角色指令为空或过长" }
        return ExpertBundle(name,version,description,prompt,source)
    }
    fun exportJson(name:String,prompt:String,description:String=""):String {
        require(name.isNotBlank() && name.length<=40) { "专家名称无效" }
        require(prompt.isNotBlank() && prompt.length<=8_000) { "专家角色指令为空或过长" }
        require(description.length<=300) { "专家描述不能超过 300 个字符" }
        return JSONObject().put("name",name).put("version","1.0.0").put("description",description)
            .put("prompt",prompt).put("source","Nestro 本地专家").toString(2)
    }
}
