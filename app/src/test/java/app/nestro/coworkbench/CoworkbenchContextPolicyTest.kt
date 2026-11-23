package app.nestro.coworkbench

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoworkbenchContextPolicyTest {
    @Test
    fun excludesLocalCredentialsAndSigningMaterialFromModelContext() {
        listOf(
            "local.properties", "gradle.properties", ".env.production",
            "config/google-services.json", "release/keystore/upload.jks",
            "secrets/service-account.json", "credentials/cloud.json", "certs/private.pem"
        ).forEach { path ->
            assertTrue(CoworkbenchContextPolicy.isSensitivePath(path))
        }
        assertFalse(CoworkbenchContextPolicy.isSensitivePath("app/src/main/Feature.kt"))
    }

    @Test
    fun redactsTokensBearerHeadersAssignmentsAndPrivateKeys() {
        val githubToken = "ghp_" + "A".repeat(36)
        val awsKey = "AKIA" + "B".repeat(16)
        val source = """
            api_key = "ordinary-secret-value"
            Authorization: Bearer $githubToken
            aws_secret_access_key=$awsKey
            -----BEGIN PRIVATE KEY-----
            private-key-material
            -----END PRIVATE KEY-----
        """.trimIndent()

        val redacted = CoworkbenchContextPolicy.redactSecrets(source)
        assertFalse(redacted.contains(githubToken))
        assertFalse(redacted.contains(awsKey))
        assertFalse(redacted.contains("ordinary-secret-value"))
        assertFalse(redacted.contains("private-key-material"))
        assertTrue(redacted.contains("[REDACTED]"))
    }
}
