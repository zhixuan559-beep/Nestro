package app.nestro.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SkillCommunityInstallPlannerTest {
    private fun release(name: String, version: String = "1.0.0") =
        SignedSkillRelease(name, version, "", "https://example.org/$name-$version.json", "a".repeat(64))

    private fun bundle(name: String, version: String = "1.0.0", vararg deps: SkillDependency) =
        SkillBundle(name, version, "", "prompt", SkillInstallPolicy.VERIFIED_COMMUNITY_SOURCE, emptyList(), deps.toList())

    private fun metadata(version: String) = JSONObject().put("version", version)
        .put("source", SkillInstallPolicy.VERIFIED_COMMUNITY_SOURCE).toString()

    @Test
    fun downloadsMissingTransitiveDependenciesInDependencyFirstOrder() {
        val root = release("根技能")
        val middle = release("中间技能", "2.0.0")
        val leaf = release("底层技能", "1.5.0")
        val catalog = listOf(root, middle, leaf)
        val packages = mapOf(
            root.name to bundle(root.name, root.version, SkillDependency(middle.name, "^2.0.0")),
            middle.name to bundle(middle.name, middle.version, SkillDependency(leaf.name, ">=1.0.0")),
            leaf.name to bundle(leaf.name, leaf.version)
        )
        val plan = SkillCommunityInstallPlanner.createPlan(root, catalog, emptyMap(), emptyMap()) {
            packages.getValue(it.name)
        }
        assertEquals(listOf("底层技能", "中间技能", "根技能"), plan.bundles.map { it.name })
    }

    @Test
    fun usesInstalledEnabledDependencyWhenVersionMatches() {
        val root = release("根技能")
        val dep = release("已安装依赖", "2.0.0")
        var downloads = 0
        val plan = SkillCommunityInstallPlanner.createPlan(
            root, listOf(root, dep),
            mapOf("已安装依赖" to "prompt"),
            mapOf("已安装依赖" to metadata("2.1.0"))
        ) {
            downloads++
            if (it.name == root.name) bundle(it.name, it.version, SkillDependency("已安装依赖", "^2.0.0")) else bundle(it.name, it.version)
        }
        assertEquals(listOf("根技能"), plan.bundles.map { it.name })
        assertEquals(1, downloads)
    }

    @Test
    fun rejectsCyclesMissingVersionsAndDisabledDependencies() {
        val root = release("根技能")
        val dep = release("依赖")
        val cyclicPackages = mapOf(
            root.name to bundle(root.name, root.version, SkillDependency(dep.name, "*")),
            dep.name to bundle(dep.name, dep.version, SkillDependency(root.name, "*"))
        )
        assertThrows(IllegalStateException::class.java) {
            SkillCommunityInstallPlanner.createPlan(root, listOf(root, dep), emptyMap(), emptyMap()) {
                cyclicPackages.getValue(it.name)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SkillCommunityInstallPlanner.createPlan(root, listOf(root), emptyMap(), emptyMap()) {
                bundle(root.name, root.version, SkillDependency("不存在", "*"))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SkillCommunityInstallPlanner.createPlan(root, listOf(root, dep),
                mapOf("依赖" to "prompt"), mapOf("依赖" to metadata("1.0.0")), setOf("依赖")) {
                if (it.name == root.name) bundle(it.name, it.version, SkillDependency("依赖", "*")) else bundle(it.name, it.version)
            }
        }
    }

    @Test
    fun rejectsLocalSkillOverwriteAndConflictingDependencyConstraints() {
        val root = release("根技能")
        val dep1 = release("共享依赖", "1.5.0")
        val dep2 = release("共享依赖", "2.1.0")
        val localConflict = assertThrows(IllegalArgumentException::class.java) {
            SkillCommunityInstallPlanner.createPlan(
                root, listOf(root),
                mapOf(root.name to "local prompt"),
                mapOf(root.name to JSONObject().put("version", "1.0.0").put("source", "本地文件").toString())
            ) { bundle(it.name, it.version) }
        }
        assertTrue(localConflict.message.orEmpty().contains("不得覆盖"))
        val packages = mapOf(
            root.name to bundle(root.name, root.version,
                SkillDependency("父级甲", "*"), SkillDependency("父级乙", "*")),
            "父级甲" to bundle("父级甲", "1.0.0", SkillDependency("共享依赖", "^1.0.0")),
            "父级乙" to bundle("父级乙", "1.0.0", SkillDependency("共享依赖", "^2.0.0")),
            "共享依赖" to bundle("共享依赖", "1.5.0")
        )
        assertThrows(IllegalArgumentException::class.java) {
            SkillCommunityInstallPlanner.createPlan(root, listOf(root, dep1, dep2, release("父级甲"), release("父级乙")), emptyMap(), emptyMap()) {
                packages.getValue(it.name)
            }
        }
    }

    @Test
    fun rejectsInstalledDependencyWhenItsTransitiveClosureIsBroken() {
        val root = release("根技能")
        val installed = mapOf("已安装中间技能" to "middle prompt")
        val metadata = mapOf(
            "已安装中间技能" to JSONObject().put("version", "1.2.0").put(
                "dependencies", org.json.JSONArray().put(
                    JSONObject().put("name", "缺失底层技能").put("version", "*")
                )
            ).toString()
        )
        assertThrows(IllegalArgumentException::class.java) {
            SkillCommunityInstallPlanner.createPlan(
                root, listOf(root, release("已安装中间技能")), installed, metadata
            ) {
                if (it.name == root.name) bundle(root.name, root.version, SkillDependency("已安装中间技能", "^1.0.0"))
                else bundle(it.name, it.version)
            }
        }
    }

    @Test
    fun rejectsCommunityUpgradeThatBreaksAnInstalledDependent() {
        val target = release("目标技能", "2.0.0")
        val installed = mapOf("目标技能" to "old prompt", "下游技能" to "child prompt")
        val metadata = mapOf(
            "目标技能" to metadata("1.0.0"),
            "下游技能" to JSONObject().put("version", "1.0.0").put(
                "dependencies", org.json.JSONArray().put(
                    JSONObject().put("name", "目标技能").put("version", "^1.0.0")
                )
            ).toString()
        )
        assertThrows(IllegalArgumentException::class.java) {
            SkillCommunityInstallPlanner.createPlan(target, listOf(target), installed, metadata) {
                bundle(it.name, it.version)
            }
        }
    }

}
