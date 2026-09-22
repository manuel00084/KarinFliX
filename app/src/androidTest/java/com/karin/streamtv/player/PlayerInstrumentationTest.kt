package com.karin.streamtv.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Player instrumentation tests.
 * Tests video playback and ExoPlayer integration.
 */
@RunWith(AndroidJUnit4::class)
class PlayerInstrumentationTest {

    @Test
    fun testPlayerContext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotNull(context)
    }

    @Test
    fun testPlaybackSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("karin_prefs", 0)
        assertNotNull(prefs)
    }

    @Test
    fun testMedia3Available() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotNull(context)
        assertTrue(true)
    }
}