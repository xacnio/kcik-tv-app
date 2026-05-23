package dev.xacnio.kciktv.mobile.ui.home.featured

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.net.toUri
import com.amazonaws.ivs.player.Player
import com.amazonaws.ivs.player.PlayerView

class HeroPreviewPlayer(private val context: Context) {

    private val TAG = "HeroPreviewPlayer"
    private val mainHandler = Handler(Looper.getMainLooper())

    val playerView: PlayerView = PlayerView(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        controlsEnabled = false
    }

    private val player: Player get() = playerView.player

    private var currentUrl: String? = null
    private var currentContainer: FrameLayout? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val playerListener = object : Player.Listener() {
        override fun onStateChanged(state: Player.State) {
            mainHandler.post {
                if (state == Player.State.PLAYING) {
                    playerView.visibility = android.view.View.VISIBLE
                }
            }
        }

        override fun onError(error: com.amazonaws.ivs.player.PlayerException) {
            Log.w(TAG, "Hero player error: ${error.errorMessage}")
            mainHandler.post { showThumbnailFallback() }
        }

        override fun onCue(cue: com.amazonaws.ivs.player.Cue) = Unit
        override fun onDurationChanged(duration: Long) = Unit
        override fun onMetadata(type: String, data: java.nio.ByteBuffer) = Unit
        override fun onQualityChanged(quality: com.amazonaws.ivs.player.Quality) = Unit
        override fun onRebuffering() = Unit
        override fun onSeekCompleted(position: Long) = Unit
        override fun onVideoSizeChanged(width: Int, height: Int) = Unit
    }

    init {
        player.addListener(playerListener)
        player.isMuted = true
        player.setRebufferToLive(true)
    }

    fun attachTo(container: FrameLayout) {
        if (playerView.parent != null) {
            (playerView.parent as? ViewGroup)?.removeView(playerView)
        }
        container.addView(playerView)
        currentContainer = container
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
        currentUrl = url
        try {
            player.load(url.toUri())
            player.play()
            currentContainer?.visibility = android.view.View.VISIBLE
        } catch (e: Exception) {
            Log.w(TAG, "loadAndPlay error: ${e.message}")
            showThumbnailFallback()
        }
    }

    fun pause() {
        try { player.pause() } catch (e: Exception) { /* ignore */ }
    }

    fun resume() {
        if (currentUrl != null && isWifi()) {
            try { player.play() } catch (e: Exception) { /* ignore */ }
        }
    }

    fun stop() {
        currentUrl = null
        try { player.pause() } catch (e: Exception) { /* ignore */ }
        showThumbnailFallback()
    }

    fun release() {
        unregisterWifiCallback()
        try {
            player.removeListener(playerListener)
            player.release()
        } catch (e: Exception) { /* ignore */ }
        if (playerView.parent != null) {
            (playerView.parent as? ViewGroup)?.removeView(playerView)
        }
        currentContainer = null
    }

    fun detach() {
        pause()
        if (playerView.parent != null) {
            (playerView.parent as? ViewGroup)?.removeView(playerView)
        }
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
        currentContainer?.visibility = android.view.View.GONE
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
            try { connectivityManager.unregisterNetworkCallback(it) } catch (e: Exception) { /* ignore */ }
            networkCallback = null
        }
    }
}
