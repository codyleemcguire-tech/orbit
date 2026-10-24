@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.orbit.player

import android.content.Context
import android.os.Build
import android.view.Display
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

// NuvioTV's playback engine (NuvioMedia/NuvioTV, GPL-3.0) with its default settings: Nuvio's
// build of Media3, the TV's decoders first with FFmpeg / AV1 / MPEG-H behind them, OkHttp
// networking tuned for big files, hardware video decoders ahead of software ones, Dolby Vision
// profile 7 played as HDR10 on TVs without Dolby Vision, and an audio delay for lip sync.
object NuvioEngine {

    // Nuvio's player user agent; some hosts refuse requests without a browser-like one.
    const val userAgent = "Mozilla/5.0 (Linux; Android 13; Android TV) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // ---- Networking (PlayerPlaybackNetworking, IPv4FirstDns, PlaybackSocketFactory) ----

    // IPv4 addresses first: avoids long timeouts on networks with broken IPv6.
    private object IPv4FirstDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            Dns.SYSTEM.lookup(hostname).sortedBy { if (it is Inet4Address) 0 else 1 }
    }

    // Asks for a large receive buffer on playback sockets only when the box allows more than
    // its own tuning ceiling (asking turns the kernel's auto-tuning off for that socket).
    private val receiveWindowBytes: Int by lazy {
        val request = 8 * 1024 * 1024
        val granted = runCatching { Socket().use { it.receiveBufferSize = request; it.receiveBufferSize } }.getOrDefault(0)
        val ceiling = runCatching {
            File("/proc/sys/net/ipv4/tcp_rmem").readText().trim().split(Regex("\\s+")).last().toInt()
        }.getOrNull()
        val ask = if (ceiling != null) granted > ceiling else granted >= 2 * request
        if (ask) request else 0
    }

    private object PlaybackSocketFactory : SocketFactory() {
        private val delegate = getDefault()
        private fun Socket.tuned() = apply {
            runCatching { tcpNoDelay = true }
            if (receiveWindowBytes > 0) runCatching { receiveBufferSize = receiveWindowBytes }
        }
        override fun createSocket(): Socket = delegate.createSocket().tuned()
        override fun createSocket(host: String?, port: Int): Socket = delegate.createSocket(host, port).tuned()
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            delegate.createSocket(host, port, localHost, localPort).tuned()
        override fun createSocket(host: InetAddress?, port: Int): Socket = delegate.createSocket(host, port).tuned()
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            delegate.createSocket(address, port, localAddress, localPort).tuned()
    }

    // One pool for the process, so warmed connections are reused between plays.
    private val connectionPool = ConnectionPool(32, 3, TimeUnit.MINUTES)

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 32 })
            .dns(IPv4FirstDns)
            .socketFactory(PlaybackSocketFactory)
            .connectionPool(connectionPool)
            // HTTP/2 made as much garbage as it downloaded for no speed gain (Nuvio's finding).
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    // For addon requests (catalogs, streams, subtitles): the same pool and DNS, so repeat
    // requests to an addon reuse its warm connection instead of a new TLS handshake.
    val apiClient: OkHttpClient by lazy { httpClient.newBuilder().build() }

    private fun dataSourceFactory(context: Context, headers: Map<String, String>): DataSource.Factory {
        val requestHeaders = headers.mapNotNull { (k, v) ->
            val key = k.trim()
            val value = v.trim()
            if (key.isEmpty() || value.isEmpty() || key.equals("Range", true)) null else key to value
        }.toMap()
        val http = OkHttpDataSource.Factory(httpClient).apply {
            setDefaultRequestProperties(requestHeaders)
            if (requestHeaders.keys.none { it.equals("User-Agent", true) }) setUserAgent(userAgent)
        }
        return DefaultDataSource.Factory(context, http)
    }

    // ---- Renderers ----

    // DefaultRenderersFactory with Nuvio's video renderer: hardware decoders ahead of software
    // ones, and Dolby Vision 7 mapped to its HEVC base layer when the TV can't show it.
    private class Renderers(context: Context, private val mapDv7ToHevc: Boolean) : DefaultRenderersFactory(context) {
        override fun buildVideoRenderers(
            context: Context,
            extensionRendererMode: Int,
            mediaCodecSelector: MediaCodecSelector,
            enableDecoderFallback: Boolean,
            eventHandler: android.os.Handler,
            eventListener: VideoRendererEventListener,
            allowedVideoJoiningTimeMs: Long,
            out: ArrayList<Renderer>
        ) {
            val built = ArrayList<Renderer>()
            super.buildVideoRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
                eventHandler, eventListener, allowedVideoJoiningTimeMs, built)
            for (renderer in built) {
                if (renderer::class.java == MediaCodecVideoRenderer::class.java) {
                    out.add(HardwareFirstVideoRenderer(MediaCodecVideoRenderer.Builder(context)
                        .setCodecAdapterFactory(codecAdapterFactory)
                        .setMediaCodecSelector(mediaCodecSelector)
                        .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                        .setEnableDecoderFallback(enableDecoderFallback)
                        .setEventHandler(eventHandler)
                        .setEventListener(eventListener)
                        .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
                        .setMapDV7ToHevc(mapDv7ToHevc)))
                } else out.add(renderer)
            }
        }
    }

    private fun displaySupportsDolbyVision(display: Display?): Boolean = runCatching {
        val types = if (Build.VERSION.SDK_INT >= 34) display?.mode?.supportedHdrTypes
        else @Suppress("DEPRECATION") display?.hdrCapabilities?.supportedHdrTypes
        types?.contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) == true
    }.getOrDefault(false)

    // ---- Player ----

    class Built(val player: ExoPlayer, val mediaSource: MediaSource)

    // A player for url with Nuvio's defaults. audioDelayUs is read while playing, so the
    // lip-sync adjustment applies straight away.
    fun build(
        context: Context,
        display: Display?,
        url: String,
        headers: Map<String, String>,
        mimeType: String?,
        tunneling: Boolean,
        audioDelayUs: () -> Long
    ): Built {
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
            .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)
        val dataSources = dataSourceFactory(context, headers)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSources, extractors)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(8))
        val renderers = Renderers(context, mapDv7ToHevc = !displaySupportsDolbyVision(display))
            // The TV's decoders first; FFmpeg and the others for what it can't play.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val trackSelector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)
                .setTunnelingEnabled(tunneling)
                .build()
        }
        // Nuvio's default buffer: Media3's, with a short back buffer so a small rewind
        // doesn't go back to the network.
        val loadControl = DefaultLoadControl.Builder()
            .setBackBuffer(1_500, true)
            .build()
        val player = ExoPlayer.Builder(context, renderers)
            .setBandwidthMeter(DefaultBandwidthMeter.Builder(context).setInitialBitrateEstimate(25_000_000L).build())
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setReleaseTimeoutMs(3000)
            // Orbit switches the TV's mode itself (Match frame rate); the player mustn't as well.
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.setAudioAttributes(AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        player.setHandleAudioBecomingNoisy(true)
        val item = MediaItem.Builder().setUri(url).apply { mimeType?.let { setMimeType(it) } }.build()
        val source = AudioDelayMediaSource(mediaSourceFactory.createMediaSource(item), audioDelayUs)
        return Built(player, source)
    }
}
