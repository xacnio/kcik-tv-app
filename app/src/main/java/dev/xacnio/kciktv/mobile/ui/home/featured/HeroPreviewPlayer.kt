package dev.xacnio.kciktv.mobile.ui.home.featured

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.net.toUri
import com.amazonaws.ivs.player.Cue
import com.amazonaws.ivs.player.Player
import com.amazonaws.ivs.player.PlayerException
import com.amazonaws.ivs.player.Quality
import java.nio.ByteBuffer

class HeroPreviewPlayer(private val context: Context) {

    private val TAG = "HeroPreviewPlayer"
    private val mainHandler = Handler(Looper.getMainLooper())

    // Always VISIBLE so its hardware layer stays alive (GONE breaks surface creation).
    // Use alpha to hide/show video content over the thumbnail.
    val textureView: TextureView = TextureView(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        visibility = View.VISIBLE
        alpha = 0f
    }

    private var player1: Player? = null
    private var player2: Player? = null

    private var currentUrl: String? = null
    private var preloadUrl: String? = null
    private var currentContainer: FrameLayout? = null
    private var currentSurface: Surface? = null

    var isMuted: Boolean = true
        set(value) {
            field = value
            player1?.isMuted = value
        }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
            currentSurface?.release()
            currentSurface = Surface(st)
            player1?.setSurface(currentSurface)
            // Player may have reached PLAYING before the surface was ready (e.g. after swap)
            if (player1?.state == Player.State.PLAYING) {
                textureView.alpha = 1f
            }
        }
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            player1?.setSurface(null)
            currentSurface?.release()
            currentSurface = null
            return true
        }
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    }

    private val player1Listener = object : Player.Listener() {
        override fun onStateChanged(state: Player.State) {
            mainHandler.post {
                if (state == Player.State.PLAYING) {
                    textureView.alpha = 1f
                }
            }
        }
        override fun onError(error: PlayerException) {
            Log.w(TAG, "player1 error: ${error.errorMessage}")
            mainHandler.post { showThumbnailFallback() }
        }
        override fun onCue(cue: Cue) = Unit
        override fun onDurationChanged(duration: Long) = Unit
        override fun onMetadata(type: String, data: ByteBuffer) = Unit
        override fun onQualityChanged(quality: Quality) = Unit
        override fun onRebuffering() = Unit
        override fun onSeekCompleted(position: Long) = Unit
        override fun onVideoSizeChanged(width: Int, height: Int) = Unit
    }

    init {
        textureView.surfaceTextureListener = surfaceListener
        initPlayers()
    }

    private fun initPlayers() {
        player1 = Player.Factory.create(context).apply {
            isMuted = this@HeroPreviewPlayer.isMuted
            setRebufferToLive(true)
            addListener(player1Listener)
        }
        player2 = Player.Factory.create(context).apply {
            isMuted = true
            setRebufferToLive(true)
        }
    }

    fun attachTo(container: FrameLayout) {
        if (textureView.parent != null) {
            (textureView.parent as? ViewGroup)?.removeView(textureView)
        }
        container.visibility = View.VISIBLE  // Container starts GONE in XML; must be visible for surface creation
        container.addView(textureView)
        currentContainer = container
        if (textureView.isAvailable) {
            if (currentSurface == null) {
                currentSurface = Surface(textureView.surfaceTexture)
            }
            player1?.setSurface(currentSurface)
        }
    }

    fun detach() {
        pause()
        if (textureView.parent != null) {
            (textureView.parent as? ViewGroup)?.removeView(textureView)
        }
        currentContainer = null
    }

    fun preloadNext(url: String?) {
        if (url.isNullOrEmpty() || !isWifi()) return
        if (url == preloadUrl || url == currentUrl) return
        preloadUrl = url
        player2?.isMuted = true
        try {
            player2?.load(url.toUri())
            player2?.play()
        } catch (e: Exception) {
            Log.w(TAG, "preloadNext error: ${e.message}")
        }
    }

    fun activateOrLoad(url: String?) {
        if (url.isNullOrEmpty()) {
            showThumbnailFallback()
            return
        }
        if (!isWifi()) {
            showThumbnailFallback()
            registerWifiCallback()
            return
        }
        if (url == preloadUrl && player2 != null) {
            // Swap preloaded player2 into the display role
            player1?.setSurface(null)
            player1?.removeListener(player1Listener)

            val old1 = player1
            player1 = player2
            player2 = old1

            player1?.addListener(player1Listener)
            player1?.isMuted = isMuted
            player2?.isMuted = true  // old player1 must always be silent as background preloader
            player2?.pause()
            currentUrl = url
            preloadUrl = null

            if (currentSurface != null) {
                player1?.setSurface(currentSurface)
            }
            try { player1?.play() } catch (e: Exception) {}
            textureView.alpha = 1f
        } else {
            loadAndPlay(url)
        }
    }

    fun loadAndPlay(url: String?) {
        if (url.isNullOrEmpty()) {
            showThumbnailFallback()
            return
        }
        if (!isWifi()) {
            showThumbnailFallback()
            registerWifiCallback()
            return
        }
        if (url == currentUrl) {
            try { player1?.play() } catch (e: Exception) {}
            return
        }
        textureView.alpha = 0f  // Hide previous frame while new channel loads
        currentUrl = url
        try {
            player1?.load(url.toUri())
            player1?.play()
        } catch (e: Exception) {
            Log.w(TAG, "loadAndPlay error: ${e.message}")
            showThumbnailFallback()
        }
    }

    fun pause() {
        try { player1?.pause() } catch (e: Exception) {}
        try { player2?.pause() } catch (e: Exception) {}
    }

    fun resume() {
        if (currentUrl != null && isWifi()) {
            try { player1?.play() } catch (e: Exception) {}
        }
    }

    fun reset() {
        currentUrl = null
        preloadUrl = null
        try { player1?.pause() } catch (e: Exception) {}
        try { player2?.pause() } catch (e: Exception) {}
        showThumbnailFallback()
    }

    fun release() {
        unregisterWifiCallback()
        try {
            player1?.removeListener(player1Listener)
            player1?.setSurface(null)
            player1?.release()
        } catch (e: Exception) {}
        try {
            player2?.setSurface(null)
            player2?.release()
        } catch (e: Exception) {}
        player1 = null
        player2 = null
        if (textureView.parent != null) {
            (textureView.parent as? ViewGroup)?.removeView(textureView)
        }
        currentSurface?.release()
        currentSurface = null
        currentContainer = null
    }

    fun isWifi(): Boolean {
        val caps = connectivityManager.getNetworkCapabilities(
            connectivityManager.activeNetwork
        ) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun showThumbnailFallback() {
        textureView.alpha = 0f
    }

    private fun registerWifiCallback() {
        if (networkCallback != null) return
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post {
                    val url = currentUrl
                    if (url != null && currentContainer != null) {
                        loadAndPlay(url)
                    }
                    unregisterWifiCallback()
                }
            }
        }
        try {
            connectivityManager.registerNetworkCallback(req, networkCallback!!)
        } catch (e: Exception) {
            Log.w(TAG, "registerWifiCallback failed: ${e.message}")
            networkCallback = null
        }
    }

    private fun unregisterWifiCallback() {
        networkCallback?.let {
            try { connectivityManager.unregisterNetworkCallback(it) } catch (e: Exception) {}
            networkCallback = null
        }
    }
}
