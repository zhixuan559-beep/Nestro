package app.nestro.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalVaultApiKeyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun apiKeysAreStoredAndDeletedIndependently() {
        val firstId = "test-key-a-localvault"
        val secondId = "test-key-b-localvault"
        try {
            LocalVault.writeApiKey(context, firstId, "secret-a")
            LocalVault.writeApiKey(context, secondId, "secret-b")

            assertEquals("secret-a", LocalVault.readApiKey(context, firstId))
            assertEquals("secret-b", LocalVault.readApiKey(context, secondId))

            LocalVault.deleteApiKey(context, firstId)

            assertEquals("", LocalVault.readApiKey(context, firstId))
            assertEquals("secret-b", LocalVault.readApiKey(context, secondId))
        } finally {
            LocalVault.deleteApiKey(context, firstId)
            LocalVault.deleteApiKey(context, secondId)
        }
    }

    @Test
    fun legacyProfileCredentialsUseTheIndependentKeyStore() {
        val profileId = "legacy-localvault-test"
        val keyId = "legacy-key-$profileId"
        try {
            LocalVault.writeProfile(context, profileId, "legacy-secret")

            assertEquals("legacy-secret", LocalVault.readApiKey(context, keyId))
            assertEquals("legacy-secret", LocalVault.readProfile(context, profileId))

            LocalVault.deleteProfile(context, profileId)

            assertTrue(LocalVault.readApiKey(context, keyId).isBlank())
            assertTrue(LocalVault.readProfile(context, profileId).isBlank())
        } finally {
            LocalVault.deleteProfile(context, profileId)
        }
    }
}
