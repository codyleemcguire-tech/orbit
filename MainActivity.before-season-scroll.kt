package com.orbit.player

import android.app.Activity
import android.os.Bundle
import kotlin.concurrent.thread
import java.net.HttpURLConnection
import java.net.URL
import android.os.Looper
import android.os.Handler
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class MainActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var root: LinearLayout
    private lateinit var playerView: PlayerView
    private var currentScreen = "home"
    private var lastFilmTitle = ""
    private var lastPosterFile = ""

    private val bg = Color.rgb(8, 12, 24)
    private val accent = Color.rgb(90, 125, 255)
    private val testUrl =
        "https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
        window.navigationBarColor = bg

        showHomeScreen()
    }

    private fun loadPosterImage(image: ImageView, posterPath: String?) {
        if (posterPath.isNullOrBlank()) return

        thread {
            try {
                val url = URL("https://image.tmdb.org/t/p/w342$posterPath")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val bitmap = connection.inputStream.use {
                    BitmapFactory.decodeStream(it)
                }
                connection.disconnect()

                if (bitmap != null) {
                    Handler(Looper.getMainLooper()).post {
                        image.setImageBitmap(bitmap)
                    }
                }
            } catch (_: Exception) {
                // Keep the existing image if downloading fails.
            }
        }
    }

    private fun showHomeScreen() {
        currentScreen = "home"
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(40, 24, 40, 24)
            setBackground(
                GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(
                        Color.rgb(12, 22, 48),
                        Color.rgb(8, 12, 24),
                        Color.rgb(18, 14, 42)
                    )
                )
            )
        }

        val homeScroll = ScrollView(this).apply {
            isFillViewport = true
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        homeScroll.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val header = TextView(this).apply {
            text = "O R B I T"
            textSize = 38f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        root.addView(header)

        val tagline = TextView(this).apply {
            text = "YOUR ENTERTAINMENT. YOUR UNIVERSE."
            textSize = 13f
            letterSpacing = 0.12f
            setTextColor(Color.rgb(155, 175, 220))
            setPadding(0, 8, 0, 48)
        }
        root.addView(tagline)

        val eyebrow = TextView(this).apply {
            text = "WELCOME HOME"
            textSize = 13f
            letterSpacing = 0.2f
            setTextColor(accent)
        }
        root.addView(eyebrow)

        val featured = TextView(this).apply {
            text = "Your next adventure\nstarts here."
            textSize = 34f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(0, 12, 0, 14)
        }
        root.addView(featured)

        val description = TextView(this).apply {
            text = "Discover your personal world of films and shows."
            textSize = 17f
            setTextColor(Color.rgb(195, 202, 220))
            setPadding(0, 0, 0, 32)
        }
        root.addView(description)

        val playButton = TextView(this).apply {
            text = "▶   PLAY TEST VIDEO"
            textSize = 16f
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(accent)
                cornerRadius = 12f
            }
            setOnClickListener { playTestVideo() }
        }
        root.addView(
            playButton,
            LinearLayout.LayoutParams(360, 68)
        )

        val rowTitle = TextView(this).apply {
            text = "EXPLORE"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(0, 30, 0, 12)
        }
        root.addView(rowTitle)

        val posterScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
        }

        val posterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        data class HomeFilm(
            val title: String,
            val localPoster: String?,
            val tmdbPosterPath: String? = null
        )

        val fallbackFilms = listOf(
            HomeFilm("THE DARK KNIGHT", "dark_knight.jpg"),
            HomeFilm("INTERSTELLAR", "interstellar.jpg"),
            HomeFilm("THE MATRIX", "matrix.jpg"),
            HomeFilm("INCEPTION", "inception.jpg"),
            HomeFilm("GLADIATOR", "gladiator.jpg")
        )

        fun showFilms(films: List<HomeFilm>) {
            posterRow.removeAllViews()

            films.forEach { film ->
                val poster = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    setPadding(6, 6, 6, 10)
                    background = GradientDrawable().apply {
                        setColor(bg)
                        cornerRadius = 12f
                    }
                    isFocusable = true
                    isClickable = true
                    setOnClickListener {
                        val posterRef = film.tmdbPosterPath
                            ?: film.localPoster.orEmpty()
                        showFilmDetails(film.title, posterRef)
                    }
                    setOnFocusChangeListener { view, focused ->
                        view.scaleX = if (focused) 1.07f else 1.0f
                        view.scaleY = if (focused) 1.07f else 1.0f
                        view.elevation = if (focused) 12f else 0f
                        view.background = GradientDrawable().apply {
                            setColor(if (focused) Color.rgb(35, 55, 105) else bg)
                            cornerRadius = 12f
                            if (focused) setStroke(3, accent)
                        }
                    }
                }

                val image = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = film.title
                }

                if (film.tmdbPosterPath != null) {
                    loadPosterImage(image, film.tmdbPosterPath)
                } else if (film.localPoster != null) {
                    try {
                        assets.open("posters/${film.localPoster}").use { stream ->
                            image.setImageBitmap(BitmapFactory.decodeStream(stream))
                        }
                    } catch (_: Exception) {
                    }
                }

                poster.addView(image, LinearLayout.LayoutParams(-1, 285))

                posterRow.addView(
                    poster,
                    LinearLayout.LayoutParams(190, 285).apply {
                        setMargins(0, 0, 14, 0)
                    }
                )
            }
        }

        showFilms(fallbackFilms)
        posterScroll.addView(posterRow)
        root.addView(posterScroll)

        thread {
            try {
                val key = BuildConfig.TMDB_API_KEY
                if (key.isNotBlank()) {
                    val liveFilms = TmdbTest.fetchPopularMovies(key)
                        .filter { !it.title.isBlank() && it.posterPath != null }
                        .map {
                            HomeFilm(
                                title = it.title,
                                localPoster = null,
                                tmdbPosterPath = it.posterPath
                            )
                        }

                    if (liveFilms.isNotEmpty()) {
                        Handler(Looper.getMainLooper()).post {
                            if (currentScreen == "home") showFilms(liveFilms)
                        }
                    }
                }
            } catch (_: Exception) {
                // Keep the local fallback posters if TMDB is unavailable.
            }
        }

        val tvTitle = TextView(this).apply {
            text = "POPULAR TV SHOWS"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(0, 30, 0, 12)
        }
        root.addView(tvTitle)

        val tvScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
        }
        val tvRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        tvScroll.addView(tvRow)
        root.addView(tvScroll)

        thread {
            try {
                val key = BuildConfig.TMDB_API_KEY
                if (key.isNotBlank()) {
                    val shows = TmdbTest.fetchPopularTvShows(key)
                        .filter { it.title.isNotBlank() && it.posterPath != null }

                    Handler(Looper.getMainLooper()).post {
                        if (currentScreen == "home") {
                            tvRow.removeAllViews()
                            shows.forEach { show ->
                                val poster = LinearLayout(this).apply {
                                    orientation = LinearLayout.VERTICAL
                                    gravity = Gravity.CENTER
                                    setPadding(6, 6, 6, 10)
                                    background = GradientDrawable().apply {
                                        setColor(bg)
                                        cornerRadius = 12f
                                    }
                                    isFocusable = true
                                    isClickable = true
                                    setOnClickListener {
                                        showTvDetails(show.id, show.title, show.posterPath.orEmpty())
                                    }
                                    setOnFocusChangeListener { view, focused ->
                                        view.scaleX = if (focused) 1.07f else 1.0f
                                        view.scaleY = if (focused) 1.07f else 1.0f
                                        view.elevation = if (focused) 12f else 0f
                                        view.background = GradientDrawable().apply {
                                            setColor(if (focused) Color.rgb(35, 55, 105) else bg)
                                            cornerRadius = 12f
                                            if (focused) setStroke(3, accent)
                                        }
                                    }
                                }
                                val image = ImageView(this).apply {
                                    scaleType = ImageView.ScaleType.CENTER_CROP
                                    contentDescription = show.title
                                }
                                loadPosterImage(image, show.posterPath)
                                poster.addView(image, LinearLayout.LayoutParams(-1, 285))
                                tvRow.addView(
                                    poster,
                                    LinearLayout.LayoutParams(190, 285).apply {
                                        setMargins(0, 0, 14, 0)
                                    }
                                )
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        val footer = TextView(this).apply {
            text = "ORBIT  •  PERSONAL MEDIA PLAYER  •  V1.0"
            textSize = 11f
            letterSpacing = 0.06f
            setTextColor(Color.rgb(115, 128, 155))
            setPadding(0, 48, 0, 0)
        }
        root.addView(footer)

        playerView = PlayerView(this).apply {
            useController = true
            visibility = View.GONE
        }

        setContentView(homeScroll)
        playButton.requestFocus()
    }

    private fun showFilmDetails(title: String, posterFile: String) {
        currentScreen = "details"
        lastFilmTitle = title
        lastPosterFile = posterFile
        root.removeAllViews()

        val backButton = TextView(this).apply {
            text = "‹   BACK TO ORBIT"
            textSize = 18f
            setTextColor(Color.WHITE)
            isFocusable = true
            isClickable = true
            setPadding(0, 12, 0, 28)
            setOnClickListener { showHomeScreen() }
        }
        root.addView(backButton)

        val filmInfo = when (title.uppercase()) {
            "THE DARK KNIGHT" -> arrayOf(
                "2008", "ACTION  •  CRIME  •  DRAMA", "2h 32m",
                "Batman faces the Joker, a criminal mastermind who plunges Gotham into chaos and pushes Batman to his limits."
            )
            "INTERSTELLAR" -> arrayOf(
                "2014", "SCI-FI  •  ADVENTURE  •  DRAMA", "2h 49m",
                "A team of explorers travels beyond our galaxy in search of a future for humanity among the stars."
            )
            "THE MATRIX" -> arrayOf(
                "1999", "SCI-FI  •  ACTION", "2h 16m",
                "A computer hacker discovers that the world he knows is a simulated reality and joins a rebellion against its controllers."
            )
            "INCEPTION" -> arrayOf(
                "2010", "SCI-FI  •  THRILLER", "2h 28m",
                "A skilled thief who enters people's dreams is offered a chance to change his future by planting an idea in someone's mind."
            )
            "GLADIATOR" -> arrayOf(
                "2000", "ACTION  •  DRAMA  •  HISTORY", "2h 35m",
                "A betrayed Roman general is forced into slavery and fights in the arena in his quest for justice."
            )
            else -> arrayOf(
                "FEATURED FILM", "DISCOVER ON ORBIT", "",
                "Discover $title on Orbit. Your personal entertainment universe."
            )
        }

        val detailsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(
            detailsRow,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "$title poster"
        }

        if (posterFile.startsWith("/")) {
            loadPosterImage(image, posterFile)
        } else {
            try {
                assets.open("posters/$posterFile").use { stream ->
                    image.setImageBitmap(BitmapFactory.decodeStream(stream))
                }
            } catch (_: Exception) {
                // Keep the image empty if a local poster is unavailable.
            }
        }
        detailsRow.addView(
            image,
            LinearLayout.LayoutParams(280, 420).apply {
                rightMargin = 40
            }
        )

        val infoColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        detailsRow.addView(
            infoColumn,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        val heading = TextView(this).apply {
            text = title
            textSize = 32f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(0, 0, 0, 14)
        }
        infoColumn.addView(heading)

        val metadata = TextView(this).apply {
            text = "${filmInfo[0]}   •   ${filmInfo[2]}\n${filmInfo[1]}"
            textSize = 15f
            setTextColor(Color.rgb(145, 175, 255))
            setLineSpacing(6f, 1f)
            setPadding(0, 0, 0, 22)
        }
        infoColumn.addView(metadata)

        val description = TextView(this).apply {
            text = filmInfo[3]
            textSize = 18f
            setTextColor(Color.LTGRAY)
            setLineSpacing(5f, 1f)
            setPadding(0, 0, 0, 28)
        }
        infoColumn.addView(description)

        val playButton = TextView(this).apply {
            text = "▶   PLAY TEST VIDEO"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply {
                setColor(accent)
                cornerRadius = 12f
            }
            setPadding(28, 18, 28, 18)
            setOnClickListener { playTestVideo() }
        }
        infoColumn.addView(
            playButton,
            LinearLayout.LayoutParams(300, 68)
        )

        addCastSection(title)

        // Load live TMDB details without changing the existing layout.
        thread {
            try {
                val key = BuildConfig.TMDB_API_KEY
                if (key.isNotBlank()) {
                    val live = TmdbTest.fetchMetadata(title, key)
                    Handler(Looper.getMainLooper()).post {
                        if (currentScreen != "details" || lastFilmTitle != title) return@post
                        if (live != null) {
                            if (live.releaseYear.isNotBlank() || live.runtimeMinutes != null || live.genres.isNotEmpty()) {
                                val year = live.releaseYear.ifBlank { filmInfo[0] }
                                val runtime = live.runtimeMinutes?.let { minutes ->
                                    "${minutes / 60}h ${minutes % 60}m"
                                } ?: filmInfo[2]
                                val genres = live.genres.joinToString("  •  ").ifBlank { filmInfo[1] }
                                metadata.text = "$year   •   $runtime\\n$genres"
                            }
                            if (live.overview.isNotBlank()) {
                                description.text = live.overview
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Keep the original details if TMDB is unavailable.
            }
        }

        backButton.requestFocus()
    }



    private fun showTvDetails(tvId: Int, title: String, posterPath: String) {
        currentScreen = "tv_details"
        lastFilmTitle = title
        lastPosterFile = posterPath
        root.removeAllViews()
        root.gravity = Gravity.TOP

        val backButton = TextView(this).apply {
            text = "‹   BACK TO ORBIT"
            textSize = 18f
            setTextColor(Color.WHITE)
            isFocusable = true
            isClickable = true
            setPadding(0, 12, 0, 24)
            setOnClickListener { showHomeScreen() }
        }
        root.addView(backButton)

        val heading = TextView(this).apply {
            text = title
            textSize = 30f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(0, 0, 0, 12)
        }
        root.addView(heading)

        val infoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        root.addView(infoRow)

        val poster = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "$title poster"
        }
        loadPosterImage(poster, posterPath)
        infoRow.addView(poster, LinearLayout.LayoutParams(190, 285).apply {
            rightMargin = 24
        })

        val infoColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        infoRow.addView(infoColumn, LinearLayout.LayoutParams(0, -2, 1f))

        val metadata = TextView(this).apply {
            text = "Loading show information..."
            textSize = 15f
            setTextColor(Color.rgb(145, 175, 255))
            setPadding(0, 0, 0, 12)
        }
        infoColumn.addView(metadata)

        val overview = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.LTGRAY)
            setLineSpacing(4f, 1f)
        }
        infoColumn.addView(overview)

        val seasonsHeading = TextView(this).apply {
            text = "SEASONS"
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(0, 24, 0, 12)
        }
        root.addView(seasonsHeading)

        val seasonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val seasonScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
        }
        seasonScroll.addView(seasonRow)
        root.addView(seasonScroll)

        val episodesHeading = TextView(this).apply {
            text = "EPISODES"
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(0, 20, 0, 10)
        }
        root.addView(episodesHeading)

        val episodeList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(episodeList)

        val status = TextView(this).apply {
            text = "Loading seasons..."
            textSize = 15f
            setTextColor(Color.LTGRAY)
        }
        root.addView(status)

        fun loadSeason(seasonNumber: Int) {
            status.text = "Loading episodes..."
            episodeList.removeAllViews()

            thread {
                try {
                    val episodes = TmdbTest.fetchTvEpisodes(
                        tvId, seasonNumber, BuildConfig.TMDB_API_KEY
                    )
                    Handler(Looper.getMainLooper()).post {
                        if (currentScreen != "tv_details" || lastFilmTitle != title) return@post
                        episodeList.removeAllViews()
                        status.text = if (episodes.isEmpty()) {
                            "No episodes found for this season."
                        } else {
                            ""
                        }

                        episodes.forEach { episode ->
                            val card = LinearLayout(this).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(16, 12, 16, 12)
                                isFocusable = true
                                isClickable = true
                                background = GradientDrawable().apply {
                                    setColor(bg)
                                    cornerRadius = 10f
                                }
                                setOnFocusChangeListener { view, focused ->
                                    view.background = GradientDrawable().apply {
                                        setColor(if (focused) Color.rgb(35, 55, 105) else bg)
                                        cornerRadius = 10f
                                        if (focused) setStroke(2, accent)
                                    }
                                }
                            }
                            val episodeTitle = TextView(this).apply {
                                text = "E${episode.episodeNumber}  •  ${episode.name}" +
                                    (episode.runtimeMinutes?.let { "  •  ${it} min" } ?: "")
                                textSize = 16f
                                setTextColor(Color.WHITE)
                                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                            }
                            card.addView(episodeTitle)

                            if (episode.overview.isNotBlank()) {
                                card.addView(TextView(this).apply {
                                    text = episode.overview
                                    textSize = 13f
                                    setTextColor(Color.LTGRAY)
                                    setPadding(0, 6, 0, 0)
                                    maxLines = 3
                                    ellipsize = android.text.TextUtils.TruncateAt.END
                                })
                            }

                            episodeList.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                                bottomMargin = 8
                            })
                        }
                    }
                } catch (_: Exception) {
                    Handler(Looper.getMainLooper()).post {
                        if (currentScreen == "tv_details" && lastFilmTitle == title) {
                            status.text = "Couldn't load episodes. Please try again."
                        }
                    }
                }
            }
        }

        thread {
            try {
                val key = BuildConfig.TMDB_API_KEY
                val details = TmdbTest.fetchMetadata(title, key)
                val seasons = TmdbTest.fetchTvSeasons(tvId, key)

                Handler(Looper.getMainLooper()).post {
                    if (currentScreen != "tv_details" || lastFilmTitle != title) return@post

                    if (details != null) {
                        val year = details.releaseYear.ifBlank { "TV SERIES" }
                        val genres = details.genres.joinToString("  •  ")
                        metadata.text = listOf(year, genres).filter { it.isNotBlank() }
                            .joinToString("   •   ")
                        overview.text = details.overview
                    } else {
                        metadata.text = "TV SERIES"
                    }

                    seasonRow.removeAllViews()
                    if (seasons.isEmpty()) {
                        status.text = "No seasons found."
                    } else {
                        status.text = ""
                        seasons.forEach { season ->
                            val button = TextView(this).apply {
                                text = season.name + "  (${season.episodeCount})"
                                textSize = 15f
                                setTextColor(Color.WHITE)
                                gravity = Gravity.CENTER
                                isFocusable = true
                                isClickable = true
                                setPadding(18, 12, 18, 12)
                                background = GradientDrawable().apply {
                                    setColor(bg)
                                    cornerRadius = 10f
                                }
                                setOnClickListener { loadSeason(season.seasonNumber) }
                            }
                            seasonRow.addView(button, LinearLayout.LayoutParams(-2, -2).apply {
                                rightMargin = 10
                            })
                        }
                        loadSeason(seasons.first().seasonNumber)
                    }
                }
            } catch (_: Exception) {
                Handler(Looper.getMainLooper()).post {
                    if (currentScreen == "tv_details" && lastFilmTitle == title) {
                        status.text = "Couldn't load show information."
                    }
                }
            }
        }

        backButton.requestFocus()
    }

    private fun addCastSection(title: String) {
        val castHeading = TextView(this).apply {
            text = "CAST & CREW"
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(0, 24, 0, 12)
        }
        root.addView(castHeading)

        val loading = TextView(this).apply {
            text = "Loading cast..."
            textSize = 15f
            setTextColor(Color.LTGRAY)
        }
        root.addView(loading)

        val castScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
        }
        val castRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        castScroll.addView(castRow)
        root.addView(castScroll, LinearLayout.LayoutParams(-1, 245))

        thread {
            try {
                val key = BuildConfig.TMDB_API_KEY
                val cast = TmdbTest.fetchCast(title, key)
                Handler(Looper.getMainLooper()).post {
                    if (currentScreen != "details" || lastFilmTitle != title) return@post
                    loading.visibility = View.GONE
                    if (cast.isEmpty()) {
                        loading.text = "No cast information found."
                        loading.visibility = View.VISIBLE
                        return@post
                    }

                    castScroll.visibility = View.VISIBLE
                    cast.take(12).forEach { member ->
                        val card = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                            setPadding(4, 4, 18, 4)
                            isFocusable = true
                            isFocusableInTouchMode = true
                            isClickable = true

                            background = GradientDrawable().apply {
                                setColor(Color.TRANSPARENT)
                                cornerRadius = 10f
                            }

                        }
                        val photo = ImageView(this).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            contentDescription = member.name
                        }
                        card.addView(photo, LinearLayout.LayoutParams(140, 150))

                        val name = TextView(this).apply {
                            text = member.name
                            textSize = 14f
                            setTextColor(Color.WHITE)
                            gravity = Gravity.CENTER
                            maxLines = 1
                            isSingleLine = true
                            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
                            marqueeRepeatLimit = -1
                            isSelected = false
                            setPadding(0, 8, 0, 0)
                        }
                        card.addView(name, LinearLayout.LayoutParams(140, -2))

                        card.setOnFocusChangeListener { view, focused ->
                            view.background = GradientDrawable().apply {
                                setColor(
                                    if (focused) Color.rgb(30, 48, 90)
                                    else Color.TRANSPARENT
                                )
                                cornerRadius = 10f
                                if (focused) {
                                    setStroke(3, accent)
                                }
                            }
                            view.scaleX = if (focused) 1.04f else 1f
                            view.scaleY = if (focused) 1.04f else 1f
                            name.isSelected = focused
                        }

                        val character = TextView(this).apply {
                            text = member.character
                            textSize = 12f
                            setTextColor(Color.LTGRAY)
                            gravity = Gravity.CENTER
                            maxLines = 2
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        }
                        card.addView(character, LinearLayout.LayoutParams(140, -2))
                        card.setOnClickListener {
                            showActorProfile(member.name, member.profilePath, title)
                        }
                        castRow.addView(card, LinearLayout.LayoutParams(158, -2))

                        if (member.profilePath != null) {
                            thread {
                                try {
                                    val bitmap = BitmapFactory.decodeStream(
                                        URL("https://image.tmdb.org/t/p/w185${member.profilePath}")
                                            .openStream()
                                    )
                                    Handler(Looper.getMainLooper()).post {
                                        if (currentScreen == "details" && lastFilmTitle == title) {
                                            photo.setImageBitmap(bitmap)
                                        }
                                    }
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                Handler(Looper.getMainLooper()).post {
                    if (currentScreen == "details" && lastFilmTitle == title) {
                        loading.text = "Cast unavailable. Check your connection."
                    }
                }
            }
        }
    }

    private fun showActorProfile(actorName: String, profilePath: String?, filmTitle: String) {
        currentScreen = "actor"
        root.removeAllViews()

        val backButton = TextView(this).apply {
            text = "‹   BACK TO $filmTitle"
            textSize = 18f
            setTextColor(Color.WHITE)
            isFocusable = true
            isClickable = true
            setPadding(0, 12, 0, 28)
            setOnClickListener { showFilmDetails(filmTitle, lastPosterFile) }
        }
        root.addView(backButton)

        val portrait = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = actorName
        }
        root.addView(portrait, LinearLayout.LayoutParams(220, 300))

        val heading = TextView(this).apply {
            text = actorName
            textSize = 32f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(0, 16, 0, 12)
        }
        root.addView(heading)

        val biography = TextView(this).apply {
            text = "Loading biography..."
            textSize = 17f
            setTextColor(Color.LTGRAY)
        }
        root.addView(biography)

        val filmographyHeading = TextView(this).apply {
            text = "FILMOGRAPHY"
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(0, 24, 0, 12)
        }
        root.addView(filmographyHeading)

        val filmography = TextView(this).apply {
            text = "Loading filmography..."
            textSize = 15f
            setTextColor(Color.rgb(190, 200, 225))
        }
        root.addView(filmography)

        if (!profilePath.isNullOrBlank()) {
            thread {
                try {
                    val bitmap = BitmapFactory.decodeStream(
                        URL("https://image.tmdb.org/t/p/w500$profilePath").openStream()
                    )
                    Handler(Looper.getMainLooper()).post {
                        if (currentScreen == "actor") portrait.setImageBitmap(bitmap)
                    }
                } catch (_: Exception) {}
            }
        }

        thread {
            try {
                val details = TmdbTest.fetchActorProfile(actorName, BuildConfig.TMDB_API_KEY)
                Handler(Looper.getMainLooper()).post {
                    if (currentScreen != "actor") return@post
                    biography.text = details.first.ifBlank { "No biography is available." }
                    filmography.text = details.second.ifBlank { "No filmography is available." }
                }
            } catch (_: Exception) {
                Handler(Looper.getMainLooper()).post {
                    if (currentScreen == "actor") {
                        biography.text = "Biography unavailable."
                        filmography.text = "Filmography unavailable."
                    }
                }
            }
        }
        backButton.requestFocus()
    }

    private fun playTestVideo() {
        currentScreen = "player"
        root.removeAllViews()
        playerView.visibility = View.VISIBLE
        root.addView(playerView, LinearLayout.LayoutParams(-1, -1))

        if (player == null) {
            player = ExoPlayer.Builder(this).build().also {
                playerView.player = it
                it.setMediaItem(MediaItem.fromUri(testUrl))
                it.prepare()
            }
        }
        player?.play()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK &&
            event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0
        ) {
            onBackPressed()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        when (currentScreen) {
            "player" -> {
                playerView.player = null
                player?.release()
                player = null
                showFilmDetails(lastFilmTitle, lastPosterFile)
            }
            "details", "actor" -> showHomeScreen()
            else -> super.onBackPressed()
        }
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
    }
}
