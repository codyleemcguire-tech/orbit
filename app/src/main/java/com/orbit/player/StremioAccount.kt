package com.orbit.player

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Signs in to a Stremio account and reads its installed addons, so Discover shows the
// same catalogs as Stremio (and any app synced from it). Only the session key is kept;
// the password is sent once to Stremio and never stored.
object StremioAccount {

    data class Session(val authKey: String, val email: String)
    data class Addon(val name: String, val url: String)

    class SessionExpiredException : Exception("Stremio session expired")

    fun login(email: String, password: String): Session {
        val result = call("login", JSONObject().apply {
            put("type", "Login")
            put("email", email)
            put("password", password)
            put("facebook", false)
        })
        val authKey = result.optString("authKey")
        if (authKey.isBlank()) throw IllegalStateException("Stremio didn't return a session")
        val accountEmail = result.optJSONObject("user")?.optString("email").orEmpty()
        return Session(authKey, accountEmail.ifBlank { email })
    }

    fun fetchAddons(authKey: String): List<Addon> {
        val result = call("addonCollectionGet", JSONObject().apply {
            put("type", "AddonCollectionGet")
            put("authKey", authKey)
            put("update", true)
        })
        val addons = result.optJSONArray("addons") ?: return emptyList()
        return (0 until addons.length()).mapNotNull { i ->
            val addon = addons.optJSONObject(i) ?: return@mapNotNull null
            val url = addon.optString("transportUrl")
            if (!url.startsWith("http")) return@mapNotNull null
            val name = addon.optJSONObject("manifest")?.optString("name").orEmpty()
            Addon(name.ifBlank { url }, url)
        }
    }

    fun logout(authKey: String) {
        try {
            call("logout", JSONObject().apply {
                put("type", "Logout")
                put("authKey", authKey)
            })
        } catch (_: Exception) {
            // Signing out locally is enough if Stremio can't be reached.
        }
    }

    private fun call(method: String, body: JSONObject): JSONObject =
        callAny(method, body) as? JSONObject ?: throw IllegalStateException("Stremio sent an unexpected answer")

    // The "result" of any API method: an object for most, an array for datastoreGet.
    internal fun callAny(method: String, body: JSONObject): Any {
        val connection = URL("https://api.strem.io/api/$method").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.outputStream.use { it.write(body.toString().toByteArray()) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = JSONObject(stream.bufferedReader().use { it.readText() })

            response.optJSONObject("error")?.let { error ->
                // Code 1: the session no longer exists (signed out elsewhere or expired).
                if (error.optInt("code") == 1) throw SessionExpiredException()
                throw IllegalStateException(error.optString("message", "Stremio error"))
            }
            return response.opt("result")?.takeIf { it != JSONObject.NULL }
                ?: throw IllegalStateException("Stremio HTTP $status")
        } finally {
            connection.disconnect()
        }
    }
}
