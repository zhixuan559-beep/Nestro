package app.nestro.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.nestro.data.ArtifactStore
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class ArtifactLibraryWindowSizeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun clearArtifacts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "artifacts").deleteRecursively()
    }

    @After
    fun removeArtifacts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "artifacts").deleteRecursively()
    }

    @Test
    fun searchFiltersArtifactsAndHistoricalVersionCanBeRestoredAsNewVersion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ArtifactStore.save(context, "artifact-ui-project", "Keep Plan", "# first version")
        ArtifactStore.save(context, "artifact-ui-project", "Other Notes", "# unrelated")

        composeRule.setContent { ArtifactLibraryPage("artifact-ui-project") }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("搜索作品名称").performTextInput("Keep")
        composeRule.onNodeWithText("Keep Plan").assertIsDisplayed()
        composeRule.onAllNodesWithText("Other Notes").assertCountEquals(0)

        composeRule.onNodeWithText("预览").performClick()
        // “预览”读文件是异步协程（Dispatchers.IO 读完才置 preview 态弹对话框），
        // 按钮在断言瞬间可能尚未完成显示；等它真正显示出来再断言，避免时序抖动。
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithText("恢复为新版本").assertIsDisplayed()
            }.isSuccess
        }
        composeRule.onNodeWithText("恢复为新版本").assertIsDisplayed()
        composeRule.onNodeWithText("恢复为新版本").performClick()
        composeRule.waitForIdle()

        // list() 按版本逐条返回（ArtifactStoreTest 已定：同一标题保存两次即 2 条记录）；
        // 恢复为新版本会新增一条 v2 记录：Keep Plan v1+v2、Other Notes v1，共 3 条。
        assertEquals(3, ArtifactStore.list(context, "artifact-ui-project").size)
        assertEquals(
            listOf(1, 2),
            ArtifactStore.list(context, "artifact-ui-project")
                .filter { it.title == "Keep Plan" }.map { it.version }.sorted()
        )
        assertEquals(
            listOf(1),
            ArtifactStore.list(context, "artifact-ui-project")
                .filter { it.title == "Other Notes" }.map { it.version }
        )
    }

    @Test
    fun artifactLibraryRemainsScrollableAcrossPhoneFoldableAndTabletWidths() {
        val windowSize = mutableStateOf(DpSize(320.dp, 640.dp))
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(windowSize.value)) {
                ArtifactLibraryPage("window-test-project")
            }
        }

        listOf(
            DpSize(320.dp, 640.dp),
            DpSize(393.dp, 852.dp),
            DpSize(673.dp, 900.dp),
            DpSize(840.dp, 900.dp),
            DpSize(1200.dp, 900.dp)
        ).forEach { size ->
            composeRule.runOnIdle { windowSize.value = size }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("产物库").assertIsDisplayed()
            composeRule.onNodeWithText("产物名称").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("已保存的产物").performScrollTo().assertIsDisplayed()
        }
    }
}
