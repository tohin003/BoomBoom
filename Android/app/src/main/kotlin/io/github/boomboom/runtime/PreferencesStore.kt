package io.github.boomboom.runtime

import android.content.Context
import android.content.SharedPreferences
import io.github.boomboom.logic.KeyValueStore

/**
 * The stores' saved values (the iPhone app's `UserDefaults`) in the app's own `SharedPreferences`. A value of another
 * type than the one asked for reads as nothing, as the interface asks. Writes are applied in the background.
 */
class PreferencesStore(context: Context, name: String = "boom") : KeyValueStore {
    private val preferences: SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private inline fun <T> read(key: String, get: () -> T): T? =
        if (!preferences.contains(key)) {
            null
        } else {
            try {
                get()
            } catch (otherType: ClassCastException) {
                null
            }
        }

    override fun getString(key: String): String? = read(key) { preferences.getString(key, null) }
    override fun putString(key: String, value: String) = preferences.edit().putString(key, value).apply()
    override fun getBoolean(key: String): Boolean? = read(key) { preferences.getBoolean(key, false) }
    override fun putBoolean(key: String, value: Boolean) = preferences.edit().putBoolean(key, value).apply()
    override fun getInt(key: String): Int? = read(key) { preferences.getInt(key, 0) }
    override fun putInt(key: String, value: Int) = preferences.edit().putInt(key, value).apply()
    override fun getLong(key: String): Long? = read(key) { preferences.getLong(key, 0) }
    override fun putLong(key: String, value: Long) = preferences.edit().putLong(key, value).apply()
    override fun remove(key: String) = preferences.edit().remove(key).apply()
}
