package com.karin.streamtv.karinlink.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Safe readers for a decoded payload.
 *
 * Payloads arrive from the network, so every accessor tolerates a missing key,
 * an explicit null or a wrong type. The previous call sites used
 * `JSONObject.optString`, which answers "" for a number and 0 for a string, so a
 * malformed or hostile message was indistinguishable from a well-formed one:
 * a `"positionMs":"abc"` payload read back as 0 and looked like a request to
 * seek to the start. These return [fallback] unless the value really is of the
 * requested type.
 */

/** A string field, or [fallback] when absent, null or not a JSON string. */
fun JsonObject.str(key: String, fallback: String = ""): String {
    val primitive = this[key] as? JsonPrimitive ?: return fallback
    // content on a numeric primitive is its text form, so the type has to be
    // checked explicitly or every number would read as a string.
    if (!primitive.isString) return fallback
    return primitive.content
}

/** A long field, or [fallback] when absent or not a JSON number. */
fun JsonObject.long(key: String, fallback: Long = 0L): Long {
    val primitive = this[key] as? JsonPrimitive ?: return fallback
    // A quoted "42" is not a number: longOrNull would happily parse it.
    if (primitive.isString) return fallback
    return primitive.longOrNull ?: fallback
}

/** A double field, or [fallback] when absent or not a JSON number. */
fun JsonObject.double(key: String, fallback: Double = 0.0): Double {
    val primitive = this[key] as? JsonPrimitive ?: return fallback
    if (primitive.isString) return fallback
    return primitive.doubleOrNull ?: fallback
}

/** A boolean field, or [fallback] when absent or not a JSON boolean. */
fun JsonObject.bool(key: String, fallback: Boolean = false): Boolean {
    val primitive = this[key] as? JsonPrimitive ?: return fallback
    // booleanOrNull parses the string "true" as true, so quoted values are refused.
    if (primitive.isString) return fallback
    return primitive.booleanOrNull ?: fallback
}

/** A nested object, or null when absent or not an object. */
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** A nested array, or an empty list when absent or not an array. */
fun JsonObject.arr(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())
