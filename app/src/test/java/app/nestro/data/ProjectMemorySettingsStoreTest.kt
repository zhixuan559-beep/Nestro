package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ProjectMemorySettingsStoreTest {
    @Test fun projectMemorySettingsRoundTripWithoutEnablingOtherProjects() {
        val encoded = ProjectMemorySettingsStore.encode(
            mapOf("project-a" to true, "project-b" to false)
        )
        val decoded = ProjectMemorySettingsStore.decode(encoded)
        assertEquals(true, decoded["project-a"])
        assertEquals(false, decoded["project-b"])
        assertFalse(decoded["default"] ?: false)
    }

    @Test fun malformedIdsAndNonBooleanSettingsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ProjectMemorySettingsStore.decode("""{"":true}""")
        }
        assertThrows(IllegalStateException::class.java) {
            ProjectMemorySettingsStore.decode("""{"project-a":"true"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectMemorySettingsStore.encode(mapOf("" to true))
        }
    }

    @Test fun settingsAreBoundedToTheProjectLimit() {
        val tooMany = (1..101).associate { "project-$it" to true }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectMemorySettingsStore.encode(tooMany)
        }
    }
}
