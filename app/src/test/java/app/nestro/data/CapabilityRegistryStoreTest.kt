package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CapabilityRegistryStoreTest {
    @Test fun descriptorRoundTripPreservesMetadataAndSafetyFlags() {
        val source = CapabilityDescriptor(
            id = "phone.agent", title = "Phone Agent", category = "device",
            description = "Controlled Android actions", version = "2.1",
            source = "builtin.phone", status = "available", detail = "Accessibility permission required",
            route = "phone", available = true, projectScoped = true,
            requiresConfirmation = true, defaultGranted = false
        )
        val decoded = CapabilityRegistryStore.decodeDescriptor(CapabilityRegistryStore.encodeDescriptor(source))
            ?: throw AssertionError("Descriptor should decode")
        assertEquals(source, decoded)
    }

    @Test fun descriptorRejectsInvalidIdsAndBlankNames() {
        assertNull(CapabilityRegistryStore.decodeDescriptor(org.json.JSONObject().put("id", "../unsafe").put("title", "Bad")))
        assertNull(CapabilityRegistryStore.decodeDescriptor(org.json.JSONObject().put("id", "valid-id").put("title", " ")))
    }

    @Test fun descriptorBoundsUntrustedMetadataLengths() {
        val decoded = CapabilityRegistryStore.decodeDescriptor(
            org.json.JSONObject().put("id", "safe").put("title", "T".repeat(200))
                .put("description", "D".repeat(800)).put("source", "S".repeat(120))
        ) ?: throw AssertionError("Descriptor should decode")
        assertEquals(80, decoded.title.length)
        assertEquals(500, decoded.description.length)
        assertEquals(80, decoded.source.length)
        assertFalse(decoded.defaultGranted)
    }
}
