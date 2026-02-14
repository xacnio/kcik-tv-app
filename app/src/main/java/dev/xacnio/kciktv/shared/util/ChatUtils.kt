/**
 * File: ChatUtils.kt
 *
 * Description: Utility helper class providing static methods for Chat.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.util

import android.content.Context
import dev.xacnio.kciktv.R

fun Context.getLocalizedChatError(errorCode: String): String {
    return when (errorCode) {
        "SUBSCRIBERS_ONLY_ERROR" -> getString(R.string.chat_error_subscribers_only)
        "FOLLOWERS_ONLY_ERROR" -> getString(R.string.chat_error_followers_only)
        "SLOW_MODE_ERROR" -> getString(R.string.chat_error_slow_mode)
        "BANNED_USER_ERROR" -> getString(R.string.chat_error_banned)
        "MESSAGE_TOO_LONG" -> getString(R.string.chat_error_message_too_long)
        "DUPLICATE_MESSAGE_ERROR" -> getString(R.string.chat_error_duplicate_message)
        else -> getString(R.string.chat_error_generic, errorCode)
    }
}
