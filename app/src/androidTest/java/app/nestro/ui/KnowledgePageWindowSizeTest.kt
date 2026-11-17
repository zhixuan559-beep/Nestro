package app.nestro.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.nestro.data.NestroDatabase
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class KnowledgePageWindowSizeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var database: NestroDatabase

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            NestroDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() {
        composeRule.waitForIdle()
        database.close()
    }

    @Test
    fun knowledgePageRemainsScrollableAcrossPhoneFoldableAndTabletWidths() {
        val windowSize = mutableStateOf(DpSize(320.dp, 640.dp))
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(windowSize.value)) {
                KnowledgePage(database, "window-test-project")
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
            composeRule.onNodeWithText("本地知识库").assertIsDisplayed()
            composeRule.onNodeWithText("＋ 导入文档").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("测试知识库检索").performScrollTo().assertIsDisplayed()
        }
    }
}
