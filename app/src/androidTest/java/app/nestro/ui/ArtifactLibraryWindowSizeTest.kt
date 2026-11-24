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
import org.junit.Assert.assertTrue

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
        composeRule.onNodeWithText("恢复为新版本").assertIsDisplayed()
        composeRule.onNodeWithText("恢复为新版本").performClick()
        composeRule.waitForIdle()

        assertEquals(2, ArtifactStore.list(context, "artifact-ui-project").size)
        assertTrue(ArtifactStore.list(context, "artifact-ui-project").any { it.version == 2 })
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
