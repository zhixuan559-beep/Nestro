package app.nestro.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CapabilityRegistryStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences("capability_registry_integration_test", Context.MODE_PRIVATE)

    @After
    fun clearPreferences() {
        prefs.edit().clear().commit()
    }

    @Test
    fun descriptorRoundTripPreservesMetadataAndSafetyFlags() {
        val source = CapabilityDescriptor(
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

        CapabilityRegistryStore.registerAll(prefs, listOf(source))

        assertEquals(source, CapabilityRegistryStore.find(prefs, source.id))
        assertFalse(CapabilityRegistryStore.isGranted(prefs, "project-a", source.id))
        CapabilityRegistryStore.setGranted(prefs, "project-a", source.id, true)
        assertTrue(CapabilityRegistryStore.isGranted(prefs, "project-a", source.id))
        assertFalse(CapabilityRegistryStore.isGranted(prefs, "project-b", source.id))
    }

    @Test
    fun registryRejectsInvalidIdsAndBlankNames() {
        val invalidId = CapabilityDescriptor(
            id = "../unsafe", title = "Bad", category = "test", description = "test"
        )
        val blankTitle = CapabilityDescriptor(
            id = "valid-id", title = " ", category = "test", description = "test"
        )

        assertTrue(runCatching { CapabilityRegistryStore.registerAll(prefs, listOf(invalidId)) }.isFailure)
        assertTrue(runCatching { CapabilityRegistryStore.registerAll(prefs, listOf(blankTitle)) }.isFailure)
    }

    @Test
    fun decoderBoundsUntrustedMetadataLengthsAndDefaultsToDenied() {
        val row = JSONObject()
            .put("id", "safe")
            .put("title", "T".repeat(200))
            .put("description", "D".repeat(800))
            .put("source", "S".repeat(120))
        assertTrue(prefs.edit().putString("capability_registry_v1", JSONArray().put(row).toString()).commit())

        val decoded = CapabilityRegistryStore.list(prefs).single()
        assertEquals(80, decoded.title.length)
        assertEquals(500, decoded.description.length)
        assertEquals(80, decoded.source.length)
        assertFalse(decoded.defaultGranted)
    }

    @Test
    fun toolGrantKeysAreScopedAndUnsafeParameterNamesAreFiltered() {
        assertEquals("server.one::search", CapabilityToolPolicy.grantKey("server.one", "search"))
        assertTrue(
            CapabilityToolPolicy.grantKey("server.one", "search") !=
                CapabilityToolPolicy.grantKey("server.two", "search")
        )
        assertTrue(runCatching { CapabilityToolPolicy.grantKey("../server", "search") }.isFailure)
        assertTrue(runCatching { CapabilityToolPolicy.grantKey("server", "bad tool") }.isFailure)
        assertEquals(
            listOf("query", "limit"),
            CapabilityToolPolicy.safeParameterNames(
                listOf("query", "query", "limit", "secret value", "token")
            )
        )
    }
}
