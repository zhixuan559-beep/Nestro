package app.nestro.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.nestro.data.SkillBundle
import app.nestro.data.SkillBundleParser
import app.nestro.data.SkillCommunityClient
import app.nestro.data.SkillInstallPolicy
import app.nestro.data.SignedSkillRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private data class CapabilityEntry(val name:String,val description:String,val source:String,val permission:String,val state:String)

@Composable
fun CapabilityDirectoryPage() {
    val context=LocalContext.current
    val prefs=remember { context.getSharedPreferences("nestro_local",Context.MODE_PRIVATE) }
    val scope=rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var catalogUrl by remember { mutableStateOf(prefs.getString("skill_catalog_url_v1","") ?: "") }
    var trustedKey by remember { mutableStateOf(prefs.getString("skill_catalog_key_v1","") ?: "") }
    var releases by remember { mutableStateOf(emptyList<SignedSkillRelease>()) }
    var status by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var pendingBundle by remember { mutableStateOf<SkillBundle?>(null) }
    var installStatus by remember { mutableStateOf("") }
    val rawSkills=prefs.getString("skills","{}") ?: "{}"
    val rawMetadata=prefs.getString("skill_metadata_v1","{}") ?: "{}"
    val rawDisabled=prefs.getString("disabled_skills_v1","[]") ?: "[]"
    val entries=remember(rawSkills,rawMetadata,rawDisabled) {
        val builtIns=listOf(
            CapabilityEntry("模型连接","连接用户自行配置的 OpenAI 兼容模型服务。","Nestro 内置","网络访问；API Key 由 Android Keystore 保护","已内置"),
            CapabilityEntry("语音输入","调用 Android 系统语音识别。","Android 系统服务","麦克风运行时权限","已内置"),
            CapabilityEntry("语音输出（预留）","当前版本不内置语音合成；未来可由用户自行配置第三方服务。","扩展接口预留","不申请额外权限","未接入"),
            CapabilityEntry("本地知识库","导入文档并进行项目内检索。","Nestro 本地模块","用户主动选择文件","已内置"),
            CapabilityEntry("专家与团队","使用本地专家配置和并行分析。","Nestro 本地模块","按模型配置发送请求","已内置"),
            CapabilityEntry("Skills","管理本地技能包；只解析提示词和元数据。","本地技能包","导入前预览；声明不自动授权","已内置"),
            CapabilityEntry("工作流","按顺序执行提示词、专家和技能节点。","Nestro 本地模块","按节点实际调用能力","已内置"),
            CapabilityEntry("Phone Agent","用户授权下读取可见界面并执行受限动作。","Android Accessibility","无障碍授权；高风险动作交还用户","已内置"),
            CapabilityEntry("产物库","按项目保存 Markdown 成果、版本、分享和导出。","Nestro 本地存储","应用私有目录","已内置"),
            CapabilityEntry("MCP 工具","当前版本尚未接入 MCP 服务发现与工具调用。","未接入","未授予权限","未接入")
        )
        runCatching {
            val skills=JSONObject(rawSkills); val metadata=JSONObject(rawMetadata); val disabled=JSONArray(rawDisabled)
            val off=(0 until disabled.length()).map { disabled.optString(it) }.toSet()
            val permissionNames=mapOf("network_read" to "网络读取","phone_accessibility" to "手机无障碍","file_read" to "文件读取","file_write" to "文件写入","mcp_call" to "MCP 工具调用")
            builtIns + skills.keys().asSequence().filterNot { name -> builtIns.any { it.name==name } }.map { name ->
                val meta=runCatching { JSONObject(metadata.optString(name,"{}")) }.getOrDefault(JSONObject())
                val perms=meta.optJSONArray("permissions") ?: JSONArray()
                val deps=meta.optJSONArray("dependencies") ?: JSONArray()
                val permissionText=(0 until perms.length()).map { permissionNames[perms.optString(it)] ?: perms.optString(it) }
                val dependencyText=(0 until deps.length()).map { deps.optJSONObject(it)?.let { d -> d.optString("name")+"@"+d.optString("version","*") } ?: "" }
                CapabilityEntry(name,meta.optString("description","本地自定义技能")+" · v"+meta.optString("version","1.0.0"),
                    "包内声明："+meta.optString("source","未声明"),
                    "权限声明（不代表已授权）："+permissionText.ifEmpty { listOf("无") }.joinToString("、")+
                        if(dependencyText.isEmpty()) "" else "；依赖："+dependencyText.joinToString("、"),
                    if(name in off) "已停用" else "已启用")
            }.toList()
        }.getOrDefault(builtIns)
    }
    val filtered=entries.filter { query.isBlank() || listOf(it.name,it.description,it.source,it.permission).any { value -> value.contains(query,true) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("能力目录",style=MaterialTheme.typography.headlineSmall)
        Text("社区目录必须由固定 RSA 公钥验签；技能包必须匹配签名目录中的 SHA-256。安装前展示权限声明，不会自动授予 Android 权限。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(catalogUrl,{catalogUrl=it},label={Text("社区目录 HTTPS 地址")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        OutlinedTextField(trustedKey,{trustedKey=it},label={Text("受信任 RSA 公钥（Base64 X.509）")},modifier=Modifier.fillMaxWidth(),minLines=2)
        Button(enabled=!loading && catalogUrl.startsWith("https://") && trustedKey.isNotBlank(),onClick={
            loading=true
            prefs.edit().putString("skill_catalog_url_v1",catalogUrl.trim()).putString("skill_catalog_key_v1",trustedKey.trim()).apply()
            scope.launch {
                val result=withContext(Dispatchers.IO) { runCatching { SkillCommunityClient.fetchCatalog(catalogUrl.trim(),trustedKey.trim()) } }
                result.fold({ releases=it; status="签名验证通过，共 "+it.size+" 个版本。公钥指纹："+runCatching { SkillCommunityClient.fingerprint(trustedKey.trim()).take(23) }.getOrDefault("不可用") },
                    { releases=emptyList(); status="目录拒绝加载："+(it.message ?: "验证失败") })
                loading=false
            }
        },modifier=Modifier.fillMaxWidth()) { Text(if(loading) "验证中…" else "验证并刷新社区目录") }
        if(status.isNotBlank()) Text(status,style=MaterialTheme.typography.bodySmall)
        if(loading) CircularProgressIndicator()
        if(releases.isNotEmpty()) {
            Text("已验签社区技能",style=MaterialTheme.typography.titleMedium)
            releases.forEach { release ->
                val installedSkills = runCatching { JSONObject(rawSkills) }.getOrDefault(JSONObject())
                val installedMetadata = runCatching { JSONObject(JSONObject(rawMetadata).optString(release.name, "{}")) }.getOrDefault(JSONObject())
                val installedVersion = installedMetadata.optString("version", "")
                val isInstalled = installedSkills.has(release.name)
                val isCommunityInstalled = isInstalled && installedMetadata.optString("source") == "已验证签名社区目录"
                val updateAvailable = isCommunityInstalled && installedVersion.isNotBlank() &&
                    runCatching { SkillCommunityClient.isNewerVersion(release.version, installedVersion) }.getOrDefault(false)
                val sameOrOlder = isCommunityInstalled && !updateAvailable
                Card(modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        Text(release.name+" · v"+release.version,style=MaterialTheme.typography.titleSmall)
                        Text(release.description,style=MaterialTheme.typography.bodySmall)
                        if (isInstalled) {
                            Text(
                                if (updateAvailable) "本机已安装 v$installedVersion，可更新" else "本机已安装 v$installedVersion，不提供降级",
                                style=MaterialTheme.typography.labelSmall,
                                color=MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text("SHA-256："+release.sha256.take(20)+"…",style=MaterialTheme.typography.labelSmall)
                        OutlinedButton(enabled=!loading && !sameOrOlder,onClick={
                            loading=true
                            scope.launch {
                                val result=withContext(Dispatchers.IO) { runCatching { SkillCommunityClient.downloadVerifiedBundle(release) } }
                                result.fold({ pendingBundle=it },{ installStatus="下载拒绝："+(it.message ?: "校验失败") })
                                loading=false
                            }
                        }) { Text(if (updateAvailable) "下载并更新" else if (sameOrOlder) "已安装 / 阻止降级" else "下载并校验") }
                    }
                }
            }
        }
        if(installStatus.isNotBlank()) Text(installStatus,style=MaterialTheme.typography.bodySmall)
        Text("本地能力",style=MaterialTheme.typography.titleMedium)
        OutlinedTextField(value=query,onValueChange={query=it},label={Text("筛选能力")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            filtered.forEach { item ->
                Card(shape=RoundedCornerShape(12.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant),modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Text(item.name,style=MaterialTheme.typography.titleMedium)
                            Text(item.state,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                        }
                        Text(item.description,style=MaterialTheme.typography.bodySmall)
                        Text("来源："+item.source,style=MaterialTheme.typography.labelSmall)
                        Text(item.permission,style=MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
    pendingBundle?.let { bundle ->
        val installedSkillsJson = runCatching { JSONObject(prefs.getString("skills", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val installedMetadataJson = runCatching { JSONObject(prefs.getString("skill_metadata_v1", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val disabledJson = runCatching { JSONArray(prefs.getString("disabled_skills_v1", "[]") ?: "[]") }.getOrDefault(JSONArray())
        val installedSkills = installedSkillsJson.keys().asSequence().associateWith { installedSkillsJson.optString(it) }
        val installedMetadata = installedMetadataJson.keys().asSequence().associateWith { installedMetadataJson.optString(it) }
        val disabled = (0 until disabledJson.length()).map { disabledJson.optString(it) }.toSet()
        val assessment = remember(bundle, installedSkillsJson.toString(), installedMetadataJson.toString(), disabled) {
            SkillInstallPolicy.inspectCommunityRelease(bundle, installedSkills, installedMetadata, disabled)
        }
        AlertDialog(
            onDismissRequest={pendingBundle=null},
            title={Text("安装社区技能？")},
            text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(bundle.name+" · v"+bundle.version)
                Text(bundle.description)
                Text("权限声明："+bundle.permissions.ifEmpty { listOf("无") }.joinToString("、"))
                Text("依赖："+bundle.dependencies.map { it.name+"@"+it.version }.ifEmpty { listOf("无") }.joinToString("、"))
                if (assessment.issues.isNotEmpty()) {
                    Text("当前不能安装：", color=MaterialTheme.colorScheme.error)
                    assessment.issues.forEach { Text("• "+it, color=MaterialTheme.colorScheme.error, style=MaterialTheme.typography.bodySmall) }
                } else {
                    Text("依赖检查通过。"+if(bundle.name in installedSkills) "将升级现有社区技能。" else "将安装为新技能。")
                }
                Text("已验证签名目录和文件哈希。技能内容仅作为提示词保存，不执行包内代码；声明权限不会自动授予系统权限。")
            }},
            confirmButton={TextButton(enabled=assessment.canInstall,onClick={
                val skills=runCatching { JSONObject(prefs.getString("skills","{}") ?: "{}") }.getOrDefault(JSONObject())
                val metadata=runCatching { JSONObject(prefs.getString("skill_metadata_v1","{}") ?: "{}") }.getOrDefault(JSONObject())
                skills.put(bundle.name,bundle.prompt)
                metadata.put(bundle.name,SkillBundleParser.metadataJson(bundle))
                val saved = prefs.edit().putString("skills",skills.toString())
                    .putString("skill_metadata_v1",metadata.toString()).commit()
                installStatus=if(saved) "已安装 "+bundle.name+"。请在本地技能库中手动选择启用。" else "安装失败：本地存储写入失败，原有技能未确认更新。"
                if(saved) pendingBundle=null
            }) { Text("确认安装") }},
            dismissButton={TextButton(onClick={pendingBundle=null}) { Text("取消") }}
        )
    }
}
