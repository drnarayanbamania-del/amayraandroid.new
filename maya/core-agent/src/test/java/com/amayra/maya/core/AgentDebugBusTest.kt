package com.amayra.maya.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AgentDebugBus: live tool/turn introspection for the debug panel.
 * Pins the observable contract: running → finished arc, redaction, bounding.
 */
class AgentDebugBusTest {

    @Test
    fun `tool lifecycle running then success clears current tool`() {
        AgentDebugBus.clear()
        val id = AgentDebugBus.toolStarted("open_app", """{"app_name":"Settings"}""")
        assertEquals("running", AgentDebugBus.currentTool.value?.outcome)
        assertEquals("open_app", AgentDebugBus.currentTool.value?.tool)
        AgentDebugBus.toolFinished(id, "success", "Opened Settings.", 42)
        assertNull(AgentDebugBus.currentTool.value)
        val ev = AgentDebugBus.toolEvents.value.first()
        assertEquals("success", ev.outcome)
        assertEquals("Opened Settings.", ev.summary)
        assertEquals(42, ev.durationMs)
    }

    @Test
    fun `failed finish keeps outcome and summary`() {
        AgentDebugBus.clear()
        val id = AgentDebugBus.toolStarted("click_element", """{"text":"Search settings"}""")
        AgentDebugBus.toolFinished(id, "failure", "Element nahi mila", 15)
        val ev = AgentDebugBus.toolEvents.value.first()
        assertEquals("failure", ev.outcome)
        assertEquals("Element nahi mila", ev.summary)
        assertNull(AgentDebugBus.currentTool.value)
    }

    @Test
    fun `rejected tool lands as failure without running phase`() {
        AgentDebugBus.clear()
        AgentDebugBus.toolRejected("no_such_tool", "{}", "Unknown tool: no_such_tool")
        assertNull(AgentDebugBus.currentTool.value)
        val ev = AgentDebugBus.toolEvents.value.first()
        assertEquals("no_such_tool", ev.tool)
        assertEquals("failure", ev.outcome)
        assertTrue(ev.summary.contains("Unknown tool"))
    }

    @Test
    fun `args and summaries are redacted before storage`() {
        AgentDebugBus.clear()
        AgentDebugBus.toolRejected("t", """{"api_key":"sk-ABCDEF1234567890"}""", "boom")
        val ev = AgentDebugBus.toolEvents.value.first()
        assertFalse(ev.argsJson.contains("sk-ABCDEF1234567890"))
    }

    @Test
    fun `long args are clipped`() {
        AgentDebugBus.clear()
        val long = """{"a":"${"x".repeat(1000)}"}"""
        AgentDebugBus.toolRejected("t", long, "boom")
        val ev = AgentDebugBus.toolEvents.value.first()
        assertTrue(ev.argsJson.length <= 300)
        assertTrue(ev.argsJson.endsWith("…"))
    }

    @Test
    fun `tool history is bounded newest first`() {
        AgentDebugBus.clear()
        repeat(50) { i ->
            val id = AgentDebugBus.toolStarted("t$i", "{}")
            AgentDebugBus.toolFinished(id, "success", "ok#$i", 1)
        }
        val events = AgentDebugBus.toolEvents.value
        assertEquals(40, events.size)                     // MAX_TOOL_EVENTS
        assertEquals("t49", events.first().tool)          // newest first
        assertEquals("t10", events.last().tool)           // 49 down to 10
    }

    @Test
    fun `turn events record phases newest first and are bounded`() {
        AgentDebugBus.clear()
        AgentDebugBus.turnStarted("hello maya")
        AgentDebugBus.turnFinished("done!")
        AgentDebugBus.turnError("rate_limit: 429")
        AgentDebugBus.turnCancelled()
        val events = AgentDebugBus.turnEvents.value
        assertEquals("cancel", events[0].phase)
        assertEquals("error", events[1].phase)
        assertEquals("done", events[2].phase)
        assertEquals("start", events[3].phase)
        assertTrue(events[3].detail.contains("hello maya"))
    }

    @Test
    fun `clear resets everything`() {
        val id = AgentDebugBus.toolStarted("x", "{}")
        assertNotNull(AgentDebugBus.currentTool.value)
        AgentDebugBus.toolFinished(id, "success", "ok", 1)
        AgentDebugBus.turnStarted("q")
        AgentDebugBus.clear()
        assertTrue(AgentDebugBus.toolEvents.value.isEmpty())
        assertTrue(AgentDebugBus.turnEvents.value.isEmpty())
        assertNull(AgentDebugBus.currentTool.value)
    }
}
