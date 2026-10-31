package com.dreamos.desktop.core.util

import kotlinx.serialization.json.*

// org.json-style reading for TMDB / OMDb / Trakt / GitHub answers: missing or null values read as the default,
// so a port of fire-home's `optString` / `optInt` stays one line.

fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray? = this as? JsonArray
fun JsonObject?.o(key: String): JsonObject? = this?.get(key) as? JsonObject
fun JsonObject?.a(key: String): JsonArray? = this?.get(key) as? JsonArray

/** The string, "" for missing / null (never the text "null", unlike org.json). Numbers read as their text. */
fun JsonObject?.str(key: String, def: String = ""): String {
    val p = this?.get(key) as? JsonPrimitive ?: return def
    return if (p is JsonNull) def else p.content
}

fun JsonObject?.strOrNull(key: String): String? = str(key).ifEmpty { null }
fun JsonObject?.int(key: String, def: Int = 0): Int = (this?.get(key) as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() } ?: def
fun JsonObject?.long(key: String, def: Long = 0): Long = (this?.get(key) as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: def
fun JsonObject?.dbl(key: String, def: Double = 0.0): Double = (this?.get(key) as? JsonPrimitive)?.doubleOrNull ?: def
fun JsonObject?.bool(key: String, def: Boolean = false): Boolean = (this?.get(key) as? JsonPrimitive)?.booleanOrNull ?: def
fun JsonObject?.isNull(key: String): Boolean = this?.get(key).let { it == null || it is JsonNull }

fun JsonArray?.objects(): List<JsonObject> = this?.mapNotNull { it as? JsonObject }.orEmpty()
fun JsonArray?.ints(): List<Int> = this?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.orEmpty()
fun JsonArray?.strings(): List<String> = this?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }.orEmpty()

fun parseJson(text: String): JsonElement = Json.parseToJsonElement(text)
