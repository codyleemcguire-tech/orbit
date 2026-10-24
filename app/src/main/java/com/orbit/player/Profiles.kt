package com.orbit.player

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

// Viewer profiles. Each keeps its own settings, Stremio login, addons and collections (one
// preferences file) and its own watch progress (a second file). The first profile uses the
// files Orbit had before profiles existed, so nothing needs migrating.
object Profiles {

    data class Profile(val id: String, val name: String, val color: Int)

    val colors = listOf(
        Color.rgb(108, 140, 255), Color.rgb(255, 122, 104), Color.rgb(72, 200, 140),
        Color.rgb(255, 190, 70), Color.rgb(176, 120, 255), Color.rgb(60, 196, 210)
    )

    private const val mainId = "main"

    // Chosen on the "Who's watching?" screen; lasts until Orbit's process ends.
    var chosenThisLaunch = false

    private fun store(context: Context) = context.getSharedPreferences("orbit_profiles", Context.MODE_PRIVATE)

    fun prefsName(id: String) = if (id == mainId) "orbit" else "orbit_$id"
    fun progressName(id: String) = if (id == mainId) "orbit_progress" else "orbit_progress_$id"

    fun list(context: Context): List<Profile> {
        val json = store(context).getString("profiles", null)
        val parsed = try {
            json?.let { JSONArray(it) }?.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                    Profile(it.getString("id"), it.getString("name"), it.optInt("color", colors[0]))
                }
            }
        } catch (_: Exception) {
            null
        }
        return parsed?.takeIf { it.isNotEmpty() } ?: listOf(Profile(mainId, "Main", colors[0]))
    }

    private fun save(context: Context, profiles: List<Profile>) {
        val arr = JSONArray()
        profiles.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("color", it.color)) }
        store(context).edit().putString("profiles", arr.toString()).commit()
    }

    fun active(context: Context): Profile {
        val all = list(context)
        val id = store(context).getString("active", null)
        return all.firstOrNull { it.id == id } ?: all.first()
    }

    fun setActive(context: Context, id: String) {
        store(context).edit().putString("active", id).commit()
    }

    // First time Orbit is opened: no profiles saved and nothing in the first one yet.
    // Anyone updating from an earlier Orbit already has data, so skips the welcome.
    fun isFirstRun(context: Context): Boolean {
        val s = store(context)
        if (s.getBoolean("onboarded", false)) return false
        val used = s.contains("profiles") || prefs(context, mainId).all.isNotEmpty() ||
            context.getSharedPreferences(progressName(mainId), Context.MODE_PRIVATE).all.isNotEmpty()
        if (used) setOnboarded(context)
        return !used
    }

    fun setOnboarded(context: Context) {
        store(context).edit().putBoolean("onboarded", true).commit()
    }

    fun prefs(context: Context, id: String): SharedPreferences =
        context.getSharedPreferences(prefsName(id), Context.MODE_PRIVATE)

    // A name that isn't taken yet: "Cody", "Cody 2", …
    private fun freeName(all: List<Profile>, name: String, except: String? = null): String {
        val base = name.trim().ifBlank { "Profile" }.take(20)
        var candidate = base
        var n = 2
        while (all.any { it.id != except && it.name.equals(candidate, true) }) candidate = "$base ${n++}"
        return candidate
    }

    fun add(context: Context, name: String): Profile {
        val all = list(context)
        val used = all.map { it.color }
        val profile = Profile(
            "p" + System.currentTimeMillis().toString(36),
            freeName(all, name),
            colors.firstOrNull { it !in used } ?: colors[all.size % colors.size]
        )
        save(context, all + profile)
        return profile
    }

    fun rename(context: Context, id: String, name: String) {
        val all = list(context)
        save(context, all.map { if (it.id == id) it.copy(name = freeName(all, name, except = id)) else it })
    }

    // Removes a profile and everything in it. The active profile can't be removed.
    fun delete(context: Context, id: String): Boolean {
        val all = list(context)
        if (all.size <= 1 || id == active(context).id) return false
        save(context, all.filter { it.id != id })
        context.deleteSharedPreferences(prefsName(id))
        context.deleteSharedPreferences(progressName(id))
        return true
    }

    // Everything in a profile, for sending to another device.
    fun export(context: Context, id: String): JSONObject {
        val profile = list(context).first { it.id == id }
        return JSONObject()
            .put("orbitProfile", 1)
            .put("name", profile.name)
            .put("color", profile.color)
            .put("prefs", dump(context.getSharedPreferences(prefsName(id), Context.MODE_PRIVATE)))
            .put("progress", dump(context.getSharedPreferences(progressName(id), Context.MODE_PRIVATE)))
    }

    // Adds a received profile as a new one on this device and returns it; with into, it
    // replaces that profile's contents instead (a new device's empty first profile).
    fun import(context: Context, data: JSONObject, into: String? = null): Profile {
        require(data.optInt("orbitProfile") == 1) { "Not an Orbit profile" }
        val profile = into?.let { id -> list(context).firstOrNull { it.id == id } }
            ?.also { rename(context, it.id, data.optString("name", it.name)) }
            ?: add(context, data.optString("name", "Profile"))
        load(context.getSharedPreferences(prefsName(profile.id), Context.MODE_PRIVATE), data.optJSONObject("prefs"))
        load(context.getSharedPreferences(progressName(profile.id), Context.MODE_PRIVATE), data.optJSONObject("progress"))
        val color = data.optInt("color", profile.color)
        save(context, list(context).map { if (it.id == profile.id) it.copy(color = color) else it })
        return profile.copy(color = color)
    }

    // Preferences as JSON, each value tagged with its type so it reads back the same.
    private fun dump(prefs: SharedPreferences): JSONObject {
        val out = JSONObject()
        prefs.all.forEach { (key, value) ->
            val entry = when (value) {
                is String -> JSONObject().put("t", "s").put("v", value)
                is Boolean -> JSONObject().put("t", "b").put("v", value)
                is Int -> JSONObject().put("t", "i").put("v", value)
                is Long -> JSONObject().put("t", "l").put("v", value)
                is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(value.map { it.toString() }))
                else -> null
            }
            if (entry != null) out.put(key, entry)
        }
        return out
    }

    private fun load(prefs: SharedPreferences, data: JSONObject?) {
        val editor = prefs.edit().clear()
        data?.keys()?.forEach { key ->
            val entry = data.optJSONObject(key) ?: return@forEach
            when (entry.optString("t")) {
                "s" -> editor.putString(key, entry.optString("v"))
                "b" -> editor.putBoolean(key, entry.optBoolean("v"))
                "i" -> editor.putInt(key, entry.optInt("v"))
                "l" -> editor.putLong(key, entry.optLong("v"))
                "f" -> editor.putFloat(key, entry.optDouble("v").toFloat())
                "set" -> entry.optJSONArray("v")?.let { arr ->
                    editor.putStringSet(key, (0 until arr.length()).map { arr.optString(it) }.toSet())
                }
            }
        }
        editor.commit()
    }
}
