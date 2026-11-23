package app.nestro.media

import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class MediaScene(val startMs: Long, val endMs: Long, val title: String, val description: String, val suggestedCaption: String = "")

interface MediaIntelligenceProvider {
    val providerId: String
    val displayName: String
    val requiresNetworkTransfer: Boolean
    val capabilities: Set<MediaIntelligenceCapability>
    suspend fun transcribe(asset: MediaAsset, sourceFile: File, languageTag: String, onProgress: (Float) -> Unit = {}): List<MediaSubtitleCue>
    suspend fun detectScenes(asset: MediaAsset, sourceFile: File, onProgress: (Float) -> Unit = {}): List<MediaScene>
    suspend fun processAudio(asset: MediaAsset, sourceFile: File, operation: String, onProgress: (Float) -> Unit = {}): File
    suspend fun analyzeImage(asset: MediaAsset, sourceFile: File, prompt: String, onProgress: (Float) -> Unit = {}): String =
        error("当前媒体 AI Provider 不支持图片理解")
    fun cancelActiveRequests() = Unit
}

object MediaIntelligenceRegistry {
    private val providers = ConcurrentHashMap<String, MediaIntelligenceProvider>()
    @Volatile private var activeProviderId: String? = null
    fun register(value: MediaIntelligenceProvider) {
        require(value.providerId.isNotBlank() && value.displayName.isNotBlank())
        providers[value.providerId] = value
        if (activeProviderId == null) activeProviderId = value.providerId
    }
    fun activate(providerId: String) {
        require(providers.containsKey(providerId)) { "媒体 AI Provider 未注册" }
        activeProviderId = providerId
    }
    fun unregister(providerId: String) {
        providers.remove(providerId)
        if (activeProviderId == providerId) activeProviderId = providers.keys.firstOrNull()
    }
    fun current(): MediaIntelligenceProvider? = activeProviderId?.let(providers::get)
    fun list(): List<MediaIntelligenceProvider> = providers.values.toList().sortedBy { provider -> provider.displayName }
}

object MediaPromptFactory {
    fun summarize(transcript: String): String = "请根据以下字幕/转写内容生成简洁的视频摘要，列出核心信息、适合的标题和3个发布文案版本。不得补充原文没有的事实。\n\n" + transcript.take(30_000)
    fun suggestScript(topic: String, durationSeconds: Int, audience: String): String = "为短视频生成可执行脚本。主题：" + topic.take(500) + "；时长：" + durationSeconds.coerceIn(10, 600) + "秒；目标受众：" + audience.take(300) + "。请输出开场钩子、分镜、口播、画面动作、字幕重点和结尾行动指引。"
    fun suggestShotList(topic: String, transcript: String): String = "根据主题和字幕生成可拍摄分镜表，按时间顺序给出镜头内容、景别、机位、画面动作、字幕和转场建议。不得假装已经分析过未提供的视频画面。\n主题：" + topic.take(500) + "\n字幕：\n" + transcript.take(20_000)
}
