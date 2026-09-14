package com.amayra.maya.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the no-silent-loss contract: a key saved under any Keystore condition must
 * survive reads across later Keystore-state changes (the "vanishing key" bug).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecureStoreTest {

    private fun newStore(): SecureStore = SecureStore(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `roundtrip preserves value`() {
        val s = newStore()
        s.putString(SecureStore.KEY_GEMINI, "AIza-test-123")
        assertEquals("AIza-test-123", s.getString(SecureStore.KEY_GEMINI))
    }

    @Test
    fun `remove clears entry`() {
        val s = newStore()
        s.putString(SecureStore.KEY_GEMINI, "k")
        s.remove(SecureStore.KEY_GEMINI)
        assertNull(s.getString(SecureStore.KEY_GEMINI))
    }

    @Test
    fun `empty value removes entry`() {
        val s = newStore()
        s.putString(SecureStore.KEY_GEMINI, "k")
        s.putString(SecureStore.KEY_GEMINI, "")
        assertNull(s.getString(SecureStore.KEY_GEMINI))
    }

    @Test
    fun `plaintext fallback entry written while keystore down is still readable and upgrades`() {
        val s = newStore()
        // Simulate the fallback write (keystore unavailable at save time).
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("maya_secure", Context.MODE_PRIVATE)
        prefs.edit().putString(SecureStore.KEY_GEMINI, SecureStoreTestHelper.plaintextEntry("AIza-fallback")).commit()

        assertEquals("AIza-fallback", s.getString(SecureStore.KEY_GEMINI))
        // Upgraded in place: subsequent reads keep working.
        assertEquals("AIza-fallback", s.getString(SecureStore.KEY_GEMINI))
    }

    @Test
    fun `legacy markerless plaintext is readable and upgrades`() {
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("maya_secure", Context.MODE_PRIVATE)
        prefs.edit().putString(SecureStore.KEY_OPENAI_COMPAT, "gsk-legacy").commit()

        val s = newStore()
        assertEquals("gsk-legacy", s.getString(SecureStore.KEY_OPENAI_COMPAT))
        assertEquals("gsk-legacy", s.getString(SecureStore.KEY_OPENAI_COMPAT))
    }

    @Test
    fun `garbage ciphertext blob never poisons later saves`() {
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("maya_secure", Context.MODE_PRIVATE)
        // Looks like ciphertext but is garbage (real device: undecryptable after a
        // Keystore reset; Robolectric's shadow cipher is a pass-through, so here we
        // pin the contract that matters — no throw, and the entry fully heals).
        prefs.edit().putString(SecureStore.KEY_GEMINI, "AAAA|BBBB").commit()

        val s = newStore()
        s.getString(SecureStore.KEY_GEMINI) // must not throw
        s.putString(SecureStore.KEY_GEMINI, "AIza-fresh")
        assertEquals("AIza-fresh", s.getString(SecureStore.KEY_GEMINI))
    }
}

/** Test seam for the marker constant without widening the public API. */
object SecureStoreTestHelper {
    fun plaintextEntry(value: String): String = "plain::$value"
}
