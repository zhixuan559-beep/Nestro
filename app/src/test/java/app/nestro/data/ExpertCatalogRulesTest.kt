package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExpertCatalogRulesTest {
    @Test fun rejectsInvalidDefinitions() {
        assertEquals("专家角色指令必须为 1 到 8000 个字符", ExpertCatalogRules.validateDefinition("研究员", " ", "", emptyList()))
        assertEquals("专家名称必须为 1 到 40 个字符", ExpertCatalogRules.validateDefinition(" ", "分析任务", "", emptyList()))
        assertEquals("专家角色指令必须为 1 到 8000 个字符", ExpertCatalogRules.validateDefinition("研究员", "指令".repeat(8001), "", emptyList()))
    }

    @Test fun protectsBuiltinNamesAndRejectsDuplicates() {
        assertEquals("已存在同名专家，请更换名称", ExpertCatalogRules.validateDefinition("研究员", "分析任务", "", listOf("研究员")))
        assertEquals("内置专家不可改名", ExpertCatalogRules.validateDefinition("新名字", "分析任务", "文案官", listOf("文案官")))
        assertNull(ExpertCatalogRules.validateDefinition("文案官", "更新后的角色要求", "文案官", listOf("文案官")))
    }

    @Test fun renamingExpertUpdatesWorkflowReferences() {
        val workflow = WorkflowDefinition(name = "写作流程", steps = listOf(WorkflowStep(kind = WorkflowStepKind.EXPERT, title = "写作", targetId = "旧专家")))
        val renamed = ExpertCatalogRules.renameWorkflowReferences(listOf(workflow), "旧专家", "新专家")
        assertEquals("新专家", renamed.single().steps.single().targetId)
    }
}
