package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
