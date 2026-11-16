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

    @Test fun sha256UsesStableHexEncoding() {
        org.junit.Assert.assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            LocalBackupCompatibility.sha256("abc")
        )
    }
}
