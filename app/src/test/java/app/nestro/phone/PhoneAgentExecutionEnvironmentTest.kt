package app.nestro.phone

import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PhoneAgentExecutionEnvironmentTest {
    private class FakeEnvironment : PhoneAgentExecutionEnvironment {
        override fun hasValidGrant() = true
        override fun isTaskStopped() = false
        override fun requestTaskStop() = Unit
        override fun captureVisibleText() = "isolated screen"
        override fun clickVisibleText(exactText: String) = true
        override fun setTextOnVisibleField(label: String, value: String) = true
        override fun scroll(direction: String) = true
        override fun openUrl(rawUrl: String) = true
        override fun launchPackage(packageName: String) = true
        override fun pressBack() = true
        override fun verifyVisibleText(expected: String) = true
        override fun activePackageName(): String? = "test.package"
        override fun screenTextChanged(before: String) = true
    }

    @After
    fun tearDown() {
        PhoneAgentExecutionEnvironmentProvider.active = null
    }

    @Test
    fun selectedEnvironmentBecomesTheOnlyActiveExecutionTarget() {
        val selected = FakeEnvironment()

        PhoneAgentExecutionEnvironmentProvider.attach(selected)

        assertSame(selected, PhoneAgentExecutionEnvironmentProvider.active)
    }

    @Test
    fun detachingSelectedEnvironmentLeavesNoImplicitFallback() {
        val selected = FakeEnvironment()
        PhoneAgentExecutionEnvironmentProvider.attach(selected)

        PhoneAgentExecutionEnvironmentProvider.detach(selected)

        assertNull(PhoneAgentExecutionEnvironmentProvider.active)
    }

    @Test
    fun staleEnvironmentCannotDetachAReplacement() {
        val previous = FakeEnvironment()
        val selected = FakeEnvironment()
        PhoneAgentExecutionEnvironmentProvider.attach(previous)
        PhoneAgentExecutionEnvironmentProvider.attach(selected)

        PhoneAgentExecutionEnvironmentProvider.detach(previous)

        assertSame(selected, PhoneAgentExecutionEnvironmentProvider.active)
    }
}
