package app.nestro.ui

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpertTeamPauseControllerTest {
    @Test
    fun pausedExecutionWaitsUntilTheUserResumes() = runBlocking {
        val controller = ExpertTeamPauseController()
        assertTrue(controller.pause())
        assertFalse(controller.pause())

        var resumed = false
        val waiting = launch {
            controller.awaitResume()
            resumed = true
        }

        yield()
        assertTrue(controller.isPaused())
        assertFalse(resumed)

        assertTrue(controller.resume())
        withTimeout(1_000) { waiting.join() }

        assertTrue(resumed)
        assertFalse(controller.isPaused())
    }

    @Test
    fun resumeWithoutPauseDoesNotCreateAStateChange() {
        val controller = ExpertTeamPauseController()
        assertFalse(controller.isPaused())
        assertFalse(controller.resume())
        assertFalse(controller.isPaused())
    }
}
