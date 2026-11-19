package app.nestro.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A project package contains one portable project and only the durable data/resources
 * visible to that project. Device-wide model settings, API credentials and access grants
 * are deliberately excluded.
 */
internal object ProjectPackageCodec {
    private const val FORMAT = "nestro-project-package"
    private const val VERSION = 1

    fun export(fullBackupRaw: String, projectId: String, readableProjectIds: Set<String>): String {
        val backup = JSONObject(fullBackupRaw)
        require(LocalBackupCompatibility.verifyChecksum(backup)) { "本机数据快照校验失败，无法导出项目包" }
        val project = arrayField(backup, "projects").let { rows ->
            (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                .firstOrNull { it.optString("id") == projectId }
        } ?: error("项目不存在，无法导出项目包")
        val scopes = ProjectResourceScopeStore.decode(stringField(backup, "project_resource_scopes_v1", "{}"))
        val visibleProjects = readableProjectIds + projectId
        fun visible(kind: ProjectResourceKind, id: String) =
            ProjectResourceScopeStore.isVisibleFromProjects(scopes, kind, id, projectId, visibleProjects)

        val conversations = filterArray(arrayField(backup, "conversations_v1")) { it.optString("project_id") == projectId }
        val conversationIds = stringSet(conversations, "id")
        val drafts = JSONObject()
        val sourceDrafts = objectField(backup, "conversation_drafts_v1")
        conversationIds.forEach { id -> if (sourceDrafts.has(id)) drafts.put(id, sourceDrafts.get(id)) }

        val documents = filterArray(arrayField(backup, "knowledge_documents_v1")) { it.optString("projectId") == projectId }
        val documentIds = stringSet(documents, "id")
        val chunks = filterArray(arrayField(backup, "knowledge_chunks_v1")) {
            it.optString("projectId") == projectId && it.optString("documentId") in documentIds
        }
        val memories = filterArray(arrayField(backup, "user_memories")) { it.optString("projectId") == projectId }
        val artifacts = filterArray(arrayField(backup, "artifacts_v1")) { it.optString("projectId") == projectId }

        val allExperts = arrayField(backup, "experts")
        val expertPrompts = objectField(backup, "expert_prompts")
        val expertProfiles = objectField(backup, "expert_profiles_v1")
        val expertVersions = objectField(backup, "expert_versions_v1")
        val selectedExperts = allExperts.toStringList().filter { visible(ProjectResourceKind.EXPERT, it) }.toMutableSet()

        val allTeams = arrayField(backup, "expert_teams_v1")
        val teams = JSONArray()
        for (team in allTeams.objects()) {
            val id = team.optString("id")
            if (!visible(ProjectResourceKind.TEAM, id)) continue
            val members = team.optJSONArray("members") ?: JSONArray()
            val memberNames = members.toStringList()
            if (memberNames.all { it in selectedExperts }) teams.put(JSONObject(team.toString()))
        }

        val allSkills = objectField(backup, "skills")
        val skills = JSONObject()
        val skillKeys = allSkills.keys()
        while (skillKeys.hasNext()) {
            val name = skillKeys.next()
            if (visible(ProjectResourceKind.SKILL, name)) skills.put(name, allSkills.get(name))
        }
        val skillNames = skills.keys().asSequence().toSet()
        val decodedProfiles = ExpertProfileCodec.decode(expertProfiles.toString())
        val validProfileNames = decodedProfiles.filterValues { profile -> profile.skillNames.all { it in skillNames } }.keys
        selectedExperts.retainAll { it !in decodedProfiles || it in validProfileNames }
        val validatedTeams = JSONArray()
        teams.objects().filter { team ->
            (team.optJSONArray("members") ?: JSONArray()).toStringList().all { it in selectedExperts }
        }.forEach { validatedTeams.put(JSONObject(it.toString())) }

        val allWorkflows = arrayField(backup, "workflows_v1")
        val workflows = JSONArray()
        for (workflow in allWorkflows.objects()) {
            if (!visible(ProjectResourceKind.WORKFLOW, workflow.optString("id"))) continue
            val steps = workflow.optJSONArray("steps") ?: JSONArray()
            val dependenciesVisible = steps.objects().all { step ->
                if (step.optBoolean("enabled", true).not()) true
                else {
                    val target = step.optString("targetId").ifBlank { step.optString("title") }
                    when (step.optString("kind").uppercase()) {
                        "EXPERT" -> target in selectedExperts
                        "SKILL" -> target in skillNames
                        else -> true
                    }
                }
            }
            if (dependenciesVisible) workflows.put(JSONObject(workflow.toString()))
        }

        val validatedWorkflows = JSONArray()
        workflows.objects().filter { workflow ->
            (workflow.optJSONArray("steps") ?: JSONArray()).objects().all { step ->
                if (!step.optBoolean("enabled", true)) true
                else {
                    val target = step.optString("targetId").ifBlank { step.optString("title") }
                    when (step.optString("kind").uppercase()) {
                        "EXPERT" -> target in selectedExperts
                        "SKILL" -> target in skillNames
                        else -> true
                    }
                }
            }
        }.forEach { validatedWorkflows.put(JSONObject(it.toString())) }
        val teamIds = stringSet(validatedTeams, "id")
        val workflowIds = stringSet(validatedWorkflows, "id")
        val selectedExpertPrompts = expertPrompts.only(selectedExperts)
        val selectedExpertProfiles = JSONObject(ExpertProfileCodec.encode(decodedProfiles.filterKeys { it in selectedExperts }))
        val decodedRevisions = ExpertRevisionCodec.decode(expertVersions.toString())
        val safeRevisions = decodedRevisions.filterKeys { it in selectedExperts }
            .mapValues { (_, rows) -> rows.filter { revision -> revision.profile.skillNames.all { it in skillNames } } }
            .filterValues { it.isNotEmpty() }
        val selectedExpertVersions = JSONObject(ExpertRevisionCodec.encode(safeRevisions))
        val skillMetadata = objectField(backup, "skill_metadata_v1").only(skillNames)
        // Skill enable/disable is device-global today, so it is intentionally not copied in a project package.
        val disabledSkills = JSONArray()

        val exportedScopes = linkedMapOf<String, Set<String>>()
        fun exportScope(kind: ProjectResourceKind, id: String) {
            val original = scopes[ProjectResourceScopeStore.resourceKey(kind, id)]
            exportedScopes[ProjectResourceScopeStore.resourceKey(kind, id)] =
                if (original == null || "*" in original) setOf("*") else setOf(projectId)
        }
        selectedExperts.forEach { exportScope(ProjectResourceKind.EXPERT, it) }
        teamIds.forEach { exportScope(ProjectResourceKind.TEAM, it) }
        skillNames.forEach { exportScope(ProjectResourceKind.SKILL, it) }
        workflowIds.forEach { exportScope(ProjectResourceKind.WORKFLOW, it) }

        val settings = ProjectMemorySettingsStore.decode(stringField(backup, "project_memory_settings_v1", "{}"))
        val resources = JSONObject()
            .put("experts", JSONArray(selectedExperts.sorted()))
            .put("expert_prompts", selectedExpertPrompts)
            .put("expert_profiles_v1", selectedExpertProfiles)
            .put("expert_versions_v1", selectedExpertVersions)
            .put("expert_teams_v1", validatedTeams)
            .put("skills", skills)
            .put("skill_metadata_v1", skillMetadata)
            .put("disabled_skills_v1", disabledSkills)
            .put("workflows_v1", validatedWorkflows)
            .put("project_resource_scopes_v1", JSONObject(ProjectResourceScopeStore.encode(exportedScopes)))

        val result = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("created_at", System.currentTimeMillis())
            .put("project", JSONObject(project.toString()))
            .put("conversations_v1", conversations)
            .put("conversation_drafts_v1", drafts)
            .put("knowledge_documents_v1", documents)
            .put("knowledge_chunks_v1", chunks)
            .put("user_memories", memories)
            .put("artifacts_v1", artifacts)
            .put("project_memory_enabled", settings[projectId] == true)
            .put("resources", resources)
        seal(result)
        val encoded = result.toString(2)
        require(encoded.toByteArray(Charsets.UTF_8).size <= 10_000_000) { "项目包超过 10 MB，请先清理项目数据" }
        return encoded
    }

    fun mergeIntoFullBackup(currentBackupRaw: String, packageRaw: String): String {
        require(packageRaw.toByteArray(Charsets.UTF_8).size <= 10_000_000) { "项目包超过 10 MB" }
        val current = JSONObject(currentBackupRaw)
        require(LocalBackupCompatibility.verifyChecksum(current)) { "当前本机备份校验失败，已停止导入" }
        val incoming = JSONObject(packageRaw)
        require(incoming.optString("format") == FORMAT && incoming.optInt("version") == VERSION) { "项目包格式或版本不受支持" }
        require(LocalBackupCompatibility.verifyChecksum(incoming)) { "项目包校验失败，文件可能已损坏或被修改" }

        val sourceProject = incoming.getJSONObject("project")
        val projects = arrayField(current, "projects")
        require(projects.length() < 100) { "项目空间数量已达到 100 个上限" }
        val usedNames = (0 until projects.length()).mapNotNull { projects.optJSONObject(it)?.optString("name") }.toMutableSet()
        val projectName = uniqueName(sourceProject.optString("name").ifBlank { "导入项目" }, usedNames, 40)
        val newProjectId = UUID.randomUUID().toString()
        val newProject = JSONObject()
            .put("id", newProjectId)
            .put("name", projectName)
            .put("instructions", sourceProject.optString("instructions").take(4000))
            .put("created_at", System.currentTimeMillis())
            .put("archived_at", 0L)
        projects.put(newProject)
        current.put("projects", projects.toString())

        val conversations = arrayField(current, "conversations_v1")
        val conversationIds = mutableMapOf<String, String>()
        val messageIds = mutableMapOf<String, String>()
        val incomingConversations = arrayField(incoming, "conversations_v1")
        require(conversations.length() + maxOf(1, incomingConversations.length()) <= 200) { "导入后会话总数超过 200 个上限" }
        for (row in incomingConversations.objects()) {
            val oldId = row.optString("id")
            require(oldId.isNotBlank()) { "项目包中的会话 ID 无效" }
            val newId = UUID.randomUUID().toString()
            conversationIds[oldId] = newId
            val messages = row.optJSONArray("messages") ?: JSONArray()
            val copiedMessages = JSONArray()
            for (message in messages.objects()) {
                val oldMessageId = message.optString("id")
                require(oldMessageId.isNotBlank()) { "项目包中的消息 ID 无效" }
                val newMessageId = UUID.randomUUID().toString()
                messageIds[oldMessageId] = newMessageId
                copiedMessages.put(JSONObject(message.toString()).put("id", newMessageId))
            }
            conversations.put(JSONObject(row.toString())
                .put("id", newId)
                .put("project_id", newProjectId)
                .put("messages", copiedMessages))
        }
        val importedActiveConversationId = conversationIds.values.firstOrNull() ?: UUID.randomUUID().toString().also { id ->
            conversations.put(JSONObject().put("id", id).put("title", "新对话")
                .put("updated_at", System.currentTimeMillis()).put("project_id", newProjectId)
                .put("messages", JSONArray()))
        }
        current.put("conversations_v1", conversations)

        val drafts = objectField(current, "conversation_drafts_v1")
        val incomingDrafts = objectField(incoming, "conversation_drafts_v1")
        val draftKeys = incomingDrafts.keys()
        while (draftKeys.hasNext()) {
            val oldId = draftKeys.next()
            conversationIds[oldId]?.let { drafts.put(it, incomingDrafts.get(oldId)) }
        }
        current.put("conversation_drafts_v1", drafts)

        val documents = arrayField(current, "knowledge_documents_v1")
        val existingHashes = (0 until documents.length()).mapNotNull { documents.optJSONObject(it)?.optString("contentHash") }.toMutableSet()
        val documentIds = mutableMapOf<String, String>()
        for (doc in arrayField(incoming, "knowledge_documents_v1").objects()) {
            val hash = doc.optString("contentHash")
            if (hash.isBlank() || hash in existingHashes) continue
            val oldId = doc.optString("id")
            val newId = UUID.randomUUID().toString()
            documentIds[oldId] = newId
            existingHashes += hash
            documents.put(JSONObject(doc.toString()).put("id", newId).put("projectId", newProjectId))
        }
        current.put("knowledge_documents_v1", documents)

        val chunks = arrayField(current, "knowledge_chunks_v1")
        for (chunk in arrayField(incoming, "knowledge_chunks_v1").objects()) {
            val newDocumentId = documentIds[chunk.optString("documentId")] ?: continue
            chunks.put(JSONObject(chunk.toString())
                .put("id", UUID.randomUUID().toString())
                .put("documentId", newDocumentId)
                .put("projectId", newProjectId))
        }
        current.put("knowledge_chunks_v1", chunks)

        val memories = arrayField(current, "user_memories")
        for (memory in arrayField(incoming, "user_memories").objects()) {
            val oldConversation = memory.optString("sourceConversationId")
            val oldMessage = memory.optString("sourceMessageId")
            memories.put(JSONObject(memory.toString())
                .put("id", UUID.randomUUID().toString())
                .put("projectId", newProjectId)
                .put("sourceConversationId", conversationIds[oldConversation] ?: "")
                .put("sourceMessageId", messageIds[oldMessage] ?: ""))
        }
        current.put("user_memories", memories)

        val artifacts = arrayField(current, "artifacts_v1")
        for (artifact in arrayField(incoming, "artifacts_v1").objects()) {
            artifacts.put(JSONObject(artifact.toString())
                .put("id", UUID.randomUUID().toString())
                .put("projectId", newProjectId))
        }
        current.put("artifacts_v1", artifacts)

        mergeResources(current, incoming.optJSONObject("resources") ?: JSONObject(), newProjectId)
        val memorySettings = objectField(current, "project_memory_settings_v1")
        memorySettings.put(newProjectId, incoming.optBoolean("project_memory_enabled", false))
        current.put("project_memory_settings_v1", memorySettings.toString())
        current.put("active_project_id", newProjectId)
        current.put("active_conversation_id", importedActiveConversationId)
        seal(current)
        return current.toString(2)
    }

    private fun mergeResources(current: JSONObject, incoming: JSONObject, projectId: String) {
        val currentExperts = arrayField(current, "experts")
        val incomingExperts = arrayField(incoming, "experts")
        val currentNames = currentExperts.toStringList().toMutableSet()
        val currentPrompts = objectField(current, "expert_prompts")
        val currentProfiles = objectField(current, "expert_profiles_v1")
        val currentVersions = objectField(current, "expert_versions_v1")
        val newPrompts = objectField(incoming, "expert_prompts")
        val newProfiles = objectField(incoming, "expert_profiles_v1")
        val newVersions = objectField(incoming, "expert_versions_v1")
        for (name in incomingExperts.toStringList()) {
            if (name in currentNames) {
                require(sameValue(currentPrompts.opt(name), newPrompts.opt(name)) &&
                    sameValue(currentProfiles.opt(name), newProfiles.opt(name))) {
                    "专家「$name」与本机已有配置冲突，请先在项目包来源端重命名"
                }
            } else {
                currentExperts.put(name)
                currentNames += name
            }
        }
        current.put("experts", currentExperts.toString())
        current.put("expert_prompts", mergeObjects(currentPrompts, newPrompts, "专家指令").toString())
        current.put("expert_profiles_v1", mergeObjects(currentProfiles, newProfiles, "专家配置").toString())
        current.put("expert_versions_v1", mergeObjects(currentVersions, newVersions, "专家版本历史").toString())

        val currentTeams = arrayField(current, "expert_teams_v1")
        val teamNames = currentTeams.objects().map { it.optString("name") }.toMutableSet()
        val teamIdMap = mutableMapOf<String, String>()
        val incomingTeams = arrayField(incoming, "expert_teams_v1")
        for (team in incomingTeams.objects()) {
            val oldId = team.optString("id")
            val newId = UUID.randomUUID().toString()
            teamIdMap[oldId] = newId
            val name = uniqueName(team.optString("name").ifBlank { "导入团队" }, teamNames, 40)
            teamNames += name
            currentTeams.put(JSONObject(team.toString()).put("id", newId).put("name", name))
        }
        current.put("expert_teams_v1", currentTeams.toString())

        val currentSkills = objectField(current, "skills")
        val newSkills = objectField(incoming, "skills")
        val globalTemplateSkillRefs = ExpertTemplateCodec.decode(stringField(current, "expert_templates_v1", "[]"))
            .flatMap { it.profile.skillNames }.toSet()
        val incomingResourceScopes = objectField(incoming, "project_resource_scopes_v1")
        val templateCollisions = newSkills.keys().asSequence()
            .filter { name ->
                if (currentSkills.has(name) || name !in globalTemplateSkillRefs) false
                else "*" !in (incomingResourceScopes.optJSONArray(ProjectResourceScopeStore.resourceKey(ProjectResourceKind.SKILL, name))?.toStringList().orEmpty())
            }.toList()
        require(templateCollisions.isEmpty()) {
            "项目包技能名称与本机全局专家模板引用冲突：" + templateCollisions.joinToString("、")
        }
        current.put("skills", mergeObjects(currentSkills, newSkills, "技能").toString())
        current.put("skill_metadata_v1", mergeObjects(objectField(current, "skill_metadata_v1"), objectField(incoming, "skill_metadata_v1"), "技能元数据").toString())
        val disabled = arrayField(current, "disabled_skills_v1")
        val disabledSet = disabled.toStringList().toMutableSet()
        arrayField(incoming, "disabled_skills_v1").toStringList().forEach { if (disabledSet.add(it)) disabled.put(it) }
        current.put("disabled_skills_v1", disabled.toString())

        val currentWorkflows = arrayField(current, "workflows_v1")
        val workflowNames = currentWorkflows.objects().map { it.optString("name") }.toMutableSet()
        val workflowIdMap = mutableMapOf<String, String>()
        for (workflow in arrayField(incoming, "workflows_v1").objects()) {
            val oldId = workflow.optString("id")
            val newId = UUID.randomUUID().toString()
            workflowIdMap[oldId] = newId
            val name = uniqueName(workflow.optString("name").ifBlank { "导入工作流" }, workflowNames, 60)
            workflowNames += name
            currentWorkflows.put(JSONObject(workflow.toString()).put("id", newId).put("name", name))
        }
        current.put("workflows_v1", currentWorkflows.toString())

        val scopes = ProjectResourceScopeStore.decode(stringField(current, "project_resource_scopes_v1", "{}")).toMutableMap()
        val importedScopes = incomingResourceScopes
        val keys = importedScopes.keys()
        while (keys.hasNext()) {
            val oldKey = keys.next()
            val kind = oldKey.substringBefore(':')
            val oldId = oldKey.substringAfter(':')
            val newId = when (kind) {
                "team" -> teamIdMap[oldId] ?: oldId
                "workflow" -> workflowIdMap[oldId] ?: oldId
                else -> oldId
            }
            val values = importedScopes.optJSONArray(oldKey) ?: JSONArray()
            val importedOwners = if (values.toStringList().contains("*")) setOf("*") else setOf(projectId)
            val key = "$kind:$newId"
            val existing = scopes[key]
            scopes[key] = when {
                existing == null -> importedOwners
                "*" in existing || "*" in importedOwners -> setOf("*")
                else -> existing + projectId
            }
        }
        current.put("project_resource_scopes_v1", ProjectResourceScopeStore.encode(scopes))
        current.put("selected_team_id", teamIdMap.values.firstOrNull() ?: "")
        current.put("selected_expert", "通用助手")
    }

    private fun mergeObjects(current: JSONObject, incoming: JSONObject, label: String): JSONObject {
        val result = JSONObject(current.toString())
        val keys = incoming.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (result.has(key)) require(sameValue(result.opt(key), incoming.opt(key))) { "$label「$key」与本机已有内容冲突，请先重命名或处理重复项" }
            else result.put(key, incoming.get(key))
        }
        return result
    }

    private fun sameValue(left: Any?, right: Any?): Boolean = when {
        left == null || right == null -> left == right
        else -> left.toString() == right.toString()
    }

    private fun uniqueName(raw: String, used: Set<String>, maxLength: Int): String {
        val base = raw.trim().ifBlank { "导入项目" }.take(maxLength)
        fun exists(value: String) = used.any { it.trim().equals(value.trim(), ignoreCase = true) }
        if (!exists(base)) return base
        val suffix = "（导入）"
        var candidate = base.take(maxLength - suffix.length) + suffix
        var index = 2
        while (exists(candidate)) {
            val numbered = "（导入$index）"
            candidate = base.take(maxLength - numbered.length) + numbered
            index++
        }
        return candidate
    }

    private fun arrayField(root: JSONObject, key: String): JSONArray = when (val value = root.opt(key)) {
        null -> JSONArray()
        is JSONArray -> JSONArray(value.toString())
        is String -> JSONArray(value)
        else -> error("项目包字段 $key 格式无效")
    }

    private fun objectField(root: JSONObject, key: String): JSONObject = when (val value = root.opt(key)) {
        null -> JSONObject()
        is JSONObject -> JSONObject(value.toString())
        is String -> JSONObject(value)
        else -> error("项目包字段 $key 格式无效")
    }

    private fun stringField(root: JSONObject, key: String, default: String): String =
        if (!root.has(key)) default else root.opt(key) as? String ?: error("项目包字段 $key 必须是文本")

    private fun filterArray(rows: JSONArray, predicate: (JSONObject) -> Boolean): JSONArray =
        JSONArray().apply { rows.objects().filter(predicate).forEach { put(JSONObject(it.toString())) } }

    private fun stringSet(rows: JSONArray, key: String): Set<String> =
        rows.objects().map { it.optString(key) }.filter { it.isNotBlank() }.toSet()

    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { index -> opt(index) as? String }

    private fun JSONArray.filterStrings(predicate: (String) -> Boolean): JSONArray =
        JSONArray().apply { toStringList().filter(predicate).forEach { put(it) } }

    private fun JSONObject.only(keys: Set<String>): JSONObject {
        val result = JSONObject()
        keys.forEach { key -> if (has(key)) result.put(key, get(key)) }
        return result
    }

    private fun seal(root: JSONObject) {
        root.remove("checksum_sha256")
        root.put("checksum_sha256", LocalBackupCompatibility.sha256(LocalBackupCompatibility.canonicalJson(root)))
    }
}
