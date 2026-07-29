package com.runelitetablet.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentStatePublisherTest {
    @Test
    fun `publishes changes and suppresses timestamp only churn`() {
        var capturedAt = 1L
        var posture = "booting"
        val publisher = AgentStatePublisher(
            snapshotProvider = {
                fixture(capturedAt++, posture)
            }
        )
        val subscription = publisher.subscribe(null)
        val initial = subscription.next(50)
        assertNotNull(initial)
        assertTrue(initial!!.contains("event: state"))

        publisher.observeNow()
        assertEquals(null, subscription.next(10))

        posture = "idle"
        publisher.observeNow()
        val changed = subscription.next(50)
        assertNotNull(changed)
        assertTrue(changed!!.contains(""""posture":"idle""""))
        subscription.close()
    }

    @Test
    fun `generation mismatch produces reset snapshot`() {
        val publisher = AgentStatePublisher(snapshotProvider = { fixture(1, "idle") })
        val subscription = publisher.subscribe("old-generation:4")

        val event = subscription.next(50)

        assertNotNull(event)
        assertTrue(event!!.contains("event: reset"))
        assertTrue(event.contains("generation_changed"))
        subscription.close()
    }

    private fun fixture(capturedAt: Long, posture: String) =
        AgentStateSnapshotV1(
            capturedAtEpochMs = capturedAt,
            ui = AgentUiStateV1(
                activity = "MainActivity",
                lifecycle = "resumed",
                screen = "launch",
                sentinel = "launch_screen"
            ),
            setup = AgentSetupStateV1(),
            session = AgentSessionStateV1("idle"),
            render = AgentRenderStateV1(
                x11BinderAlive = false,
                x11Generation = 0,
                x11LastEvent = "idle"
            ),
            stateMachine = AgentStateMachineV1(
                posture = posture,
                reasons = listOf(posture),
                observationReady = true,
                blockers = emptyList()
            )
        )
}
