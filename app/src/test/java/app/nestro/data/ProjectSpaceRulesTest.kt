package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class ProjectSpaceRulesTest {
    private val existing = listOf(
        ProjectSpaceRules.Entry("default", "默认空间"),
        ProjectSpaceRules.Entry("work", "工作")
    )

    @Test fun validatesNamesAndDuplicateNamesCaseInsensitively() {
        assertEquals("项目名称必须为 1 到 40 个字符", ProjectSpaceRules.validate(" ", "", existing))
        assertEquals("项目名称必须为 1 到 40 个字符", ProjectSpaceRules.validate("名".repeat(41), "", existing))
        assertEquals("已存在同名项目空间，请更换名称", ProjectSpaceRules.validate(" 工作 ", "", existing))
        assertNull(ProjectSpaceRules.validate("工作", "work", existing))
    }

    @Test fun enforcesProjectLimitOnlyForCreation() {
        val full = (1..100).map { ProjectSpaceRules.Entry("p$it", "项目$it") }
        assertEquals("项目空间数量已达到 100 个上限", ProjectSpaceRules.validate("新项目", "", full))
        assertNull(ProjectSpaceRules.validate("重命名", "p1", full))
    }

    @Test fun validatesImportedProjectCollectionsWithoutSilentTruncation() {
        assertNull(ProjectSpaceRules.validateCollection(existing))
        assertEquals("项目空间 ID 重复", ProjectSpaceRules.validateCollection(listOf(
            ProjectSpaceRules.Entry("default", "默认空间"),
            ProjectSpaceRules.Entry("work", "工作"),
            ProjectSpaceRules.Entry("work", "另一个")
        )))
        assertEquals("项目空间名称重复", ProjectSpaceRules.validateCollection(listOf(
            ProjectSpaceRules.Entry("default", "默认空间"),
            ProjectSpaceRules.Entry("work", " 默认空间 ")
        )))
        assertEquals("默认项目空间缺失", ProjectSpaceRules.validateCollection(listOf(
            ProjectSpaceRules.Entry("work", "工作")
        )))
        val overLimit = (1..100).map { ProjectSpaceRules.Entry("p$it", "项目$it") } +
            ProjectSpaceRules.Entry("default", "默认空间")
        assertEquals("项目空间数量无效，最多保留 100 个", ProjectSpaceRules.validateCollection(overLimit))
    }

    @Test fun projectSerializationRejectsInvalidCollectionsInsteadOfSilentlyTruncating() {
        val tooMany = listOf(ProjectRecord("default", "默认空间", "", 0L)) +
            (1..100).map { ProjectRecord("p$it", "项目$it", "", it.toLong()) }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectPreferencesStore.projectsToJson(tooMany)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectPreferencesStore.projectsToJson(existing.map {
                ProjectRecord(it.id, it.name, if (it.id == "work") "指令".repeat(4001) else "", 0L)
            })
        }
    }

    @Test fun projectSerializationPreservesEveryValidProjectAndFullInstructionText() {
        val instructions = "规则".repeat(2000)
        val projects = listOf(
            ProjectRecord("default", "默认空间", "", 1L),
            ProjectRecord("work", "工作", instructions, 2L, 3L)
        )
        val encoded = ProjectPreferencesStore.projectsToJson(projects)
        val decoded = ProjectPreferencesStore.parseProjects(JSONArray(encoded))
        assertEquals(projects, decoded)
    }

    @Test fun loadingProjectsRejectsOversizedFieldsInsteadOfSilentlyTruncatingThem() {
        val oversized = JSONArray().put(
            JSONObject().put("id", "work").put("name", "工作")
                .put("instructions", "规则".repeat(2001)).put("created_at", 1L)
        )
        assertThrows(IllegalArgumentException::class.java) {
            ProjectPreferencesStore.parseProjects(oversized)
        }
    }

    @Test fun loadingProjectsRejectsMalformedAndDuplicateRecords() {
        val nonStringId = JSONArray().put(
            JSONObject().put("id", 123).put("name", "工作").put("instructions", "")
        )
        assertThrows(IllegalStateException::class.java) {
            ProjectPreferencesStore.parseProjects(nonStringId)
        }

        val duplicateIds = JSONArray()
            .put(JSONObject().put("id", "default").put("name", "默认空间"))
            .put(JSONObject().put("id", "work").put("name", "工作"))
            .put(JSONObject().put("id", "work").put("name", "另一个"))
        assertThrows(IllegalArgumentException::class.java) {
            ProjectPreferencesStore.parseProjects(duplicateIds)
        }
    }

    @Test fun loadingProjectsRejectsFractionalTimestampsInsteadOfCoercingThem() {
        val fractionalTimestamp = JSONArray().put(
            JSONObject().put("id", "default").put("name", "默认空间")
                .put("created_at", 1.5)
        )
        assertThrows(IllegalArgumentException::class.java) {
            ProjectPreferencesStore.parseProjects(fractionalTimestamp)
        }
    }

    @Test fun loadingLegacyProjectsAddsOnlyTheMissingDefaultWithoutDroppingRecords() {
        val legacy = JSONArray().put(
            JSONObject().put("id", "work").put("name", "工作")
                .put("instructions", "项目专属指令").put("created_at", 10L)
        )
        val loaded = ProjectPreferencesStore.parseProjects(legacy)
        assertEquals(listOf("default", "work"), loaded.map { it.id }.sorted())
        assertEquals("项目专属指令", loaded.single { it.id == "work" }.instructions)
    }

    @Test fun protectsDefaultAndProjectsWithLinkedDataFromDeletion() {
        assertFalse(ProjectSpaceRules.canDelete("default", "work", emptySet()))
        assertFalse(ProjectSpaceRules.canDelete("work", "default", setOf("work")))
        assertFalse(ProjectSpaceRules.canDelete("work", "work", emptySet()))
        assertTrue(ProjectSpaceRules.canDelete("work", "default", emptySet()))
        assertFalse(ProjectSpaceRules.canDelete("", "default", emptySet()))
    }

    @Test fun onlyRecoversInterruptedDeletionWhenProjectIsStillSafeToDelete() {
        fun canRecover(
            projectId: String = "work",
            activeProjectId: String = "default",
            hasRunningRequest: Boolean = false,
            hasDraft: Boolean = false,
            hasLocalConversationData: Boolean = false,
            hasLinkedData: Boolean = false
        ) = ProjectSpaceRules.canRecoverPendingDeletion(
            projectId, activeProjectId, hasRunningRequest, hasDraft,
            hasLocalConversationData, hasLinkedData
        )

        assertTrue(canRecover())
        assertFalse(canRecover(projectId = "default"))
        assertFalse(canRecover(activeProjectId = "work"))
        assertFalse(canRecover(hasRunningRequest = true))
        assertFalse(canRecover(hasDraft = true))
        assertFalse(canRecover(hasLocalConversationData = true))
        assertFalse(canRecover(hasLinkedData = true))
    }

    @Test fun projectResourceScopesPreserveLegacyGlobalDefaultsAndSupportProjectSharing() {
        val kind = ProjectResourceKind.SKILL
        val legacy = ProjectResourceScopeStore.decode("{}")
        assertTrue(ProjectResourceScopeStore.isVisible(legacy, kind, "research", "project-a"))
        assertTrue(ProjectResourceScopeStore.isVisible(legacy, kind, "research", "project-b"))

        val scoped = ProjectResourceScopeStore.setProjectScope(
            legacy, kind, "research", setOf("project-a", "project-b")
        )
        assertTrue(ProjectResourceScopeStore.isVisible(scoped, kind, "research", "project-a"))
        assertTrue(ProjectResourceScopeStore.isVisible(scoped, kind, "research", "project-b"))
        assertFalse(ProjectResourceScopeStore.isVisible(scoped, kind, "research", "project-c"))

        val global = ProjectResourceScopeStore.setGlobal(scoped, kind, "research")
        assertTrue(ProjectResourceScopeStore.isVisible(global, kind, "research", "project-c"))
        assertEquals(global, ProjectResourceScopeStore.decode(ProjectResourceScopeStore.encode(global)))
    }

    @Test fun projectResourceScopesRejectMalformedAndAmbiguousOwnership() {
        assertThrows(IllegalStateException::class.java) {
            ProjectResourceScopeStore.decode("""{"skill:research":[123]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectResourceScopeStore.decode("""{"skill:research":["project-a","project-a"]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectResourceScopeStore.decode("""{"skill:research":["*","project-a"]}""")
        }
    }

}\
    @Test fun projectDuplicationCarriesExplicitResourceAssociationsWithoutChangingGlobalResources() {
        val initial = mapOf(
            ProjectResourceScopeStore.resourceKey(ProjectResourceKind.EXPERT, "writer") to setOf("source"),
            ProjectResourceScopeStore.resourceKey(ProjectResourceKind.SKILL, "global-skill") to setOf("*"),
            ProjectResourceScopeStore.resourceKey(ProjectResourceKind.WORKFLOW, "hidden") to setOf("other")
        )
        val copied = ProjectResourceScopeStore.duplicateProjectScope(initial, "source", "copy")
        assertTrue(ProjectResourceScopeStore.isVisible(copied, ProjectResourceKind.EXPERT, "writer", "copy"))
        assertTrue(ProjectResourceScopeStore.isVisible(copied, ProjectResourceKind.EXPERT, "writer", "source"))
        assertTrue(ProjectResourceScopeStore.isVisible(copied, ProjectResourceKind.SKILL, "global-skill", "copy"))
        assertFalse(ProjectResourceScopeStore.isVisible(copied, ProjectResourceKind.WORKFLOW, "hidden", "copy"))
    }

}\
    @Test fun deletingProjectRemovesItsResourceReferencesWithoutLeavingOrphans() {
        val scopes = mapOf(
            ProjectResourceScopeStore.resourceKey(ProjectResourceKind.EXPERT, "only-here") to setOf("deleted"),
            ProjectResourceScopeStore.resourceKey(ProjectResourceKind.SKILL, "shared") to setOf("deleted", "kept"),
            ProjectResourceScopeStore.resourceKey(ProjectResourceKind.TEAM, "global") to setOf("*")
        )
        val cleaned = ProjectResourceScopeStore.removeProjectScope(scopes, "deleted")
        assertTrue(ProjectResourceScopeStore.isVisible(cleaned, ProjectResourceKind.EXPERT, "only-here", "any-project"))
        assertTrue(ProjectResourceScopeStore.isVisible(cleaned, ProjectResourceKind.SKILL, "shared", "kept"))
        assertFalse(ProjectResourceScopeStore.isVisible(cleaned, ProjectResourceKind.SKILL, "shared", "other"))
        assertTrue(ProjectResourceScopeStore.isVisible(cleaned, ProjectResourceKind.TEAM, "global", "other"))
    }

}\
    @Test fun projectMemorySettingsDefaultOffOutsideLegacyDefaultAndPreserveExplicitChoices() {
        val settings = ProjectMemorySettingsStore.decode("""{"default":true,"research":false,"writing":true}""")
        assertTrue(settings["default"] == true)
        assertFalse(settings["research"] == true)
        assertTrue(settings["writing"] == true)
        assertEquals(settings, ProjectMemorySettingsStore.decode(ProjectMemorySettingsStore.encode(settings)))
    }

    @Test fun projectMemorySettingsRejectInvalidTypesAndProjectIds() {
        assertThrows(IllegalStateException::class.java) {
            ProjectMemorySettingsStore.decode("""{"research":"enabled"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectMemorySettingsStore.decode("""{"":[true]}""")
        }
    }

}
