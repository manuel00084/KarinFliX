package com.karin.streamtv.karinlink.protocol

import android.content.Context
import android.provider.Settings
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Minimal string-keyed persistence.
 *
 * PeerRegistry talks to this instead of SharedPreferences directly so the trust
 * logic can be unit tested on a plain JVM, without Robolectric. Robolectric was
 * rejected here because it pulls ~15 MB of android-all jars into the test
 * runtime for what is really just a string map.
 */
interface TrustStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/** Production store, backed by SharedPreferences. */
class PrefsTrustStore(context: Context) : TrustStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String?) {
        prefs.edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }

    companion object {
        const val PREFS = "karin_link_v2"
    }
}

/**
 * Persistence for paired devices.
 *
 * Once a device is paired, discovery reconnects to it automatically with no
 * further user interaction, so this table is what makes KARIN Link "just work"
 * after the first pairing.
 *
 * The stored keys are derived from the PIN via HKDF. They are not secret on
 * their own, but they grant control of this device, so the prefs file is worth
 * treating as sensitive.
 */
class PeerRegistry(
    private val store: TrustStore,
    /** Display name to use when the user has not chosen one. */
    private val systemDeviceName: () -> String = { "" },
    /** Injected so liveness and pruning can be tested without sleeping. */
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    constructor(context: Context) : this(
        encryptedTrustStore(context),
        {
            Settings.Global.getString(context.applicationContext.contentResolver, Settings.Global.DEVICE_NAME).orEmpty()
        }
    )

    // ── Own identity ──────────────────────────────────────────────

    /**
     * Stable id for this device. Generated once, then reused forever: rotating
     * it would orphan every pairing and re-prompt the user.
     */
    val deviceId: String
        get() = store.get(KEY_DEVICE_ID) ?: java.util.UUID.randomUUID().toString().also {
            store.put(KEY_DEVICE_ID, it)
        }

    val deviceName: String
        get() = store.get(KEY_DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: systemDeviceName().ifBlank { "KarinFLiX" }

    fun setDeviceName(name: String) {
        store.put(KEY_DEVICE_NAME, name.trim().take(MAX_NAME_LENGTH))
    }

    /**
     * Pairing code currently displayed in the UI, so the user can read it aloud.
     *
     * Expires on its own: a code that never went stale would let anyone who
     * happened onto the LAN months later pair with a device the user thought
     * had stopped waiting. The value is cleared from storage once it lapses, so
     * a stale entry cannot be revived by an earlier read.
     */
    var pendingPin: String?
        get() {
            val pin = store.get(KEY_PENDING_PIN) ?: return null
            val setAt = store.get(KEY_PENDING_PIN_AT)?.toLongOrNull() ?: return null
            if (clock() - setAt >= pendingPinTtlMs) {
                store.put(KEY_PENDING_PIN, null)
                store.put(KEY_PENDING_PIN_AT, null)
                return null
            }
            return pin
        }
        set(value) {
            store.put(KEY_PENDING_PIN, value)
            store.put(KEY_PENDING_PIN_AT, value?.let { clock().toString() })
        }

    /** How long a code stays usable. */
    var pendingPinTtlMs: Long = DEFAULT_PENDING_PIN_TTL_MS

    /** True when a code is displayed and waiting to be typed on another device. */
    val isAwaitingPairing: Boolean get() = pendingPin != null

    // ── Trust table ───────────────────────────────────────────────

    /** @return the shared key for [remoteId], or null when not paired. */
    fun keyFor(remoteId: String): ByteArray? = loadKeys()[remoteId]?.let(Pairing::decodeKey)

    fun isTrusted(remoteId: String): Boolean = loadKeys().containsKey(remoteId)

    /** Every paired device, most recently seen first. */
    fun trustedPeers(): List<TrustedPeer> {
        val keys = loadKeys()
        val meta = loadMeta()
        return keys.keys.mapNotNull { id ->
            val key = keys[id] ?: return@mapNotNull null
            TrustedPeer(
                deviceId = id,
                deviceName = meta[id]?.name ?: id,
                key = key,
                lastSeen = meta[id]?.lastSeen ?: 0L,
                capabilities = Capability.decode(meta[id]?.caps)
            )
        }.sortedByDescending { it.lastSeen }
    }

    /**
     * Records a successful pairing.
     *
     * @param pin the code both sides typed
     * @param remoteId the other device
     * @param remoteName display name for the UI
     */
    fun trust(pin: String, remoteId: String, remoteName: String, remoteCaps: Set<Capability> = emptySet()) {
        if (remoteId == deviceId) return
        val key = Pairing.deriveKey(pin, deviceId, remoteId)

        val keys = loadKeys().toMutableMap()
        keys[remoteId] = Pairing.encodeKey(key)
        store.put(KEY_KEYS, encodeKeys(keys))

        val meta = loadMeta().toMutableMap()
        meta[remoteId] = StoredMeta(
            deviceId = remoteId,
            name = remoteName,
            lastSeen = clock(),
            caps = Capability.encode(remoteCaps)
        )
        store.put(KEY_META, encodeMeta(meta))

        pendingPin = null
        Log.i(TAG, "Paired with $remoteName ($remoteId)")
    }

    /** Records a pairing established out of band, e.g. via a relay key in a QR. */
    fun trustWithKey(
        remoteId: String,
        remoteName: String,
        key: ByteArray,
        remoteCaps: Set<Capability> = emptySet()
    ) {
        if (remoteId == deviceId) return

        val keys = loadKeys().toMutableMap()
        keys[remoteId] = Pairing.encodeKey(key)
        store.put(KEY_KEYS, encodeKeys(keys))

        val meta = loadMeta().toMutableMap()
        meta[remoteId] = StoredMeta(
            deviceId = remoteId,
            name = remoteName,
            lastSeen = clock(),
            caps = Capability.encode(remoteCaps)
        )
        store.put(KEY_META, encodeMeta(meta))

        // A pairing just completed, so the device is no longer waiting for a
        // code. Without this the UI would keep showing "enter the code" and,
        // worse, the session would keep accepting new pairings.
        pendingPin = null
    }

    /** Call after a verified exchange, to refresh liveness and capabilities. */
    fun touch(remoteId: String, remoteName: String? = null, remoteCaps: Set<Capability>? = null) {
        val meta = loadMeta().toMutableMap()
        val prev = meta[remoteId]
        meta[remoteId] = StoredMeta(
            deviceId = remoteId,
            name = remoteName ?: prev?.name ?: remoteId,
            lastSeen = clock(),
            caps = remoteCaps?.let { Capability.encode(it) } ?: prev?.caps ?: ""
        )
        store.put(KEY_META, encodeMeta(meta))
    }

    fun capabilitiesOf(remoteId: String): Set<Capability> = Capability.decode(loadMeta()[remoteId]?.caps)

    /** Forgets a device. The next contact will require pairing again. */
    fun untrust(remoteId: String) {
        val keys = loadKeys().toMutableMap()
        keys.remove(remoteId)
        store.put(KEY_KEYS, encodeKeys(keys))

        val meta = loadMeta().toMutableMap()
        meta.remove(remoteId)
        store.put(KEY_META, encodeMeta(meta))
        Log.i(TAG, "Unpaired $remoteId")
    }

    fun clearAll() {
        store.put(KEY_KEYS, null)
        store.put(KEY_META, null)
        store.put(KEY_PENDING_PIN, null)
        store.put(KEY_PENDING_PIN_AT, null)
    }

    /**
     * Drops peers not seen for [maxAgeMs], so the list does not grow forever.
     *
     * @return how many were removed
     */
    fun prune(maxAgeMs: Long = DEFAULT_MAX_AGE_MS): Int {
        val cutoff = clock() - maxAgeMs
        val meta = loadMeta()
        val stale = meta.filterValues { it.lastSeen in 1 until cutoff }.keys.toList()
        if (stale.isEmpty()) return 0

        val keys = loadKeys().toMutableMap()
        stale.forEach { keys.remove(it) }
        store.put(KEY_KEYS, encodeKeys(keys))
        store.put(KEY_META, encodeMeta(meta.filterKeys { it !in stale }))

        Log.i(TAG, "Pruned ${stale.size} stale peer(s)")
        return stale.size
    }

    // ── Storage codecs ────────────────────────────────────────────

    /**
     * On-disk shape of the metadata table. [deviceId] is the primary key, so it
     * lives in the record rather than being the map key.
     */
    @Serializable
    private data class StoredMeta(
        val deviceId: String,
        val name: String,
        val lastSeen: Long,
        val caps: String = ""
    )

    private fun loadKeys(): Map<String, String> =
        runCatching {
            val raw = store.get(KEY_KEYS) ?: return emptyMap()
            LinkProtocol.json.decodeFromString(
                MapSerializer(String.serializer(), String.serializer()), raw
            )
        }.getOrElse {
            discard(KEY_KEYS)
            emptyMap()
        }

    private fun loadMeta(): Map<String, StoredMeta> =
        runCatching {
            val raw = store.get(KEY_META) ?: return emptyMap()
            LinkProtocol.json.decodeFromString(ListSerializer(StoredMeta.serializer()), raw)
                .associateBy { meta -> meta.deviceId }
        }.getOrElse {
            discard(KEY_META)
            emptyMap()
        }

    /** A half-written prefs file must not wedge the feature permanently. */
    private fun discard(key: String) {
        Log.w(TAG, "Corrupt '$key', discarding")
        store.put(key, null)
    }

    private fun encodeKeys(map: Map<String, String>): String =
        LinkProtocol.json.encodeToString(MapSerializer(String.serializer(), String.serializer()), map)

    private fun encodeMeta(map: Map<String, StoredMeta>): String? =
        if (map.isEmpty()) null
        else LinkProtocol.json.encodeToString(
            ListSerializer(StoredMeta.serializer()),
            map.values.sortedByDescending { it.lastSeen }
                .map { StoredMeta(it.deviceId, it.name, it.lastSeen, it.caps) }
        )

    companion object {
        private const val TAG = "PeerRegistry"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_PENDING_PIN = "pending_pin"
private const val KEY_PENDING_PIN_AT = "pending_pin_at"

/** Long enough to walk a code to the other device, short enough to not linger. */
private const val DEFAULT_PENDING_PIN_TTL_MS = 5L * 60 * 1000
        private const val KEY_KEYS = "trusted_keys"
        private const val KEY_META = "trusted_meta"
        private const val MAX_NAME_LENGTH = 48
        private const val DEFAULT_MAX_AGE_MS = 180L * 24 * 60 * 60 * 1000
    }
}

/** In-memory [TrustStore] for tests. */
class InMemoryTrustStore(initial: Map<String, String> = emptyMap()) : TrustStore {

    private val values = LinkedHashMap(initial)

    /** Lets a test simulate a half-written or corrupt prefs file. */
    fun seed(key: String, raw: String) = values.put(key, raw)

    override fun get(key: String): String? = values[key]

    override fun put(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }

    /** The stored bytes, for tests that care what actually reached the disk. */
    fun snapshot(): Map<String, String> = LinkedHashMap(values)
}
