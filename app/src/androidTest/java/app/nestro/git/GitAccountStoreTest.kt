package app.nestro.git

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GitAccountStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun accessTokensAreEncryptedAndDeletedWithTheirAccount() {
        val id = "test-git-account"
        val token = "git-test-token-should-never-be-plain"
        val prefs = context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
        val store = GitAccountStore(context)
        try {
            store.save(GitAccount(id, GitProvider.GITHUB, "test-user"), token)
            val packed = prefs.getString("git_access_token_enc_v1_" + id, null)
            assertNotNull(packed)
            assertFalse(packed.orEmpty().contains(token))
            assertEquals(token, store.token(id))
            assertEquals("test-user", store.list().first { it.id == id }.username)
            store.delete(id)
            assertNull(prefs.getString("git_access_token_enc_v1_" + id, null))
            assertFalse(store.list().any { it.id == id })
        } finally {
            runCatching { store.delete(id) }
        }
    }

    @Test
    fun encryptedTokenCannotBeMovedBetweenAccountIds() {
        val first = "test-git-aad-first"
        val second = "test-git-aad-second"
        val prefs = context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
        val store = GitAccountStore(context)
        try {
            store.save(GitAccount(first, GitProvider.GITHUB, "first-user"), "token-first")
            store.save(GitAccount(second, GitProvider.GITHUB, "second-user"), "token-second")
            val firstCipher = prefs.getString("git_access_token_enc_v1_" + first, null)
            val secondCipher = prefs.getString("git_access_token_enc_v1_" + second, null)
            prefs.edit()
                .putString("git_access_token_enc_v1_" + first, secondCipher)
                .putString("git_access_token_enc_v1_" + second, firstCipher)
                .commit()
            assertThrows(IllegalStateException::class.java) { store.token(first) }
        } finally {
            runCatching { store.delete(first) }
            runCatching { store.delete(second) }
        }
    }
}
