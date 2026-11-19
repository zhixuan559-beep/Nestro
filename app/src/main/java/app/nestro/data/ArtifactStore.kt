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

private fun JSONObject.artifactString(key: String): String =
    opt(key) as? String ?: error("产物备份字段 $key 类型无效")

private fun JSONObject.artifactInt(key: String): Int {
    val value = opt(key) as? Number ?: error("产物备份字段 $key 必须是整数")
    val number = value.toDouble()
    require(number.isFinite() && number % 1.0 == 0.0 && number in 1.0..10_000.0) {
        "产物备份字段 $key 数值无效"
    }
    return number.toInt()
}

private fun JSONObject.artifactLongOrDefault(key: String, default: Long): Long {
    if (!has(key)) return default
    val value = opt(key) as? Number ?: error("产物备份字段 $key 必须是整数")
    val number = value.toDouble()
    require(number.isFinite() && number % 1.0 == 0.0) { "产物备份字段 $key 数值无效" }
    return value.toLong()
}

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
        val cleanTitle = title.trim()
        require(cleanTitle.isNotBlank()) { "请输入产物名称" }
        require(cleanTitle.length <= 100) { "产物名称不能超过 100 个字符" }
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
    fun duplicateProject(context: Context, sourceProjectId: String, targetProjectId: String): List<ArtifactRecord> {
        require(sourceProjectId.isNotBlank() && targetProjectId.isNotBlank() && sourceProjectId != targetProjectId) {
            "项目产物复制参数无效"
        }
        require(list(context, targetProjectId).isEmpty()) { "目标项目已有产物，拒绝覆盖" }
        val source = list(context, sourceProjectId)
            .sortedWith(compareBy<ArtifactRecord> { it.title.lowercase() }.thenBy { it.version })
            .map { it.title to read(context, it) }
        require(source.size <= MAX_ITEMS) { "源项目产物数量超过复制上限" }
        val created = mutableListOf<ArtifactRecord>()
        try {
            source.forEach { (title, content) -> created += save(context, targetProjectId, title, content) }
            return created
        } catch (error: Throwable) {
            created.asReversed().forEach { record -> runCatching { delete(context, record) } }
            throw error
        }
    }

    @Synchronized
    fun deleteProject(context: Context, projectId: String) {
        require(projectId.isNotBlank() && projectId != "default") { "不能清理默认项目产物" }
        val all = readAll(context)
        val kept = JSONArray()
        val files = mutableListOf<File>()
        for (index in 0 until all.length()) {
            val row = all.optJSONObject(index) ?: error("产物索引包含无效条目")
            if (row.optString("projectId") == projectId) {
                val id = row.artifactString("id")
                require(id.matches(Regex("[0-9a-fA-F-]{36}")) && row.artifactString("fileName") == "$id.md") {
                    "项目产物文件引用无效，拒绝清理"
                }
                files += File(root(context), "$id.md")
            } else kept.put(row)
        }
        writeIndex(context, kept)
        files.forEach { it.delete() }
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
        require(rows.length() <= MAX_BACKUP_ITEMS) { "产物版本数量超过备份上限" }
        val output = JSONArray()
        var totalBytes = 0L
        for (index in 0 until rows.length()) {
            val row = requireNotNull(rows.optJSONObject(index)) { "产物索引包含无效条目，已中止备份" }
            val id = row.artifactString("id")
            val projectId = row.artifactString("projectId")
            require(id.matches(Regex("[0-9a-fA-F-]{36}")) && projectId in allowedProjectIds) { "产物索引包含无效 ID 或项目归属" }
            val fileName = "$id.md"
            require(row.artifactString("fileName") == fileName) { "产物索引文件引用无效" }
            val file = File(root(context), fileName)
            require(file.isFile) { "产物文件缺失，已中止备份以避免静默丢失内容" }
            require(file.length() == row.artifactLongOrDefault("sizeBytes", -1L)) { "产物文件大小与索引不一致，已中止备份" }
            require(file.length() <= MAX_ARTIFACT_BYTES) { "产物文件超出备份大小限制" }
            val title = row.artifactString("title")
            val version = row.artifactInt("version")
            require(title.isNotBlank() && title.length <= 100 && title == title.trim()) { "产物标题无效，已中止备份" }
            require(version in 1..10_000) { "产物版本号无效，已中止备份" }
            totalBytes += file.length()
            require(totalBytes <= 3_000_000L) { "产物总量超过备份安全容量" }
            output.put(JSONObject()
                .put("id", id)
                .put("projectId", row.optString("projectId"))
                .put("title", title)
                .put("version", version)
                .put("createdAt", row.artifactLongOrDefault("createdAt", 0L))
                .put("content", file.readText(Charsets.UTF_8)))
        }
        return output
    }

    fun validateBackup(rows: JSONArray, allowedProjectIds: Set<String>): JSONArray {
        require(rows.length() <= MAX_BACKUP_ITEMS) { "备份中的产物版本数量超出限制" }
        val normalized = JSONArray()
        val ids = mutableSetOf<String>()
        var totalBytes = 0L
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val id = row.artifactString("id")
            val projectId = row.artifactString("projectId")
            val title = row.artifactString("title")
            val content = row.artifactString("content")
            val bytes = content.toByteArray(Charsets.UTF_8)
            val version = row.artifactInt("version")
            require(id.matches(Regex("[0-9a-fA-F-]{36}")) && ids.add(id) && projectId in allowedProjectIds) { "产物项目归属、ID 无效或重复" }
            require(title.isNotBlank() && title.length <= 100 && title == title.trim() && version in 1..10000) { "产物元数据无效" }
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
