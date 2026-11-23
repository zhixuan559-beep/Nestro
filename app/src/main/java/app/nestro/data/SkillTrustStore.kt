package app.nestro.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Independent authority-signed revocation feed for community catalog signing keys. */
class SkillTrustStore(private val preferences: SharedPreferences) {
    fun pinAuthorityKey(base64X509: String) {
        val key = decodeKey(base64X509)
        val fingerprint = fingerprint(key)
        val previous = preferences.getString(KEY_AUTHORITY_FINGERPRINT, null)
        require(previous == null || previous == fingerprint) { "信任根已固定；更换信任根必须走独立的人工核验流程" }
        check(preferences.edit().putString(KEY_AUTHORITY, Base64.getEncoder().encodeToString(key))
            .putString(KEY_AUTHORITY_FINGERPRINT, fingerprint).commit()) { "信任根保存失败" }
    }

    fun authorityFingerprint(): String = preferences.getString(KEY_AUTHORITY_FINGERPRINT, "").orEmpty()
    fun isSignerRevoked(signerPublicKeyBase64: String): Boolean = fingerprint(decodeKey(signerPublicKeyBase64)) in revokedFingerprints()
    fun isFingerprintRevoked(value: String): Boolean = value.replace(":", "").lowercase() in revokedFingerprints()

    fun refreshRevocations(url: String): Int {
        val authority = preferences.getString(KEY_AUTHORITY, null) ?: error("尚未固定独立的社区信任根公钥")
        val uri = runCatching { URI(url) }.getOrNull() ?: error("撤销清单地址无效")
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.rawFragment == null && uri.rawPath.orEmpty().split('/').none { it == "." || it == ".." }) { "撤销清单必须使用无凭据 HTTPS 地址且不能包含路径回退" }
        val connection = URL(uri.toASCIIString()).openConnection() as? HttpURLConnection ?: error("无法连接撤销清单")
        val bytes = try {
            connection.instanceFollowRedirects = false; connection.connectTimeout = 8_000; connection.readTimeout = 12_000
            require(connection.responseCode in 200..299) { "撤销清单返回 HTTP ${connection.responseCode}" }
            val out = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(4096)
                while (true) { val n=input.read(buffer); if(n<0) break; require(out.size()+n<=256*1024) { "撤销清单超过 256 KB" }; out.write(buffer,0,n) }
            }
            out.toByteArray()
        } finally { connection.disconnect() }
        return applySignedRevocationEnvelope(bytes.toString(Charsets.UTF_8))
    }

    fun applySignedRevocationEnvelope(rawEnvelope: String): Int {
        require(rawEnvelope.toByteArray(Charsets.UTF_8).size in 1..256*1024) { "撤销清单为空或超过 256 KB" }
        val authority = preferences.getString(KEY_AUTHORITY, null) ?: error("尚未固定独立的社区信任根公钥")
        val envelope = JSONObject(rawEnvelope)
        val payload = Base64.getDecoder().decode(envelope.getString("payload"))
        val signature = Base64.getDecoder().decode(envelope.getString("signature"))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(authority)))
        val verifier = Signature.getInstance("SHA256withRSA"); verifier.initVerify(publicKey); verifier.update(payload)
        require(verifier.verify(signature)) { "撤销清单签名无效，未更新信任状态" }
        val root = JSONObject(payload.toString(Charsets.UTF_8))
        require(root.optInt("schema") == 1) { "不支持的撤销清单格式" }
        val issuedAt = root.getLong("issued_at")
        val now = System.currentTimeMillis() / 1000
        require(issuedAt <= now + 300) { "撤销清单签发时间超前，拒绝更新" }
        val expiresAt = root.getLong("expires_at")
        require(expiresAt > now && expiresAt <= issuedAt + 90L * 24 * 60 * 60) { "撤销清单已过期或有效期超过 90 天" }
        val previousIssuedAt = preferences.getLong(KEY_ISSUED_AT, 0L)
        require(issuedAt > previousIssuedAt) { "撤销清单版本过旧或重复，拒绝回滚信任状态" }
        val rows = root.optJSONArray("revoked_signers") ?: JSONArray()
        require(rows.length() <= 500) { "撤销签名者数量超限" }
        val next = revokedFingerprints().toMutableSet()
        for (i in 0 until rows.length()) {
            val value = rows.getString(i).replace(":", "").lowercase()
            require(value.matches(Regex("[a-f0-9]{64}"))) { "撤销清单包含无效 SHA-256 指纹" }
            next.add(value)
        }
        check(preferences.edit().putString(KEY_REVOKED, JSONArray(next.sorted()).toString()).putLong(KEY_ISSUED_AT, issuedAt).commit()) { "撤销清单保存失败" }
        return next.size
    }

    private fun revokedFingerprints(): Set<String> = runCatching {
        val rows=JSONArray(preferences.getString(KEY_REVOKED, "[]") ?: "[]")
        (0 until rows.length()).map { rows.getString(it).replace(":", "").lowercase() }.toSet()
    }.getOrDefault(emptySet())

    private fun decodeKey(value: String): ByteArray {
        val bytes=Base64.getDecoder().decode(value.filterNot(Char::isWhitespace))
        require(bytes.isNotEmpty() && bytes.size<=8192) { "RSA X.509 公钥格式无效" }
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(bytes))
        return bytes
    }
    private fun fingerprint(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val KEY_AUTHORITY="skill_trust_authority_key_v1"
        const val KEY_AUTHORITY_FINGERPRINT="skill_trust_authority_fingerprint_v1"
        const val KEY_REVOKED="skill_trust_revoked_signers_v1"
        const val KEY_ISSUED_AT="skill_trust_revocations_issued_at_v1"
    }
}
