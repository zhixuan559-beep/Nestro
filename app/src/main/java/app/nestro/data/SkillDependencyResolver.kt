package app.nestro.data

/** Resolves a complete signed dependency graph before any local mutation. */
object SkillDependencyResolver {
    data class InstallPlan(val orderedBundles: List<SkillBundle>, val alreadySatisfied: Map<String, String>) {
        val root: SkillBundle get() = orderedBundles.last()
    }

    fun resolve(rootRelease: SignedSkillRelease, catalog: List<SignedSkillRelease>, installedVersions: Map<String, String>): InstallPlan {
        val byName = catalog.groupBy { it.name }
        val resolved = linkedMapOf<String, SkillBundle>()
        val satisfied = linkedMapOf<String, String>()
        val visiting = linkedSetOf<String>()
        val visited = mutableSetOf<String>()

        fun select(name: String, range: String): SignedSkillRelease {
            val installed = installedVersions[name]
            if (installed != null && runCatching { SkillSemVer.matches(installed, range) }.getOrDefault(false)) {
                satisfied[name] = installed
                return byName[name]?.firstOrNull() ?: SignedSkillRelease(name, installed, "已安装依赖", "", "0".repeat(64))
            }
            return byName[name].orEmpty().filter { runCatching { SkillSemVer.matches(it.version, range) }.getOrDefault(false) }
                .maxWithOrNull { a, b -> SkillSemVer.compare(a.version, b.version) }
                ?: error("社区目录缺少依赖：$name $range")
        }

        fun visit(release: SignedSkillRelease) {
            if (release.name in visited) {
                val existing = resolved[release.name]
                require(existing == null || existing.version == release.version) { "依赖图为 ${release.name} 选择了冲突版本" }
                return
            }
            require(visiting.add(release.name)) { "社区技能依赖存在循环引用：${(visiting + release.name).joinToString(" → ")}" }
            val bundle = SkillCommunityClient.downloadVerifiedBundle(release)
            require(bundle.name == release.name && bundle.version == release.version) { "下载包与签名目录版本不一致" }
            bundle.dependencies.forEach { dependency ->
                val installed = installedVersions[dependency.name]
                if (installed != null && runCatching { SkillSemVer.matches(installed, dependency.version) }.getOrDefault(false)) {
                    satisfied[dependency.name] = installed
                } else visit(select(dependency.name, dependency.version))
            }
            visiting.remove(release.name)
            visited.add(release.name)
            resolved[release.name] = bundle
            require(resolved.size <= 50) { "依赖安装计划超过 50 个技能" }
        }

        visit(rootRelease)
        return InstallPlan(resolved.values.toList(), satisfied)
    }
}
