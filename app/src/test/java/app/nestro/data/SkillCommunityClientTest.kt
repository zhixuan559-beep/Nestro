package app.nestro.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class SkillCommunityClientTest {
    private fun signedEnvelope(payload: ByteArray, keyPair: java.security.KeyPair): String {
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keyPair.private)
        signer.update(payload)
        return JSONObject()
            .put("payload", Base64.getEncoder().encodeToString(payload))
            .put("signature", Base64.getEncoder().encodeToString(signer.sign()))
            .toString()
    }

    @Test
    fun comparesCommunitySkillVersionsWithoutLexicographicDowngrade() {
        assertTrue(SkillCommunityClient.isNewerVersion("1.10.0", "1.9.9"))
        assertTrue(SkillCommunityClient.isNewerVersion("2.0.0", "1.99.99"))
        assertTrue(SkillCommunityClient.isNewerVersion("1.0.0", "1.0.0-rc.2"))
        assertTrue(SkillCommunityClient.isNewerVersion("1.0.0-rc.10", "1.0.0-rc.2"))
        assertEquals(false, SkillCommunityClient.isNewerVersion("1.0.0-rc.1", "1.0.0"))
        assertEquals(false, SkillCommunityClient.isNewerVersion("1.9.9", "1.10.0"))
        assertEquals(false, SkillCommunityClient.isNewerVersion("1.0.0", "1.0.0+build.2"))
    }

    @Test
    fun acceptsCatalogOnlyWhenSignatureMatchesPinnedKey() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val payload = JSONObject()
            .put("schema", 1)
            .put("releases", org.json.JSONArray().put(
                JSONObject()
                    .put("name", "研究助手")
                    .put("version", "1.2.0")
                    .put("description", "检索与核验")
                    .put("bundle_url", "https://example.org/skill.json")
                    .put("sha256", "a".repeat(64))
            )).toString().toByteArray(Charsets.UTF_8)
        val key = Base64.getEncoder().encodeToString(pair.public.encoded)
        val releases = SkillCommunityClient.verifyCatalogEnvelope(signedEnvelope(payload, pair), key)
        assertEquals("研究助手", releases.single().name)
        assertEquals("1.2.0", releases.single().version)
        assertTrue(SkillCommunityClient.fingerprint(key).contains(":"))
    }

    @Test
    fun rejectsTamperedCatalogPayload() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val original = """{"schema":1,"releases":[]}""".toByteArray()
        val envelope = JSONObject(signedEnvelope(original, pair))
        val changed = """{"schema":1,"releases":[{}]}""".toByteArray()
        envelope.put("payload", Base64.getEncoder().encodeToString(changed))
        val key = Base64.getEncoder().encodeToString(pair.public.encoded)
        try {
            SkillCommunityClient.verifyCatalogEnvelope(envelope.toString(), key)
            throw AssertionError("tampered catalog must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun rejectsInsecureBundleUrlAndDuplicateReleases() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val row = JSONObject()
            .put("name", "技能").put("version", "1.0.0").put("description", "")
            .put("bundle_url", "http://example.org/skill.json").put("sha256", "b".repeat(64))
        val payload = JSONObject().put("schema", 1).put("releases", org.json.JSONArray().put(row)).toString().toByteArray()
        val key = Base64.getEncoder().encodeToString(pair.public.encoded)
        try {
            SkillCommunityClient.verifyCatalogEnvelope(signedEnvelope(payload, pair), key)
            throw AssertionError("HTTP bundle URL must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }
    @Test
    fun rejectsBundleWhenSignedSha256DoesNotMatch() {
        val bytes = """{"name":"研究助手","version":"1.0.0","prompt":"请核对来源。"}""".toByteArray()
        val release = SignedSkillRelease("研究助手", "1.0.0", "", "https://example.org/skill.json", "0".repeat(64))
        try {
            SkillCommunityClient.verifyBundleBytes(release, bytes)
            throw AssertionError("bundle with wrong digest must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun acceptsBundleOnlyWhenDigestNameAndVersionMatch() {
        val bytes = """{"name":"研究助手","version":"1.0.0","prompt":"请核对来源。"}""".toByteArray()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val release = SignedSkillRelease("研究助手", "1.0.0", "签名目录说明", "https://example.org/skill.json", digest)
        val bundle = SkillCommunityClient.verifyBundleBytes(release, bytes)
        assertEquals("研究助手", bundle.name)
        assertEquals("已验证签名社区目录", bundle.source)
    }

    @Test
    fun rejectsWeakRsaKeysAndCatalogsWithoutReleaseArray() {
        val weakPair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val key = Base64.getEncoder().encodeToString(weakPair.public.encoded)
        val payload = """{"schema":1,"releases":[]}""".toByteArray()
        try {
            SkillCommunityClient.verifyCatalogEnvelope(signedEnvelope(payload, weakPair), key)
            throw AssertionError("weak RSA key must be rejected")
        } catch (_: IllegalArgumentException) {
        }

        val strongPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val malformedPayload = """{"schema":1}""".toByteArray()
        val strongKey = Base64.getEncoder().encodeToString(strongPair.public.encoded)
        try {
            SkillCommunityClient.verifyCatalogEnvelope(signedEnvelope(malformedPayload, strongPair), strongKey)
            throw AssertionError("catalog without releases array must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun comparesLongNumericPrereleaseIdentifiersBySemverRules() {
        assertTrue(SkillCommunityClient.isNewerVersion("1.0.0-0abc", "1.0.0-99999999999999999999"))
        assertTrue(SkillCommunityClient.isNewerVersion("1.0.0-100000000000000000000", "1.0.0-99999999999999999999"))
        assertEquals(false, SkillCommunityClient.isNewerVersion("1.0.0-99999999999999999999", "1.0.0-0abc"))
    }

}
