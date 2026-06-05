package dev.xacnio.kciktv.mobile.ui.sheet

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import dev.xacnio.kciktv.mobile.LoginActivity
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences.StoredAccount

class AccountSwitcherSheetManager(private val activity: MobilePlayerActivity) {

    private val prefs get() = activity.prefs

    fun show() {
        val sheet = BottomSheetDialog(activity, R.style.BottomSheetDialogTheme)
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_account_switcher, null)
        sheet.setContentView(view)

        val recycler = view.findViewById<RecyclerView>(R.id.accountSwitcherRecycler)
        val addBtn = view.findViewById<LinearLayout>(R.id.btnAddAccount)

        recycler.layoutManager = LinearLayoutManager(activity)

        fun refresh() {
            val accounts = prefs.getAccounts()
            val activeId = prefs.activeAccountId
            recycler.adapter = AccountRowAdapter(accounts, activeId,
                onSwitch = { acc ->
                    sheet.dismiss()
                    activity.authManager.switchActiveAccount(acc.userId)
                },
                onRemove = { acc ->
                    prefs.removeAccount(acc.userId)
                    refresh()
                }
            )
        }
        refresh()

        addBtn.setOnClickListener {
            sheet.dismiss()
            activity.startActivity(Intent(activity, LoginActivity::class.java))
        }

        sheet.show()
    }

    private class AccountRowAdapter(
        private val accounts: List<StoredAccount>,
        private val activeId: Long,
        private val onSwitch: (StoredAccount) -> Unit,
        private val onRemove: (StoredAccount) -> Unit
    ) : RecyclerView.Adapter<AccountRowAdapter.VH>() {

        inner class VH(val view: View) : RecyclerView.ViewHolder(view) {
            val avatar: ImageView = view.findViewById(R.id.accountRowAvatar)
            val username: TextView = view.findViewById(R.id.accountRowUsername)
            val activeTick: ImageView = view.findViewById(R.id.accountRowActiveTick)
            val removeBtn: ImageView = view.findViewById(R.id.accountRowRemove)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_account_row, parent, false))

        override fun getItemCount() = accounts.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val acc = accounts[position]
            val isActive = acc.userId == activeId

            holder.username.text = acc.username

            val picUrl = if (acc.profilePic.isNullOrEmpty()) {
                val hash = acc.username.hashCode()
                val index = (if (hash < 0) -hash else hash) % 6 + 1
                "https://kick.com/img/default-profile-pictures/default-avatar-$index.webp"
            } else acc.profilePic

            Glide.with(holder.view.context)
                .load(picUrl)
                .circleCrop()
                .placeholder(R.drawable.default_avatar)
                .into(holder.avatar)

            holder.activeTick.visibility = if (isActive) View.VISIBLE else View.INVISIBLE
            holder.removeBtn.visibility = if (isActive) View.INVISIBLE else View.VISIBLE

            holder.view.isClickable = !isActive
            holder.view.alpha = if (isActive) 1f else 1f

            holder.view.setOnClickListener {
                if (!isActive) onSwitch(acc)
            }

            holder.removeBtn.setOnClickListener {
                onRemove(acc)
            }
        }
    }
}
