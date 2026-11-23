package app.nestro.data

/** Backtracking resolver: intersects all ranges for a dependency before selecting one signed release. */
object SkillDependencyResolver {
    data class InstallPlan(val orderedBundles: List<SkillBundle>, val alreadySatisfied: Map<String, String>) {
        val root: SkillBundle get() = orderedBundles.last()
    }

    private data class State(
        val constraints: Map<String, List<String>>,
        val selected: Map<String, SignedSkillRelease>,
        val bundles: Map<String, SkillBundle>,
        val satisfied: Map<String, String>
    )

    /**
     * Validates the complete installed dependency closure before a skill is injected into
     * model context. Legacy skills without dependency metadata remain compatible.
     */
    fun inspectInstalledSkill(
        name: String,
        installedSkills: Map<String, String>,
        installedMetadata: Map<String, String>,
        disabledSkills: Set<String> = emptySet()
    ): List<String> {
        val issues = linkedSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(current: String, chain: Set<String>, depth: Int) {
            if (depth > 32) { issues += "技能依赖深度超过 32 层：$current"; return }
            if (current in chain) { issues += "技能依赖形成循环：${(chain + current).joinToString(" → ")}"; return }
            if (current in disabledSkills) { issues += "依赖技能已停用：$current"; return }
            if (!installedSkills.containsKey(current)) { issues += "依赖技能未安装或当前项目不可见：$current"; return }
            if (!visited.add(current)) return
            val raw = installedMetadata[current]
            if (raw.isNullOrBlank()) return
            val metadata = runCatching { org.json.JSONObject(raw) }.getOrElse {
                issues += "技能依赖元数据损坏：$current"; return
            }
            val version = metadata.optString("version", "1.0.0").ifBlank { "1.0.0" }
            if (!runCatching { SkillSemVer.compare(version, version) }.isSuccess) {
                issues += "技能版本元数据无效：$current"; return
            }
            if (metadata.has("dependencies") && metadata.optJSONArray("dependencies") == null) {
                issues += "技能依赖列表格式无效：$current"; return
            }
            val dependencies = metadata.optJSONArray("dependencies") ?: return
            val nextChain = chain + current
            for (index in 0 until dependencies.length()) {
                val row = dependencies.optJSONObject(index)
                if (row == null) { issues += "技能依赖项格式无效：$current#$index"; continue }
                val dependency = row.optString("name").trim()
                val constraint = row.optString("version", "*").trim()
                if (dependency.isBlank() || dependency.length > 80 ||
                    !runCatching { SkillSemVer.matches("1.0.0", constraint) }.isSuccess
                ) { issues += "技能依赖约束无效：$current#$index"; continue }
                val rawDependencyMetadata = installedMetadata[dependency]
                val installedVersion = if (!installedSkills.containsKey(dependency)) null else if (rawDependencyMetadata.isNullOrBlank()) {
                    "1.0.0"
                } else runCatching {
                    org.json.JSONObject(rawDependencyMetadata).optString("version", "1.0.0").ifBlank { "1.0.0" }
                }.getOrNull()
                if (installedVersion == null || !runCatching { SkillSemVer.matches(installedVersion, constraint) }.getOrDefault(false)) {
                    issues += "依赖版本不满足：$dependency@$constraint"; continue
                }
                visit(dependency, nextChain, depth + 1)
            }
        }
        visit(name, emptySet(), 0)
        return issues.toList()
    }

    /**
     * Returns skills that directly depend on the target. Corrupt stored metadata fails closed
     * so callers cannot disable, rename, or delete a dependency on an incomplete graph.
     */
    fun findDependents(
        skillName: String,
        installedSkills: Map<String, String>,
        installedMetadata: Map<String, String>
    ): List<String>? {
        if (skillName.isBlank() || skillName.length > 40) return null
        val dependents = mutableListOf<String>()
        installedSkills.forEach { (candidateName, prompt) ->
            if (candidateName == skillName) return@forEach
            val bundle = runCatching {
                SkillBundleParser.fromStored(candidateName, prompt, installedMetadata[candidateName])
            }.getOrNull() ?: return null
            if (bundle.dependencies.any { it.name == skillName }) dependents += candidateName
        }
        return dependents.sorted()
    }

    fun inspectCandidate(
        candidate: SkillBundle,
        installedSkills: Map<String, String>,
        installedMetadata: Map<String, String>,
        disabledSkills: Set<String> = emptySet()
    ): List<String> {
        val stagedSkills = installedSkills + (candidate.name to candidate.prompt)
        val stagedMetadata = installedMetadata + (candidate.name to SkillBundleParser.metadataJson(candidate))
        return inspectInstalledSkill(
            candidate.name,
            candidate.prompt,
            stagedSkills,
            stagedMetadata,
            disabledSkills - candidate.name
        )
    }

    fun findIncompatibleDependents(
        skillName: String,
        candidateVersion: String,
        installedSkills: Map<String, String>,
        installedMetadata: Map<String, String>
    ): List<String>? {
        if (!runCatching { SkillSemVer.compare(candidateVersion, candidateVersion) }.isSuccess) return null
        val conflicts = mutableListOf<String>()
        installedSkills.forEach { (dependentName, prompt) ->
            if (dependentName == skillName) return@forEach
            val dependent = runCatching {
                SkillBundleParser.fromStored(dependentName, prompt, installedMetadata[dependentName])
            }.getOrNull() ?: return null
            if (dependent.dependencies.any { dependency ->
                    dependency.name == skillName &&
                        !runCatching { SkillSemVer.matches(candidateVersion, dependency.version) }.getOrDefault(false)
                }
            ) {
                conflicts += dependentName
            }
        }
        return conflicts.sorted()
    }

    fun filterSatisfiedSkills(
        installedSkills: Map<String, String>,
        installedMetadata: Map<String, String>,
        disabledSkills: Set<String> = emptySet()
    ): Map<String, String> {
        val active = installedSkills.filterKeys { it !in disabledSkills }
        return active.filter { (name, _) ->
            inspectInstalledSkill(name, installedSkills, installedMetadata, disabledSkills).isEmpty()
        }
    }

    fun resolve(rootRelease: SignedSkillRelease, catalog: List<SignedSkillRelease>, installedVersions: Map<String, String>, verifiedRootBundle: SkillBundle? = null): InstallPlan {
        val byName = catalog.groupBy { it.name }
        var attempts = 0
        val rootBundle = verifiedRootBundle ?: SkillCommunityClient.downloadVerifiedBundle(rootRelease)
        require(rootBundle.name == rootRelease.name && rootBundle.version == rootRelease.version && rootBundle.source == "已验证签名社区目录" && rootBundle.signerFingerprint == rootRelease.signerFingerprint) { "根技能与签名目录不一致" }
        val initialConstraints = rootBundle.dependencies.groupBy { it.name }.mapValues { (_, rows) -> rows.map { it.version }.distinct() }
        val initial = State(initialConstraints, mapOf(rootRelease.name to rootRelease), mapOf(rootRelease.name to rootBundle), emptyMap())

        fun hasCycle(bundles: Map<String, SkillBundle>): Boolean {
            val visiting = mutableSetOf<String>()
            val visited = mutableSetOf<String>()
            fun visit(name: String): Boolean {
                if (name in visiting) return false
                if (name in visited) return true
                visiting.add(name)
                bundles[name]?.dependencies.orEmpty().forEach { dependency ->
                    if (dependency.name in bundles && !visit(dependency.name)) return false
                }
                visiting.remove(name)
                visited.add(name)
                return true
            }
            return bundles.keys.any { !visit(it) }
        }

        fun search(state: State, depth: Int): State? {
            if (depth > 50) return null
            if (state.selected.any { (name, release) ->
                    state.constraints[name].orEmpty().any { range -> !runCatching { SkillSemVer.matches(release.version, range) }.getOrDefault(false) }
                }) return null
            if (state.satisfied.any { (name, version) ->
                    state.constraints[name].orEmpty().any { range -> !runCatching { SkillSemVer.matches(version, range) }.getOrDefault(false) }
                }) return null
            val unresolved = state.constraints.keys.firstOrNull { it !in state.selected && it !in state.satisfied }
            if (unresolved == null) return if (hasCycle(state.bundles)) null else state
            val ranges = state.constraints[unresolved].orEmpty()
            val installed = installedVersions[unresolved]
            if (installed != null && ranges.all { runCatching { SkillSemVer.matches(installed, it) }.getOrDefault(false) }) {
                val installedSolution = search(state.copy(satisfied = state.satisfied + (unresolved to installed)), depth + 1)
                if (installedSolution != null) return installedSolution
                // A later dependency may add a stricter range; then try a signed catalog version instead.
            }
            val candidates = byName[unresolved].orEmpty()
                .filter { release -> ranges.all { runCatching { SkillSemVer.matches(release.version, it) }.getOrDefault(false) } }
                .sortedWith { a, b -> SkillSemVer.compare(b.version, a.version) }
            for (candidate in candidates) {
                attempts++
                require(attempts <= 100) { "依赖冲突求解超过 100 次尝试，已停止以避免无限下载" }
                val bundle = runCatching { SkillCommunityClient.downloadVerifiedBundle(candidate) }.getOrNull() ?: continue
                if (bundle.name != candidate.name || bundle.version != candidate.version || bundle.source != "已验证签名社区目录" || bundle.signerFingerprint != candidate.signerFingerprint) continue
                val nextConstraints = state.constraints.mapValues { it.value.toMutableList() }.toMutableMap()
                bundle.dependencies.forEach { dep ->
                    val current = nextConstraints[dep.name].orEmpty().toMutableList()
                    current.add(dep.version)
                    nextConstraints[dep.name] = current.distinct().toMutableList()
                }
                val next = State(nextConstraints, state.selected + (unresolved to candidate), state.bundles + (unresolved to bundle), state.satisfied)
                val solved = search(next, depth + 1)
                if (solved != null) return solved
            }
            return null
        }

        val solved = search(initial, 0) ?: error("社区目录无法同时满足全部依赖版本约束；未修改本地技能")
        val selectedBundles = solved.bundles
        val graph = selectedBundles.mapValues { (_, bundle) -> bundle.dependencies.map { it.name }.filter { it in selectedBundles } }
        val ordered = mutableListOf<SkillBundle>()
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(name: String) {
            require(name !in visiting) { "技能依赖存在循环引用：$name" }
            if (!visited.add(name)) return
            visiting.add(name)
            graph[name].orEmpty().forEach(::visit)
            visiting.remove(name)
            ordered.add(selectedBundles.getValue(name))
        }
        selectedBundles.keys.forEach(::visit)
        return InstallPlan(ordered, solved.satisfied)
    }
}
