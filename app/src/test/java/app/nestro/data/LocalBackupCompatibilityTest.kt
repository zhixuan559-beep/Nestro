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
}
