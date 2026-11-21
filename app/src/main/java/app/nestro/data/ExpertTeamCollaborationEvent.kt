package app.nestro.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Append-only, project-scoped collaboration event shown in the expert workspace. */
data class ExpertTeamCollaborationEvent(
    val id: String = UUID.randomUUID().toString(),
    val taskId: String,
    val projectId: String,
    val teamId: String,
    val sequence: Long,
    val type: String,
    val memberName: String = "",
    val relatedMemberName: String = "",
    val status: String = "",
    val message: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun validate() {
        require(id.isNotBlank() && id.length <= 80)
        require(taskId.isNotBlank() && taskId.length <= 120)
        require(projectId.isNotBlank() && projectId.length <= 120)
        require(teamId.isNotBlank() && teamId.length <= 120)
        require(sequence >= 0)
        require(type in TYPES)
        require(memberName.length <= 80 && relatedMemberName.length <= 80)
        require(status.length <= 40 && message.length <= 8_000)
        require(createdAt >= 0)
    }

    companion object {
        val TYPES = setOf(
            "team_started", "member_started", "member_retrying", "member_completed",
            "member_failed", "member_handoff", "member_message", "artifact_submitted",
            "review_requested", "review_feedback", "team_completed", "team_cancelled",
            "team_failed"
        )
    }
}

/** Sink is supplied by the app's project-scoped persistence layer. */
fun interface ExpertTeamEventSink {
    suspend fun append(event: ExpertTeamCollaborationEvent)
}

object ExpertTeamCollaborationEventCodec {
    private const val MAX_EVENTS = 500
    private const val MAX_BYTES = 2_000_000

    fun encode(events: List<ExpertTeamCollaborationEvent>): String =
        JSONArray().apply {
            events.takeLast(MAX_EVENTS).forEach { event ->
                event.validate()
                put(JSONObject()
                    .put("id", event.id)
                    .put("taskId", event.taskId)
                    .put("projectId", event.projectId)
                    .put("teamId", event.teamId)
                    .put("sequence", event.sequence)
                    .put("type", event.type)
                    .put("memberName", event.memberName)
                    .put("relatedMemberName", event.relatedMemberName)
                    .put("status", event.status)
                    .put("message", event.message.take(8_000))
                    .put("createdAt", event.createdAt))
            }
        }.toString()

    fun decode(raw: String): List<ExpertTeamCollaborationEvent> = runCatching {
        require(raw.length <= MAX_BYTES) { "专家协作事件记录过大" }
        val array = JSONArray(raw)
        (0 until minOf(array.length(), MAX_EVENTS)).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val event = ExpertTeamCollaborationEvent(
                id = item.optString("id"),
                taskId = item.optString("taskId"),
                projectId = item.optString("projectId"),
                teamId = item.optString("teamId"),
                sequence = item.optLong("sequence", -1),
                type = item.optString("type"),
                memberName = item.optString("memberName").take(80),
                relatedMemberName = item.optString("relatedMemberName").take(80),
                status = item.optString("status").take(40),
                message = item.optString("message").take(8_000),
                createdAt = item.optLong("createdAt", -1)
            )
            runCatching { event.validate() }.getOrNull()?.let { event }
        }.sortedWith(compareBy<ExpertTeamCollaborationEvent> { it.taskId }.thenBy { it.sequence })
    }.getOrDefault(emptyList())
}
