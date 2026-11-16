package app.nestro.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBackupCompatibilityTest {
    @Test fun acceptsEverySupportedBackupVersionIncludingCurrent() {
        for (version in 1..LocalBackupCompatibility.CURRENT_VERSION) {
            assertTrue("version $version should be supported", LocalBackupCompatibility.isSupported(version))
        }
    }

    @Test fun rejectsUnknownBackupVersions() {
        assertFalse(LocalBackupCompatibility.isSupported(0))
        assertFalse(LocalBackupCompatibility.isSupported(LocalBackupCompatibility.CURRENT_VERSION + 1))
    }

    @Test fun canonicalJsonSortsNestedObjectKeysButPreservesArrayOrder() {
        val first = JSONObject("""{"z":1,"nested":{"b":2,"a":1},"items":[{"y":2,"x":1}]}""")
        val second = JSONObject("""{"items":[{"x":1,"y":2}],"nested":{"a":1,"b":2},"z":1}""")
        org.junit.Assert.assertEquals(LocalBackupCompatibility.canonicalJson(first), LocalBackupCompatibility.canonicalJson(second))
    }

    @Test fun sha256UsesStableHexEncoding() {
        org.junit.Assert.assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            LocalBackupCompatibility.sha256("abc")
        )
    }
}
