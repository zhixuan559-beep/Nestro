package app.nestro.coworkbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoworkbenchPolicyTest {
    @Test fun rejectsTraversalAndGitMetadataPaths() {
        listOf("../outside", "/absolute", "src/../secret", ".git/config", "src/.git/index").forEach { path ->
            assertFalse(runCatching { CoworkbenchPolicy.safePath(path) }.isSuccess)
        }
        assertEquals("src/main/File.kt", CoworkbenchPolicy.safePath("src/main/File.kt"))
    }

    @Test fun rejectsDuplicatePathsAndOversizedPatch() {
        assertFalse(runCatching {
            CoworkbenchPolicy.validate(listOf(CoworkbenchProposal("a.txt", "one"), CoworkbenchProposal("a.txt", "two")))
        }.isSuccess)
        assertFalse(runCatching {
            CoworkbenchPolicy.validate(listOf(CoworkbenchProposal("a.txt", "x".repeat(CoworkbenchPolicy.MAX_FILE_BYTES + 1))))
        }.isSuccess)
    }

    @Test fun detectsBuildPermissionAndCommandExecutionRisks() {
        val gradle = CoworkbenchPolicy.impact("app/build.gradle.kts", "", "dependencies { implementation(\"x:y:1\") }")
        assertTrue(gradle.any { it.contains("依赖") })
        val manifest = CoworkbenchPolicy.impact("app/src/main/AndroidManifest.xml", "", "<uses-permission android:name=\"android.permission.CAMERA\"/>")
        assertTrue(manifest.any { it.contains("权限") })
        val script = CoworkbenchPolicy.impact("src/Runner.kt", "", "Runtime.getRuntime().exec(\"sh\")")
        assertTrue(script.any { it.contains("高风险") })
    }

    @Test fun diffContainsOnlyChangedRegion() {
        val diff = CoworkbenchPolicy.diff("same\nold\nend", "same\nnew\nend")
        assertTrue(diff.contains("-old"))
        assertTrue(diff.contains("+new"))
        assertFalse(diff.contains("-same"))
        assertFalse(diff.contains("+end"))
    }

    @Test fun additionsAndDeletionsAreVisibleInImpact() {
        assertTrue(CoworkbenchPolicy.impact("new.txt", null, "new").contains("新增文件"))
        assertTrue(CoworkbenchPolicy.impact("old.txt", "old", null).contains("删除文件"))
    }
    @Test fun stagedApprovalSelectsOnlyReviewedFiles() {
        val files = listOf(
            CoworkbenchFileReview("a.txt", "old-a", "new-a", "-old-a\\n+new-a", emptyList()),
            CoworkbenchFileReview("b.txt", "old-b", "new-b", "-old-b\\n+new-b", emptyList())
        )
        assertEquals(listOf("b.txt"), CoworkbenchPolicy.selectStage(files, setOf("b.txt")).map { it.path })
        assertFalse(runCatching { CoworkbenchPolicy.selectStage(files, emptySet()) }.isSuccess)
        assertFalse(runCatching { CoworkbenchPolicy.selectStage(files, setOf("outside.txt")) }.isSuccess)
    }

}
