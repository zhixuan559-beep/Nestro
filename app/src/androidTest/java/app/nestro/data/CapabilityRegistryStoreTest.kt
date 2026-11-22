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
        assertTrue(CapabilityRegistryStore.recentToolAudit(preferences, "project-a").size >= 5)
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
}
