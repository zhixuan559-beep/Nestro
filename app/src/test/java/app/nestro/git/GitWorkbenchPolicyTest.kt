package app.nestro.git

import org.junit.Assert.*
import org.junit.Test

class GitWorkbenchPolicyTest {
    @Test fun acceptsOnlyProviderHttpsCloneUrls() {
        assertTrue(GitWorkbenchPolicy.validCloneUrl(GitProvider.GITHUB, "https://github.com/octo/repo.git"))
        assertTrue(GitWorkbenchPolicy.validCloneUrl(GitProvider.GITLAB, "https://gitlab.com/group/repo.git"))
        assertTrue(GitWorkbenchPolicy.validCloneUrl(GitProvider.GITEE, "https://gitee.com/octo/repo.git"))
        assertFalse(GitWorkbenchPolicy.validCloneUrl(GitProvider.GITHUB, "http://github.com/octo/repo.git"))
        assertFalse(GitWorkbenchPolicy.validCloneUrl(GitProvider.GITHUB, "https://user:token@github.com/octo/repo.git"))
        assertFalse(GitWorkbenchPolicy.validCloneUrl(GitProvider.GITHUB, "https://attacker.example/octo/repo.git"))
        assertFalse(GitWorkbenchPolicy.validCloneUrl(GitProvider.GITHUB, "https://github.com/octo/repo.git?token=secret"))
    }

    @Test fun branchNamesUseGitRefRules() {
        assertTrue(GitWorkbenchPolicy.validBranchName("feature/mobile-workbench"))
        assertTrue(GitWorkbenchPolicy.validBranchName("release/v1.2"))
        assertFalse(GitWorkbenchPolicy.validBranchName(""))
        assertFalse(GitWorkbenchPolicy.validBranchName("-option"))
        assertFalse(GitWorkbenchPolicy.validBranchName("origin/feature"))
        assertFalse(GitWorkbenchPolicy.validBranchName("bad branch"))
        assertFalse(GitWorkbenchPolicy.validBranchName("branch..name"))
        assertFalse(GitWorkbenchPolicy.validBranchName("branch.lock"))
    }

    @Test fun filePathsCannotEscapeRepositoryOrTouchGitMetadata() {
        assertEquals("app/src/Main.kt", GitWorkbenchPolicy.safeRelativePath("app/src/Main.kt"))
        assertThrows(IllegalArgumentException::class.java) { GitWorkbenchPolicy.safeRelativePath("../outside.txt") }
        assertThrows(IllegalArgumentException::class.java) { GitWorkbenchPolicy.safeRelativePath("/etc/passwd") }
        assertThrows(IllegalArgumentException::class.java) { GitWorkbenchPolicy.safeRelativePath("C:\\outside.txt") }
        assertThrows(IllegalArgumentException::class.java) { GitWorkbenchPolicy.safeRelativePath(".git/config") }
        assertThrows(IllegalArgumentException::class.java) { GitWorkbenchPolicy.safeRelativePath("src/.git/config") }
    }
}
