package app.nestro.data

data class SkillCommunityInstallPlan(
    /** Dependency-first order; the requested root skill is always last. */
    val bundles: List<SkillBundle>
)

/**
 * Resolves and verifies the complete dependency closure of a signed community release.
 * Network access is injected so the resolver can be tested without a live catalog.
 */
object SkillCommunityInstallPlanner {
    fun createPlan(
        rootRelease: SignedSkillRelease,
        catalog: List<SignedSkillRelease>,
        installedSkills: Map<String, String>,
        installedMetadata: Map<String, String>,
        disabledSkills: Set<String> = emptySet(),
        loadVerifiedBundle: (SignedSkillRelease) -> SkillBundle
    ): SkillCommunityInstallPlan {
        require(catalog.size <= 200) { "社区目录条目超过 200 项" }
        val catalogByName = catalog.groupBy { it.name }
        val selected = linkedMapOf<String, SkillBundle>()
        val visiting = linkedSetOf<String>()
        val ordered = mutableListOf<SkillBundle>()

        fun installedVersion(name: String): String? {
            if (name !in installedSkills) return null
            val raw = installedMetadata[name]
            val metadata = raw?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
                ?: error("已安装技能“$name”的版本元数据损坏")
            return metadata.optString("version", "1.0.0").ifBlank { "1.0.0" }
        }

        fun ensureReplaceable(release: SignedSkillRelease) {
            if (release.name !in installedSkills) return
            val metadata = installedMetadata[release.name]?.let {
                runCatching { org.json.JSONObject(it) }.getOrNull()
            } ?: error("本机技能“${release.name}”元数据损坏，拒绝覆盖")
            require(metadata.optString("source") == SkillInstallPolicy.VERIFIED_COMMUNITY_SOURCE) {
                "本机已有同名本地技能“${release.name}”，社区包不得覆盖"
            }
            val current = metadata.optString("version")
            require(current.isNotBlank() && SkillCommunityClient.isNewerVersion(release.version, current)) {
                "技能“${release.name}”仅允许升级到严格高于本机的社区版本"
            }
        }

        fun chooseRelease(name: String, constraint: String): SignedSkillRelease {
            val candidates = catalogByName[name].orEmpty().filter {
                SkillDependencyResolver.versionSatisfies(it.version, constraint)
            }
            require(candidates.isNotEmpty()) { "社区目录没有满足依赖 $name@$constraint 的已签名版本" }
            return candidates.reduce { best, next ->
                if (SkillCommunityClient.isNewerVersion(next.version, best.version)) next else best
            }
        }

        fun visit(release: SignedSkillRelease, expectedConstraint: String?, isRoot: Boolean = false) {
            val name = release.name
            if (name in visiting) error("社区技能依赖形成循环：${(visiting + name).joinToString(" → ")}")
            val alreadySelected = selected[name]
            if (alreadySelected != null) {
                require(expectedConstraint == null || SkillDependencyResolver.versionSatisfies(alreadySelected.version, expectedConstraint)) {
                    "技能“$name”存在互相冲突的依赖版本约束"
                }
                return
            }
            require(selected.size < 25) { "单次安装依赖超过 25 个技能，已停止解析" }
            val installed = installedVersion(name)
            if (!isRoot && installed != null) {
                require(name !in disabledSkills) { "依赖技能“$name”已停用，请先在本地技能库启用" }
                if (expectedConstraint != null && SkillDependencyResolver.versionSatisfies(installed, expectedConstraint)) return
            }
            ensureReplaceable(release)
            if (!isRoot && name in installedSkills) {
                val meta = installedMetadata[name]?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
                require(meta?.optString("source") == SkillInstallPolicy.VERIFIED_COMMUNITY_SOURCE) {
                    "依赖“$name”需要升级，但本机存在同名本地技能，拒绝覆盖"
                }
            }

            visiting += name
            val bundle = loadVerifiedBundle(release)
            require(bundle.name == release.name && bundle.version == release.version) {
                "下载技能包与签名目录条目不一致：$name"
            }
            if (expectedConstraint != null) {
                require(SkillDependencyResolver.versionSatisfies(bundle.version, expectedConstraint)) {
                    "下载技能“$name”的版本不满足依赖约束 $expectedConstraint"
                }
            }
            bundle.dependencies.forEach { dependency ->
                val installedDependencyVersion = installedVersion(dependency.name)
                if (installedDependencyVersion != null && dependency.name !in disabledSkills &&
                    SkillDependencyResolver.versionSatisfies(installedDependencyVersion, dependency.version)) {
                    return@forEach
                }
                require(dependency.name !in disabledSkills) {
                    "依赖技能“${dependency.name}”已停用，请先在本地技能库启用"
                }
                val dependencyRelease = chooseRelease(dependency.name, dependency.version)
                visit(dependencyRelease, dependency.version)
            }
            visiting -= name
            selected[name] = bundle
            ordered += bundle
        }

        require(rootRelease in catalog || catalog.any {
            it.name == rootRelease.name && it.version == rootRelease.version && it.sha256 == rootRelease.sha256
        }) { "请求安装的技能不在当前已验签社区目录中" }
        visit(rootRelease, null, isRoot = true)
        return SkillCommunityInstallPlan(ordered.toList())
    }
}
