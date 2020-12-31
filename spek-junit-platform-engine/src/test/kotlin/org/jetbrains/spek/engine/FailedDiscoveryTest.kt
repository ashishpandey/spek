package org.jetbrains.spek.engine

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.jetbrains.spek.engine.support.AbstractSpekTestEngineTest
import org.junit.jupiter.api.Test

/**
 * @author Ashish Pandey
 */
class FailedDiscoveryTest: AbstractSpekTestEngineTest() {
    @Test
    fun testIgnoreAbstractClass() {
        val recorder = executeForPackage("org.jetbrains.spek.engine.packageWithDiscoveryFailureSpek")
        assertThat(recorder.testStartedCount, equalTo(1))
        assertThat(recorder.testFailureCount, equalTo(1))
    }
}
