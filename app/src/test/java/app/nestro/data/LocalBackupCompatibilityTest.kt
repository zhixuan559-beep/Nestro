package app.nestro.data

import org.json.JSONObject
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

    @Test fun canonicalJsonIgnoresObjectKeyOrderButPreservesArrayOrder() {
        val first = org.json.JSONObject("""{"z":1,"nested":{"b":2,"a":1},"items":[3,2]}""")
        val reordered = org.json.JSONObject("""{"items":[3,2],"nested":{"a":1,"b":2},"z":1}""")
        val changedArray = org.json.JSONObject("""{"items":[2,3],"nested":{"a":1,"b":2},"z":1}""")
        org.junit.Assert.assertEquals(LocalBackupCompatibility.canonicalJson(first), LocalBackupCompatibility.canonicalJson(reordered))
        org.junit.Assert.assertNotEquals(LocalBackupCompatibility.canonicalJson(first), LocalBackupCompatibility.canonicalJson(changedArray))
    }

    @Test fun checksumChangesWhenBackupPayloadChanges() {
        val original = LocalBackupCompatibility.sha256(LocalBackupCompatibility.canonicalJson(org.json.JSONObject("""{"messages":[]}""")))
        val modified = LocalBackupCompatibility.sha256(LocalBackupCompatibility.canonicalJson(org.json.JSONObject("""{"messages":[{"id":"m1"}]}""")))
        org.junit.Assert.assertNotEquals(original, modified)
    }

    @Test fun verifiesBackupChecksumAndRejectsTampering() {
        val payload = org.json.JSONObject("""{"version":10,"messages":[]}""")
        val checksum = LocalBackupCompatibility.sha256(LocalBackupCompatibility.canonicalJson(payload))
        val backup = org.json.JSONObject(payload.toString()).put("checksum_sha256", checksum)
        org.junit.Assert.assertTrue(LocalBackupCompatibility.verifyChecksum(backup))
        backup.put("messages", org.json.JSONArray().put(org.json.JSONObject().put("id", "tampered")))
        org.junit.Assert.assertFalse(LocalBackupCompatibility.verifyChecksum(backup))
    }

    @Test fun acceptsLegacyBackupWithoutChecksum() {
        org.junit.Assert.assertTrue(LocalBackupCompatibility.verifyChecksum(org.json.JSONObject("""{"version":9}""")))
    }
}
