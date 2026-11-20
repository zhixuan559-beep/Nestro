package app.nestro.phone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAgentUrlPolicyTest {
    @Test
    fun acceptsPublicHttpAndHttpsUrls() {
        assertTrue(PhoneAgentUrlPolicy.isSafePublicWebUrl("https://example.com/search?q=coffee"))
        assertTrue(PhoneAgentUrlPolicy.isSafePublicWebUrl("http://example.com"))
    }

    @Test
    fun rejectsLocalAndPrivateNetworkTargets() {
        listOf(
            "http://localhost",
            "http://router.local",
            "http://192.168.1.1",
            "http://10.0.0.8",
            "http://172.16.0.1",
            "http://169.254.1.2",
            "http://127.0.0.1",
            "http://127.1",
            "http://2130706433",
            "http://0x7f000001",
            "http://[::1]"
        ).forEach { assertFalse(it, PhoneAgentUrlPolicy.isSafePublicWebUrl(it)) }
    }

    @Test
    fun rejectsRelativeMalformedAndCredentialBearingQueryUrls() {
        listOf(
            "example.com/path",
            "https://",
            "https://example.com/?%61ccess_token=secret",
            "https://example.com/?api_key=secret",
            "https://example.com/path with spaces"
        ).forEach { assertFalse(it, PhoneAgentUrlPolicy.isSafePublicWebUrl(it)) }
    }

    @Test
    fun rejectsUnsafeSchemesCredentialsAndNonStandardPorts() {
        listOf(
            "file:///etc/passwd",
            "javascript:alert(1)",
            "https://user:pass@example.com",
            "https://example.com:8443",
            "https://example.com:80",
            "http://example.com:443"
        ).forEach { assertFalse(it, PhoneAgentUrlPolicy.isSafePublicWebUrl(it)) }
    }
}
