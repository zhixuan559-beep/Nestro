package app.nestro.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ProjectPackageCodecTest {
    @Test fun exportFiltersProjectDataAndCarriesOnlyVisibleReusableResources() {
        val source = fixtureBackup(includeForeignProject = true)
        val exported = JSONObject(ProjectPackageCodec.export(source.toString(), "source", emptySet()))

        assertEquals("nestro-project-package", exported.getString("format"))
        assertEquals(1, exported.getJSONArray("conversations_v1").length())
        assertEquals(1, exported.getJSONArray("knowledge_documents_v1").length())
        assertEquals(1, exported.getJSONArray("knowledge_chunks_v1").length())
        assertEquals(1, exported.getJSONArray("user_memories").length())
        assertEquals(1, exported.getJSONArray("artifacts_v1").length())

        val resources = exported.getJSONObject("resources")
        assertEquals(listOf("analyst", "writer"), (0 until resources.getJSONArray("experts").length()).map { resources.getJSONArray("experts").getString(it) }.sorted())
        assertEquals(1, resources.getJSONArray("expert_teams_v1").length())
        assertEquals(1, resources.getJSONArray("workflows_v1").length())
        assertTrue(resources.getJSONObject("skills").has("research"))
        assertTrue(LocalBackupCompatibility.verifyChecksum(exported))
    }

    @Test fun exportPreservesLegacyDefaultProjectAutomaticMemorySetting() {
        val source = fixtureBackup(
            includeForeignProject = false,
            projectId = "default",
            projectName = "默认空间",
            conversationId = "default-conversation",
            includeProjectContent = false
        )
        source.put("project_memory_settings_v1", "{}")
        source.put("automatic_memory_enabled", true)
        source.remove("checksum_sha256")
        seal(source)

        val exported = JSONObject(ProjectPackageCodec.export(source.toString(), "default", emptySet()))
        assertTrue(exported.getBoolean("project_memory_enabled"))
    }

    @Test fun importCreatesIsolatedProjectAndRemapsAllProjectOwnedIds() {
        val source = fixtureBackup(includeForeignProject = false)
        val packageRaw = ProjectPackageCodec.export(source.toString(), "source", emptySet())
        val destination = fixtureBackup(
            includeForeignProject = false,
            projectId = "destination",
            projectName = "现有项目",
            conversationId = "destination-conversation",
            includeProjectContent = false
        )
        clearReusableResources(destination)
        val mergedRaw = try {
            ProjectPackageCodec.mergeIntoFullBackup(destination.toString(), packageRaw)
        } catch (error: IllegalArgumentException) {
            throw AssertionError("Valid project package import was rejected: ${error.message}", error)
        }
        val merged = JSONObject(mergedRaw)
        val importedProjectId = merged.getString("active_project_id")

        assertNotEquals("source", importedProjectId)
        assertEquals(2, JSONArray(merged.getString("projects")).length())
        val conversations = merged.getJSONArray("conversations_v1")
        assertEquals(2, conversations.length())
        assertEquals("destination", conversations.getJSONObject(0).getString("project_id"))
        val importedConversation = (0 until conversations.length()).map { conversations.getJSONObject(it) }
            .single { it.getString("project_id") == importedProjectId }
        assertNotEquals("source-conversation", importedConversation.getString("id"))
        assertNotEquals("source-message", importedConversation.getJSONArray("messages").getJSONObject(0).getString("id"))

        val documents = merged.getJSONArray("knowledge_documents_v1")
        assertEquals(importedProjectId, documents.getJSONObject(0).getString("projectId"))
        assertNotEquals("source-document", documents.getJSONObject(0).getString("id"))
        assertEquals(importedProjectId, merged.getJSONArray("knowledge_chunks_v1").getJSONObject(0).getString("projectId"))
        assertEquals(importedProjectId, merged.getJSONArray("user_memories").getJSONObject(0).getString("projectId"))
        assertEquals(importedConversation.getString("id"), merged.getJSONArray("user_memories").getJSONObject(0).getString("sourceConversationId"))
        assertEquals(importedConversation.getJSONArray("messages").getJSONObject(0).getString("id"), merged.getJSONArray("user_memories").getJSONObject(0).getString("sourceMessageId"))
        assertEquals(importedProjectId, merged.getJSONArray("artifacts_v1").getJSONObject(0).getString("projectId"))

        val importedTeams = JSONArray(merged.getString("expert_teams_v1"))
        val importedTeamId = importedTeams.getJSONObject(0).getString("id")
        val importedWorkflowId = JSONArray(merged.getString("workflows_v1")).getJSONObject(0).getString("id")
        val scopes = ProjectResourceScopeStore.decode(merged.getString("project_resource_scopes_v1"))
        assertTrue(importedProjectId in scopes.getValue(ProjectResourceScopeStore.resourceKey(ProjectResourceKind.EXPERT, "writer")))
        assertTrue(importedProjectId in scopes.getValue(ProjectResourceScopeStore.resourceKey(ProjectResourceKind.TEAM, importedTeamId)))
        assertTrue(importedProjectId in scopes.getValue(ProjectResourceScopeStore.resourceKey(ProjectResourceKind.SKILL, "research")))
        assertTrue(importedProjectId in scopes.getValue(ProjectResourceScopeStore.resourceKey(ProjectResourceKind.WORKFLOW, importedWorkflowId)))
        assertTrue(JSONObject(merged.getString("project_memory_settings_v1")).getBoolean(importedProjectId))
        assertTrue(LocalBackupCompatibility.verifyChecksum(merged))
    }

    @Test fun sharedResourceWriteRequiresPermissionFromEveryForeignOwner() {
        val scopes = ProjectResourceScopeStore.setProjectScope(
            emptyMap(), ProjectResourceKind.EXPERT, "shared", setOf("source-a", "source-b")
        )
        assertFalse(ProjectResourceScopeStore.canWriteFromProject(
            scopes, ProjectResourceKind.EXPERT, "shared", "target", setOf("source-a")
        ))
        assertTrue(ProjectResourceScopeStore.canWriteFromProject(
            scopes, ProjectResourceKind.EXPERT, "shared", "target", setOf("source-a", "source-b")
        ))
        assertTrue(ProjectResourceScopeStore.canWriteFromProject(
            scopes, ProjectResourceKind.EXPERT, "shared", "source-a", setOf("source-b")
        ))
        assertFalse(ProjectResourceScopeStore.canWriteFromProject(
            scopes, ProjectResourceKind.EXPERT, "shared", "target", emptySet()
        ))
    }

    @Test fun exportOmitsExpertAndTeamWhenExpertDependsOnHiddenSkill() {
        val source = fixtureBackup(includeForeignProject = false)
        val scopes = ProjectResourceScopeStore.decode(source.getString("project_resource_scopes_v1")).toMutableMap()
        scopes[ProjectResourceScopeStore.resourceKey(ProjectResourceKind.SKILL, "hidden")] = setOf("foreign")
        source.put("skills", JSONObject().put("research", "研究技能指令").put("hidden", "不可见技能指令").toString())
        source.put("expert_profiles_v1", ExpertProfileCodec.encode(
            mapOf("writer" to ExpertProfile(skillNames = listOf("hidden")))
        ))
        source.put("project_resource_scopes_v1", ProjectResourceScopeStore.encode(scopes))
        source.remove("checksum_sha256")
        seal(source)

        val resources = JSONObject(ProjectPackageCodec.export(source.toString(), "source", emptySet()))
            .getJSONObject("resources")
        assertEquals(1, resources.getJSONArray("experts").length())
        assertEquals("analyst", resources.getJSONArray("experts").getString(0))
        assertEquals(0, resources.getJSONArray("expert_teams_v1").length())
        assertFalse(resources.getJSONObject("skills").has("hidden"))
    }

    @Test fun importRejectsSkillNameThatWouldBindGlobalTemplateToProjectSkill() {
        val source = fixtureBackup(includeForeignProject = false)
        val packageRaw = ProjectPackageCodec.export(source.toString(), "source", emptySet())
        val destination = fixtureBackup(
            includeForeignProject = false,
            projectId = "destination",
            projectName = "现有项目",
            conversationId = "destination-conversation",
            includeProjectContent = false
        )
        destination.put("skills", JSONObject().toString())
        destination.put("expert_templates_v1", ExpertTemplateCodec.encode(listOf(
            ExpertTemplate(
                name = "全局模板",
                description = "模板描述",
                prompt = "模板指令",
                profile = ExpertProfile(skillNames = listOf("research"))
            )
        )))
        destination.remove("checksum_sha256")
        seal(destination)
        assertThrows(IllegalArgumentException::class.java) {
            ProjectPackageCodec.mergeIntoFullBackup(destination.toString(), packageRaw)
        }
    }

    @Test fun importRejectsNameCollisionWithProjectOwnedExpertInsteadOfSharingIt() {
        val source = fixtureBackup(includeForeignProject = false)
        val packageRaw = ProjectPackageCodec.export(source.toString(), "source", emptySet())
        val destination = fixtureBackup(
            includeForeignProject = false,
            projectId = "destination",
            projectName = "现有项目",
            conversationId = "destination-conversation",
            includeProjectContent = false
        )
        val scopes = ProjectResourceScopeStore.decode(destination.getString("project_resource_scopes_v1")).toMutableMap()
        scopes[ProjectResourceScopeStore.resourceKey(ProjectResourceKind.EXPERT, "writer")] = setOf("destination")
        destination.put("project_resource_scopes_v1", ProjectResourceScopeStore.encode(scopes))
        destination.remove("checksum_sha256")
        seal(destination)
        assertThrows(IllegalArgumentException::class.java) {
            ProjectPackageCodec.mergeIntoFullBackup(destination.toString(), packageRaw)
        }
    }

    @Test fun importRejectsPrivateExpertCollisionWithAnExistingGlobalExpert() {
        val source = fixtureBackup(includeForeignProject = false)
        val packageRaw = ProjectPackageCodec.export(source.toString(), "source", emptySet())
        val destination = fixtureBackup(
            includeForeignProject = false,
            projectId = "destination",
            projectName = "现有项目",
            conversationId = "destination-conversation",
            includeProjectContent = false
        )
        assertThrows(IllegalArgumentException::class.java) {
            ProjectPackageCodec.mergeIntoFullBackup(destination.toString(), packageRaw)
        }
    }

    @Test fun importRejectsPrivateSkillCollisionWithAnExistingGlobalSkill() {
        val source = fixtureBackup(includeForeignProject = false)
        val packageRaw = ProjectPackageCodec.export(source.toString(), "source", emptySet())
        val destination = fixtureBackup(
            includeForeignProject = false,
            projectId = "destination",
            projectName = "现有项目",
            conversationId = "destination-conversation",
            includeProjectContent = false
        )
        destination.put("experts", JSONArray().put("unrelated-expert").toString())
        assertThrows(IllegalArgumentException::class.java) {
            ProjectPackageCodec.mergeIntoFullBackup(destination.toString(), packageRaw)
        }
    }

    @Test fun importRejectsResourceScopeReferencesOutsideTheSourceProject() {
        val source = fixtureBackup(includeForeignProject = false)
        val packageRoot = JSONObject(ProjectPackageCodec.export(source.toString(), "source", emptySet()))
        val resources = packageRoot.getJSONObject("resources")
        val scopes = ProjectResourceScopeStore.decode(
            resources.getJSONObject("project_resource_scopes_v1").toString()
        ).toMutableMap()
        scopes[ProjectResourceScopeStore.resourceKey(ProjectResourceKind.SKILL, "research")] = setOf("unrelated-project")
        resources.put("project_resource_scopes_v1", JSONObject(ProjectResourceScopeStore.encode(scopes)))
        packageRoot.remove("checksum_sha256")
        seal(packageRoot)
        assertThrows(IllegalArgumentException::class.java) {
            ProjectPackageCodec.mergeIntoFullBackup(source.toString(), packageRoot.toString())
        }
    }

    @Test fun importRejectsNameCollisionWithProjectOwnedSkillInsteadOfSharingIt() {
        val source = fixtureBackup(includeForeignProject = false)
        val packageRaw = ProjectPackageCodec.export(source.toString(), "source", emptySet())
        val destination = fixtureBackup(
            includeForeignProject = false,
            projectId = "destination",
            projectName = "现有项目",
            conversationId = "destination-conversation",
            includeProjectContent = false
        )
        val scopes = ProjectResourceScopeStore.decode(destination.getString("project_resource_scopes_v1")).toMutableMap()
        scopes[ProjectResourceScopeStore.resourceKey(ProjectResourceKind.SKILL, "research")] = setOf("destination")
        destination.put("project_resource_scopes_v1", ProjectResourceScopeStore.encode(scopes))
        destination.remove("checksum_sha256")
        seal(destination)
        assertThrows(IllegalArgumentException::class.java) {
            ProjectPackageCodec.mergeIntoFullBackup(destination.toString(), packageRaw)
        }
    }

    @Test fun exportRejectsMalformedResourceArraysInsteadOfSilentlyDroppingEntries() {
        val source = fixtureBackup(includeForeignProject = false)
        source.put("experts", JSONArray().put("writer").put(123).toString())
        source.remove("checksum_sha256")
        seal(source)
        assertThrows(IllegalStateException::class.java) {
            ProjectPackageCodec.export(source.toString(), "source", emptySet())
        }
    }

    @Test fun importingKnowledgeWithSameHashAsAnotherProjectKeepsItInTheNewProject() {
        val source = fixtureBackup(includeForeignProject = false)
        val packageRaw = ProjectPackageCodec.export(source.toString(), "source", emptySet())
        val destination = fixtureBackup(
            includeForeignProject = true,
            projectId = "destination",
            projectName = "现有项目",
            conversationId = "destination-conversation",
            includeProjectContent = false
        )
        destination.getJSONArray("knowledge_documents_v1").put(
            JSONObject().put("id", "foreign-document").put("projectId", "foreign")
                .put("name", "相同内容资料.txt").put("mimeType", "text/plain")
                .put("sizeBytes", 8L).put("importedAt", 1L).put("contentHash", "a".repeat(64))
        )
        clearReusableResources(destination)
        destination.remove("checksum_sha256")
        seal(destination)

        val merged = JSONObject(ProjectPackageCodec.mergeIntoFullBackup(destination.toString(), packageRaw))
        val importedProjectId = merged.getString("active_project_id")
        val matchingDocuments = merged.getJSONArray("knowledge_documents_v1")
        assertTrue((0 until matchingDocuments.length()).map { matchingDocuments.getJSONObject(it) }
            .any { it.getString("projectId") == importedProjectId && it.getString("contentHash") == "a".repeat(64) })
        assertTrue((0 until matchingDocuments.length()).map { matchingDocuments.getJSONObject(it) }
            .any { it.getString("projectId") == "foreign" && it.getString("contentHash") == "a".repeat(64) })
    }

    @Test fun importRejectsDuplicateTeamAndWorkflowIdsBeforeScopeRemapping() {
        val source = fixtureBackup(includeForeignProject = false)
        val packageRaw = ProjectPackageCodec.export(source.toString(), "source", emptySet())

        val duplicateTeam = JSONObject(packageRaw)
        val teams = duplicateTeam.getJSONObject("resources").getJSONArray("expert_teams_v1")
        teams.put(JSONObject(teams.getJSONObject(0).toString()).put("name", "另一个团队"))
        duplicateTeam.remove("checksum_sha256")
        seal(duplicateTeam)
        assertThrows(IllegalArgumentException::class.java) {
            ProjectPackageCodec.mergeIntoFullBackup(source.toString(), duplicateTeam.toString())
        }

        val duplicateWorkflow = JSONObject(packageRaw)
        val workflows = duplicateWorkflow.getJSONObject("resources").getJSONArray("workflows_v1")
        workflows.put(JSONObject(workflows.getJSONObject(0).toString()).put("name", "另一个工作流"))
        duplicateWorkflow.remove("checksum_sha256")
        seal(duplicateWorkflow)
        assertThrows(IllegalArgumentException::class.java) {
            ProjectPackageCodec.mergeIntoFullBackup(source.toString(), duplicateWorkflow.toString())
        }
    }

    @Test fun importRejectsMalformedProjectIdentityAndNestedMessageArrays() {
        val source = fixtureBackup(includeForeignProject = false)
        val packageRaw = ProjectPackageCodec.export(source.toString(), "source", emptySet())

        val invalidIdentity = JSONObject(packageRaw)
        invalidIdentity.getJSONObject("project").put("id", 123)
        invalidIdentity.remove("checksum_sha256")
        seal(invalidIdentity)
        assertThrows(IllegalStateException::class.java) {
            ProjectPackageCodec.mergeIntoFullBackup(source.toString(), invalidIdentity.toString())
        }

        val invalidMessages = JSONObject(packageRaw)
        invalidMessages.getJSONArray("conversations_v1").getJSONObject(0).put("messages", "not-an-array")
        invalidMessages.remove("checksum_sha256")
        seal(invalidMessages)
        assertThrows(IllegalStateException::class.java) {
            ProjectPackageCodec.mergeIntoFullBackup(source.toString(), invalidMessages.toString())
        }
    }

    @Test fun modifiedProjectPackageIsRejectedBeforeMerge() {
        val source = fixtureBackup(includeForeignProject = false)
        val packageRoot = JSONObject(ProjectPackageCodec.export(source.toString(), "source", emptySet()))
        packageRoot.getJSONObject("project").put("name", "篡改后的名称")
        assertThrows(IllegalArgumentException::class.java) {
            ProjectPackageCodec.mergeIntoFullBackup(source.toString(), packageRoot.toString())
        }
    }

    private fun clearReusableResources(root: JSONObject) {
        root.put("experts", JSONArray().toString())
        root.put("expert_prompts", JSONObject().toString())
        root.put("expert_profiles_v1", JSONObject().toString())
        root.put("expert_versions_v1", JSONObject().toString())
        root.put("expert_teams_v1", JSONArray().toString())
        root.put("skills", JSONObject().toString())
        root.put("skill_metadata_v1", JSONObject().toString())
        root.put("workflows_v1", JSONArray().toString())
        seal(root)
    }

    private fun fixtureBackup(
        includeForeignProject: Boolean,
        projectId: String = "source",
        projectName: String = "源项目",
        conversationId: String = "source-conversation",
        includeProjectContent: Boolean = true
    ): JSONObject {
        val projects = JSONArray().put(project(projectId, projectName))
        if (includeForeignProject) projects.put(project("foreign", "其他项目"))
        val conversations = JSONArray()
        val documents = JSONArray()
        val chunks = JSONArray()
        val memories = JSONArray()
        val artifacts = JSONArray()
        val teams = JSONArray()
        val workflows = JSONArray()
        val scopes = linkedMapOf<String, Set<String>>()
        if (includeProjectContent) {
            val messageId = if (projectId == "source") "source-message" else "$conversationId-message"
            conversations.put(
                JSONObject().put("id", conversationId).put("title", "项目会话")
                    .put("updated_at", 10L).put("project_id", projectId)
                    .put("messages", JSONArray().put(
                        JSONObject().put("id", messageId).put("role", "user").put("content", "项目内容")
                            .put("versions", JSONArray()).put("active_version", 0)
                    ))
            )
            if (includeForeignProject) conversations.put(
                JSONObject().put("id", "foreign-conversation").put("title", "其他会话")
                    .put("updated_at", 11L).put("project_id", "foreign").put("messages", JSONArray())
            )
            documents.put(
                JSONObject().put("id", "source-document").put("projectId", projectId)
                    .put("name", "项目资料").put("mimeType", "text/plain").put("sizeBytes", 8L)
                    .put("importedAt", 1L).put("contentHash", "a".repeat(64))
            )
            chunks.put(
                JSONObject().put("id", "source-chunk").put("documentId", "source-document")
                    .put("projectId", projectId).put("position", 0).put("content", "项目资料内容")
            )
            memories.put(
                JSONObject().put("id", "source-memory").put("projectId", projectId)
                    .put("category", "project").put("content", "这是一个项目记忆内容")
                    .put("fingerprint", "source-fingerprint").put("sourceConversationId", conversationId)
                    .put("sourceMessageId", messageId).put("confidence", 0.9)
                    .put("createdAt", 1L).put("updatedAt", 2L)
            )
            artifacts.put(
                JSONObject().put("id", "source-artifact").put("projectId", projectId)
                    .put("title", "项目产物").put("version", 1).put("createdAt", 1L)
                    .put("content", "# 项目产物")
            )
            val teamId = "source-team"
            teams.put(JSONObject().put("id", teamId).put("name", "项目团队")
                .put("description", "团队说明").put("members", JSONArray().put("writer").put("analyst")))
            workflows.put(JSONObject().put("id", "source-workflow").put("name", "项目工作流")
                .put("description", "工作流说明").put("steps", JSONArray().put(
                    JSONObject().put("kind", "SKILL").put("targetId", "research")
                        .put("title", "使用技能").put("enabled", true)
                )))
            scopes[ProjectResourceScopeStore.resourceKey(ProjectResourceKind.EXPERT, "writer")] = setOf(projectId)
            scopes[ProjectResourceScopeStore.resourceKey(ProjectResourceKind.EXPERT, "analyst")] = setOf(projectId)
            scopes[ProjectResourceScopeStore.resourceKey(ProjectResourceKind.TEAM, teamId)] = setOf(projectId)
            scopes[ProjectResourceScopeStore.resourceKey(ProjectResourceKind.SKILL, "research")] = setOf(projectId)
            scopes[ProjectResourceScopeStore.resourceKey(ProjectResourceKind.WORKFLOW, "source-workflow")] = setOf(projectId)
        } else {
            conversations.put(
                JSONObject().put("id", conversationId).put("title", "现有会话")
                    .put("updated_at", 9L).put("project_id", projectId).put("messages", JSONArray())
            )
        }

        val root = JSONObject()
            .put("format", "nestro-local-backup")
            .put("version", LocalBackupCompatibility.CURRENT_VERSION)
            .put("projects", projects.toString())
            .put("active_project_id", projectId)
            .put("active_conversation_id", conversationId)
            .put("conversations_v1", conversations)
            .put("conversation_drafts_v1", JSONObject())
            .put("knowledge_documents_v1", documents)
            .put("knowledge_chunks_v1", chunks)
            .put("user_memories", memories)
            .put("artifacts_v1", artifacts)
            .put("project_memory_settings_v1", JSONObject().put(projectId, true).toString())
            .put("project_resource_scopes_v1", ProjectResourceScopeStore.encode(scopes))
            .put("experts", JSONArray().put("writer").put("analyst").toString())
            .put("expert_prompts", JSONObject().put("writer", "专家指令").put("analyst", "分析专家指令").toString())
            .put("expert_profiles_v1", JSONObject().toString())
            .put("expert_versions_v1", JSONObject().toString())
            .put("expert_teams_v1", teams.toString())
            .put("skills", JSONObject().put("research", "研究技能指令").toString())
            .put("skill_metadata_v1", JSONObject().toString())
            .put("disabled_skills_v1", JSONArray().toString())
            .put("workflows_v1", workflows.toString())
        seal(root)
        return root
    }

    private fun project(id: String, name: String) =
        JSONObject().put("id", id).put("name", name).put("instructions", "项目指令")
            .put("created_at", 1L).put("archived_at", 0L)

    private fun seal(root: JSONObject) {
        root.put("checksum_sha256", LocalBackupCompatibility.sha256(LocalBackupCompatibility.canonicalJson(root)))
    }
}
