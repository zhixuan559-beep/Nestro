package app.nestro.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactStoreReferenceTest {
    private val indexed = ArtifactRecord(
        id = "123e4567-e89b-12d3-a456-426614174000",
        projectId = "project-a",
        title = "Draft",
        fileName = "123e4567-e89b-12d3-a456-426614174000.md",
        mimeType = "text/markdown",
        createdAt = 1L,
        sizeBytes = 12L,
        version = 1
    )

    @Test
    fun acceptsOnlyTheExactIndexedProjectReference() {
        assertTrue(artifactReferenceMatchesIndex(indexed, indexed))
    }

    @Test
    fun rejectsReferenceFromAnotherProjectEvenWhenFileIdMatches() {
        val forged = indexed.copy(projectId = "project-b")
        assertFalse(artifactReferenceMatchesIndex(forged, indexed))
    }

    @Test
    fun rejectsMismatchedFileNameAndId() {
        val forged = indexed.copy(fileName = "other.md")
        assertFalse(artifactReferenceMatchesIndex(forged, indexed))
    }

    @Test
    fun rejectsDifferentArtifactIds() {
        val forged = indexed.copy(id = "223e4567-e89b-12d3-a456-426614174000")
        assertFalse(artifactReferenceMatchesIndex(forged, indexed))
    }
}
