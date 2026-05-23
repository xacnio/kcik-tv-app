package dev.xacnio.kciktv.mobile.ui.home.featured

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.xacnio.kciktv.shared.data.chat.KcikChatWebSocket
import dev.xacnio.kciktv.shared.data.model.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class HeroChatPreview(
    private val context: Context,
    private val container: FrameLayout,
    private val isCompact: Boolean,

    private val fetchHistory: (suspend (Long) -> List<ChatMessage>)? = null,
) {
    private val TAG = "HeroChatPreview"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val maxMessages = if (isCompact) 20 else 50

    private val adapter = MiniChatAdapter(isCompact)

    private val recyclerView: RecyclerView = RecyclerView(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        layoutManager = LinearLayoutManager(context).apply {
            stackFromEnd = true
        }
        adapter = this@HeroChatPreview.adapter
        isNestedScrollingEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
    }

    private var webSocket: KcikChatWebSocket? = null
    private var currentChatroomId: Long? = null
    private var currentChannelId: Long? = null
    private var isPaused = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var historyJob: Job? = null

    init {
        container.addView(recyclerView)
    }

    fun switchTo(chatroomId: Long?, channelId: Long?, force: Boolean = false) {
        if (chatroomId == null) return
        if (!force && chatroomId == currentChatroomId) return
        currentChatroomId = chatroomId
        currentChannelId = channelId
        disconnect()
        adapter.clear()
        recyclerView.alpha = 0f
        if (!isPaused) {
            connect(chatroomId, channelId)
        }
    }

    fun pause() {
        isPaused = true
        disconnect()
    }

    fun resume() {
        isPaused = false
        val id = currentChatroomId ?: return
        if (webSocket == null) {
            connect(id, currentChannelId)
        }
    }

    fun reconnect() {
        isPaused = false
        val chatroomId = currentChatroomId ?: return
        val channelId = currentChannelId
        disconnect()
        adapter.clear()
        recyclerView.alpha = 0f
        connect(chatroomId, channelId)
    }

    fun release() {
        disconnect()
        scope.cancel()
        container.removeView(recyclerView)
    }

    private fun connect(chatroomId: Long, channelId: Long?) {

        historyJob?.cancel()
        if (channelId != null && fetchHistory != null) {
            historyJob = scope.launch {
                val msgs = try { fetchHistory.invoke(channelId) } catch (_: Exception) { emptyList() }
                if (msgs.isNotEmpty()) {
                    mainHandler.post {
                        msgs.forEach { adapter.append(it, maxMessages) }
                        recyclerView.scrollToPosition(adapter.itemCount - 1)
                        recyclerView.animate().alpha(1f).setDuration(400).start()
                    }
                } else {
                    mainHandler.post {
                        recyclerView.animate().alpha(1f).setDuration(400).start()
                    }
                }
            }
        } else {
            mainHandler.post {
                recyclerView.animate().alpha(1f).setDuration(400).start()
            }
        }

        webSocket = KcikChatWebSocket(
            context = context,
            onMessageReceived = { msg ->
                mainHandler.post { appendMessage(msg) }
            },
            onConnectionStateChanged = { connected ->
                if (connected) {
                    webSocket?.subscribeToChat(chatroomId)
                }
            }
        )
        webSocket?.connect()
        Log.d(TAG, "Connecting to chatroom $chatroomId (channelId=$channelId)")
    }

    private fun disconnect() {
        historyJob?.cancel()
        historyJob = null
        try { webSocket?.disconnect() } catch (_: Exception) {}
        webSocket = null
    }

    private fun appendMessage(msg: ChatMessage) {
        adapter.append(msg, maxMessages)
        recyclerView.scrollToPosition(adapter.itemCount - 1)
    }
}
