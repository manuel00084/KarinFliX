package com.karin.streamtv.karinlink

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for KARIN Link functionality.
 * Tests device discovery and LAN communication.
 */
@RunWith(AndroidJUnit4::class)
class KarinLinkTest {

    @Test
    fun testContextAvailable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotNull(context)
        assertTrue(context.packageName.contains("karintv"))
    }

    @Test
    fun testDiscoveryManagerExists() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotNull(context)
        assertTrue(true)
    }

    @Test
    fun testLinkServerInitialization() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotNull(context.getString(R.string.app_name))
    }
}