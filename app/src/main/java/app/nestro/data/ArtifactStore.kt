package app.nestro.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ArtifactRecord(
    val id: String,
    val projectId: String,
    val title: String,
    val fileName: String,
    val mimeType: String,
    val createdAt: Long,
    val sizeBytes: Long,
    val version: Int
)

object ArtifactStore {
    private const val MAX_ARTIFACT_BYTES = 1_500_000
    private const val MAX_ITEMS = 200

    private fun root(context: Context): File =
        File(context.applicationContext.filesDir, "artifacts").apply { mkdirs() }

    private fun indexFile(context: Context) = File(root(context), "index.json")

    @Synchronized
    fun list(context: Context, projectId: String): List<ArtifactRecord> {
        val raw = indexFile(context).takeIf { it.exists() }?.readText(Charsets.UTF_8) ?: "[]"
        val rows = JSONArray(raw)
        return (0 until rows.length()).mapNotNull { index ->
            runCatching {
                val row = rows.getJSONObject(index)
                val id = row.getString("id")
                require(id.matches(Regex("[0-9a-fA-F-]{36}")))
                ArtifactRecord(
                    id = id,
                    projectId = row.getString("projectId"),
                    title = row.getString("title"),
                    fileName = row.getString("fileName"),
                    mimeType = row.optString("mimeType", "text/markdown"),
                    createdAt = row.getLong("createdAt"),
                    sizeBytes = row.getLong("sizeBytes"),
                    version = row.optInt("version", 1).coerceAtLeast(1)
                )
            }.getOrNull()
        }.filter { it.projectId == projectId && File(root(context), it.fileName).isFile }
            .sortedWith(compareByDescending<ArtifactRecord> { it.createdAt }.thenByDescending { it.version })
    }

    @Synchronized
    fun save(context: Context, projectId: String, title: String, content: String): ArtifactRecord {
        require(projectId.isNotBlank()) { "项目空间无效" }
        val cleanTitle = title.trim().take(100)
        require(cleanTitle.isNotBlank()) { "请输入产物名称" }
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty()) { "内容不能为空" }
        require(bytes.size <= MAX_ARTIFACT_BYTES) { "单个产物不能超过 1.5 MB" }

        val current = list(context, projectId)
        require(current.size < MAX_ITEMS) { "当前项目最多保存 $MAX_ITEMS 个版本" }
        val version = (current.filter { it.title.equals(cleanTitle, true) }.maxOfOrNull { it.version } ?: 0) + 1
        val id = UUID.randomUUID().toString()
        val fileName = "$id.md"
        val target = File(root(context), fileName)
        val temp = File(root(context), "$id.tmp")
        temp.writeBytes(bytes)
        check(temp.renameTo(target)) { "产物文件写入失败" }

        val all = readAll(context)
        val record = ArtifactRecord(id, projectId, cleanTitle, fileName, "text/markdown", System.currentTimeMillis(), bytes.size.toLong(), version)
        all.put(JSONObject().put("id", record.id).put("projectId", record.projectId)
            .put("title", record.title).put("fileName", record.fileName)
            .put("mimeType", record.mimeType).put("createdAt", record.createdAt)
            .put("sizeBytes", record.sizeBytes).put("version", record.version))
        writeIndex(context, all)
        return record
    }

    fun read(context: Context, record: ArtifactRecord): String {
        require(record.id.matches(Regex("[0-9a-fA-F-]{36}")) && record.fileName == "${record.id}.md") { "产物文件引用无效" }
        val file = File(root(context), record.fileName)
        require(file.isFile && file.length() <= MAX_ARTIFACT_BYTES) { "产物文件不存在或超出大小限制" }
        return file.readText(Charsets.UTF_8)
    }

    @Synchronized
    fun delete(context: Context, record: ArtifactRecord) {
        require(record.id.matches(Regex("[0-9a-fA-F-]{36}")) && record.fileName == "${record.id}.md") { "产物文件引用无效" }
        File(root(context), record.fileName).delete()
        val all = readAll(context)
        val kept = JSONArray()
        for (i in 0 until all.length()) {
            val row = all.optJSONObject(i) ?: continue
            if (row.optString("id") != record.id || row.optString("projectId") != record.projectId) kept.put(row)
        }
        writeIndex(context, kept)
    }

    fun export(context: Context, record: ArtifactRecord, destination: Uri) {
        val content = read(context, record)
        context.contentResolver.openOutputStream(destination)?.use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
        } ?: error("无法写入所选位置")
    }

    private fun readAll(context: Context): JSONArray {
        val file = indexFile(context)
        return if (!file.exists()) JSONArray() else JSONArray(file.readText(Charsets.UTF_8))
    }

    private fun writeIndex(context: Context, rows: JSONArray) {
        val target = indexFile(context)
        val temp = File(root(context), "index.tmp")
        temp.writeText(rows.toString(), Charsets.UTF_8)
        check(temp.renameTo(target) || run {
            target.writeText(temp.readText(Charsets.UTF_8), Charsets.UTF_8)
            temp.delete()
            true
        }) { "产物索引写入失败" }
    }
}
