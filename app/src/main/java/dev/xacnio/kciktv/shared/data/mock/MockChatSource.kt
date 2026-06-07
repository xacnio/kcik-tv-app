package dev.xacnio.kciktv.shared.data.mock

import dev.xacnio.kciktv.shared.data.model.*
import kotlinx.coroutines.*
import kotlin.random.Random

/**
 * Produces a continuous stream of fake chat messages on a coroutine dispatcher.
 * Used only when MockConfig.enabled is true, in place of the real WebSocket.
 *
 * Call [start] to begin emitting and [stop] to cancel.
 */
class MockChatSource(
    private val chatroomId: Long,
    private val onMessage: (ChatMessage) -> Unit
) {
    private var job: Job? = null
    private val rng = Random(chatroomId)

    fun start(scope: CoroutineScope) {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            var counter = 0
            while (isActive) {
                val delay = rng.nextLong(MockConfig.CHAT_MIN_INTERVAL_MS, MockConfig.CHAT_MAX_INTERVAL_MS)
                delay(delay)
                if (!isActive) break

                val message = buildMessage(counter++)
                withContext(Dispatchers.Main) {
                    onMessage(message)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun buildMessage(index: Int): ChatMessage {
        val userIdx = rng.nextInt(MockDataPools.usernames.size)
        val username = MockDataPools.usernames[userIdx]
        val userId = 10_000L + userIdx

        val badges = MockDataPools.randomBadgeTypes(rng)
            ?.map { ChatBadge(it, null, null) }

        return ChatMessage(
            id = "mock-live-${chatroomId}-$index",
            content = MockDataPools.randomContentWithEmotes(rng),
            sender = ChatSender(
                id = userId,
                username = username,
                color = MockDataPools.chatColors[rng.nextInt(MockDataPools.chatColors.size)],
                badges = badges,
                profilePicture = MockImageUrls.avatar(userIdx)
            ),
            createdAt = System.currentTimeMillis(),
            type = MessageType.CHAT
        )
    }
}
