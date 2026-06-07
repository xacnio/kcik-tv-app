package dev.xacnio.kciktv.shared.data.mock

object MockConfig {
    @Volatile var enabled: Boolean = false

    // Live chat message interval
    const val CHAT_MIN_INTERVAL_MS = 700L
    const val CHAT_MAX_INTERVAL_MS = 2400L

    const val HISTORY_SIZE = 80
    const val LIVE_STREAM_COUNT = 60
    const val FOLLOWING_COUNT = 20
}
