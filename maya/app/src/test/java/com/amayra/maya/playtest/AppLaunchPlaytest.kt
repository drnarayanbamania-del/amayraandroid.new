package com.amayra.maya.playtest

import androidx.test.core.app.ApplicationProvider
import com.amayra.maya.MayaApplication
import com.amayra.maya.integration.PcRelay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * First-user launch path under Robolectric (no emulator on this host — WHPX
 * unavailable, QEMU dies at startup). Boots the full Application.onCreate:
 * anything unresolvable in the DI graph throws right here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = MayaApplication::class, sdk = [34])
class AppLaunchPlaytest {

    private fun app(): MayaApplication = ApplicationProvider.getApplicationContext()

    @Test
    fun `application onCreate wires the full graph`() {
        val a = app()
        assertNotNull(a.settings)
        assertNotNull(a.secure)
        assertNotNull(a.ai)
        assertNotNull(a.registry)
        assertNotNull(a.voice)
        assertNotNull(a.avatar)
        assertNotNull(a.core)
        assertNotNull(a.guardian)
        assertTrue(
            "tool registry should have the v4.15.1 tools, had ${a.registry.all().size}",
            a.registry.all().size >= 25
        )
    }

    @Test
    fun `persona voice resolves to the persona default on fresh install, never blank`() {
        val persona = com.amayra.maya.AppGraph.personas.active.value
        val voice = com.amayra.maya.AppGraph.personas.voice.value
        assertTrue("voice must never be blank", voice.isNotBlank())
        assertEquals("fresh install must use the persona default, not a ghost pick", persona.defaultVoice, voice)
    }

    @Test
    fun `pcRelay default port matches reference`() {
        assertEquals(18789, PcRelay.DEFAULT_PORT)
    }
}
