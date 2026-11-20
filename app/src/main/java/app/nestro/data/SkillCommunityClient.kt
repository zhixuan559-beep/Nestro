package app.nestro.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

data class SignedSkillRelease(
    val name: String,
    val version: String,
    val description: String,
    val bundleUrl: String,
    val sha256: String
)

/** Read-only community catalog. A user-pinned RSA key authenticates the catalog;
 * the signed SHA-256 authenticates each downloaded bundle. This class never installs or grants permissions. */
object SkillCommunityClient {
    private const val MAX_CATALOG_BYTES = 512 * 1024
    private const val MAX_BUNDLE_BYTES = 30_000

    fun verifyCatalogEnvelope(rawEnvelope: String, trustedPublicKeyBase64: String): List<SignedSkillRelease> {
        require(rawEnvelope.toByteArray(Charsets.UTF_8).size in 1..MAX_CATALOG_BYTES) { "目录数据为空或超过 512 KB" }
        val envelope = JSONObject(rawEnvelope)
        val decoder = Base64.getDecoder()
        val payload = decoder.decode(envelope.getString("payload"))
        val signatureBytes = decoder.decode(envelope.getString("signature"))
        require(payload.isNotEmpty() && payload.size <= MAX_CATALOG_BYTES) { "目录数据为空或超过 512 KB" }
        val keyBytes = decoder.decode(trustedPublicKeyBase64.filterNot(Char::isWhitespace))
        require(keyBytes.isNotEmpty() && keyBytes.size <= 8192) { "受信任公钥格式无效" }
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes)) as? RSAPublicKey
            ?: throw IllegalArgumentException("受信任公钥必须为 RSA")
        require(publicKey.modulus.bitLength() in 2048..8192) { "RSA 公钥长度必须为 2048 到 8192 位" }
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update(payload)
        require(verifier.verify(signatureBytes)) { "社区目录签名验证失败，已拒绝加载" }

        val root = JSONObject(payload.toString(Charsets.UTF_8))
        require(root.optInt("schema") == 1) { "不支持的社区目录格式" }
        require(root.has("releases") && root.optJSONArray("releases") != null) { "社区目录缺少有效的 releases 数组" }
        val rows = root.getJSONArray("releases")
        require(rows.length() <= 200) { "社区目录条目超过 200 项" }
        val seen = mutableSetOf<String>()
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val name = row.optString("name").trim()
            val version = row.optString("version").trim()
            val description = row.optString("description").trim()
            val bundleUrl = row.optString("bundle_url").trim()
            val hash = row.optString("sha256").lowercase()
            require(name.isNotBlank() && name.length <= 40) { "社区技能名称无效" }
            require(SkillDependencyResolver.isValidVersion(version)) { "社区技能版本无效" }
            require(description.length <= 300) { "社区技能描述过长" }
            require(hash.matches(Regex("[a-f0-9]{64}"))) { "社区技能 SHA-256 无效" }
            require(bundleUrl.startsWith("https://")) { "技能包必须使用 HTTPS" }
            require(seen.add("$name@$version")) { "社区目录存在重复版本" }
            SignedSkillRelease(name, version, description, bundleUrl, hash)
        }
    }

    fun fetchCatalog(catalogUrl: String, trustedPublicKeyBase64: String): List<SignedSkillRelease> =
        verifyCatalogEnvelope(requestHttps(catalogUrl, MAX_CATALOG_BYTES).toString(Charsets.UTF_8), trustedPublicKeyBase64)

    fun downloadVerifiedBundle(release: SignedSkillRelease): SkillBundle {
        require(release.bundleUrl.startsWith("https://")) { "技能包必须使用 HTTPS" }
        return verifyBundleBytes(release, requestHttps(release.bundleUrl, MAX_BUNDLE_BYTES))
    }

    fun verifyBundleBytes(release: SignedSkillRelease, bytes: ByteArray): SkillBundle {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BUNDLE_BYTES) { "技能包为空或超过大小限制" }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(actual == release.sha256) { "技能包 SHA-256 不匹配，已拒绝导入" }
        val bundle = SkillBundleParser.parse(bytes.toString(Charsets.UTF_8), release.name)
        require(bundle.name == release.name && bundle.version == release.version) { "技能包名称或版本与已签名目录不一致" }
        return bundle.copy(description = release.description.ifBlank { bundle.description }, source = "已验证签名社区目录")
    }

    /** Returns true only when candidate has a strictly higher semantic version. */
    fun isNewerVersion(candidate: String, installed: String): Boolean {
        fun parse(raw: String): Pair<List<Long>, String?> {
            require(SkillDependencyResolver.isValidVersion(raw)) { "技能版本格式无效" }
            val withoutBuild = raw.substringBefore("+")
            val coreParts = withoutBuild.substringBefore("-").split(".")
            require(coreParts.all { it.length <= 18 }) { "技能版本数字过长" }
            val core = coreParts.map { it.toLong() }
            val preRelease = withoutBuild.substringAfter("-", "").takeIf { it.isNotEmpty() }
            return core to preRelease
        }
        val (nextCore, nextPre) = parse(candidate)
        val (currentCore, currentPre) = parse(installed)
        for (index in 0..2) {
            if (nextCore[index] != currentCore[index]) return nextCore[index] > currentCore[index]
        }
        if (nextPre == null) return currentPre != null
        if (currentPre == null) return false
        val nextParts = nextPre.split(".")
        val currentParts = currentPre.split(".")
        for (index in 0 until minOf(nextParts.size, currentParts.size)) {
            val nextNumeric = nextParts[index].all { it.isDigit() }
            val currentNumeric = currentParts[index].all { it.isDigit() }
            val comparison = when {
                nextNumeric && currentNumeric -> {
                    if (nextParts[index].length != currentParts[index].length) {
                        nextParts[index].length.compareTo(currentParts[index].length)
                    } else nextParts[index].compareTo(currentParts[index])
                }
                nextNumeric -> -1
                currentNumeric -> 1
                else -> nextParts[index].compareTo(currentParts[index])
            }
            if (comparison != 0) return comparison > 0
        }
        return nextParts.size > currentParts.size
    }

    fun fingerprint(trustedPublicKeyBase64: String): String {
        val keyBytes = Base64.getDecoder().decode(trustedPublicKeyBase64.filterNot(Char::isWhitespace))
        return MessageDigest.getInstance("SHA-256").digest(keyBytes).joinToString(":") { "%02X".format(it) }
    }

    private fun requestHttps(address: String, maxBytes: Int): ByteArray {
        require(address.length <= 2048 && address.startsWith("https://")) { "仅允许 HTTPS 地址" }
        val connection = URL(address).openConnection() as? HttpURLConnection ?: error("无法建立 HTTPS 连接")
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json, application/octet-stream")
            val status = connection.responseCode
            require(status in 200..299) { "远程服务器返回 HTTP $status" }
            val declaredLength = connection.contentLengthLong
            require(declaredLength < 0 || declaredLength <= maxBytes) { "远程文件超过大小限制" }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= maxBytes) { "远程文件超过大小限制" }
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        } finally {
            connection.disconnect()
        }
    }
}
