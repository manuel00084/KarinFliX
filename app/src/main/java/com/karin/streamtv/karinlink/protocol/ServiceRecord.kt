package com.karin.streamtv.karinlink.protocol

/**
 * The mDNS TXT record that accompanies a KARIN Link service.
 *
 * This is deliberately pure: the previous build hardcoded its own service type
 * and attribute names in [com.karin.streamtv.karinlink.DiscoveryManager] and
 * drifted from the protocol constants, so peers registered `_karinflinx._tcp.`
 * while they browsed for `_karinflix._tcp.` and nothing was ever discovered.
 * Deriving both sides from one place makes that class of bug impossible.
 */
object ServiceRecord {

    /**
     * Conservative ceiling on the TXT record. The DNS spec allows ~1300 bytes
     * across all records; many Android TV responders silently drop anything
     * larger, which would make the service invisible rather than merely
     * degraded.
     */
    const val MAX_TXT_BYTES = 1300

    private const val MAX_ID = 64
    private const val MAX_NAME = 48
    private const val MAX_VERSION = 16
    private const val MAX_MODEL = 32

    /**
     * Builds the TXT attributes for [peer].
     *
     * [port] is duplicated into the record so a client that resolved via a
     * cached address can still find the real port after a restart.
     */
    fun attributes(peer: PeerInfo, port: Int): Map<String, String> {
        val attrs = linkedMapOf(
            LinkProtocol.Txt.DEVICE_ID to clean(peer.deviceId, MAX_ID),
            LinkProtocol.Txt.DEVICE_NAME to clean(peer.deviceName, MAX_NAME),
            LinkProtocol.Txt.APP_VERSION to clean(peer.appVersion, MAX_VERSION),
            LinkProtocol.Txt.PROTOCOL to peer.protocolVersion.toString(),
            LinkProtocol.Txt.PORT to port.toString(),
            LinkProtocol.Txt.CAPS to Capability.encode(peer.capabilities)
        )
        if (peer.deviceModel.isNotBlank()) {
            attrs[LinkProtocol.Txt.DEVICE_MODEL] = clean(peer.deviceModel, MAX_MODEL)
        }

        // Shed the optional fields before the identifying ones, so a very long
        // device name degrades the UI label instead of the whole record.
        if (sizeOf(attrs) > MAX_TXT_BYTES) attrs.remove(LinkProtocol.Txt.CAPS)
        if (sizeOf(attrs) > MAX_TXT_BYTES) attrs.remove(LinkProtocol.Txt.DEVICE_MODEL)
        if (sizeOf(attrs) > MAX_TXT_BYTES) attrs[LinkProtocol.Txt.DEVICE_NAME] = ""

        return attrs
    }

    /**
     * Parses a resolved TXT record.
     *
     * @param serviceName the mDNS instance name, used only as a last-resort id
     * @return null when the record cannot be used, i.e. it has no device id or
     *   speaks an incompatible protocol major. Returning null rather than a
     *   half-filled record keeps unpairable devices out of the UI.
     */
    fun parse(
        attrs: Map<String, String>,
        serviceName: String = "",
        host: String = "",
        port: Int = 0
    ): DiscoveredPeer? {
        val id = attrs[LinkProtocol.Txt.DEVICE_ID]?.takeIf { it.isNotBlank() }
            ?: serviceName.takeIf { it.isNotBlank() }
            ?: return null

        val proto = attrs[LinkProtocol.Txt.PROTOCOL]?.toIntOrNull() ?: return null
        if (proto != LinkProtocol.VERSION) return null

        // Trust the TXT port when present: the resolved port is occasionally
        // stale on Android after the service restarts on a different port.
        val effectivePort = attrs[LinkProtocol.Txt.PORT]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: port

        return DiscoveredPeer(
            deviceId = id,
            deviceName = attrs[LinkProtocol.Txt.DEVICE_NAME]?.takeIf { it.isNotBlank() } ?: id,
            deviceModel = attrs[LinkProtocol.Txt.DEVICE_MODEL].orEmpty(),
            appVersion = attrs[LinkProtocol.Txt.APP_VERSION]?.takeIf { it.isNotBlank() } ?: "unknown",
            protocolVersion = proto,
            capabilities = Capability.decode(attrs[LinkProtocol.Txt.CAPS]),
            host = host,
            port = effectivePort
        )
    }

    /** Service instance name, unique per device so two TVs never collide. */
    fun instanceName(deviceId: String): String = "KarinFLiX-${clean(deviceId, MAX_ID)}"

    /**
     * Normalises a DNS-SD service type for comparison.
     *
     * Android is inconsistent about the trailing dot and the domain: some
     * builds report the type as registered (`_karinflix._tcp.`), others append
     * the local domain (`_karinflix._tcp.local.`). Comparing the raw strings
     * silently rejects every peer, so both sides are reduced to `_name._proto`
     * before matching.
     */
    fun normaliseType(raw: String): String =
        raw.trim().lowercase().removeSuffix(".").removeSuffix(".local").removeSuffix(".")

    /** True when [candidate] is a KARIN Link service type. */
    fun isOurServiceType(candidate: String): Boolean =
        normaliseType(candidate) == normaliseType(LinkProtocol.SERVICE_TYPE)

    /**
     * Strips characters that would corrupt a TXT record and bounds the length.
     * Control characters, and the dot used to separate label fields, are removed
     * rather than escaped: a name is display text, so silently dropping a
     * control char is better than letting a peer inject bytes into the record.
     */
    private fun clean(value: String, max: Int): String =
        value.filter { it.isLetterOrDigit() || it in ALLOWED_PUNCT }.take(max).trim()

    private val ALLOWED_PUNCT = setOf(' ', '-', '_', '.', '(', ')')

    private fun sizeOf(attrs: Map<String, String>): Int =
        attrs.entries.sumOf { (k, v) -> k.length + v.length + 2 }
}

/** A peer as advertised over mDNS, before any connection is attempted. */
data class DiscoveredPeer(
    val deviceId: String,
    val deviceName: String,
    val deviceModel: String = "",
    val appVersion: String = "unknown",
    val protocolVersion: Int = LinkProtocol.VERSION,
    val capabilities: Set<Capability> = emptySet(),
    val host: String = "",
    val port: Int = 0
) {
    fun toPeerInfo(): PeerInfo = PeerInfo(
        deviceId = deviceId,
        deviceName = deviceName,
        appVersion = appVersion,
        protocolVersion = protocolVersion,
        deviceModel = deviceModel,
        capabilities = capabilities
    )

    val displayName: String get() = deviceName.ifBlank { deviceId }
}
