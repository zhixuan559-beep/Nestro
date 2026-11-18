package app.nestro.phone

/** Keeps repeated phone-agent planning calls bounded without dropping the task goal. */
internal object PhoneAgentPromptBudget {
    data class Context(
        val screenText: String,
        val recentActions: String,
        val maxOutputTokens: Int
    )

    fun build(category: String, goal: String, visibleScreen: String, recentActions: String): Context {
        val normalized = category.uppercase()
        val screenLimit = when (normalized) {
            "SHOPPING" -> 5_000
            "FOOD_ORDER" -> 4_600
            "BOOKING" -> 4_400
            "FORM" -> 3_800
            "WEB_RESEARCH" -> 3_400
            else -> 3_200
        }
        val outputBudget = when {
            normalized in setOf("SHOPPING", "FOOD_ORDER", "BOOKING", "FORM") -> 2_048
            goal.length > 240 -> 1_536
            else -> 1_024
        }
        return Context(
            screenText = compactLines(visibleScreen, screenLimit),
            recentActions = compactLines(recentActions, 1_000),
            maxOutputTokens = outputBudget
        )
    }

    private fun compactLines(source: String, maxChars: Int): String {
        if (source.isBlank() || maxChars <= 0) return ""
        val seen = HashSet<String>()
        val output = StringBuilder()
        source.lineSequence().map { it.trim() }.filter { it.isNotBlank() }
            .filter { seen.add(it) }
            .forEach { line ->
                val remaining = maxChars - output.length
                if (remaining <= 0) return@forEach
                if (output.isNotEmpty()) output.append('\n')
                output.append(line.take((maxChars - output.length).coerceAtLeast(0)))
            }
        return output.toString().take(maxChars)
    }
}
