/**
 * File: PinnedMessage.kt
 *
 * Description: Implementation of Pinned Message functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.model

import dev.xacnio.kciktv.shared.data.model.PinnedMessage

data class PinnedMessage(
    val id: String,
    val content: String,
    val sender: ChatSender,
    val createdAt: Long
)
