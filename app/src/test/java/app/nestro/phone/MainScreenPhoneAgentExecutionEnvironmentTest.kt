package app.nestro.phone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenPhoneAgentExecutionEnvironmentTest {
    @Test
    fun openableUrlAcceptsHttpAndHttpsWithHost() {
        assertTrue(MainScreenPhoneAgentExecutionEnvironment.isOpenableUrl("https://example.com/a?b=1"))
        assertTrue(MainScreenPhoneAgentExecutionEnvironment.isOpenableUrl("http://192.168.1.2:8080/x"))
    }

    @Test
    fun openableUrlRejectsNonWebSchemesAndHostlessUrls() {
        assertFalse(MainScreenPhoneAgentExecutionEnvironment.isOpenableUrl("intent://example.com/open"))
        assertFalse(MainScreenPhoneAgentExecutionEnvironment.isOpenableUrl("https://"))
        assertFalse(MainScreenPhoneAgentExecutionEnvironment.isOpenableUrl("example.com/path"))
        assertFalse(MainScreenPhoneAgentExecutionEnvironment.isOpenableUrl(""))
    }
}
