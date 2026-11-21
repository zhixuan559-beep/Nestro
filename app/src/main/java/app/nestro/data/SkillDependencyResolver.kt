package app.nestro.data

/** Backtracking resolver: intersects all ranges for a dependency before selecting one signed release. */
object SkillDependencyResolver {
    data class InstallPlan(val orderedBundles: List<SkillBundle>, val alreadySatisfied: Map<String, String>) {
        val root: SkillBundle get() = orderedBundles.last()
    }

    private data class State(
        val constraints: Map<String, List<String>>,
        val selected: Map<String, SignedSkillRelease>,
        val satisfied: Map<String, String>
    )

    fun resolve(rootRelease: SignedSkillRelease, catalog: List<SignedSkillRelease>, installedVersions: Map<String, String>): InstallPlan {
        val byName = catalog.groupBy { it.name }
        var attempts = 0
        val rootBundle = SkillCommunityClient.downloadVerifiedBundle(rootRelease)
        require(rootBundle.name == rootRelease.name && rootBundle.version == rootRelease.version) { "根技能与签名目录不一致" }
        val initialConstraints = rootBundle.dependencies.groupBy { it.name }.mapValues { (_, rows) -> rows.map { it.version }.distinct() }
        val initial = State(initialConstraints, mapOf(rootRelease.name to rootRelease), emptyMap())

        fun search(state: State, depth: Int): State? {
            if (depth > 50) return null
            val unresolved = state.constraints.keys.firstOrNull { it !in state.selected && it !in state.satisfied }
                ?: return state
            val ranges = state.constraints[unresolved].orEmpty()
            val installed = installedVersions[unresolved]
            if (installed != null && ranges.all { runCatching { SkillSemVer.matches(installed, it) }.getOrDefault(false) }) {
                return search(state.copy(satisfied = state.satisfied + (unresolved to installed)), depth + 1)
            }
            val candidates = byName[unresolved].orEmpty()
                .filter { release -> ranges.all { runCatching { SkillSemVer.matches(release.version, it) }.getOrDefault(false) } }
                .sortedWith { a, b -> SkillSemVer.compare(b.version, a.version) }
            for (candidate in candidates) {
                attempts++
                require(attempts <= 100) { "依赖冲突求解超过 100 次尝试，已停止以避免无限下载" }
                val bundle = runCatching { SkillCommunityClient.downloadVerifiedBundle(candidate) }.getOrNull() ?: continue
                if (bundle.name != candidate.name || bundle.version != candidate.version) continue
                val nextConstraints = state.constraints.mapValues { it.value.toMutableList() }.toMutableMap()
                bundle.dependencies.forEach { dep ->
                    val current = nextConstraints[dep.name].orEmpty().toMutableList()
                    current.add(dep.version)
                    nextConstraints[dep.name] = current.distinct()
                }
                val next = State(nextConstraints, state.selected + (unresolved to candidate), state.satisfied)
                val solved = search(next, depth + 1)
                if (solved != null) return solved
            }
            return null
        }

        val solved = search(initial, 0) ?: error("社区目录无法同时满足全部依赖版本约束；未修改本地技能")
        val selectedBundles = solved.selected.mapValues { (name, release) ->
            if (name == rootRelease.name) rootBundle else SkillCommunityClient.downloadVerifiedBundle(release)
        }
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
