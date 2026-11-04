package dev.anct.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LifecycleStateMachineTest {
    private val machine = LifecycleStateMachine()

    @Test
    fun `start and stop follow explicit transitions`() {
        val starting = machine.transition(ActualState.Stopped, LifecycleStateMachine.Operation.START)
        assertEquals(ActualState.Starting, starting)
        val running = machine.transition(starting, LifecycleStateMachine.Operation.START_SUCCEEDED)
        assertEquals(ActualState.Running(), running)
        val stopping = machine.transition(running, LifecycleStateMachine.Operation.STOP)
        assertEquals(ActualState.Stopping, stopping)
        assertEquals(
            ActualState.Stopped,
            machine.transition(stopping, LifecycleStateMachine.Operation.STOP_SUCCEEDED),
        )
    }

    @Test
    fun `duplicate start is rejected`() {
        assertFailsWith<IllegalStateException> {
            machine.transition(ActualState.Running(), LifecycleStateMachine.Operation.START)
        }
    }
}
