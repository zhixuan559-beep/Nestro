package app.nestro.data

import org.json.JSONArray
import org.json.JSONObject

data class ExpertTeamMemberBundle(val name:String,val prompt:String)
data class ExpertTeamBundle(val name:String,val description:String,val members:List<ExpertTeamMemberBundle>)

object ExpertTeamBundleParser {
    fun parse(raw:String):ExpertTeamBundle {
        require(raw.isNotBlank() && raw.length<=40_000) { "团队文件为空或超过 40 KB" }
        val json=JSONObject(raw)
        require(json.optString("format")=="nestro-expert-team") { "不是 Nestro 专家团队文件" }
        val name=json.optString("name").trim()
        val description=json.optString("description").trim()
        val array=json.optJSONArray("members") ?: JSONArray()
        require(name.isNotBlank() && name.length<=40) { "团队名称无效" }
        require(description.isNotBlank() && description.length<=300) { "团队分工不能为空或过长" }
        require(array.length() in 2..4) { "团队必须包含 2 到 4 名专家" }
        val members=(0 until array.length()).map { index ->
            val item=array.optJSONObject(index) ?: error("团队成员格式无效")
            val memberName=item.optString("name").trim()
            val prompt=item.optString("prompt").trim()
            require(memberName.isNotBlank() && memberName.length<=40) { "团队成员名称无效" }
            require(prompt.isNotBlank() && prompt.length<=8_000) { "团队成员角色指令为空或过长" }
            ExpertTeamMemberBundle(memberName,prompt)
        }
        require(members.map { it.name }.distinct().size==members.size) { "团队成员不能重名" }
        return ExpertTeamBundle(name,description,members)
    }
    fun exportJson(name:String,description:String,members:List<ExpertTeamMemberBundle>):String {
        require(name.isNotBlank() && name.length<=40) { "团队名称无效" }
        require(description.isNotBlank() && description.length<=300) { "团队分工不能为空或过长" }
        require(members.size in 2..4 && members.map { it.name }.distinct().size==members.size) { "团队必须包含 2 到 4 名不同专家" }
        members.forEach {
            require(it.name.isNotBlank() && it.name.length<=40) { "团队成员名称无效" }
            require(it.prompt.isNotBlank() && it.prompt.length<=8_000) { "团队成员角色指令为空或过长" }
        }
        return JSONObject().put("format","nestro-expert-team").put("version","1.0.0")
            .put("name",name).put("description",description)
            .put("members",JSONArray().apply { members.forEach { put(JSONObject().put("name",it.name).put("prompt",it.prompt)) } })
            .toString(2)
    }
}
