package app.nestro.data

data class SkillInstallAssessment(
    val issues: List<String>
) {
    val canInstall: Boolean get() = issues.isEmpty()
}

/**
 * Central install gate shared by local/community installation flows.
 * Community packages may only update a package from the same verified source,
 * and only when the incoming semantic version is strictly newer.
 */
object SkillInstallPolicy {
    const val VERIFIED_COMMUNITY_SOURCE = "已验证签名社区目录"

    fun inspectCommunityRelease(
        bundle: SkillBundle,
        installedSkills: Map<String, String>,
        installedMetadata: Map<String, String>,
        disabledSkills: Set<String> = emptySet()
    ): SkillInstallAssessment {
        val issues = mutableListOf<String>()
        val installed = bundle.name in installedSkills
        val currentMetadata = installedMetadata[bundle.name]
            ?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
        if (installed && currentMetadata?.optString("source") != VERIFIED_COMMUNITY_SOURCE) {
            issues += "本机已有同名本地技能，社区包不得覆盖"
        }
        if (installed && currentMetadata?.optString("source") == VERIFIED_COMMUNITY_SOURCE) {
            val currentVersion = currentMetadata.optString("version")
            val newer = runCatching {
                currentVersion.isNotBlank() && SkillCommunityClient.isNewerVersion(bundle.version, currentVersion)
            }.getOrDefault(false)
            if (!newer) issues += "仅允许升级到严格高于本机的社区版本"
        }

        val availableDependencies = installedSkills.filterKeys { it !in disabledSkills }
        val dependencyReport = SkillDependencyResolver.inspect(bundle, availableDependencies, installedMetadata)
        dependencyReport.issues.forEach { issue ->
            issues += "依赖 ${issue.skillName}@${issue.requiredVersion}：${issue.reason}"
        }
        return SkillInstallAssessment(issues.distinct())
    }
}
