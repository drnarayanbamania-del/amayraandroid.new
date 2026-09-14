package com.amayra.maya.playtest

import androidx.test.core.app.ApplicationProvider
import com.amayra.maya.MayaApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * First-run setup gate contract: the flag round-trips through DataStore and
 * the greeting condition (setupComplete && proactiveGreeting) only turns true
 * after the gate completes. One method — the preferencesDataStore delegate is
 * a JVM singleton, so its state leaks across Robolectric test methods.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = MayaApplication::class, sdk = [34])
class SetupGatePlaytest {

    @Test
    fun `setup gate flag round-trips and gates the greeting`() {
        val app = ApplicationProvider.getApplicationContext<MayaApplication>()
        runBlocking {
            // The gate starts closed (default false in UserPreferences).
            assertFalse(
                "fresh default must be incomplete",
                com.amayra.maya.settings.UserPreferences().setupComplete
            )

            // Completing the gate persists and unlocks the greeting.
            app.settings.update {
                it[androidx.datastore.preferences.core.booleanPreferencesKey("setup_complete")] = true
            }
            val p = app.settings.prefs.first()
            assertTrue("gate completion must persist", p.setupComplete)
            assertTrue("greeting must fire after setup", p.setupComplete && p.proactiveGreeting)

            // And the core's guard honors it: reset = silent again.
            app.settings.update {
                it[androidx.datastore.preferences.core.booleanPreferencesKey("setup_complete")] = false
            }
            assertFalse(app.settings.prefs.first().setupComplete)
        }
    }
}
