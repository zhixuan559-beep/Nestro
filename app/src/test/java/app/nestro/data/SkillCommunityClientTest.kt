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
}
