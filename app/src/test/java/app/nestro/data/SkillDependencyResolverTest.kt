package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillDependencyResolverTest {
    private val signer = "f".repeat(64)

    private fun release(name: String, version: String = "1.0.0") = SignedSkillRelease(
        name, version, "", "https://example.org/$name-$version.json", "a".repeat(64), signer
    )

    private fun bundle(name: String, version: String = "1.0.0", vararg dependencies: SkillDependency) = SkillBundle(
        name, version, "", "prompt for $name", "已验证签名社区目录", emptyList(), dependencies.toList(), signer
    )

    private fun resolve(
        root: SignedSkillRelease,
        catalog: List<SignedSkillRelease>,
        packages: Map<String, SkillBundle>,
        installedVersions: Map<String, String> = emptyMap(),
        installedSkills: Map<String, String> = emptyMap(),
        installedMetadata: Map<String, String> = emptyMap(),
        disabledSkills: Set<String> = emptySet()
    ) = SkillDependencyResolver.resolve(
        root,
        catalog,
        installedVersions,
        packages.getValue("${root.name}@${root.version}"),
        installedSkills,
        installedMetadata,
        disabledSkills
    ) { release -> packages.getValue("${release.name}@${release.version}") }

    private fun key(name: String, version: String = "1.0.0") = "$name@$version"

    @Test
    fun downloadsMissingTransitiveDependenciesInDependencyFirstOrder() {
        val root = release("root")
        val middle = release("middle", "2.0.0")
        val leaf = release("leaf", "1.5.0")
        val packages = mapOf(
            key("root") to bundle("root", dependencies = arrayOf(SkillDependency("middle", "^2.0.0"))),
            key("middle", "2.0.0") to bundle("middle", "2.0.0", SkillDependency("leaf", ">=1.0.0")),
            key("leaf", "1.5.0") to bundle("leaf", "1.5.0")
        )
        val plan = resolve(root, listOf(root, middle, leaf), packages)
        assertEquals(listOf("leaf", "middle", "root"), plan.orderedBundles.map { it.name })
    }

    @Test
    fun backtracksFromNewestDependencyWhenItsTransitiveRangeConflicts() {
        val root = release("root")
        val engine2 = release("engine", "2.0.0")
        val engine1 = release("engine", "1.0.0")
        val runtime2 = release("runtime", "2.0.0")
        val runtime1 = release("runtime", "1.0.0")
        val packages = mapOf(
            key("root") to bundle(
                "root",
                dependencies = arrayOf(SkillDependency("engine", "*"), SkillDependency("runtime", "^1.0.0"))
            ),
            key("engine", "2.0.0") to bundle("engine", "2.0.0", SkillDependency("runtime", "^2.0.0")),
            key("engine", "1.0.0") to bundle("engine", "1.0.0", SkillDependency("runtime", "^1.0.0")),
            key("runtime", "2.0.0") to bundle("runtime", "2.0.0"),
            key("runtime", "1.0.0") to bundle("runtime", "1.0.0")
        )
        val plan = resolve(root, listOf(root, engine2, engine1, runtime2, runtime1), packages)
        assertEquals(listOf("runtime", "engine", "root"), plan.orderedBundles.map { it.name })
        assertEquals("1.0.0", plan.orderedBundles.first { it.name == "engine" }.version)
    }

    @Test
    fun rejectsDisabledOrTransitiveBrokenInstalledDependencies() {
        val root = release("root")
        val base = bundle("base", "1.2.0")
        val packages = mapOf(
            key("root") to bundle("root", dependencies = arrayOf(SkillDependency("base", "^1.0.0")))
        )
        val installedSkills = mapOf("base" to "base prompt")
        val installedMetadata = mapOf("base" to SkillBundleParser.metadataJson(base))
        assertFalse(
            runCatching {
                resolve(root, listOf(root), packages, mapOf("base" to "1.2.0"), installedSkills, installedMetadata, setOf("base"))
            }.isSuccess
        )

        val brokenMetadata = mapOf(
            "base" to """{"version":"1.2.0","dependencies":[{"name":"missing","version":"*"}]}"""
        )
        assertFalse(
            runCatching {
                resolve(root, listOf(root), packages, mapOf("base" to "1.2.0"), installedSkills, brokenMetadata)
            }.isSuccess
        )
    }

    @Test
    fun refusesToOverwriteLocalSkillWhenACommunityDependencyUpgradeIsNeeded() {
        val root = release("root")
        val dependency = release("base", "2.0.0")
        val packages = mapOf(
            key("root") to bundle("root", dependencies = arrayOf(SkillDependency("base", "^2.0.0"))),
            key("base", "2.0.0") to bundle("base", "2.0.0")
        )
        val local = SkillBundle("base", "1.0.0", "", "local prompt", "本地文件", emptyList(), emptyList())
        assertFalse(
            runCatching {
                resolve(
                    root,
                    listOf(root, dependency),
                    packages,
                    mapOf("base" to "1.0.0"),
                    mapOf("base" to "local prompt"),
                    mapOf("base" to SkillBundleParser.metadataJson(local))
                )
            }.isSuccess
        )
    }

    @Test
    fun rejectsConflictingDependencyConstraintsAndAlteredRootRelease() {
        val root = release("root")
        val left = release("left")
        val right = release("right")
        val shared1 = release("shared", "1.5.0")
        val shared2 = release("shared", "2.1.0")
        val packages = mapOf(
            key("root") to bundle("root", dependencies = arrayOf(SkillDependency("left", "*"), SkillDependency("right", "*"))),
            key("left") to bundle("left", dependencies = arrayOf(SkillDependency("shared", "^1.0.0"))),
            key("right") to bundle("right", dependencies = arrayOf(SkillDependency("shared", "^2.0.0"))),
            key("shared", "1.5.0") to bundle("shared", "1.5.0"),
            key("shared", "2.1.0") to bundle("shared", "2.1.0")
        )
        assertFalse(runCatching { resolve(root, listOf(root, left, right, shared1, shared2), packages) }.isSuccess)
        assertFalse(
            runCatching {
                resolve(root.copy(bundleUrl = "https://attacker.example/altered.json"), listOf(root), packages)
            }.isSuccess
        )
    }

    @Test
    fun reusesInstalledEnabledDependencyWhenItsCompleteClosureIsValid() {
        val root = release("root")
        val packages = mapOf(
            key("root") to bundle("root", dependencies = arrayOf(SkillDependency("base", "^1.0.0")))
        )
        val base = SkillBundle("base", "1.2.0", "", "base prompt", "本地文件", emptyList(), emptyList())
        val plan = resolve(
            root,
            listOf(root),
            packages,
            mapOf("base" to "1.2.0"),
            mapOf("base" to "base prompt"),
            mapOf("base" to SkillBundleParser.metadataJson(base))
        )
        assertEquals(listOf("root"), plan.orderedBundles.map { it.name })
        assertEquals("1.2.0", plan.alreadySatisfied["base"])
    }
}
