package com.orbit.player

import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import java.util.Locale

// Subtitle preferences and how they're applied in the player. Modelled on Nuvio's
// subtitle settings (NuvioMedia/NuvioTV, GPL-3.0), notably "Use Forced Subtitles": when
// the audio is in your preferred subtitle language, only a forced track (signs and
// foreign-language lines) is shown, and nothing if there isn't one.
class SubtitleSettings(private val prefs: SharedPreferences) {

    var language: String get() = prefs.getString("sub_lang", "") ?: ""
        set(v) = prefs.edit().putString("sub_lang", v).apply()
    var secondLanguage: String get() = prefs.getString("sub_lang2", "") ?: ""
        set(v) = prefs.edit().putString("sub_lang2", v).apply()
    var audioLanguage: String get() = prefs.getString("audio_lang", "") ?: ""
        set(v) = prefs.edit().putString("audio_lang", v).apply()
    var forcedOnly: Boolean get() = prefs.getBoolean("sub_forced", false)
        set(v) = prefs.edit().putBoolean("sub_forced", v).apply()
    var stripSdh: Boolean get() = prefs.getBoolean("sub_strip_sdh", false)
        set(v) = prefs.edit().putBoolean("sub_strip_sdh", v).apply()
    var size: String get() = prefs.getString("sub_size", "Medium") ?: "Medium"
        set(v) = prefs.edit().putString("sub_size", v).apply()
    var color: String get() = prefs.getString("sub_color", "White") ?: "White"
        set(v) = prefs.edit().putString("sub_color", v).apply()
    var background: String get() = prefs.getString("sub_bg", "None") ?: "None"
        set(v) = prefs.edit().putString("sub_bg", v).apply()
    var outline: Boolean get() = prefs.getBoolean("sub_outline", true)
        set(v) = prefs.edit().putBoolean("sub_outline", v).apply()
    var bold: Boolean get() = prefs.getBoolean("sub_bold", false)
        set(v) = prefs.edit().putBoolean("sub_bold", v).apply()
    var position: String get() = prefs.getString("sub_position", "Low") ?: "Low"
        set(v) = prefs.edit().putString("sub_position", v).apply()
    var addonSubtitles: Boolean get() = prefs.getBoolean("sub_addons", true)
        set(v) = prefs.edit().putBoolean("sub_addons", v).apply()
    var onlyPreferred: Boolean get() = prefs.getBoolean("sub_only_preferred", true)
        set(v) = prefs.edit().putBoolean("sub_only_preferred", v).apply()

    companion object {
        // Offered languages: name to ISO 639-1 code.
        val languages = listOf(
            "English" to "en", "Spanish" to "es", "French" to "fr", "German" to "de",
            "Italian" to "it", "Portuguese" to "pt", "Dutch" to "nl", "Swedish" to "sv",
            "Norwegian" to "no", "Danish" to "da", "Finnish" to "fi", "Polish" to "pl",
            "Czech" to "cs", "Hungarian" to "hu", "Romanian" to "ro", "Greek" to "el",
            "Turkish" to "tr", "Russian" to "ru", "Ukrainian" to "uk", "Arabic" to "ar",
            "Hebrew" to "he", "Hindi" to "hi", "Japanese" to "ja", "Korean" to "ko",
            "Chinese" to "zh", "Thai" to "th", "Vietnamese" to "vi", "Indonesian" to "id"
        )
        val sizes = listOf("Small", "Medium", "Large", "Extra large")
        val colors = listOf("White", "Yellow", "Cyan", "Green")
        val backgrounds = listOf("None", "Translucent", "Black")
        val positions = listOf("Lowest", "Low", "Middle", "High")

        fun languageName(code: String) =
            if (code.isBlank()) "Off" else languages.firstOrNull { it.second == code }?.first ?: code

        // Older bibliographic 3-letter codes that files often use, mapped to the standard ones.
        private val bibliographic = mapOf(
            "fre" to "fra", "ger" to "deu", "dut" to "nld", "chi" to "zho", "gre" to "ell",
            "cze" to "ces", "rum" to "ron", "per" to "fas", "slo" to "slk", "ice" to "isl",
            "mac" to "mkd", "may" to "msa", "alb" to "sqi", "arm" to "hye", "geo" to "kat",
            // OpenSubtitles' own code for Brazilian Portuguese.
            "pob" to "por"
        )

        // "en", "eng", "en-US", "English" all become "eng"; "nob"/"nno" count as Norwegian.
        fun normalize(raw: String?): String? {
            val base = raw?.trim()?.lowercase()?.split('-', '_', ' ')?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
            languages.firstOrNull { it.first.lowercase() == base }?.let { return normalize(it.second) }
            val three = if (base.length == 2) try {
                Locale(base).isO3Language
            } catch (_: Exception) {
                base
            } else base
            val standard = bibliographic[three] ?: three
            return if (standard == "nob" || standard == "nno") "nor" else standard
        }

        fun isForced(format: Format) =
            format.selectionFlags and C.SELECTION_FLAG_FORCED != 0 ||
                format.label?.contains("forced", ignoreCase = true) == true

        fun isSdh(format: Format) =
            format.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0 ||
                Regex("\\b(sdh|cc|hearing)\\b", RegexOption.IGNORE_CASE).containsMatchIn(format.label.orEmpty())

        // Removes sound descriptions ([door slams], (laughs), ♪ lines) and speaker labels.
        fun stripSdh(text: CharSequence): CharSequence {
            val lines = text.toString().lines().mapNotNull { line ->
                var l = line.replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)"), "")
                l = l.replace(Regex("^\\s*-?\\s*[A-Z][A-Z0-9 .'&-]{1,30}:\\s*"), "")
                if (l.contains('♪') || l.contains('♫')) l = ""
                l.trim().takeIf { it.isNotEmpty() && it != "-" }
            }
            return lines.joinToString("\n")
        }
    }

    // A subtitle track to switch on: the group and the track within it.
    data class Choice(val group: Tracks.Group, val index: Int)

    // Picks the subtitle track for this video; null means subtitles off.
    fun choose(tracks: Tracks): Choice? {
        val preferred = normalize(language) ?: return null
        val second = normalize(secondLanguage)
        val audio = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }?.let { g ->
            (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { normalize(g.getTrackFormat(it).language) }
        }
        val text = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            .flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }

        fun find(lang: String, forced: Boolean): Choice? {
            val matches = text.filter { (g, i) ->
                val f = g.getTrackFormat(i)
                normalize(f.language) == lang && isForced(f) == forced
            }
            // Regular dialogue tracks rather than SDH ones when both exist.
            val best = matches.firstOrNull { (g, i) -> !isSdh(g.getTrackFormat(i)) } ?: matches.firstOrNull()
            return best?.let { Choice(it.first, it.second) }
        }

        if (forcedOnly && audio != null && audio == preferred) {
            // Audio already in your language: forced lines only, or nothing.
            return find(preferred, forced = true)
        }
        return find(preferred, forced = false) ?: second?.let { find(it, forced = false) }
    }

    // When the video's own tracks have nothing suitable: the addon subtitle to show, by the
    // same rules as choose(). Subs are already ranked best match first.
    fun chooseAddon(tracks: Tracks, subs: List<StremioSubtitles.Subtitle>): StremioSubtitles.Subtitle? {
        val preferred = normalize(language) ?: return null
        val second = normalize(secondLanguage)
        val audio = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }?.let { g ->
            (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { normalize(g.getTrackFormat(it).language) }
        }
        fun find(lang: String, forced: Boolean) = subs.firstOrNull { normalize(it.language) == lang && it.forced == forced }
        if (forcedOnly && audio != null && audio == preferred) return find(preferred, forced = true)
        return find(preferred, forced = false) ?: second?.let { find(it, forced = false) }
    }

    fun applyStyle(view: SubtitleView?) {
        view ?: return
        val fg = when (color) {
            "Yellow" -> Color.rgb(255, 230, 80)
            "Cyan" -> Color.rgb(120, 230, 255)
            "Green" -> Color.rgb(140, 240, 140)
            else -> Color.WHITE
        }
        val bg = when (background) {
            "Translucent" -> Color.argb(150, 0, 0, 0)
            "Black" -> Color.BLACK
            else -> Color.TRANSPARENT
        }
        view.setStyle(
            CaptionStyleCompat(
                fg, bg, Color.TRANSPARENT,
                if (outline) CaptionStyleCompat.EDGE_TYPE_OUTLINE else CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                Color.BLACK,
                Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            )
        )
        // Our style replaces any colours or fonts embedded in the subtitle file.
        view.setApplyEmbeddedStyles(false)
        view.setApplyEmbeddedFontSizes(false)
        view.setFractionalTextSize(
            when (size) {
                "Small" -> 0.045f
                "Large" -> 0.068f
                "Extra large" -> 0.08f
                else -> 0.056f
            }
        )
        view.setBottomPaddingFraction(
            when (position) {
                "Lowest" -> 0.03f
                "Middle" -> 0.2f
                "High" -> 0.32f
                else -> 0.08f
            }
        )
    }

    fun filterCues(cues: List<Cue>): List<Cue> =
        if (!stripSdh) cues
        else cues.mapNotNull { cue ->
            val text = cue.text ?: return@mapNotNull cue // image subtitles pass through
            val cleaned = stripSdh(text)
            if (cleaned.isBlank()) null else cue.buildUpon().setText(cleaned).build()
        }
}
