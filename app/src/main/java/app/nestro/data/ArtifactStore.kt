package app.nestro.data

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
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
    private const val MAX_BACKUP_ITEMS = MAX_ITEMS * 100 // up to 100 project spaces

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
                require(row.getString("fileName") == "${id}.md")
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
        writeAtomically(target, bytes)

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
        require(file.length() == record.sizeBytes) { "产物文件完整性校验失败，请从备份恢复" }
        return file.readText(Charsets.UTF_8)
    }

    @Synchronized
    fun delete(context: Context, record: ArtifactRecord) {
        require(record.id.matches(Regex("[0-9a-fA-F-]{36}")) && record.fileName == "${record.id}.md") { "产物文件引用无效" }
        val all = readAll(context)
        val kept = JSONArray()
        for (i in 0 until all.length()) {
            val row = all.optJSONObject(i) ?: continue
            if (row.optString("id") != record.id || row.optString("projectId") != record.projectId) kept.put(row)
        }
        writeIndex(context, kept)
        File(root(context), record.fileName).delete()
    }

    fun export(context: Context, record: ArtifactRecord, destination: Uri) {
        val content = read(context, record)
        context.contentResolver.openOutputStream(destination)?.use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
        } ?: error("无法写入所选位置")
    }

    @Synchronized
    fun backupAll(context: Context, allowedProjectIds: Set<String>): JSONArray {
        val rows = readAll(context)
        require(rows.length() <= MAX_ITEMS) { "产物版本数量超过备份上限" }
        val output = JSONArray()
        var totalBytes = 0L
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val id = row.optString("id")
            require(id.matches(Regex("[0-9a-fA-F-]{36}")) && row.optString("projectId") in allowedProjectIds) { "产物索引包含无效 ID 或项目归属" }
            val fileName = "$id.md"
            require(row.optString("fileName") == fileName) { "产物索引文件引用无效" }
            val file = File(root(context), fileName)
            require(file.isFile) { "产物文件缺失，已中止备份以避免静默丢失内容" }
            require(file.length() == row.optLong("sizeBytes")) { "产物文件大小与索引不一致，已中止备份" }
            require(file.length() <= MAX_ARTIFACT_BYTES) { "产物文件超出备份大小限制" }
            totalBytes += file.length()
            require(totalBytes <= 3_000_000L) { "产物总量超过备份安全容量" }
            output.put(JSONObject()
                .put("id", id)
                .put("projectId", row.optString("projectId"))
                .put("title", row.optString("title").take(100))
                .put("version", row.optInt("version", 1).coerceAtLeast(1))
                .put("createdAt", row.optLong("createdAt"))
                .put("content", file.readText(Charsets.UTF_8)))
        }
        return output
    }

    fun validateBackup(rows: JSONArray, allowedProjectIds: Set<String>): JSONArray {
        require(rows.length() <= MAX_ITEMS) { "备份中的产物版本数量超出限制" }
        val normalized = JSONArray()
        val ids = mutableSetOf<String>()
        var totalBytes = 0L
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val id = row.optString("id")
            val projectId = row.optString("projectId")
            val title = row.optString("title").trim()
            val content = row.optString("content")
            val bytes = content.toByteArray(Charsets.UTF_8)
            val version = row.optInt("version", 0)
            require(id.matches(Regex("[0-9a-fA-F-]{36}")) && ids.add(id) && projectId in allowedProjectIds) { "产物项目归属、ID 无效或重复" }
            require(title.isNotBlank() && title.length <= 100 && version in 1..10000) { "产物元数据无效" }
            require(bytes.isNotEmpty() && bytes.size <= MAX_ARTIFACT_BYTES) { "产物内容为空或超出大小限制" }
            totalBytes += bytes.size
            require(totalBytes <= 3_000_000L) { "产物总量超过备份安全容量" }
            normalized.put(JSONObject().put("id", id).put("projectId", projectId).put("title", title)
                .put("version", version).put("createdAt", row.optLong("createdAt")).put("content", content))
        }
        return normalized
    }

    @Synchronized
    fun restoreBackup(context: Context, rows: JSONArray, allowedProjectIds: Set<String>) {
        val pendingRows = validateBackup(rows, allowedProjectIds)
        data class Pending(val id: String, val projectId: String, val title: String, val version: Int, val createdAt: Long, val content: String)
        val pending = (0 until pendingRows.length()).map { index ->
            val row = pendingRows.getJSONObject(index)
            Pending(row.getString("id"), row.getString("projectId"), row.getString("title"), row.getInt("version"), row.optLong("createdAt"), row.getString("content"))
        }

        val directory = root(context)
        val oldRows = readAll(context)
        val oldFiles = (0 until oldRows.length()).mapNotNull { index ->
            oldRows.optJSONObject(index)?.optString("fileName")
                ?.takeIf { it.matches(Regex("[0-9a-fA-F-]{36}\\.md")) }
                ?.let { File(directory, it) }
        }
        val newIndex = JSONArray()
        val newFiles = mutableListOf<File>()
        try {
            pending.forEach { item ->
                // Fresh IDs ensure a failed restore cannot overwrite files referenced by the old index.
                val id = UUID.randomUUID().toString()
                val fileName = "${id}.md"
                val target = File(directory, fileName)
                val bytes = item.content.toByteArray(Charsets.UTF_8)
                writeAtomically(target, bytes)
                newFiles += target
                newIndex.put(JSONObject()
                    .put("id", id).put("projectId", item.projectId)
                    .put("title", item.title).put("fileName", fileName)
                    .put("mimeType", "text/markdown").put("createdAt", item.createdAt)
                    .put("sizeBytes", bytes.size.toLong()).put("version", item.version))
            }
            writeIndex(context, newIndex)
        } catch (error: Throwable) {
            newFiles.forEach { it.delete() }
            throw error
        }
        oldFiles.forEach { it.delete() }
    }

    private fun readAll(context: Context): JSONArray {
        val file = indexFile(context)
        return if (!file.exists()) JSONArray() else JSONArray(file.readText(Charsets.UTF_8))
    }

    private fun writeIndex(context: Context, rows: JSONArray) {
        writeAtomically(indexFile(context), rows.toString().toByteArray(Charsets.UTF_8))
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val atomic = AtomicFile(target)
        var output: FileOutputStream? = null
        try {
            output = atomic.startWrite()
            output.write(bytes)
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            output?.let { atomic.failWrite(it) }
            throw error
        }
    }
}
