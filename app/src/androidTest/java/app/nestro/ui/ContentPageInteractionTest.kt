package app.nestro.ui

import android.content.Context
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.nestro.data.NestroDatabase
import app.nestro.data.KnowledgeRepository
import org.junit.Rule
import org.junit.Test

class ContentPageInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun knowledgeSearchRequiresAnImportedDocumentAndAQuery() {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            NestroDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val repository = KnowledgeRepository(database)
            composeRule.setContent { KnowledgePage(repository, "interaction-test-project") }
            composeRule.onNodeWithText("测试知识库检索").assertIsNotEnabled()
            composeRule.onNodeWithText("检索测试").performTextInput("项目隔离")
            composeRule.onNodeWithText("测试知识库检索").assertIsNotEnabled()
            composeRule.onNodeWithText("＋ 导入文档").assertIsEnabled()
        } finally {
            database.close()
        }
    }

    @Test
    fun artifactSaveRequiresBothTitleAndContent() {
        composeRule.setContent { ArtifactLibraryPage("interaction-test-project") }
        composeRule.onNodeWithText("保存产物").assertIsNotEnabled()

        composeRule.onNodeWithText("产物名称").performTextInput("交互校验")
        composeRule.onNodeWithText("保存产物").assertIsNotEnabled()

        composeRule.onNodeWithText("产物内容（Markdown）").performTextInput("正文")
        composeRule.onNodeWithText("保存产物").assertIsEnabled()
    }

    @Test
    fun communityCatalogRefreshRequiresHttpsAndTrustedKey() {
        composeRule.setContent { CapabilityDirectoryPage(projectId = "interaction-test-project") }
        composeRule.onNodeWithText("验证并刷新社区目录").assertIsNotEnabled()

        composeRule.onNodeWithText("社区目录 HTTPS 地址").performTextInput("http://example.invalid/catalog.json")
        composeRule.onNodeWithText("受信任 RSA 公钥（Base64 X.509）").performTextInput("test-key")
        composeRule.onNodeWithText("验证并刷新社区目录").assertIsNotEnabled()

        composeRule.onNodeWithText("社区目录 HTTPS 地址").performTextReplacement("https://example.invalid/catalog.json")
        composeRule.onNodeWithText("验证并刷新社区目录").assertIsEnabled()
    }
}
