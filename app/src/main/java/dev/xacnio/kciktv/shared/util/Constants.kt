/**
 * File: Constants.kt
 *
 * Description: Implementation of Constants functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.util

/**
 * Application-wide constants.
 */
object Constants {
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.6834.163 Mobile Safari/537.36"

    /**
     * URLs related constants
     */
    object Urls {
        const val DEFAULT_LIVESTREAM_THUMBNAIL = "https://kick.com/img/default_livestream_thumbnail.webp"

        /** Domains trusted for link preview fetching (OG tags) */
        val TRUSTED_DOMAINS = listOf(
            "youtube.com",
            "youtu.be",
            "discord.com",
            "discord.gg",
            "instagram.com",
            "tiktok.com",
            "twitter.com",
            "x.com",
            "streamable.com",
            "kick.com",
            "imgur.com",
            "prnt.sc",
            "github.com",
            "github.io",
            "buymeacoffee.com"
        )
    }

    /**
     * Player related constants
     */
    object Player {
        const val DEFAULT_SEEK_INCREMENT_MS = 10_000L // 10 seconds
        const val DVR_OFFSET_MS = 10_000L // 10 seconds before live edge
        const val CONTROLS_AUTO_HIDE_DELAY_MS = 3_000L // 3 seconds
        const val PROGRESS_UPDATE_INTERVAL_MS = 1_000L // 1 second
        const val VIEWER_COUNT_POLL_INTERVAL_MS = 30_000L // 30 seconds
        const val STREAM_CHECK_MAX_ATTEMPTS = 10
        const val STREAM_CHECK_INTERVAL_MS = 3_000L // 3 seconds
    }
    
    /**
     * Chat related constants
     */
    object Chat {
        const val MESSAGE_LIMIT = 4000 // Max messages in chat
        const val HISTORY_FETCH_LIMIT = 200 // Messages to fetch for history
    }
    
    /**
     * Clip related constants
     */
    object Clip {
        const val MIN_DURATION_SECONDS = 15
        const val MAX_DURATION_SECONDS = 60
        const val DEFAULT_TITLE_MAX_LENGTH = 100
    }
    
    /**
     * UI Animation durations
     */
    object Animation {
        const val FAST_MS = 150L
        const val NORMAL_MS = 300L
        const val SLOW_MS = 500L
        const val OVERLAY_TRANSITION_MS = 300L
    }
    
    /**
     * Notification
     */
    object Notification {
        const val CHANNEL_ID = "kciktv_mobile_playback"
        const val NOTIFICATION_ID = 43
    }
    
    /**
     * Caching
     */
    object Cache {
        const val BLERP_CLEANUP_DELAY_MS = 180_000L // 3 minutes
        const val PROFILE_CACHE_DURATION_MS = 300_000L // 5 minutes
    }
    
    /**
     * Network
     */
    object Network {
        const val CONNECT_TIMEOUT_SECONDS = 30L
        const val READ_TIMEOUT_SECONDS = 30L
        const val WRITE_TIMEOUT_SECONDS = 30L
    }
    
    /**
     * Size limits
     */
    object Limits {
        const val MAX_EMOTE_CATEGORIES = 50
        const val MAX_QUICK_EMOTES = 20
        const val MAX_MENTIONS = 100
        const val MAX_PINNED_GIFTS = 10
    }
    
    /**
     * Intent extras and actions
     */
    object Intents {
        const val EXTRA_CHANNEL_SLUG = "channel_slug"
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_VIDEO_ID = "video_id"
        const val EXTRA_CLIP_ID = "clip_id"
        const val EXTRA_START_TIME = "start_time"
        
        const val ACTION_PIP_CONTROL = "dev.xacnio.kciktv.MOBILE_PIP_CONTROL"
        const val ACTION_STOP_PLAYBACK = "dev.xacnio.kciktv.STOP_PLAYBACK"
    }
    
    /**
     * SharedPreferences keys
     */
    object Prefs {
        const val KEY_THEME_COLOR = "theme_color"
        const val KEY_QUALITY_LIMIT = "quality_limit"
        const val KEY_MOBILE_LAYOUT_MODE = "mobile_layout_mode"
        const val KEY_LAST_LIST_MODE = "last_list_mode"
        const val KEY_BACKGROUND_AUDIO = "background_audio"
        const val KEY_DYNAMIC_QUALITY = "dynamic_quality"
        const val KEY_CHAT_FONT_SIZE = "chat_font_size"
        const val KEY_CHAT_SPACING = "chat_spacing"
        const val KEY_SHOW_TIMESTAMPS = "show_timestamps"
        const val KEY_SHOW_SECONDS = "show_seconds"
    }
    
    /**
     * Default theme colors
     */
    object Colors {
        const val DEFAULT_THEME = 0xFF53FC18.toInt() // Kick green
        const val BACKGROUND_DARK = 0xFF0f0f0f.toInt()
        const val SURFACE_DARK = 0xFF1a1a1a.toInt()
    }
}
