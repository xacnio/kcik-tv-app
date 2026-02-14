/**
 * File: ChatCommands.kt
 *
 * Description: Implementation of Chat Commands functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.model

import dev.xacnio.kciktv.shared.data.model.ChatCommands

/**
 * Chat command definitions and utilities
 */
data class ChatCommand(
    val name: String,           // Command name without / prefix (e.g., "clear")
    val description: String,    // Command description for autocomplete
    val requiresArg: Boolean = false,  // Whether the command requires an argument
    val argHint: String? = null // Hint for the argument (e.g., "username")
)

object ChatCommands {
    // Available commands list - moderator/broadcaster commands
    val MODERATOR_COMMANDS = listOf(
        ChatCommand("clear", "Clear the chat", false),
        ChatCommand("slow", "Enable slow mode", true, "seconds"),
        ChatCommand("slowoff", "Disable slow mode", false),
        ChatCommand("subscribers", "Enable subscribers-only mode", false),
        ChatCommand("subscribersoff", "Disable subscribers-only mode", false),
        ChatCommand("followers", "Enable followers-only mode", true, "minutes (0 = any)"),
        ChatCommand("followersoff", "Disable followers-only mode", false),
        ChatCommand("emoteonly", "Enable emote-only mode", false),
        ChatCommand("emoteonlyoff", "Disable emote-only mode", false),
        ChatCommand("poll", "Create a poll", false),
        ChatCommand("prediction", "Create or manage prediction", false),
        ChatCommand("ban", "Permanently ban a user", true, "username [reason]"),
        ChatCommand("unban", "Unban a user", true, "username"),
        ChatCommand("timeout", "Temporary timeout a user", true, "username [minutes]"),
        ChatCommand("category", "Change stream category", false)
    )
    
    // User commands
    val USER_COMMANDS = listOf(
        ChatCommand("user", "Show user actions", true, "username"),
    )
    
    fun getCommandsForUser(isModerator: Boolean): List<ChatCommand> {
        return if (isModerator) {
            MODERATOR_COMMANDS + USER_COMMANDS
        } else {
            USER_COMMANDS
        }
    }
    
    fun findMatchingCommands(input: String, isModerator: Boolean): List<ChatCommand> {
        if (!input.startsWith("/")) return emptyList()
        
        val query = input.drop(1).lowercase() // Remove "/" and lowercase
        val commands = getCommandsForUser(isModerator)
        
        return if (query.isEmpty()) {
            commands
        } else {
            commands.filter { it.name.startsWith(query) }
        }
    }
}
