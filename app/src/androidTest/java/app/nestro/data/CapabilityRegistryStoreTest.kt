package app.nestro.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CapabilityRegistryStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences = context.getSharedPreferences("capability_registry_test", Context.MODE_PRIVATE)

    @After
    fun cleanUp() {
        preferences.edit().clear().commit()
    }

    private fun descriptor(defaultGranted: Boolean = false) = CapabilityDescriptor(
        id = "knowledge.search",
        title = "知识库检索",
        category = "read_only",
        description = "读取当前项目知识库",
        defaultGranted = defaultGranted
    )

    @Test
    fun projectGrantAndAuditArePersistedTogether() {
        preferences.edit().clear().commit()
        CapabilityRegistryStore.registerAll(preferences, listOf(descriptor()))

        assertFalse(CapabilityRegistryStore.authorize(preferences, "project-a", "knowledge.search"))
        CapabilityRegistryStore.setGranted(preferences, "project-a", "knowledge.search", true)
        assertTrue(CapabilityRegistryStore.authorize(preferences, "project-a", "knowledge.search"))
        assertFalse(CapabilityRegistryStore.authorize(preferences, "project-b", "knowledge.search"))
        CapabilityRegistryStore.setGranted(preferences, "project-a", "knowledge.search", false)
        assertFalse(CapabilityRegistryStore.authorize(preferences, "project-a", "knowledge.search"))

        val audit = CapabilityRegistryStore.recentAudit(preferences, "project-a", 20)
        assertTrue(audit.any { it.action == "grant" && it.outcome == "saved" })
        assertTrue(audit.any { it.action == "revoke" && it.outcome == "saved" })
        assertTrue(audit.any { it.action == "invoke" && it.outcome == "authorized" })
        assertTrue(audit.any { it.action == "invoke" && it.outcome == "denied" })
    }

    @Test
    fun capabilityCatalogPreservesPresentationAndNavigationMetadata() {
        preferences.edit().clear().commit()
        val item = descriptor().copy(
            status = "已配置",
            detail = "模型服务已连接",
            route = "models"
        )
        CapabilityRegistryStore.registerAll(preferences, listOf(item))

        val restored = CapabilityRegistryStore.find(preferences, item.id)
        assertEquals("已配置", restored?.status)
        assertEquals("模型服务已连接", restored?.detail)
        assertEquals("models", restored?.route)
    }

    @Test
    fun malformedGrantStorageFailsClosedEvenForDefaultGrantedCapabilities() {
        preferences.edit().clear().commit()
        CapabilityRegistryStore.registerAll(preferences, listOf(descriptor(defaultGranted = true)))
        preferences.edit().putString("capability_grants_v1", "{invalid").commit()

        assertFalse(CapabilityRegistryStore.authorize(preferences, "project-a", "knowledge.search"))
    }

    @Test
    fun malformedRegistryFailsClosedWithoutBeingSilentlyOverwritten() {
        preferences.edit().clear().putString("capability_registry_v1", "{invalid").commit()

        CapabilityRegistryStore.registerIfAbsent(preferences, descriptor(defaultGranted = true))

        assertTrue(CapabilityRegistryStore.list(preferences).isEmpty())
        assertFalse(CapabilityRegistryStore.authorize(preferences, "project-a", "knowledge.search"))
        assertEquals("{invalid", preferences.getString("capability_registry_v1", null))
    }

    @Test
    fun unregisterAtomicallyRemovesProjectGrantAndRecordsRevocation() {
        preferences.edit().clear().commit()
        CapabilityRegistryStore.registerAll(preferences, listOf(descriptor()))
        CapabilityRegistryStore.setGranted(preferences, "project-a", "knowledge.search", true)

        CapabilityRegistryStore.unregister(preferences, "knowledge.search")

        assertEquals(null, CapabilityRegistryStore.find(preferences, "knowledge.search"))
        assertFalse(CapabilityRegistryStore.isGranted(preferences, "project-a", "knowledge.search"))
        assertTrue(CapabilityRegistryStore.recentAudit(preferences, "project-a").any {
            it.action == "revoke" && it.outcome == "capability_unregistered"
        })
    }

    @Test
    fun projectMcpGrantCanOverrideTheExistingGlobalGrant() {
        preferences.edit().clear().commit()

        assertTrue(
            CapabilityRegistryStore.authorizeToolCall(
                preferences, "project-a", "server-a", "search", defaultGranted = true
            )
        )
        CapabilityRegistryStore.setToolGranted(preferences, "project-a", "server-a", "search", false)
        assertFalse(
            CapabilityRegistryStore.authorizeToolCall(
                preferences, "project-a", "server-a", "search", defaultGranted = true
            )
        )
        CapabilityRegistryStore.setToolGranted(preferences, "project-a", "server-a", "search", true)
        assertTrue(
            CapabilityRegistryStore.authorizeToolCall(
                preferences, "project-a", "server-a", "search", defaultGranted = false
            )
        )
        assertFalse(
            CapabilityRegistryStore.authorizeToolCall(
                preferences, "project-a", "server-a", "search",
                defaultGranted = true, globallyEnabled = false
            )
        )
        assertTrue(CapabilityRegistryStore.recentToolAudit(preferences, "project-a").any {
            it.action == "invoke" && it.outcome == "denied"
        })
    }

    @Test
    fun registrySupportsMoreThanOneHundredCombinedBuiltinPluginAndMcpCapabilities() {
        preferences.edit().clear().commit()
        val items = (1..160).map { index ->
            CapabilityDescriptor(
                id = "capability.test.$index",
                title = "能力 $index",
                category = "test",
                description = "容量回归测试能力 $index"
            )
        }

        CapabilityRegistryStore.registerAll(preferences, items)

        assertEquals(160, CapabilityRegistryStore.list(preferences).size)
        // 160 个能力默认 defaultGranted=false，未授权时 authorize 必然返回 false；先显式授权再断言 authorize 通过。
        CapabilityRegistryStore.setGranted(preferences, "project-a", "capability.test.160", true)
        assertTrue(CapabilityRegistryStore.authorize(preferences, "project-a", "capability.test.160"))
    }

    @Test
    
    fun toolAuditNeverStoresSecretParameterNames() {
        assertEquals(
            listOf("query", "page_size"),
            CapabilityToolPolicy.safeParameterNames(
                listOf("query", "api_key", "authorization", "page_size", "password")
            )
        )
    }

    @Test
    fun descriptorRoundTripPreservesVersionSourceAndSafetyFlags() {
        val item = CapabilityDescriptor(
            id = "phone.agent",
            title = "Phone Agent",
            category = "device",
            description = "Controlled Android actions",
            version = "2.1",
            source = "builtin.phone",
            status = "available",
            detail = "Accessibility permission required",
            route = "phone",
            available = true,
            projectScoped = true,
            requiresConfirmation = true,
            defaultGranted = false
        )
        CapabilityRegistryStore.registerAll(preferences, listOf(item))
        assertEquals(item, CapabilityRegistryStore.find(preferences, item.id))
    }

    @Test
    fun registryRejectsInvalidIdsAndBlankNames() {
        val invalidId = CapabilityDescriptor(
            id = "../unsafe", title = "Bad", category = "test", description = "test"
        )
        val blankTitle = CapabilityDescriptor(
            id = "valid-id", title = " ", category = "test", description = "test"
        )
        assertTrue(runCatching { CapabilityRegistryStore.registerAll(preferences, listOf(invalidId)) }.isFailure)
        assertTrue(runCatching { CapabilityRegistryStore.registerAll(preferences, listOf(blankTitle)) }.isFailure)
    }

    @Test
    fun decoderBoundsUntrustedMetadataLengthsAndDefaultsToDenied() {
        val row = org.json.JSONObject()
            .put("id", "safe")
            .put("title", "T".repeat(200))
            .put("description", "D".repeat(800))
            .put("source", "S".repeat(120))
        assertTrue(preferences.edit().putString("capability_registry_v1", org.json.JSONArray().put(row).toString()).commit())

        val decoded = CapabilityRegistryStore.list(preferences).single()
        assertEquals(80, decoded.title.length)
        assertEquals(500, decoded.description.length)
        assertEquals(80, decoded.source.length)
        assertFalse(decoded.defaultGranted)
    }

    @Test
    fun toolGrantKeysAreScopedAndRejectUnsafeIdentifiers() {
        assertEquals("server.one::search", CapabilityToolPolicy.grantKey("server.one", "search"))
        assertTrue(
            CapabilityToolPolicy.grantKey("server.one", "search") !=
                CapabilityToolPolicy.grantKey("server.two", "search")
        )
        assertTrue(runCatching { CapabilityToolPolicy.grantKey("../server", "search") }.isFailure)
        assertTrue(runCatching { CapabilityToolPolicy.grantKey("server", "bad tool") }.isFailure)
    }

}
