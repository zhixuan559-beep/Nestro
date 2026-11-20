package app.nestro.data

import org.json.JSONObject

data class SkillDependencyIssue(
    val skillName: String,
    val requiredVersion: String,
    val reason: String
)

data class SkillDependencyReport(
    val resolved: List<String>,
    val issues: List<SkillDependencyIssue>
) {
    val isSatisfied: Boolean get() = issues.isEmpty()
}

/** Validates installed dependency closure. Dependencies are metadata references only. */
object SkillDependencyResolver {
    private const val MAX_DEPENDENCY_DEPTH = 32
    private const val MAX_DEPENDENCY_VISITS = 100
    private val numericIdentifier = "(?:0|[1-9][0-9]*)"
    private val prereleaseIdentifier = "(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)"
    private val buildIdentifiers = "[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*"
    private val constraintVersion = "$numericIdentifier\\.$numericIdentifier(?:\\.$numericIdentifier)?(?:-(?:$prereleaseIdentifier)(?:\\.(?:$prereleaseIdentifier))*)?(?:\\+$buildIdentifiers)?"
    private val strictVersion = "$numericIdentifier\\.$numericIdentifier\\.$numericIdentifier(?:-(?:$prereleaseIdentifier)(?:\\.(?:$prereleaseIdentifier))*)?(?:\\+$buildIdentifiers)?"
    private val constraintPattern = Regex("^(?:\\*|(?:>=|\\^|~)?$constraintVersion)$")
    private val versionPattern = Regex("^$strictVersion$")

    fun isValidConstraint(raw: String): Boolean {
        val value = raw.trim()
        if (value.length > 40 || !constraintPattern.matches(value)) return false
        if (value == "*") return true
        val version = value.removePrefix(">=").removePrefix("^").removePrefix("~")
        return version.substringBefore("-").substringBefore("+").split(".").all { it.length in 1..18 }
    }

    fun isValidVersion(raw: String): Boolean =
        raw.length <= 40 && versionPattern.matches(raw) &&
            raw.substringBefore("-").substringBefore("+").split(".").all { it.length in 1..18 }

    fun inspect(
        candidate: SkillBundle,
        installedSkills: Map<String, String>,
        installedMetadata: Map<String, String>
    ): SkillDependencyReport {
        val issues = linkedMapOf<String, SkillDependencyIssue>()
        val resolved = linkedSetOf<String>()
        val visited = mutableSetOf<String>()

        fun visit(dependency: SkillDependency, chain: Set<String>) {
            val name = dependency.name
            val issueKey = name + "@" + dependency.version
            if (chain.size >= MAX_DEPENDENCY_DEPTH) {
                issues[issueKey] = SkillDependencyIssue(name, dependency.version, "依赖深度超过 $MAX_DEPENDENCY_DEPTH 层")
                return
            }
            if (name in chain) {
                issues[issueKey] = SkillDependencyIssue(name, dependency.version, "依赖链形成循环")
                return
            }
            if (!isValidConstraint(dependency.version)) {
                issues[issueKey] = SkillDependencyIssue(name, dependency.version, "版本约束格式无效")
                return
            }
            if (!installedSkills.containsKey(name)) {
                issues[issueKey] = SkillDependencyIssue(name, dependency.version, "尚未安装")
                return
            }
            val rawMetadata = installedMetadata[name]
            val metadata = if (rawMetadata.isNullOrBlank()) JSONObject() else runCatching {
                JSONObject(rawMetadata)
            }.getOrElse {
                issues[issueKey] = SkillDependencyIssue(name, dependency.version, "已安装技能的依赖元数据损坏")
                return
            }
            val installedVersion = metadata.optString("version", "1.0.0").ifBlank { "1.0.0" }
            if (!versionSatisfies(installedVersion, dependency.version)) {
                issues[issueKey] = SkillDependencyIssue(name, dependency.version, "已安装版本为 " + installedVersion)
                return
            }
            resolved += name
            if (issueKey !in visited && visited.size >= MAX_DEPENDENCY_VISITS) {
                issues[issueKey] = SkillDependencyIssue(name, dependency.version, "依赖检查节点超过 $MAX_DEPENDENCY_VISITS 个")
                return
            }
            if (!visited.add(issueKey)) return
            if (metadata.has("dependencies") && metadata.optJSONArray("dependencies") == null) {
                issues[issueKey] = SkillDependencyIssue(name, dependency.version, "依赖元数据格式无效")
                return
            }
            val nested = metadata.optJSONArray("dependencies") ?: return
            val nextChain = chain + name
            for (index in 0 until nested.length()) {
                val row = nested.optJSONObject(index)
                if (row == null) {
                    issues[name + "#" + index] = SkillDependencyIssue(name, dependency.version, "依赖元数据格式无效")
                    continue
                }
                val nestedName = row.optString("name").trim()
                val nestedVersion = row.optString("version", "*").trim()
                if (nestedName.isBlank() || nestedName.length > 40 || !isValidConstraint(nestedVersion)) {
                    issues[name + "#" + index] = SkillDependencyIssue(name, dependency.version, "依赖元数据格式无效")
                } else {
                    visit(SkillDependency(nestedName, nestedVersion), nextChain)
                }
            }
        }

        candidate.dependencies.forEach { visit(it, setOf(candidate.name)) }
        return SkillDependencyReport(resolved.toList(), issues.values.toList())
    }


    /** Validates a stored skill's own metadata and its complete enabled dependency closure. */
    fun inspectInstalledSkill(
        name: String,
        prompt: String,
        installedSkills: Map<String, String>,
        installedMetadata: Map<String, String>,
        disabledSkills: Set<String> = emptySet()
    ): SkillDependencyReport {
        fun issue(reason: String) = SkillDependencyReport(
            emptyList(), listOf(SkillDependencyIssue(name, "*", reason))
        )
        if (name !in installedSkills) return issue("技能不存在")
        if (name in disabledSkills) return issue("技能已停用")
        val candidate = runCatching {
            SkillBundleParser.fromStored(name, prompt, installedMetadata[name])
        }.getOrElse { return issue(it.message ?: "技能元数据无效") }
        return inspect(
            candidate,
            installedSkills.filterKeys { it !in disabledSkills },
            installedMetadata
        )
    }

    /** Skills with missing, disabled, corrupt, or incompatible dependencies are excluded from execution context. */
    fun filterSatisfiedSkills(
        installedSkills: Map<String, String>,
        installedMetadata: Map<String, String>,
        disabledSkills: Set<String> = emptySet()
    ): Map<String, String> {
        val active = installedSkills.filterKeys { it !in disabledSkills }
        return active.filter { (name, prompt) ->
            inspectInstalledSkill(name, prompt, installedSkills, installedMetadata, disabledSkills).isSatisfied
        }
    }

    /**
     * Returns dependent skill names that would become incompatible with a proposed version.
     * Null means existing dependency metadata is malformed and compatibility cannot be proven.
     */
    fun findIncompatibleDependents(
        skillName: String,
        candidateVersion: String,
        installedSkills: Map<String, String>,
        installedMetadata: Map<String, String>
    ): List<String>? {
        if (!isValidVersion(candidateVersion)) return null
        val conflicts = mutableListOf<String>()
        for (dependentName in installedSkills.keys) {
            if (dependentName == skillName) continue
            val raw = installedMetadata[dependentName] ?: continue
            val metadata = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            val dependencies = metadata.optJSONArray("dependencies")
            if (metadata.has("dependencies") && dependencies == null) return null
            for (index in 0 until (dependencies?.length() ?: 0)) {
                val row = dependencies?.optJSONObject(index) ?: return null
                val dependencyName = row.optString("name").trim()
                val constraint = row.optString("version", "*").trim()
                if (dependencyName.isBlank() || !isValidConstraint(constraint)) return null
                if (dependencyName == skillName && !versionSatisfies(candidateVersion, constraint)) {
                    conflicts += dependentName
                    break
                }
            }
        }
        return conflicts
    }

    fun versionSatisfies(installed: String, constraint: String): Boolean {
        val required = constraint.trim()
        if (required == "*") return isValidVersion(installed)
        if (!isValidVersion(installed) || !isValidConstraint(required)) return false

        fun normalize(raw: String): String {
            val withoutBuild = raw.substringBefore("+")
            val core = withoutBuild.substringBefore("-").split(".")
            val suffix = if ('-' in withoutBuild) "-" + withoutBuild.substringAfter("-") else ""
            return (if (core.size == 2) core + "0" else core).joinToString(".") + suffix
        }
        fun compare(left: String, right: String): Int {
            val a = normalize(left)
            val b = normalize(right)
            return when {
                SkillCommunityClient.isNewerVersion(a, b) -> 1
                SkillCommunityClient.isNewerVersion(b, a) -> -1
                else -> 0
            }
        }

        return when {
            required.startsWith(">=") -> compare(installed, required.removePrefix(">=")) >= 0
            required.startsWith("^") || required.startsWith("~") -> {
                val operator = required.first()
                val minimum = required.drop(1)
                if (compare(installed, minimum) < 0) return false
                val core = normalize(minimum).substringBefore("-").split(".").map { it.toLongOrNull() ?: return false }
                val upper = when {
                    operator == '~' -> core[0].toString() + "." + (core[1] + 1) + ".0"
                    core[0] > 0 -> (core[0] + 1).toString() + ".0.0"
                    core[1] > 0 -> "0." + (core[1] + 1) + ".0"
                    else -> "0.0." + (core[2] + 1)
                }
                compare(installed, upper) < 0
            }
            else -> compare(installed, required) == 0
        }
    }
}
