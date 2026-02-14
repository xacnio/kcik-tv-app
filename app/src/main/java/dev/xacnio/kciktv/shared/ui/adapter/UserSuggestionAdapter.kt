/**
 * File: UserSuggestionAdapter.kt
 *
 * Description: RecyclerView Adapter for displaying User Suggestion lists.
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

class UserSuggestionAdapter(
    private val onUserClick: (String) -> Unit
) : RecyclerView.Adapter<UserSuggestionAdapter.ViewHolder>() {

    private var users: List<String> = emptyList()

    fun updateUsers(newUsers: List<String>) {
        users = newUsers
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_command_suggestion, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val user = users[position]
        holder.bind(user)
        holder.itemView.setOnClickListener {
            onUserClick(user)
        }
    }

    override fun getItemCount(): Int = users.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val commandName: TextView = view.findViewById(R.id.commandName)
        private val commandDescription: TextView = view.findViewById(R.id.commandDescription)

        fun bind(username: String) {
            commandName.text = "@$username"
            commandDescription.visibility = View.GONE
        }
    }
}
