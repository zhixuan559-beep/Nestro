package app.nestro.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import app.nestro.data.NestroDatabase
import org.junit.Rule
import org.junit.Test

class ContentPagesWindowSizeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val windowSizes = listOf(
        DpSize(320.dp, 640.dp),
        DpSize(393.dp, 852.dp),
        DpSize(673.dp, 900.dp),
        DpSize(840.dp, 900.dp),
        DpSize(1200.dp, 900.dp)
    )

    @Test
    fun knowledgePageRemainsScrollableAcrossWindowWidths() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NestroDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val windowSize = mutableStateOf(windowSizes.first())
            composeRule.setContent {
                DeviceConfigurationOverride(
                    DeviceConfigurationOverride.WindowSize(windowSize.value)
                ) {
                    KnowledgePage(database, "default")
                }
            }
            windowSizes.forEach { size ->
                composeRule.runOnIdle { windowSize.value = size }
                composeRule.waitForIdle()
                composeRule.onNodeWithText("本地知识库").assertIsDisplayed()
                composeRule.onNodeWithText("测试知识库检索").performScrollTo().assertIsDisplayed()
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun artifactLibraryRemainsScrollableAcrossWindowWidths() {
        val windowSize = mutableStateOf(windowSizes.first())
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(windowSize.value)
            ) {
                ArtifactLibraryPage("default")
            }
        }
        windowSizes.forEach { size ->
            composeRule.runOnIdle { windowSize.value = size }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("产物库").assertIsDisplayed()
            composeRule.onNodeWithText("产物名称").performScrollTo().assertIsDisplayed()
        }
    }
}
