/**
 * File: CommandSuggestionsAdapter.kt
 *
 * Description: RecyclerView Adapter for displaying Command Suggestions lists.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.ChatCommand

class CommandSuggestionsAdapter(
    private val onCommandClick: (ChatCommand) -> Unit
) : RecyclerView.Adapter<CommandSuggestionsAdapter.ViewHolder>() {

    private var commands: List<ChatCommand> = emptyList()

    fun updateCommands(newCommands: List<ChatCommand>) {
        commands = newCommands
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_command_suggestion, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val command = commands[position]
        holder.bind(command)
        holder.itemView.setOnClickListener {
            onCommandClick(command)
        }
    }

    override fun getItemCount(): Int = commands.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val commandName: TextView = view.findViewById(R.id.commandName)
        private val commandDescription: TextView = view.findViewById(R.id.commandDescription)

        fun bind(command: ChatCommand) {
            val displayName = if (command.requiresArg && command.argHint != null) {
                "/${command.name} <${command.argHint}>"
            } else {
                "/${command.name}"
            }
            commandName.text = displayName
            commandDescription.text = command.description
        }
    }
}
