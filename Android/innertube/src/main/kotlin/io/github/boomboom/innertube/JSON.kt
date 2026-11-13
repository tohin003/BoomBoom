package io.github.boomboom.innertube

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

// InnerTube answers are deep, loosely typed trees whose shape shifts between experiments, so the parsers walk a
// kotlinx.serialization `JsonElement` tree with paths (`json["contents", 0, "musicShelfRenderer"]`) instead of
// decoding fixed classes. Reading is safe: a missing key or index gives `JsonNull`, never an exception.
//
// Keep the values you navigate typed as `JsonElement`: on a `JsonObject` or `JsonArray` variable, Kotlin picks the
// collection's own single-argument `get` (which returns null for a missing key) instead of the path form below.

/** One step of a path is a key into an object (a `String`) or an index into an array (an `Int`, negative from the end). */
internal operator fun JsonElement.get(vararg path: Any): JsonElement {
    var node: JsonElement = this
    for (step in path) {
        node = when {
            step is String && node is JsonObject -> node[step] ?: JsonNull
            step is Int && node is JsonArray -> {
                val resolved = if (step < 0) node.size + step else step
                if (resolved !in node.indices) return JsonNull
                node[resolved]
            }
            else -> return JsonNull
        }
    }
    return node
}

internal val JsonElement.exists: Boolean get() = this !is JsonNull

private val strictNumber = Regex("""[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?""")

/** A decimal number in plain notation, or null: a stricter reading than `toDoubleOrNull` (which takes "1f", "NaN"). */
internal fun parseDecimal(text: String): Double? = if (strictNumber.matches(text)) text.toDoubleOrNull() else null

/** The number a JSON number literal holds; null for strings, booleans and null. */
private fun JsonPrimitive.numberValue(): Double? =
    if (this is JsonNull || isString || content == "true" || content == "false") null else content.toDoubleOrNull()

private fun numberText(value: Double): String =
    if (value == floor(value) && abs(value) < 9.2e18) value.toLong().toString() else value.toString()

internal val JsonElement.string: String?
    get() = when (this) {
        is JsonNull -> null
        is JsonPrimitive -> if (isString) content else numberValue()?.let(::numberText)
        else -> null
    }

/** A non-empty string, or null. */
internal val JsonElement.text: String?
    get() = string?.takeIf { it.isNotEmpty() }

internal val JsonElement.int: Int?
    get() = when (this) {
        is JsonNull -> null
        is JsonPrimitive ->
            if (isString) {
                content.toIntOrNull()
            } else {
                // Truncated towards zero; null (not a crash) for NaN, infinity or a number too big for an Int.
                numberValue()?.let { value ->
                    val whole = if (value < 0) ceil(value) else floor(value)
                    if (whole >= Int.MIN_VALUE && whole <= Int.MAX_VALUE) whole.toInt() else null
                }
            }
        else -> null
    }

internal val JsonElement.double: Double?
    get() = when (this) {
        is JsonNull -> null
        is JsonPrimitive -> if (isString) parseDecimal(content) else numberValue()
        else -> null
    }

internal val JsonElement.bool: Boolean?
    get() = when (this) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            isString -> when (content) {
                "true" -> true
                "false" -> false
                else -> null
            }
            content == "true" -> true
            content == "false" -> false
            else -> null
        }
        else -> null
    }

/** The elements, or an empty list for anything that is not an array. */
internal val JsonElement.array: List<JsonElement>
    get() = this as? JsonArray ?: emptyList()

internal val JsonElement.obj: JsonObject? get() = this as? JsonObject

/** For a single-key wrapper such as `{"musicShelfRenderer": {...}}`: the key and its value. */
internal val JsonElement.soleEntry: Pair<String, JsonElement>?
    get() = (this as? JsonObject)?.entries?.firstOrNull { it.key != "trackingParams" }?.let { it.key to it.value }

/** The text of a `{"runs": [...]}` or `{"simpleText": ...}` node, or a plain string. */
internal val JsonElement.runsText: String?
    get() {
        if (this is JsonPrimitive && this !is JsonNull && isString) return content.ifEmpty { null }
        val runs = this["runs"].array
        if (runs.isNotEmpty()) {
            val joined = runs.mapNotNull { it["text"].string }.joinToString("")
            return joined.ifEmpty { null }
        }
        return this["simpleText"].text ?: this["content"].text
    }

internal val JsonElement.runs: List<JsonElement> get() = this["runs"].array

// Reading and writing

private val parser = Json

internal fun parseJson(bytes: ByteArray): JsonElement = parseJson(String(bytes, Charsets.UTF_8))

/**
 * Throws `SerializationException` when [text] is not JSON. A bare value (`3`) is accepted.
 *
 * kotlinx.serialization reads any unquoted word as a value (`<html>` parses as one), where an answer that is a web
 * page, not JSON, must be refused; so every unquoted value is checked to be a JSON number (`true`, `false` and
 * `null` are told apart by the parser itself).
 */
internal fun parseJson(text: String): JsonElement = parser.parseToJsonElement(text).also(::requireStrictJson)

private val jsonNumber = Regex("""-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?""")

private fun requireStrictJson(element: JsonElement) {
    when (element) {
        is JsonObject -> element.values.forEach(::requireStrictJson)
        is JsonArray -> element.forEach(::requireStrictJson)
        is JsonNull -> {}
        is JsonPrimitive ->
            if (!element.isString && element.content != "true" && element.content != "false" &&
                !jsonNumber.matches(element.content)
            ) {
                throw SerializationException("not JSON: unquoted value")
            }
    }
}

/** Compact JSON with sorted keys, so equal trees give equal bytes. */
internal fun JsonElement.toJsonBytes(): ByteArray = sortedKeys().toString().toByteArray(Charsets.UTF_8)

internal fun JsonElement.sortedKeys(): JsonElement = when (this) {
    is JsonObject -> JsonObject(entries.sortedBy { it.key }.associate { it.key to it.value.sortedKeys() })
    is JsonArray -> JsonArray(map { it.sortedKeys() })
    else -> this
}

// Building values (request bodies, and the trees tests make up)

internal fun json(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> key as String to json(item) })
    is Iterable<*> -> JsonArray(value.map(::json))
    else -> throw IllegalArgumentException("not a JSON value: $value")
}

internal fun obj(vararg entries: Pair<String, Any?>): JsonObject =
    JsonObject(entries.associate { (key, value) -> key to json(value) })

internal fun arr(vararg items: Any?): JsonArray = JsonArray(items.map(::json))

/** A copy of this object with [key] set (an object is made of anything else, as Swift's `set` did). */
internal fun JsonElement.with(key: String, value: Any?): JsonObject {
    val entries = LinkedHashMap<String, JsonElement>((this as? JsonObject) ?: emptyMap())
    entries[key] = json(value)
    return JsonObject(entries)
}

/** A request body being put together: `body["videoId"] = id`. */
internal class BodyBuilder {
    private val entries = LinkedHashMap<String, JsonElement>()

    operator fun set(key: String, value: Any?) {
        entries[key] = json(value)
    }

    fun build(): JsonObject = JsonObject(entries)
}

internal inline fun body(block: BodyBuilder.() -> Unit): JsonObject = BodyBuilder().apply(block).build()
