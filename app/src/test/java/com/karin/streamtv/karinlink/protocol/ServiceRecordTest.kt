package com.karin.streamtv.karinlink.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the mDNS TXT record.
 *
 * The regression these guard against is real: the Android build used to publish
 * `_karinflinx._tcp.` while browsing for `_karinflix._tcp.`, so no device ever
 * discovered another and the feature looked simply broken.
 */
class ServiceRecordTest {

    private fun peer(
        id: String = "dev-1",
        name: String = "Living Room TV",
        version: String = "1.4.0",
        caps: Set<Capability> = setOf(Capability.PLAY, Capability.REMOTE),
        model: String = "Pixel TV"
    ) = PeerInfo(
        deviceId = id,
        deviceName = name,
        appVersion = version,
        deviceModel = model,
        capabilities = caps
    )

    // ── Round trip ────────────────────────────────────────────────

    @Test
    fun `attributes survive a parse round trip`() {
        val original = peer()
        val parsed = ServiceRecord.parse(ServiceRecord.attributes(original, 8787))

        assertNotNull(parsed)
        assertEquals("dev-1", parsed!!.deviceId)
        assertEquals("Living Room TV", parsed.deviceName)
        assertEquals("Pixel TV", parsed.deviceModel)
        assertEquals("1.4.0", parsed.appVersion)
        assertEquals(setOf(Capability.PLAY, Capability.REMOTE), parsed.capabilities)
        assertEquals(LinkProtocol.VERSION, parsed.protocolVersion)
    }

    @Test
    fun `port is carried in the record and preferred over the resolved port`() {
        val attrs = ServiceRecord.attributes(peer(), 9999)
        assertEquals(9999, ServiceRecord.parse(attrs, port = 1234)!!.port)
    }

    @Test
    fun `an implausible port in the record falls back to the resolved port`() {
        val attrs = ServiceRecord.attributes(peer(), 8080).toMutableMap()
        attrs[LinkProtocol.Txt.PORT] = "0"
        assertEquals(1234, ServiceRecord.parse(attrs, port = 1234)!!.port)
    }

    // ── The regression that broke discovery ───────────────────────

    @Test
    fun `record advertises the service type peers actually browse for`() {
        // Both sides must derive the type from one constant. DiscoveryManager
        // used to keep its own copy, which is how the mismatch arose.
        assertEquals("_karinflix._tcp.", LinkProtocol.SERVICE_TYPE)
        assertTrue(
            "the DNS-SD service name must be a legal _services._dns-sd._udp form",
            LinkProtocol.SERVICE_TYPE.matches(Regex("_[^.]+\\._(tcp|udp)\\."))
        )
    }

    @Test
    fun `record uses the protocol attribute names not ad-hoc ones`() {
        val attrs = ServiceRecord.attributes(peer(), 8080)
        assertTrue(attrs.containsKey(LinkProtocol.Txt.DEVICE_ID))
        assertTrue(attrs.containsKey(LinkProtocol.Txt.PROTOCOL))
        assertEquals("id", LinkProtocol.Txt.DEVICE_ID)
        assertEquals("p", LinkProtocol.Txt.PROTOCOL)
    }

    // ── Robustness ────────────────────────────────────────────────

    @Test
    fun `a record with no device id is rejected`() {
        assertNull(ServiceRecord.parse(emptyMap()))
    }

    @Test
    fun `a record from a different protocol major is rejected`() {
        val attrs = ServiceRecord.attributes(peer(), 8080).toMutableMap()
        attrs[LinkProtocol.Txt.PROTOCOL] = "1"
        assertNull("a v1 peer must not be offered for pairing", ServiceRecord.parse(attrs))
    }

    @Test
    fun `a record with an unparseable protocol is rejected`() {
        val attrs = ServiceRecord.attributes(peer(), 8080).toMutableMap()
        attrs[LinkProtocol.Txt.PROTOCOL] = "banana"
        assertNull(ServiceRecord.parse(attrs))
    }

    @Test
    fun `missing name falls back to the device id`() {
        val attrs = ServiceRecord.attributes(peer(), 8080).toMutableMap()
        attrs.remove(LinkProtocol.Txt.DEVICE_NAME)
        val parsed = ServiceRecord.parse(attrs)
        assertEquals("dev-1", parsed!!.deviceName)
    }

    @Test
    fun `missing version is reported as unknown rather than an empty string`() {
        val attrs = ServiceRecord.attributes(peer(), 8080).toMutableMap()
        attrs.remove(LinkProtocol.Txt.APP_VERSION)
        assertEquals("unknown", ServiceRecord.parse(attrs)!!.appVersion)
    }

    @Test
    fun `unknown capability flags from a newer peer are ignored`() {
        val attrs = ServiceRecord.attributes(peer(caps = setOf(Capability.PLAY)), 8080).toMutableMap()
        attrs[LinkProtocol.Txt.CAPS] = "play,hologram"
        assertEquals(setOf(Capability.PLAY), ServiceRecord.parse(attrs)!!.capabilities)
    }

    // ── Sanitisation and size limits ──────────────────────────────

    @Test
    fun `control characters are stripped from the device name`() {
        val attrs = ServiceRecord.attributes(peer(name = "TV\u0000Sal\u0007a"), 8080)
        assertEquals("TVSala", attrs[LinkProtocol.Txt.DEVICE_NAME])
    }

    @Test
    fun `an over long device name is bounded`() {
        val attrs = ServiceRecord.attributes(peer(name = "n".repeat(500)), 8080)
        assertTrue((attrs[LinkProtocol.Txt.DEVICE_NAME]?.length ?: 0) <= 48)
    }

    @Test
    fun `an over long device id is bounded`() {
        val attrs = ServiceRecord.attributes(peer(id = "i".repeat(500)), 8080)
        assertTrue((attrs[LinkProtocol.Txt.DEVICE_ID]?.length ?: 0) <= 64)
    }

    @Test
    fun `a record always fits the mDNS size budget`() {
        val hostile = peer(id = "i".repeat(400), name = "ñ".repeat(400), model = "m".repeat(400))
        val attrs = ServiceRecord.attributes(hostile, 8080)

        val bytes = attrs.entries.sumOf { (k, v) -> k.toByteArray(Charsets.UTF_8).size + v.toByteArray(Charsets.UTF_8).size + 2 }
        assertTrue("TXT record was $bytes bytes, over the ${ServiceRecord.MAX_TXT_BYTES} limit", bytes <= ServiceRecord.MAX_TXT_BYTES)
    }

    @Test
    fun `size pressure sheds capabilities before identity`() {
        val hostile = peer(name = "n".repeat(400), model = "m".repeat(400))
        val attrs = ServiceRecord.attributes(hostile, 8080)

        assertTrue("device id must survive truncation", attrs[LinkProtocol.Txt.DEVICE_ID].isNullOrBlank().not())
        assertNotNull("protocol marker must survive truncation", attrs[LinkProtocol.Txt.PROTOCOL])
    }

    @Test
    fun `empty capability set is omitted rather than sent as an empty string`() {
        val attrs = ServiceRecord.attributes(peer(caps = emptySet()), 8080)
        assertTrue(attrs[LinkProtocol.Txt.CAPS].isNullOrBlank())
    }

    @Test
    fun `blank model is not published`() {
        val attrs = ServiceRecord.attributes(peer(model = "   "), 8080)
        assertTrue(attrs[LinkProtocol.Txt.DEVICE_MODEL].isNullOrBlank())
    }

    @Test
    fun `unicode device names survive the round trip`() {
        val attrs = ServiceRecord.attributes(peer(name = "Televisor Ñoño"), 8080)
        assertEquals("Televisor Ñoño", ServiceRecord.parse(attrs)!!.deviceName)
    }

    // ── Instance naming ───────────────────────────────────────────

    @Test
    fun `instance names are prefixed and stable per device`() {
        assertEquals("KarinFLiX-dev-1", ServiceRecord.instanceName("dev-1"))
        assertEquals(
            "two devices must never publish the same instance name",
            false,
            ServiceRecord.instanceName("dev-1") == ServiceRecord.instanceName("dev-2")
        )
    }

    @Test
    fun `instance name is sanitised like every other advertised field`() {
        assertTrue(ServiceRecord.instanceName("dev/1:2").none { it == '/' || it == ':' })
    }

    // ── Service type matching ─────────────────────────────────────

    @Test
    fun `service type matches regardless of trailing dot or domain`() {
        // The exact-string comparison this replaces rejected peers on any
        // responder that appended the domain, i.e. discovery silently found
        // nothing while the logs looked healthy.
        listOf(
            "_karinflix._tcp.",
            "_karinflix._tcp",
            "_karinflix._tcp.local.",
            "_karinflix._tcp.local",
            "  _KarinFLiX._TCP.  "
        ).forEach { candidate ->
            assertTrue("should match: '$candidate'", ServiceRecord.isOurServiceType(candidate))
        }
    }

    @Test
    fun `service type does not match a different app`() {
        listOf(
            "_airplay._tcp.",
            "_googlecast._tcp.",
            "_karinflinx._tcp.",
            "_karinflix._udp."
        ).forEach { candidate ->
            assertFalse("should not match: '$candidate'", ServiceRecord.isOurServiceType(candidate))
        }
    }

    @Test
    fun `normalised type is stable under repeated application`() {
        val once = ServiceRecord.normaliseType("_karinflix._tcp.local.")
        assertEquals(once, ServiceRecord.normaliseType(once))
    }
}
