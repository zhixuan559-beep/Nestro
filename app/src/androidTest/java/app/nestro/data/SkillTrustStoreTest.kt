package app.nestro.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

class SkillTrustStoreTest {
    @Test fun independentAuthorityRevokesSignerAndRejectsReplay() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val prefs=context.getSharedPreferences("skill-trust-test",Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store=SkillTrustStore(prefs)
        val generator=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val authority=generator.generateKeyPair()
        val signer=generator.generateKeyPair()
        val authorityPublic=Base64.getEncoder().encodeToString(authority.public.encoded)
        val signerFingerprint=MessageDigest.getInstance("SHA-256").digest(signer.public.encoded).joinToString("") { "%02x".format(it) }
        store.pinAuthorityKey(authorityPublic)
        assertEquals(store.authorityFingerprint(),MessageDigest.getInstance("SHA-256").digest(authority.public.encoded).joinToString("") { "%02x".format(it) })

        val now=System.currentTimeMillis()/1000
        val payload=JSONObject().put("schema",1).put("issued_at",now).put("expires_at",now+3600)
            .put("revoked_signers",JSONArray().put(signerFingerprint)).toString().toByteArray(Charsets.UTF_8)
        val signature=Signature.getInstance("SHA256withRSA").apply { initSign(authority.private); update(payload) }.sign()
        val envelope=JSONObject().put("payload",Base64.getEncoder().encodeToString(payload))
            .put("signature",Base64.getEncoder().encodeToString(signature)).toString()
        assertEquals(1,store.applySignedRevocationEnvelope(envelope))
        assertTrue(store.isFingerprintRevoked(signerFingerprint))
        assertFalse(store.isFingerprintRevoked("0".repeat(64)))
        assertFalse(runCatching { store.applySignedRevocationEnvelope(envelope) }.isSuccess)

        val expiredPayload=JSONObject().put("schema",1).put("issued_at",now).put("expires_at",now-1)
            .put("revoked_signers",JSONArray()).toString().toByteArray(Charsets.UTF_8)
        val expiredSignature=Signature.getInstance("SHA256withRSA").apply { initSign(authority.private); update(expiredPayload) }.sign()
        val expiredEnvelope=JSONObject().put("payload",Base64.getEncoder().encodeToString(expiredPayload))
            .put("signature",Base64.getEncoder().encodeToString(expiredSignature)).toString()
        assertFalse(runCatching { store.applySignedRevocationEnvelope(expiredEnvelope) }.isSuccess)
    }
}
