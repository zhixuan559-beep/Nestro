package app.nestro.data

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.LongBuffer
import java.security.MessageDigest
import java.text.Normalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object KnowledgeEmbeddingModelManager {
    const val MODEL_ID = "bge-small-zh-v1.5-int8-v1"
    private const val MODEL_URL = "https://huggingface.co/Xenova/bge-small-zh-v1.5/resolve/75c43b069aac4d136ba6bc1122f995fedcfd2781/onnx/model_int8.onnx"
    private const val MODEL_SHA256 = "b9837c19ce154ff0726d398ee77abbc03a7faf0476c6f93016c84e531be7ebb5"
    private const val VOCAB_URL = "https://huggingface.co/BAAI/bge-small-zh-v1.5/resolve/7999e1d33597/vocab.txt"
    private const val MAX_MODEL_BYTES = 35L * 1024L * 1024L
    private const val MAX_SEQUENCE_LENGTH = 512
    private const val BATCH_SIZE = 4
    private val runtimeMutex = Mutex()
    private var cachedSession: OrtSession? = null
    private var cachedSessionOptions: OrtSession.SessionOptions? = null
    private var cachedTokenizer: BgeWordPieceTokenizer? = null

    fun isInstalled(context: Context): Boolean {
        val directory = modelDirectory(context)
        val model = File(directory, "model_int8.onnx")
        val vocab = File(directory, "vocab.txt")
        val marker = File(directory, "installed.sha256")
        return model.isFile && model.length() in (10L * 1024L)..MAX_MODEL_BYTES &&
            vocab.isFile && vocab.length() in 50_000L..200_000L &&
            marker.isFile && runCatching { marker.readText().trim() == MODEL_SHA256 }.getOrDefault(false)
    }

    suspend fun install(context: Context) = withContext(Dispatchers.IO) {
        val directory = modelDirectory(context)
        check(directory.exists() || directory.mkdirs()) { "无法创建本地模型目录。" }
        val model = File(directory, "model_int8.onnx")
        if (!model.isFile || sha256(model) != MODEL_SHA256) {
            model.delete()
            downloadFile(MODEL_URL, model, MAX_MODEL_BYTES)
            check(sha256(model) == MODEL_SHA256) { "模型文件 SHA-256 校验失败，已拒绝安装。" }
        }
        val vocab = File(directory, "vocab.txt")
        if (!vocab.isFile || !validVocabulary(vocab)) {
            vocab.delete()
            downloadFile(VOCAB_URL, vocab, 200_000L)
            check(validVocabulary(vocab)) { "模型词表校验失败，已拒绝安装。" }
        }
        File(directory, "installed.sha256").writeText(MODEL_SHA256)
        runtimeMutex.withLock {
            cachedSession?.close()
            cachedSession = null
            cachedSessionOptions?.close()
            cachedSessionOptions = null
            cachedTokenizer = null
        }
    }

    suspend fun embed(context: Context, texts: List<String>): List<FloatArray> = withContext(Dispatchers.Default) {
        if (texts.isEmpty()) return@withContext emptyList()
        val (session, tokenizer) = runtime(context)
        texts.chunked(BATCH_SIZE).flatMap { batch -> inferBatch(session, tokenizer, batch) }
    }

    private suspend fun runtime(context: Context): Pair<OrtSession, BgeWordPieceTokenizer> =
        runtimeMutex.withLock {
            check(isInstalled(context)) { "本地语义模型尚未安装。" }
            val current = cachedSession
            val tokenizer = cachedTokenizer
            if (current != null && tokenizer != null) return@withLock current to tokenizer
            val directory = modelDirectory(context)
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            options.setIntraOpNumThreads(2)
            val session = try {
                environment.createSession(File(directory, "model_int8.onnx").absolutePath, options)
            } catch (error: Exception) {
                options.close()
                throw error
            }
            val loadedTokenizer = try {
                BgeWordPieceTokenizer(File(directory, "vocab.txt").readLines())
            } catch (error: Exception) {
                session.close()
                options.close()
                throw error
            }
            cachedSessionOptions = options
            cachedSession = session
            cachedTokenizer = loadedTokenizer
            session to loadedTokenizer
        }

    private fun inferBatch(
        session: OrtSession,
        tokenizer: BgeWordPieceTokenizer,
        texts: List<String>
    ): List<FloatArray> {
        val encoded = texts.map(tokenizer::encode)
        val sequenceLength = encoded.maxOf { it.size }.coerceIn(2, MAX_SEQUENCE_LENGTH)
        val batchSize = encoded.size
        val ids = LongArray(batchSize * sequenceLength) { tokenizer.padId }
        val masks = LongArray(batchSize * sequenceLength)
        encoded.forEachIndexed { batchIndex, tokens ->
            tokens.take(sequenceLength).forEachIndexed { tokenIndex, token ->
                ids[batchIndex * sequenceLength + tokenIndex] = token
                masks[batchIndex * sequenceLength + tokenIndex] = 1L
            }
        }
        val shape = longArrayOf(batchSize.toLong(), sequenceLength.toLong())
        val environment = OrtEnvironment.getEnvironment()
        val tensors = mutableListOf<OnnxTensor>()
        return try {
            val inputs = linkedMapOf<String, OnnxTensor>()
            val idsTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), shape)
            tensors += idsTensor
            inputs["input_ids"] = idsTensor
            if ("attention_mask" in session.inputNames) {
                val maskTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(masks), shape)
                tensors += maskTensor
                inputs["attention_mask"] = maskTensor
            }
            if ("token_type_ids" in session.inputNames) {
                val typeTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(ids.size)), shape)
                tensors += typeTensor
                inputs["token_type_ids"] = typeTensor
            }
            session.run(inputs).use { result ->
                val outputTensors = result.iterator().asSequence()
                    .mapNotNull { it.value as? OnnxTensor }
                    .toList()
                val output = outputTensors.firstOrNull { it.info.shape.size == 3 }
                    ?: outputTensors.firstOrNull { it.info.shape.size == 2 }
                    ?: error("ONNX 模型没有返回可用的文本向量。")
                val raw = output.value as? Array<*> ?: error("ONNX 模型输出维度无效。")
                val vectors = raw.indices.map { batchIndex ->
                    val vector = if (output.info.shape.size >= 3) {
                        val sequence = raw[batchIndex] as? Array<*> ?: error("ONNX 序列输出无效。")
                        val tokenVectors = sequence.map { token ->
                            token as? FloatArray ?: error("ONNX token 向量格式无效。")
                        }
                        val attentionMask = LongArray(sequence.size) { tokenIndex ->
                            masks[batchIndex * sequenceLength + tokenIndex]
                        }
                        meanPoolMaskedTokens(tokenVectors, attentionMask)
                    } else {
                        raw[batchIndex] as? FloatArray ?: error("ONNX 向量输出格式无效。")
                    }
                    normalize(vector)
                }
                vectors
            }
        } finally {
            tensors.forEach { runCatching { it.close() } }
        }
    }

    private fun normalize(vector: FloatArray): FloatArray {
        require(vector.size == 512) { "BGE-small-zh-v1.5 应返回 512 维向量。" }
        val norm = kotlin.math.sqrt(vector.sumOf { it.toDouble() * it.toDouble() })
        require(norm > 0.0 && norm.isFinite()) { "模型返回了无效向量。" }
        return FloatArray(vector.size) { (vector[it] / norm).toFloat() }
    }

    private fun modelDirectory(context: Context): File =
        File(context.applicationContext.filesDir, "knowledge-embedding/bge-small-zh-v1.5")

    private fun validVocabulary(file: File): Boolean = runCatching {
        val lines = file.readLines()
        lines.size in 20_000..22_000 &&
            listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]").all { it in lines }
    }.getOrDefault(false)

    private fun downloadFile(url: String, destination: File, maxBytes: Long) {
        val temporary = File(destination.parentFile, destination.name + ".part")
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 20_000
            connection.readTimeout = 120_000
            val status = connection.responseCode
            check(status in 200..299) { "模型下载服务器返回 HTTP $status。" }
            val expectedLength = connection.contentLengthLong
            require(expectedLength <= maxBytes) { "下载文件超过安全大小限制。" }
            var total = 0L
            connection.inputStream.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= maxBytes) { "下载文件超过安全大小限制。" }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            require(total > 0L) { "模型下载结果为空。" }
            check(temporary.renameTo(destination) || run {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
                true
            }) { "无法保存模型文件。" }
        } finally {
            connection.disconnect()
            temporary.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

internal class BgeWordPieceTokenizer(
    vocabularyLines: List<String>,
    private val maxLength: Int = 512
) {
    private val vocabulary = vocabularyLines.withIndex().associate { it.value to it.index.toLong() }
    private val unknownId = vocabulary["[UNK]"] ?: error("模型词表缺少 [UNK]。")
    private val clsId = vocabulary["[CLS]"] ?: error("模型词表缺少 [CLS]。")
    private val sepId = vocabulary["[SEP]"] ?: error("模型词表缺少 [SEP]。")
    val padId: Long = vocabulary["[PAD]"] ?: error("模型词表缺少 [PAD]。")

    fun encode(text: String): LongArray {
        val pieces = basicTokenize(text).flatMap(::wordPieces)
        val body = pieces.take((maxLength - 2).coerceAtLeast(0)).map { vocabulary[it] ?: unknownId }
        return (listOf(clsId) + body + sepId).toLongArray()
    }

    private fun basicTokenize(text: String): List<String> {
        val normalized = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
        val result = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                result += current.toString()
                current.clear()
            }
        }
        normalized.forEach { char ->
            when {
                char.isWhitespace() || Character.getType(char) == Character.CONTROL.toInt() -> flush()
                isCjk(char) -> {
                    flush()
                    result += char.toString()
                }
                !char.isLetterOrDigit() && char != '_' -> {
                    flush()
                    result += char.toString()
                }
                else -> current.append(char)
            }
        }
        flush()
        return result
    }

    private fun wordPieces(token: String): List<String> {
        if (token.length > 100) return listOf("[UNK]")
        val pieces = mutableListOf<String>()
        var start = 0
        while (start < token.length) {
            var end = token.length
            var found: String? = null
            while (end > start) {
                val candidate = (if (start == 0) "" else "##") + token.substring(start, end)
                if (candidate in vocabulary) {
                    found = candidate
                    break
                }
                end--
            }
            if (found == null) return listOf("[UNK]")
            pieces += found
            start = end
        }
        return pieces
    }

    private fun isCjk(char: Char): Boolean =
        char in '\u3400'..'\u4DBF' || char in '\u4E00'..'\u9FFF' || char in '\uF900'..'\uFAFF'
}


/** BGE sentence embeddings use attention-mask-aware mean pooling, not the CLS token alone. */
internal fun meanPoolMaskedTokens(tokenVectors: List<FloatArray>, attentionMask: LongArray): FloatArray {
    require(tokenVectors.isNotEmpty() && tokenVectors.size == attentionMask.size) {
        "Token 向量与 attention mask 长度不一致。"
    }
    val dimensions = tokenVectors.first().size
    require(dimensions > 0 && tokenVectors.all { it.size == dimensions && it.all { value -> value.isFinite() } }) {
        "Token 向量维度或数值无效。"
    }
    val included = attentionMask.indices.filter { attentionMask[it] == 1L }
    require(included.isNotEmpty()) { "Attention mask 不包含有效 token。" }
    val sums = DoubleArray(dimensions)
    included.forEach { tokenIndex ->
        tokenVectors[tokenIndex].forEachIndexed { dimension, value ->
            sums[dimension] += value.toDouble()
        }
    }
    return FloatArray(dimensions) { dimension ->
        (sums[dimension] / included.size).toFloat()
    }
}
