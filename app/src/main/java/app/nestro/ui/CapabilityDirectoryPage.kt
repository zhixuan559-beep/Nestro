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
import app.nestro.data.CapabilityRegistryStore
import app.nestro.data.CapabilityDescriptor
import app.nestro.data.CapabilityAuditRecord
import app.nestro.data.CapabilityToolAuditRecord
import app.nestro.NestroApplication
import app.nestro.data.SkillBundleParser
import app.nestro.data.SkillCommunityClient
import app.nestro.data.SignedSkillRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private data class CapabilityEntry(val name:String,val description:String,val source:String,val permission:String,val state:String)

@Composable
fun CapabilityDirectoryPage(projectId: String) {
    val context=LocalContext.current
    val prefs=remember { context.getSharedPreferences("nestro_local",Context.MODE_PRIVATE) }
    val scope=rememberCoroutineScope()
    val lifecycle=remember(prefs) { app.nestro.data.SkillCommunityLifecycleStore(prefs) }
    var query by remember { mutableStateOf("") }
    var communityQuery by remember { mutableStateOf("") }
    var catalogUrl by remember { mutableStateOf(prefs.getString("skill_catalog_url_v1","") ?: "") }
    var trustedKey by remember { mutableStateOf(prefs.getString("skill_catalog_key_v1","") ?: "") }
    var pinnedTrustedKey by remember { mutableStateOf(prefs.getString("skill_catalog_key_v1","") ?: "") }
    var showKeyRotationConfirm by remember { mutableStateOf(false) }
    var pendingRotationUrl by remember { mutableStateOf("") }
    var pendingRotationKey by remember { mutableStateOf("") }
    var releases by remember { mutableStateOf(emptyList<SignedSkillRelease>()) }
    var status by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var pendingBundle by remember { mutableStateOf<SkillBundle?>(null) }
    var pendingPlan by remember { mutableStateOf<app.nestro.data.SkillDependencyResolver.InstallPlan?>(null) }
    var authorityKey by remember { mutableStateOf(prefs.getString("skill_trust_authority_key_v1", "") ?: "") }
    var pendingAuthorityKey by remember { mutableStateOf("") }
    var showAuthorityPinConfirm by remember { mutableStateOf(false) }
    var revocationUrl by remember { mutableStateOf(prefs.getString("skill_trust_revocation_url_v1", "") ?: "") }
    val trustStore=remember(prefs) { app.nestro.data.SkillTrustStore(prefs) }
    var installStatus by remember { mutableStateOf("") }
    val rawSkills=prefs.getString("skills","{}") ?: "{}"
    val rawMetadata=prefs.getString("skill_metadata_v1","{}") ?: "{}"
    val rawDisabled=prefs.getString("disabled_skills_v1","[]") ?: "[]"
    val entries=remember(rawSkills,rawMetadata,rawDisabled) {
        val builtIns=listOf(
            CapabilityEntry("模型连接","连接用户自行配置的 OpenAI 兼容模型服务。","Nestro 内置","网络访问；API Key 由 Android Keystore 保护","已内置"),
            CapabilityEntry("语音输入","调用 Android 系统语音识别。","Android 系统服务","麦克风运行时权限","已内置"),
            CapabilityEntry("语音输出","Android 本机 TTS，并支持用户主动配置远程语音合成服务。","Android TTS / 用户配置服务","本机 TTS 不外发；远程服务需用户配置并主动调用","已接入"),
            CapabilityEntry("本地知识库","导入文档并进行项目内检索。","Nestro 本地模块","用户主动选择文件","已内置"),
            CapabilityEntry("专家与团队","使用本地专家配置和并行分析。","Nestro 本地模块","按模型配置发送请求","已内置"),
            CapabilityEntry("Skills","管理本地技能包；只解析提示词和元数据。","本地技能包","导入前预览；声明不自动授权","已内置"),
            CapabilityEntry("工作流","按顺序执行提示词、专家和技能节点。","Nestro 本地模块","按节点实际调用能力","已内置"),
            CapabilityEntry("Phone Agent","用户授权下读取可见界面并执行受限动作。","Android Accessibility","无障碍授权；高风险动作交还用户","已内置"),
            CapabilityEntry("产物库","按项目保存 Markdown 成果、版本、分享和导出。","Nestro 本地存储","应用私有目录","已内置"),
            CapabilityEntry("MCP 工具","支持远程 HTTPS Streamable HTTP 服务发现、逐工具授权、调用预览、审计与撤销。","第13模块 MCP 工具中心","逐工具授权；每次调用需确认","已接入")
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
    var capabilityRefresh by remember { mutableIntStateOf(0) }
    var registeredCapabilities by remember { mutableStateOf(emptyList<CapabilityDescriptor>()) }
    var capabilityAudit by remember { mutableStateOf(emptyList<CapabilityAuditRecord>()) }
    var toolAudit by remember { mutableStateOf(emptyList<CapabilityToolAuditRecord>()) }
    var mcpTools by remember { mutableStateOf(emptyList<app.nestro.mcp.McpToolDescriptor>()) }
    var showCapabilityAudit by remember { mutableStateOf(false) }
    val appRuntime = remember(context) { context.applicationContext as? NestroApplication }
    LaunchedEffect(projectId, capabilityRefresh) {
        appRuntime?.agentToolCatalog
        registeredCapabilities = CapabilityRegistryStore.list(prefs)
        capabilityAudit = CapabilityRegistryStore.recentAudit(prefs, projectId, 50)
        toolAudit = CapabilityRegistryStore.recentToolAudit(prefs, projectId, 50)
        mcpTools = appRuntime?.mcpToolCenterRepository?.listTools().orEmpty()
    }
    fun refreshSignedCatalog(url: String, key: String) {
        loading=true
        scope.launch {
            val result=withContext(Dispatchers.IO) { runCatching { require(!trustStore.isSignerRevoked(key)) { "当前目录签名公钥已被独立信任根撤销" }; SkillCommunityClient.fetchCatalog(url,key) } }
            result.fold({ verified ->
                val fingerprint=runCatching { SkillCommunityClient.fingerprint(key) }.getOrDefault("不可用")
                val saved=prefs.edit().putString("skill_catalog_url_v1",url).putString("skill_catalog_key_v1",key).commit()
                releases=verified
                if(saved) {
                    pinnedTrustedKey=key
                    status="签名验证通过，共 "+verified.size+" 个版本。受信任公钥 SHA-256 指纹："+fingerprint
                } else {
                    status="目录签名验证通过，但本机信任配置保存失败；旧公钥仍保留。"
                }
            },{ releases=emptyList(); status="目录拒绝加载："+(it.message ?: "验证失败")+"；原有信任配置未更改。" })
            loading=false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("统一能力授权",style=MaterialTheme.typography.titleLarge)
        Text("项目：$projectId。此处管理已注册工具的项目级授权；关闭后即使模型选中该工具，也会在执行前被拦截。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if (registeredCapabilities.isEmpty()) {
            Text("当前还没有已注册的工具能力；打开聊天或对应模块后会自动登记。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        registeredCapabilities.forEach { capability ->
            val granted = CapabilityRegistryStore.isGranted(prefs, projectId, capability.id)
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text(capability.title,style=MaterialTheme.typography.titleSmall)
                        Text(capability.category+" · "+capability.source,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(capability.description,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked=granted,
                        enabled=capability.available && capability.projectScoped,
                        onCheckedChange={ enabled ->
                            runCatching { CapabilityRegistryStore.setGranted(prefs,projectId,capability.id,enabled) }
                                .onSuccess { capabilityRefresh++ }
                                .onFailure { status="能力授权更新失败："+(it.message ?: "未知错误") }
                        }
                    )
                }
            }
        }
        if (mcpTools.isNotEmpty()) {
            Text("MCP 工具项目授权",style=MaterialTheme.typography.titleMedium)
            Text("这里的开关只控制当前项目；MCP 工具仍必须先在 MCP 工具中心获得全局启用授权。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            mcpTools.forEach { tool ->
                val projectGranted = CapabilityRegistryStore.isToolGranted(
                    prefs,projectId,tool.serverId,tool.name,defaultGranted=tool.granted
                )
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                            Text(tool.name,style=MaterialTheme.typography.titleSmall)
                            Text(tool.serverName+" · "+if(tool.granted) "全局已授权" else "全局未授权",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(tool.description,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked=projectGranted,
                            enabled=tool.granted,
                            onCheckedChange={ enabled ->
                                runCatching { CapabilityRegistryStore.setToolGranted(prefs,projectId,tool.serverId,tool.name,enabled) }
                                    .onSuccess { capabilityRefresh++ }
                                    .onFailure { status="MCP 项目授权更新失败："+(it.message ?: "未知错误") }
                            }
                        )
                    }
                }
            }
        }
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("项目授权与调用审计",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
            TextButton(onClick={showCapabilityAudit=!showCapabilityAudit}) { Text(if(showCapabilityAudit) "收起" else "查看") }
        }
        if (showCapabilityAudit) {
            (capabilityAudit.take(20).map { "${it.capabilityName} · ${it.action} · ${it.outcome} · ${it.createdAt}" } +
                toolAudit.take(20).map { "${it.serviceId}/${it.toolName} · ${it.action} · ${it.outcome} · ${it.createdAt}" })
                .forEach { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            if (capabilityAudit.isEmpty() && toolAudit.isEmpty()) {
                Text("当前项目暂无授权审计记录。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider()
        Text("能力目录",style=MaterialTheme.typography.headlineSmall)
        Text("社区目录必须由固定 RSA 公钥验签；技能包必须匹配签名目录中的 SHA-256。安装前展示权限声明，不会自动授予 Android 权限。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(catalogUrl,{catalogUrl=it},label={Text("社区目录 HTTPS 地址")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        OutlinedTextField(trustedKey,{trustedKey=it},label={Text("受信任 RSA 公钥（Base64 X.509）")},modifier=Modifier.fillMaxWidth(),minLines=2)
        OutlinedTextField(authorityKey,{authorityKey=it},label={Text("独立信任根公钥（Base64 X.509）")},modifier=Modifier.fillMaxWidth(),minLines=2)
        OutlinedButton(enabled=authorityKey.isNotBlank(),onClick={ pendingAuthorityKey=authorityKey.trim(); showAuthorityPinConfirm=true },modifier=Modifier.fillMaxWidth()) { Text("固定独立信任根") }
        OutlinedTextField(revocationUrl,{revocationUrl=it},label={Text("签名密钥撤销清单 HTTPS 地址")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        OutlinedButton(enabled=revocationUrl.startsWith("https://") && trustStore.authorityFingerprint().isNotBlank() && !loading,onClick={ loading=true; scope.launch { val result=withContext(Dispatchers.IO) { runCatching { trustStore.refreshRevocations(revocationUrl.trim()) } }; status=result.fold({ count ->
                val metadata=JSONObject(prefs.getString("skill_metadata_v1","{}") ?: "{}")
                val skills=JSONObject(prefs.getString("skills","{}") ?: "{}")
                val disabled=JSONArray(prefs.getString("disabled_skills_v1","[]") ?: "[]")
                val disabledNames=(0 until disabled.length()).map { disabled.optString(it) }.toMutableSet()
                var disabledCount=0
                metadata.keys().forEach { name ->
                    val signer=runCatching { JSONObject(metadata.optString(name,"{}")).optString("signerFingerprint") }.getOrDefault("")
                    if(skills.has(name) && signer.isNotBlank() && trustStore.isFingerprintRevoked(signer) && disabledNames.add(name)) {
                        disabled.put(name); disabledCount++
                    }
                }
                check(prefs.edit().putString("skill_trust_revocation_url_v1",revocationUrl.trim()).putString("disabled_skills_v1",disabled.toString()).commit())
                "撤销清单已验签并更新，累计撤销 $count 个签名者；自动停用受影响技能 $disabledCount 个。"
            },{ "撤销清单更新失败："+(it.message ?: "验证失败") }); loading=false } },modifier=Modifier.fillMaxWidth()) { Text("验证并更新撤销清单") }
        Button(enabled=!loading && catalogUrl.startsWith("https://") && trustedKey.isNotBlank(),onClick={
            val candidateKey=trustedKey.trim()
            val pinned=pinnedTrustedKey.trim()
            if(pinned.isNotBlank() && candidateKey!=pinned) {
                pendingRotationUrl=catalogUrl.trim()
                pendingRotationKey=candidateKey
                showKeyRotationConfirm=true
            } else refreshSignedCatalog(catalogUrl.trim(),candidateKey)
        },modifier=Modifier.fillMaxWidth()) { Text(if(loading) "验证中…" else "验证并刷新社区目录") }
        if(status.isNotBlank()) Text(status,style=MaterialTheme.typography.bodySmall)
        if(loading) CircularProgressIndicator()
        if(releases.isNotEmpty()) {
            Text("已验签社区技能 · "+releases.size+" 个版本",style=MaterialTheme.typography.titleMedium)
            OutlinedTextField(value=communityQuery,onValueChange={communityQuery=it},label={Text("搜索社区技能名称或说明")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            val visibleReleases = releases.filter { !trustStore.isFingerprintRevoked(it.signerFingerprint) && (communityQuery.isBlank() || it.name.contains(communityQuery,true) || it.description.contains(communityQuery,true)) }
            if (visibleReleases.isEmpty()) Text("没有匹配的社区技能。",style=MaterialTheme.typography.bodySmall)
            visibleReleases.forEach { release ->
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
                        if (item.source.contains("已验证签名社区目录")) {
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick={
                                    val result=runCatching { lifecycle.uninstall(item.name) }
                                    installStatus=result.fold({ "已卸载社区技能："+item.name },{ "卸载失败："+(it.message ?: "未知错误") })
                                }) { Text("卸载") }
                                if (lifecycle.canRollback(item.name)) {
                                    OutlinedButton(onClick={
                                        val result=runCatching { lifecycle.rollback(item.name) }
                                        installStatus=result.fold({ "已回退社区技能："+item.name },{ "回退失败："+(it.message ?: "未知错误") })
                                    }) { Text("回退上一版本") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if(showAuthorityPinConfirm) {
        val authorityFingerprint=runCatching { SkillCommunityClient.fingerprint(pendingAuthorityKey) }.getOrDefault("无效公钥")
        AlertDialog(
            onDismissRequest={showAuthorityPinConfirm=false},
            title={Text("确认固定独立信任根？")},
            text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("SHA-256 指纹："+authorityFingerprint)
                Text("固定后不能通过普通目录刷新替换该信任根。请先从可信的独立渠道核对指纹。")
            }},
            confirmButton={TextButton(onClick={
                val result=runCatching { trustStore.pinAuthorityKey(pendingAuthorityKey) }
                status=result.fold({ "独立信任根已固定：SHA-256 "+trustStore.authorityFingerprint() },{ "信任根固定失败："+(it.message ?: "无效公钥") })
                showAuthorityPinConfirm=false
            }) { Text("确认固定") }},
            dismissButton={TextButton(onClick={showAuthorityPinConfirm=false}) { Text("取消") }}
        )
    }
    if(showKeyRotationConfirm) {
        val oldFingerprint=runCatching { SkillCommunityClient.fingerprint(pinnedTrustedKey) }.getOrDefault("无法计算")
        val newFingerprint=runCatching { SkillCommunityClient.fingerprint(pendingRotationKey) }.getOrDefault("无效公钥")
        AlertDialog(
            onDismissRequest={showKeyRotationConfirm=false},
            title={Text("更换社区信任公钥？")},
            text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("更换公钥意味着你将信任新的社区目录签名者。只有在通过新公钥验签后，才会保存新信任配置。")
                Text("当前 SHA-256：$oldFingerprint",style=MaterialTheme.typography.bodySmall)
                Text("新 SHA-256：$newFingerprint",style=MaterialTheme.typography.bodySmall)
            }},
            confirmButton={TextButton(onClick={
                showKeyRotationConfirm=false
                refreshSignedCatalog(pendingRotationUrl,pendingRotationKey)
            }) { Text("确认更换并验签") }},
            dismissButton={TextButton(onClick={showKeyRotationConfirm=false}) { Text("取消") }}
        )
    }
    pendingPlan?.let { plan ->
        AlertDialog(
            onDismissRequest={pendingPlan=null},
            title={Text("确认安装完整依赖计划？")},
            text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("以下技能将按依赖顺序一次性写入；任何一项校验失败都不会产生部分安装。")
                plan.orderedBundles.forEach { item -> Text("• "+item.name+" v"+item.version+" · 权限："+item.permissions.ifEmpty { listOf("无") }.joinToString("、")) }
                if(plan.alreadySatisfied.isNotEmpty()) Text("已满足依赖："+plan.alreadySatisfied.entries.joinToString("、") { it.key+"@"+it.value })
                Text("新安装技能默认停用；不执行技能包代码，也不会自动授予声明权限。")
            }},
            confirmButton={TextButton(onClick={
                val result=runCatching { require(plan.orderedBundles.none { trustStore.isFingerprintRevoked(it.signerFingerprint) }) { "安装计划中的签名者已被撤销，请重新刷新目录" }; lifecycle.installAll(plan.orderedBundles) }
                result.onSuccess { installStatus="已原子安装 "+plan.orderedBundles.size+" 个社区技能。新安装技能默认停用，旧版本可回退。"; pendingPlan=null }
                    .onFailure { installStatus="安装失败："+(it.message ?: "本地数据提交失败") }
            }) { Text("确认安装全部") }},
            dismissButton={TextButton(onClick={pendingPlan=null}) { Text("取消") }}
        )
    }
    pendingBundle?.let { bundle ->
        AlertDialog(
            onDismissRequest={pendingBundle=null},
            title={Text("安装社区技能？")},
            text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(bundle.name+" · v"+bundle.version)
                Text(bundle.description)
                Text("权限声明："+bundle.permissions.ifEmpty { listOf("无") }.joinToString("、"))
                Text("依赖："+bundle.dependencies.map { it.name+"@"+it.version }.ifEmpty { listOf("无") }.joinToString("、"))
                val existing = runCatching { JSONObject(prefs.getString("skills", "{}") ?: "{}") }.getOrDefault(JSONObject()).has(bundle.name)
                if (existing) Text("本地技能库已存在同名技能。确认后将替换该技能的提示词和元数据；不会自动授予任何系统权限。", color=MaterialTheme.colorScheme.error)
                Text("已验证签名目录和文件哈希。技能内容仅作为提示词保存，不执行包内代码；声明权限不会自动授予系统权限。")
            }},
            confirmButton={TextButton(onClick={
                loading=true
                scope.launch {
                    val installed=runCatching {
                        val meta=JSONObject(prefs.getString("skill_metadata_v1","{}") ?: "{}")
                        meta.keys().associateWith { name -> runCatching { JSONObject(meta.optString(name,"{}")).optString("version") }.getOrDefault("") }
                    }.getOrDefault(emptyMap())
                    val result=withContext(Dispatchers.IO) { runCatching { app.nestro.data.SkillDependencyResolver.resolve(
                        releases.firstOrNull { it.name==bundle.name && it.version==bundle.version && !trustStore.isFingerprintRevoked(it.signerFingerprint) } ?: error("原始签名目录版本已失效或签名者已撤销"),
                        releases.filterNot { trustStore.isFingerprintRevoked(it.signerFingerprint) }, installed, bundle
                    ) } }
                    result.onSuccess { pendingPlan=it; pendingBundle=null }
                        .onFailure { installStatus="依赖解析失败："+(it.message ?: "无法形成完整安装计划") }
                    loading=false
                }
            }) { Text("解析依赖并继续") }},
            dismissButton={TextButton(onClick={pendingBundle=null}) { Text("取消") }}
        )
    }
}
