package com.karin.streamtv.karinlink.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the pairing trust table.
 *
 * PeerRegistry takes a [TrustStore], so these run on a plain JVM with an
 * in-memory store. No Robolectric and no Android framework involved.
 */
class PeerRegistryTest {

    private lateinit var store: InMemoryTrustStore
    private lateinit var registry: PeerRegistry
    private var now: Long = 1_700_000_000_000

    @Before
    fun setUp() {
        store = InMemoryTrustStore()
        registry = PeerRegistry(store, systemDeviceName = { "Android TV" }, clock = { now })
    }

    @Test
    fun `device id is stable across instances`() {
        val first = registry.deviceId
        val second = PeerRegistry(InMemoryTrustStore(store.snapshot()), systemDeviceName = { "Android TV" }).deviceId
        assertEquals("device id must never rotate", first, second)
        assertTrue(first.isNotBlank())
    }

    @Test
    fun `device name falls back to the system name`() {
        assertEquals("Android TV", registry.deviceName)
    }

    @Test
    fun `fresh registry trusts nobody`() {
        assertFalse(registry.isTrusted("anyone"))
        assertNull(registry.keyFor("anyone"))
        assertTrue(registry.trustedPeers().isEmpty())
    }

    @Test
    fun `trust stores a key that matches what the remote would derive`() {
        val pin = Pairing.newPin()
        val local = registry.deviceId
        registry.trust(pin, remoteId = "tv-1", remoteName = "TV Sala")

        val stored = registry.keyFor("tv-1")
        assertNotNull(stored)
        assertTrue(
            "stored key must equal the key the remote derives",
            stored!!.contentEquals(Pairing.deriveKey(pin, local, "tv-1"))
        )
    }

    @Test
    fun `trust makes the peer trusted and lists it`() {
        registry.trust(Pairing.newPin(), "tv-1", "TV Sala")

        assertTrue(registry.isTrusted("tv-1"))
        val peers = registry.trustedPeers()
        assertEquals(1, peers.size)
        assertEquals("TV Sala", peers[0].deviceName)
        assertEquals("tv-1", peers[0].deviceId)
    }

    @Test
    fun `trust stores capabilities`() {
        val caps = setOf(Capability.PLAY, Capability.REMOTE)
        registry.trust(Pairing.newPin(), "tv-1", "TV", caps)

        assertEquals(caps, registry.capabilitiesOf("tv-1"))
        assertEquals(caps, registry.trustedPeers().first().capabilities)
    }

    @Test
    fun `a device cannot pair with itself`() {
        val me = registry.deviceId
        registry.trust(Pairing.newPin(), me, "Myself")
        assertFalse("self-pairing must be ignored", registry.isTrusted(me))
    }

    @Test
    fun `trusting clears the pending pin`() {
        registry.pendingPin = Pairing.newPin()
        assertTrue(registry.isAwaitingPairing)

        registry.trust(Pairing.newPin(), "tv-1", "TV")

        assertNull(registry.pendingPin)
        assertFalse(registry.isAwaitingPairing)
    }

    @Test
    fun `a pending pin lapses on its own`() {
        registry.pendingPin = Pairing.newPin()
        assertTrue(registry.isAwaitingPairing)

        now += registry.pendingPinTtlMs - 1
        assertTrue("the code expired early", registry.isAwaitingPairing)

        now += 1
        assertNull("a code left on screen must not pair a device months later", registry.pendingPin)
        assertFalse(registry.isAwaitingPairing)
    }

    @Test
    fun `setting a new pin restarts the clock`() {
        registry.pendingPin = Pairing.newPin()
        now += registry.pendingPinTtlMs - 1

        val second = Pairing.newPin()
        registry.pendingPin = second
        now += registry.pendingPinTtlMs - 1

        assertEquals(second, registry.pendingPin)
    }

    @Test
    fun `untrust removes the peer completely`() {
        registry.trust(Pairing.newPin(), "tv-1", "TV")
        registry.untrust("tv-1")

        assertFalse(registry.isTrusted("tv-1"))
        assertNull(registry.keyFor("tv-1"))
        assertTrue(registry.trustedPeers().isEmpty())
    }

    @Test
    fun `untrust of an unknown peer is a no-op`() {
        registry.untrust("never-seen")
        assertTrue(registry.trustedPeers().isEmpty())
    }

    @Test
    fun `touch updates liveness and keeps the existing name`() {
        registry.trust(Pairing.newPin(), "tv-1", "TV Sala")
        registry.touch("tv-1")
        assertEquals("touch must not clobber the name", "TV Sala", registry.trustedPeers().first().deviceName)
    }

    @Test
    fun `touch can update the name and capabilities`() {
        registry.trust(Pairing.newPin(), "tv-1", "Old Name")
        registry.touch("tv-1", remoteName = "New Name", remoteCaps = setOf(Capability.PLAY))

        val peer = registry.trustedPeers().first()
        assertEquals("New Name", peer.deviceName)
        assertEquals(setOf(Capability.PLAY), peer.capabilities)
    }

    @Test
    fun `touch on an unknown peer creates an entry but grants no trust`() {
        registry.touch("stranger", "Stranger")
        assertFalse("touch must not imply trust", registry.isTrusted("stranger"))
    }

    @Test
    fun `trust table survives a new registry instance over the same store`() {
        val pin = Pairing.newPin()
        registry.trust(pin, "tv-1", "TV Sala", setOf(Capability.PLAY))
        val key = registry.keyFor("tv-1")!!

        val reopened = PeerRegistry(store, systemDeviceName = { "Android TV" })

        assertTrue(reopened.isTrusted("tv-1"))
        assertTrue("persisted key must survive", reopened.keyFor("tv-1")!!.contentEquals(key))
        assertEquals(setOf(Capability.PLAY), reopened.capabilitiesOf("tv-1"))
    }

    @Test
    fun `multiple peers coexist with independent keys`() {
        val a = Pairing.newPin()
        val b = Pairing.newPin()
        registry.trust(a, "tv-1", "TV Sala")
        registry.trust(b, "tv-2", "TV Cocina")

        assertEquals(2, registry.trustedPeers().size)
        assertTrue(registry.keyFor("tv-1")!!.contentEquals(Pairing.deriveKey(a, registry.deviceId, "tv-1")))
        assertTrue(registry.keyFor("tv-2")!!.contentEquals(Pairing.deriveKey(b, registry.deviceId, "tv-2")))
    }

    @Test
    fun `trustWithKey stores an externally supplied key`() {
        val key = Pairing.randomKey()
        registry.trustWithKey("remote-tv", "TV Remota", key)
        assertTrue(registry.keyFor("remote-tv")!!.contentEquals(key))
    }

    @Test
    fun `clearAll wipes every pairing`() {
        registry.trust(Pairing.newPin(), "tv-1", "TV")
        registry.clearAll()

        assertTrue(registry.trustedPeers().isEmpty())
        assertNull(registry.keyFor("tv-1"))
    }

    // ── Pruning ───────────────────────────────────────────────────

    @Test
    fun `prune removes stale peers and keeps recent ones`() {
        registry.trust(Pairing.newPin(), "old-tv", "Old TV")
        now += 10 * 60_000
        registry.trust(Pairing.newPin(), "new-tv", "New TV")

        assertEquals("only the stale peer should be dropped", 1, registry.prune(maxAgeMs = 5 * 60_000))
        assertFalse(registry.isTrusted("old-tv"))
        assertTrue(registry.isTrusted("new-tv"))
    }

    @Test
    fun `prune keeps recent peers`() {
        registry.trust(Pairing.newPin(), "tv-1", "TV")
        now += 60_000
        assertEquals(0, registry.prune(maxAgeMs = 5 * 60_000))
        assertTrue(registry.isTrusted("tv-1"))
    }

    @Test
    fun `prune with a large max age keeps everything`() {
        registry.trust(Pairing.newPin(), "tv-1", "TV")
        now += 10_000
        assertEquals(0, registry.prune(maxAgeMs = Long.MAX_VALUE / 2))
        assertTrue(registry.isTrusted("tv-1"))
    }

    @Test
    fun `prune keeps the most recently seen peer first`() {
        registry.trust(Pairing.newPin(), "tv-1", "First")
        now += 1_000
        registry.trust(Pairing.newPin(), "tv-2", "Second")
        now += 1_000
        registry.trust(Pairing.newPin(), "tv-3", "Third")

        assertEquals(listOf("tv-3", "tv-2", "tv-1"), registry.trustedPeers().map { it.deviceId })
    }

    // ── Corruption tolerance ──────────────────────────────────────

    @Test
    fun `corrupt trust table is discarded instead of crashing`() {
        store.seed("trusted_keys", "NOT VALID JSON {{{")
        assertTrue(registry.trustedPeers().isEmpty())
        assertFalse(registry.isTrusted("tv-1"))
    }

    @Test
    fun `corrupt metadata is discarded instead of crashing`() {
        store.seed("trusted_meta", "[[[")
        assertTrue(registry.trustedPeers().isEmpty())
    }

    @Test
    fun `registry is usable again after corruption is discarded`() {
        store.seed("trusted_keys", "garbage")
        assertTrue(registry.trustedPeers().isEmpty())

        registry.trust(Pairing.newPin(), "tv-1", "TV")

        assertTrue(registry.isTrusted("tv-1"))
    }

    // ── Device naming ─────────────────────────────────────────────

    @Test
    fun `setDeviceName trims and persists`() {
        registry.setDeviceName("  TV Sala  ")
        assertEquals("TV Sala", PeerRegistry(store, systemDeviceName = { "Android TV" }).deviceName)
    }

    @Test
    fun `setDeviceName caps the length so the mDNS TXT record stays small`() {
        registry.setDeviceName("x".repeat(500))
        assertTrue(registry.deviceName.length <= 48)
    }

    @Test
    fun `system name is used only when no custom name is set`() {
        registry.setDeviceName("Mia")
        assertEquals("Mia", PeerRegistry(store, systemDeviceName = { "Otro" }).deviceName)
    }
}
