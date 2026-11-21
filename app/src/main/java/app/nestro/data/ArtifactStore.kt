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

internal fun artifactReferenceMatchesIndex(
    requested: ArtifactRecord,
    indexed: ArtifactRecord
): Boolean =
    requested.id.matches(Regex("[0-9a-fA-F-]{36}")) &&
        requested.fileName == "${requested.id}.md" &&
        requested.id == indexed.id &&
        requested.projectId == indexed.projectId &&
        requested.fileName == indexed.fileName

object ArtifactStore {
    private const val MAX_ARTIFACT_BYTES = 1_500_000
    private const val MAX_ITEMS = 200
    private const val MAX_BACKUP_ITEMS = MAX_ITEMS * 100 // up to 100 project spaces

    private fun root(context: Context): File =
        File(context.applicationContext.filesDir, "artifacts").apply { mkdirs() }

    private fun indexFile(context: Context) = File(root(context), "index.json")

    private fun recoverStagedProjectDeletes(context: Context) {
        val directory = File(context.applicationContext.filesDir, "artifacts")
        if (!directory.isDirectory) return
        val stagedPattern = Regex("^\\.([0-9a-fA-F-]{36}\\.md)\\.[0-9a-fA-F-]{36}\\.deleting$")
        val index = File(directory, "index.json")
        val indexedNames = try {
            if (!index.isFile) emptySet()
            else {
                val rows = JSONArray(index.readText(Charsets.UTF_8))
                (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.optString("fileName") }.toSet()
            }
        } catch (_: Throwable) {
            null
        }
        directory.listFiles().orEmpty().forEach { staged ->
            val originalName = stagedPattern.matchEntire(staged.name)?.groupValues?.getOrNull(1) ?: return@forEach
            val original = File(directory, originalName)
            if (indexedNames == null || originalName in indexedNames) {
                if (!original.exists()) staged.renameTo(original)
            } else {
                staged.delete()
            }
        }
    }

    @Synchronized
    fun list(context: Context, projectId: String): List<ArtifactRecord> {
        require(projectId.isNotBlank()) { "项目空间无效" }
        recoverStagedProjectDeletes(context)
        val index = indexFile(context)
        if (!index.exists()) {
            val unindexedFiles = root(context).listFiles().orEmpty().any { file ->
                file.isFile && file.name.endsWith(".md")
            }
            require(!unindexedFiles) { "产物索引缺失，但检测到未登记作品文件；已停止显示空列表以避免误判数据丢失" }
            return emptyList()
        }
        val rows = JSONArray(index.readText(Charsets.UTF_8))
        val records = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val id = row.artifactString("id")
            val fileName = row.artifactString("fileName")
            val record = ArtifactRecord(
                id = id,
                projectId = row.artifactString("projectId"),
                title = row.artifactString("title"),
                fileName = fileName,
                mimeType = row.optString("mimeType", "text/markdown"),
                createdAt = row.artifactLongOrDefault("createdAt", 0L),
                sizeBytes = row.artifactLongOrDefault("sizeBytes", -1L),
                version = row.artifactInt("version")
            )
            require(id.matches(Regex("[0-9a-fA-F-]{36}")) && fileName == "${id}.md") {
                "产物索引包含无效文件引用"
            }
            require(record.projectId.isNotBlank() && record.title.isNotBlank() &&
                record.createdAt >= 0L && record.sizeBytes in 1..MAX_ARTIFACT_BYTES.toLong() &&
                record.version in 1..10_000) {
                "产物索引元数据无效"
            }
            record
        }
        require(records.map { it.id }.distinct().size == records.size) { "产物索引包含重复 ID" }
        val versionKeys = records.map { Triple(it.projectId, it.title.lowercase(), it.version) }
        require(versionKeys.distinct().size == versionKeys.size) { "产物索引包含重复版本号" }
        require(records.groupingBy { it.projectId }.eachCount().values.all { it <= MAX_ITEMS }) {
            "产物索引中的项目版本数量超过单项目上限"
        }
        return records.filter { it.projectId == projectId }
            .onEach { record ->
                require(File(root(context), record.fileName).isFile) {
                    "产物文件缺失：${record.title} v${record.version}，已停止显示以避免误判为空"
                }
            }
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
        try {
            writeIndex(context, all)
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
        return record
    }

    private fun resolveIndexedRecord(context: Context, requested: ArtifactRecord): ArtifactRecord {
        require(requested.id.matches(Regex("[0-9a-fA-F-]{36}")) &&
            requested.fileName == "${requested.id}.md") { "产物文件引用无效" }
        val rows = readAll(context)
        val matches = (0 until rows.length()).map { rows.getJSONObject(it) }
            .filter { it.optString("id") == requested.id }
        require(matches.size == 1) { "产物记录不存在或索引重复" }
        val row = matches.single()
        val indexed = ArtifactRecord(
            id = row.artifactString("id"),
            projectId = row.artifactString("projectId"),
            title = row.artifactString("title"),
            fileName = row.artifactString("fileName"),
            mimeType = row.optString("mimeType", "text/markdown"),
            createdAt = row.artifactLongOrDefault("createdAt", 0L),
            sizeBytes = row.artifactLongOrDefault("sizeBytes", -1L),
            version = row.artifactInt("version")
        )
        require(artifactReferenceMatchesIndex(requested, indexed)) {
            "产物不属于当前项目，或引用已失效"
        }
        require(indexed.fileName == "${indexed.id}.md" &&
            indexed.title.isNotBlank() && indexed.createdAt >= 0L &&
            indexed.sizeBytes in 1..MAX_ARTIFACT_BYTES.toLong() &&
            indexed.version in 1..10_000) { "产物索引元数据无效" }
        return indexed
    }

    @Synchronized
    fun read(context: Context, record: ArtifactRecord): String {
        val indexed = resolveIndexedRecord(context, record)
        val file = File(root(context), indexed.fileName)
        require(file.isFile && file.length() <= MAX_ARTIFACT_BYTES) { "产物文件不存在或超出大小限制" }
        require(file.length() == indexed.sizeBytes) { "产物文件完整性校验失败，请从备份恢复" }
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
            .map { record -> record to read(context, record) }
        require(source.size <= MAX_ITEMS) { "源项目产物数量超过复制上限" }
        val all = readAll(context)
        val created = mutableListOf<ArtifactRecord>()
        val createdFiles = mutableListOf<File>()
        try {
            source.forEach { (original, content) ->
                val bytes = content.toByteArray(Charsets.UTF_8)
                require(bytes.size in 1..MAX_ARTIFACT_BYTES) { "源项目产物内容无效，已中止复制" }
                val id = UUID.randomUUID().toString()
                val fileName = "$id.md"
                val target = File(root(context), fileName)
                writeAtomically(target, bytes)
                createdFiles += target
                val copied = original.copy(id = id, projectId = targetProjectId, fileName = fileName, sizeBytes = bytes.size.toLong())
                created += copied
                all.put(JSONObject()
                    .put("id", copied.id).put("projectId", copied.projectId)
                    .put("title", copied.title).put("fileName", copied.fileName)
                    .put("mimeType", copied.mimeType).put("createdAt", copied.createdAt)
                    .put("sizeBytes", copied.sizeBytes).put("version", copied.version))
            }
            writeIndex(context, all)
            return created
        } catch (error: Throwable) {
            createdFiles.forEach { it.delete() }
            throw error
        }
    }

    @Synchronized
    fun hasProjectArtifactsOrOrphans(context: Context, projectId: String): Boolean {
        require(projectId.isNotBlank()) { "项目 ID 无效" }
        val all = readAll(context)
        val indexedFiles = mutableSetOf<String>()
        var hasProjectArtifacts = false
        for (index in 0 until all.length()) {
            val row = all.getJSONObject(index)
            val id = row.artifactString("id")
            val fileName = row.artifactString("fileName")
            require(id.matches(Regex("[0-9a-fA-F-]{36}")) && fileName == "$id.md") {
                "项目产物索引引用无效，拒绝判断项目是否为空"
            }
            indexedFiles += fileName
            if (row.artifactString("projectId") == projectId) {
                hasProjectArtifacts = true
                require(File(root(context), fileName).isFile) { "项目产物文件缺失，拒绝删除项目" }
            }
        }
        val orphanedFiles = root(context).listFiles().orEmpty().any { file ->
            file.isFile && file.name != "index.json" && file.name.endsWith(".md") && file.name !in indexedFiles
        }
        return hasProjectArtifacts || orphanedFiles
    }

    @Synchronized
    fun deleteProjectIfEmpty(context: Context, projectId: String): Boolean {
        if (hasProjectArtifactsOrOrphans(context, projectId)) return false
        deleteProject(context, projectId)
        return true
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
                val file = File(root(context), "$id.md")
                require(file.isFile) { "项目产物文件缺失，拒绝清理" }
                files += file
            } else kept.put(row)
        }
        val staged = mutableListOf<Pair<File, File>>()
        try {
            files.forEach { file ->
                val temporary = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.deleting")
                check(file.renameTo(temporary)) { "项目产物暂存失败，已取消删除" }
                staged += file to temporary
            }
            writeIndex(context, kept)
        } catch (error: Throwable) {
            staged.asReversed().forEach { (original, temporary) ->
                if (temporary.exists()) temporary.renameTo(original)
            }
            throw error
        }
        staged.forEach { (_, temporary) ->
            check(!temporary.exists() || temporary.delete()) { "项目索引已清理，但部分暂存文件未能回收：${temporary.name}" }
        }
    }

    @Synchronized
    fun delete(context: Context, record: ArtifactRecord) {
        val indexed = resolveIndexedRecord(context, record)
        val all = readAll(context)
        val kept = JSONArray()
        var removed = 0
        for (i in 0 until all.length()) {
            val row = all.optJSONObject(i) ?: error("产物索引包含无效条目")
            if (row.optString("id") == indexed.id && row.optString("projectId") == indexed.projectId) {
                removed++
            } else {
                kept.put(row)
            }
        }
        require(removed == 1) { "产物索引状态已变化，请刷新后重试" }
        writeIndex(context, kept)
        val file = File(root(context), indexed.fileName)
        if (file.exists() && !file.delete()) {
            runCatching { writeIndex(context, all) }
            error("产物文件清理失败，已尝试恢复索引；请刷新后重试")
        }
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
        val projectCounts = mutableMapOf<String, Int>()
        val versionKeys = mutableSetOf<Triple<String, String, Int>>()
        val artifactIds = mutableSetOf<String>()
        for (index in 0 until rows.length()) {
            val row = requireNotNull(rows.optJSONObject(index)) { "产物索引包含无效条目，已中止备份" }
            val id = row.artifactString("id")
            val projectId = row.artifactString("projectId")
            require(id.matches(Regex("[0-9a-fA-F-]{36}")) && artifactIds.add(id) && projectId in allowedProjectIds) { "产物索引包含无效/重复 ID 或项目归属" }
            val projectCount = (projectCounts[projectId] ?: 0) + 1
            require(projectCount <= MAX_ITEMS) { "项目 $projectId 的产物版本数量超过 $MAX_ITEMS 个" }
            projectCounts[projectId] = projectCount
            val fileName = "$id.md"
            require(row.artifactString("fileName") == fileName) { "产物索引文件引用无效" }
            val file = File(root(context), fileName)
            require(file.isFile) { "产物文件缺失，已中止备份以避免静默丢失内容" }
            require(file.length() == row.artifactLongOrDefault("sizeBytes", -1L)) { "产物文件大小与索引不一致，已中止备份" }
            require(file.length() <= MAX_ARTIFACT_BYTES) { "产物文件超出备份大小限制" }
            val title = row.artifactString("title")
            val version = row.artifactInt("version")
            val createdAt = row.artifactLongOrDefault("createdAt", 0L)
            require(createdAt >= 0L) { "产物创建时间无效，已中止备份" }
            val versionKey = Triple(projectId, title.lowercase(), version)
            require(versionKeys.add(versionKey)) { "同一作品包含重复版本号，已中止备份" }
            require(title.isNotBlank() && title.length <= 100 && title == title.trim()) { "产物标题无效，已中止备份" }
            require(version in 1..10_000) { "产物版本号无效，已中止备份" }
            totalBytes += file.length()
            require(totalBytes <= 3_000_000L) { "产物总量超过备份安全容量" }
            output.put(JSONObject()
                .put("id", id)
                .put("projectId", row.optString("projectId"))
                .put("title", title)
                .put("version", version)
                .put("createdAt", createdAt)
                .put("content", file.readText(Charsets.UTF_8)))
        }
        return output
    }

    fun validateBackup(rows: JSONArray, allowedProjectIds: Set<String>): JSONArray {
        require(rows.length() <= MAX_BACKUP_ITEMS) { "备份中的产物版本数量超出限制" }
        val normalized = JSONArray()
        val ids = mutableSetOf<String>()
        val projectCounts = mutableMapOf<String, Int>()
        val versionKeys = mutableSetOf<Triple<String, String, Int>>()
        var totalBytes = 0L
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val id = row.artifactString("id")
            val projectId = row.artifactString("projectId")
            val title = row.artifactString("title")
            val content = row.artifactString("content")
            val bytes = content.toByteArray(Charsets.UTF_8)
            val version = row.artifactInt("version")
            val createdAt = row.artifactLongOrDefault("createdAt", 0L)
            require(createdAt >= 0L) { "产物创建时间无效" }
            require(id.matches(Regex("[0-9a-fA-F-]{36}")) && ids.add(id) && projectId in allowedProjectIds) { "产物项目归属、ID 无效或重复" }
            val projectCount = (projectCounts[projectId] ?: 0) + 1
            require(projectCount <= MAX_ITEMS) { "项目 $projectId 的产物版本数量超过 $MAX_ITEMS 个" }
            projectCounts[projectId] = projectCount
            val versionKey = Triple(projectId, title.lowercase(), version)
            require(versionKeys.add(versionKey)) { "同一作品包含重复版本号" }
            require(title.isNotBlank() && title.length <= 100 && title == title.trim() && version in 1..10000) { "产物元数据无效" }
            require(bytes.isNotEmpty() && bytes.size <= MAX_ARTIFACT_BYTES) { "产物内容为空或超出大小限制" }
            totalBytes += bytes.size
            require(totalBytes <= 3_000_000L) { "产物总量超过备份安全容量" }
            normalized.put(JSONObject().put("id", id).put("projectId", projectId).put("title", title)
                .put("version", version).put("createdAt", createdAt).put("content", content))
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
        val stagedOldFiles = mutableListOf<Pair<File, File>>()
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
            oldFiles.forEach { oldFile ->
                if (oldFile.isFile) {
                    val temporary = File(directory, ".${oldFile.name}.${UUID.randomUUID()}.deleting")
                    check(oldFile.renameTo(temporary)) { "旧产物暂存失败，已取消备份恢复" }
                    stagedOldFiles += oldFile to temporary
                }
            }
            writeIndex(context, newIndex)
        } catch (error: Throwable) {
            stagedOldFiles.asReversed().forEach { (original, temporary) ->
                if (temporary.exists()) temporary.renameTo(original)
            }
            newFiles.forEach { it.delete() }
            throw error
        }
        // If the process stops here, the next read reconciles staged files against the committed index.
        stagedOldFiles.forEach { (_, temporary) -> temporary.delete() }
    }

    private fun readAll(context: Context): JSONArray {
        recoverStagedProjectDeletes(context)
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
