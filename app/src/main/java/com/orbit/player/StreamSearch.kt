package com.orbit.player

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

// Searches for a title's streams, after NuvioTV's StreamSearchSessionCache (NuvioMedia/NuvioTV,
// GPL-3.0): every addon is asked at once, and a search is kept for 15 minutes, so going back
// to a title shows its streams straight away. A search that's still running is shared, so
// opening the sources page while Orbit is already looking (see prefetch) just joins in.
object StreamSearch {

    interface Listener {
        // The addons being asked, in the account's order.
        fun onAddons(addons: List<StremioStreams.Addon>)
        // One addon's answer (possibly empty); called on the main thread.
        fun onResult(addon: StremioStreams.Addon, streams: List<StremioStreams.Stream>)
    }

    class Session internal constructor(val type: String, val id: String, private val addonKey: String) {
        @Volatile var addons: List<StremioStreams.Addon>? = null
            internal set
        // Answers so far, in arrival order.
        val results = CopyOnWriteArrayList<Pair<StremioStreams.Addon, List<StremioStreams.Stream>>>()
        @Volatile var completedAt = 0L
            internal set
        private val listeners = CopyOnWriteArrayList<Listener>()
        internal val key get() = "$addonKey|$type|$id"

        val pending: Int get() = addons?.let { it.size - results.size } ?: -1

        // Replays what's already in, then passes on what arrives. Main thread.
        fun observe(listener: Listener) {
            addons?.let { listener.onAddons(it) }
            results.forEach { (addon, streams) -> listener.onResult(addon, streams) }
            listeners.add(listener)
        }

        fun stop(listener: Listener) {
            listeners.remove(listener)
        }

        internal fun publishAddons(list: List<StremioStreams.Addon>) = main.post {
            addons = list
            if (list.isEmpty()) completedAt = System.currentTimeMillis()
            listeners.forEach { it.onAddons(list) }
        }

        internal fun publish(addon: StremioStreams.Addon, streams: List<StremioStreams.Stream>) = main.post {
            results.add(addon to streams)
            if (pending == 0) completedAt = System.currentTimeMillis()
            listeners.forEach { it.onResult(addon, streams) }
        }
    }

    private const val keepMs = 15 * 60 * 1000L
    private val main = Handler(Looper.getMainLooper())
    private val sessions = LinkedHashMap<String, Session>()
    // No cap on addons asked at once (as NuvioTV): a slow addon never holds up another.
    private val pool = Executors.newCachedThreadPool()

    // The search for this title: a running or recent one, else a new one. Main thread.
    fun get(type: String, id: String, fresh: Boolean = false): Session {
        val addonKey = StremioAddons.addonUrls.joinToString(",").hashCode().toString()
        val key = "$addonKey|$type|$id"
        val now = System.currentTimeMillis()
        sessions.entries.removeAll { (_, s) -> s.completedAt > 0 && now - s.completedAt > keepMs }
        sessions[key]?.let { existing ->
            // A finished search that found nothing (offline, addons down) is tried again.
            val foundNothing = existing.completedAt > 0 && existing.results.all { it.second.isEmpty() }
            if (!fresh && !foundNothing) return existing
        }
        val session = Session(type, id, addonKey)
        sessions[key] = session
        while (sessions.size > 30) sessions.remove(sessions.keys.first())
        pool.execute {
            val addons = try {
                StremioStreams.addonsFor(type, id)
            } catch (_: Exception) {
                emptyList()
            }
            session.publishAddons(addons)
            addons.forEach { addon ->
                pool.execute {
                    val streams = try {
                        StremioStreams.fetchStreams(addon, type, id)
                    } catch (_: Exception) {
                        emptyList()
                    }
                    session.publish(addon, streams)
                }
            }
        }
        return session
    }

    // Starts looking before the viewer asks (a film's page opening), so the sources page
    // usually has its streams already.
    fun prefetch(type: String, id: String) {
        main.post { get(type, id) }
    }
}
