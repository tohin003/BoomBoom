package io.github.boomboom.logic

/**
 * Where the stores keep small saved values (settings, presets, favourites, the accounts' names, flags): the iPhone
 * app's `UserDefaults`. The Android app backs it with its own storage; tests use [InMemoryKeyValueStore].
 *
 * A getter returns null when the key holds nothing, or holds a value of another type. Lists and objects are saved by
 * the stores as JSON text under a string key. Longs are here (the iPhone's cache limit is an Int64, and ten
 * gigabytes does not fit an Int).
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getBoolean(key: String): Boolean?
    fun putBoolean(key: String, value: Boolean)
    fun getInt(key: String): Int?
    fun putInt(key: String, value: Int)
    fun getLong(key: String): Long?
    fun putLong(key: String, value: Long)
    fun remove(key: String)
}

/** Keeps everything in memory. For tests and previews. */
class InMemoryKeyValueStore : KeyValueStore {
    private val values = HashMap<String, Any>()

    override fun getString(key: String): String? = values[key] as? String
    override fun putString(key: String, value: String) { values[key] = value }
    override fun getBoolean(key: String): Boolean? = values[key] as? Boolean
    override fun putBoolean(key: String, value: Boolean) { values[key] = value }
    override fun getInt(key: String): Int? = values[key] as? Int
    override fun putInt(key: String, value: Int) { values[key] = value }
    override fun getLong(key: String): Long? = values[key] as? Long
    override fun putLong(key: String, value: Long) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
}
