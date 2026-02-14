/**
 * File: GiftShopBottomSheet.kt
 *
 * Description: Bottom Sheet implementation for displaying Gift Shop Bottom content.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.ui.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dev.xacnio.kciktv.R
import java.text.NumberFormat
import java.util.Locale
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

import com.bumptech.glide.Glide
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import dev.xacnio.kciktv.shared.data.repository.AuthRepository

data class GiftItem(
    val id: String,
    val title: String,
    val subtitle: String?,
    val cost: Int
)

class GiftShopBottomSheet : BottomSheetDialogFragment() {

    private val basicGifts = listOf(
        GiftItem("hell_yeah", "Hell Yeah", null, 1),
        GiftItem("hype", "Hype", null, 10),
        GiftItem("skull_emoji", "Skull Emoji", null, 50),
        GiftItem("full_send", "Full Send", null, 100)
    )

    private val levelUpGifts = listOf(
        GiftItem("rage_quit", "Rage Quit", "Pin 10 mins", 500),
        GiftItem("pack_it_up", "Pack It Up", "Pin 20 mins", 1000),
        GiftItem("yap", "YAP", "Pin 40 mins", 2000),
        GiftItem("stomp", "Stomp", "Pin 1 hour", 5000),
        GiftItem("flex", "Flex", "Pin 2 hours", 10000),
        GiftItem("boom", "BOOOOOOM", "Pin 5 hours", 50000)
    )

    private var channelId: Long = 0
    private var currentBalance: Long = 0
    private var currentGift: GiftItem? = null
    private var currentQuantity: Int = 1

    companion object {
        private const val ARG_CHANNEL_ID = "channel_id"
        
        fun newInstance(channelId: Long): GiftShopBottomSheet {
            val fragment = GiftShopBottomSheet()
            fragment.arguments = Bundle().apply {
                putLong(ARG_CHANNEL_ID, channelId)
            }
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        channelId = arguments?.getLong(ARG_CHANNEL_ID) ?: 0
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_gift_shop, container, false)
    }
    
    override fun onStart() {
        super.onStart()
        dialog?.let { dialog ->
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let {
                val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(it)
                behavior.skipCollapsed = true
                behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                
                // Set max height
                val displayMetrics = resources.displayMetrics
                val maxHeight = (displayMetrics.heightPixels * 0.85).toInt()
                it.layoutParams.height = maxHeight
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.btnClose).setOnClickListener {
            dismiss()
        }

        view.findViewById<View>(R.id.btnClosePreview).setOnClickListener {
            dismiss()
        }

        val flipper = view.findViewById<android.widget.ViewFlipper>(R.id.giftShopFlipper)
        view.findViewById<View>(R.id.btnBackToShop).setOnClickListener {
            flipper.displayedChild = 0
        }

        // Quantity Selector
        setupQuantitySelector(view)

        view.findViewById<View>(R.id.btnSendGift).setOnClickListener {
            val gift = currentGift ?: return@setOnClickListener
            
            // For gifts >= 500, we allow a message. For smaller ones, it's empty.
            val canHaveMessage = gift.cost >= 500
            val messageInput = view.findViewById<android.widget.EditText>(R.id.edtGiftMessage)
            val message = if (canHaveMessage) messageInput.text.toString() else ""
            
            val prefs = dev.xacnio.kciktv.shared.data.prefs.AppPreferences(requireContext())
            val token = prefs.authToken ?: return@setOnClickListener
            
            val quantity = currentQuantity
            val sendButton = it as Button
            sendButton.isEnabled = false
            
            lifecycleScope.launch {
                var successCount = 0
                var lastError: String? = null
                
                for (i in 1..quantity) {
                    if (quantity > 1) {
                        sendButton.text = "$i/$quantity"
                    }
                    
                    val response = dev.xacnio.kciktv.shared.data.repository.AuthRepository().sendGift(token, channelId, gift.id, message)
                    if (response?.message == "Success") {
                        successCount++
                    } else {
                        lastError = response?.data?.details ?: response?.message ?: getString(R.string.gift_unknown_error)
                        break
                    }
                }
                
                if (successCount == quantity) {
                    val msg = if (quantity > 1) {
                        getString(R.string.gift_sent_success) + " (x$quantity)"
                    } else {
                        getString(R.string.gift_sent_success)
                    }
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    dismiss()
                } else if (successCount > 0) {
                    Toast.makeText(requireContext(), getString(R.string.gift_sent_success) + " ($successCount/$quantity) - $lastError", Toast.LENGTH_SHORT).show()
                    sendButton.isEnabled = true
                    sendButton.text = getString(R.string.send_action)
                } else {
                    sendButton.isEnabled = true
                    sendButton.text = getString(R.string.send_action)
                    Toast.makeText(requireContext(), getString(R.string.gift_send_failed, lastError ?: ""), Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Fetch Balance
        val txtBalance = view.findViewById<TextView>(R.id.txtBalance)
        val previewFooterCost = view.findViewById<TextView>(R.id.previewFooterCost)
        val prefs = dev.xacnio.kciktv.shared.data.prefs.AppPreferences(requireContext())
        val token = prefs.authToken
        if (token != null) {
            lifecycleScope.launch {
                val balanceData = dev.xacnio.kciktv.shared.data.repository.AuthRepository().fetchKicksBalance(token)
                balanceData?.balance?.available?.let {
                    currentBalance = it
                    val formatted = NumberFormat.getNumberInstance(Locale.US).format(it)
                    txtBalance?.text = formatted
                    previewFooterCost?.text = formatted
                }
            }
        }
        
        val rvBasic = view.findViewById<RecyclerView>(R.id.rvBasicGifts)
        rvBasic.layoutManager = GridLayoutManager(context, 3)
        rvBasic.adapter = GiftAdapter(basicGifts) {
             onGiftSelected(view, it)
        }

        val rvLevelUp = view.findViewById<RecyclerView>(R.id.rvLevelUpGifts)
        rvLevelUp.layoutManager = GridLayoutManager(context, 3)
        rvLevelUp.adapter = GiftAdapter(levelUpGifts) {
             onGiftSelected(view, it)
        }
    }

    private fun setupQuantitySelector(view: View) {
        val qtyButtons = listOf(
            view.findViewById<TextView>(R.id.btnQty1),
            view.findViewById<TextView>(R.id.btnQty2),
            view.findViewById<TextView>(R.id.btnQty3),
            view.findViewById<TextView>(R.id.btnQty4),
            view.findViewById<TextView>(R.id.btnQty5)
        )

        fun updateQuantityUI(selectedIndex: Int) {
            qtyButtons.forEachIndexed { index, btn ->
                if (index == selectedIndex) {
                    btn.setBackgroundResource(R.drawable.bg_quantity_chip_selected)
                    btn.setTextColor(android.graphics.Color.parseColor("#000000"))
                } else {
                    btn.setBackgroundResource(R.drawable.bg_quantity_chip)
                    btn.setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
                }
            }
        }

        qtyButtons.forEachIndexed { index, btn ->
            btn.setOnClickListener {
                currentQuantity = index + 1
                updateQuantityUI(index)
            }
        }
    }

    private fun onGiftSelected(view: View, gift: GiftItem) {
        currentGift = gift
        currentQuantity = 1
        setupQuantitySelector(view) // Reset quantity UI to x1
        val flipper = view.findViewById<android.widget.ViewFlipper>(R.id.giftShopFlipper)
        flipper.displayedChild = 1

        val prefs = dev.xacnio.kciktv.shared.data.prefs.AppPreferences(requireContext())
        val username = prefs.username ?: getString(R.string.anonymous_user)
        val chatColorHex = prefs.chatColor ?: "#A970FF"
        val userColor = try { android.graphics.Color.parseColor(chatColorHex) } catch (e: Exception) { android.graphics.Color.parseColor("#A970FF") }

        // Dynamic Background Gradient
        val cardBg = view.findViewById<View>(R.id.previewCardBackground)
        val giftImage = view.findViewById<ImageView>(R.id.previewGiftImage)
        val isPinGift = gift.cost >= 500
        
        if (cardBg != null) {
            if (isPinGift) {
                val r = android.graphics.Color.red(userColor)
                val g = android.graphics.Color.green(userColor)
                val b = android.graphics.Color.blue(userColor)
                
                val gradient = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(
                        android.graphics.Color.parseColor("#1A1A1A"),
                        android.graphics.Color.argb(80, r, g, b),
                        android.graphics.Color.argb(120, r, g, b)
                    )
                )
                gradient.cornerRadius = 8f * resources.displayMetrics.density
                cardBg.background = gradient
                cardBg.setPadding(
                    (16 * resources.displayMetrics.density).toInt(),
                    (16 * resources.displayMetrics.density).toInt(),
                    (16 * resources.displayMetrics.density).toInt(),
                    (16 * resources.displayMetrics.density).toInt()
                )
            } else {
                // Small gift: No gradient, smaller padding
                cardBg.setBackgroundResource(R.drawable.bg_gift_input) // Simple border/dark bg
                cardBg.setPadding(
                    (16 * resources.displayMetrics.density).toInt(),
                    (10 * resources.displayMetrics.density).toInt(),
                    (16 * resources.displayMetrics.density).toInt(),
                    (10 * resources.displayMetrics.density).toInt()
                )
            }
        }

        val senderText = view.findViewById<TextView>(R.id.previewSenderText)
        val fullText = if (isPinGift) getString(R.string.gift_sent_format_pin, username, gift.title) else getString(R.string.gift_sent_format, username + " " + gift.title)
        val spannable = android.text.SpannableString(fullText)
        
        // Color username
        spannable.setSpan(
            android.text.style.ForegroundColorSpan(userColor),
            0,
            username.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        // Ensure bold for everything
        spannable.setSpan(
            android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
            0,
            fullText.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        // White color for the rest
        spannable.setSpan(
            android.text.style.ForegroundColorSpan(android.graphics.Color.WHITE),
            username.length,
            fullText.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        
        senderText.text = spannable

        val costTextSmall = view.findViewById<TextView>(R.id.previewCostSmall)
        val balanceTextFooter = view.findViewById<TextView>(R.id.previewFooterCost)
        val formattedCost = NumberFormat.getNumberInstance(Locale.US).format(gift.cost)
        val formattedBalance = NumberFormat.getNumberInstance(Locale.US).format(currentBalance)
        
        costTextSmall.text = formattedCost
        balanceTextFooter.text = formattedBalance

        val durationText = view.findViewById<TextView>(R.id.previewDuration)
        val timerIcon = view.findViewById<ImageView>(R.id.previewTimerIcon)
        val messageInput = view.findViewById<View>(R.id.edtGiftMessage)

        if (gift.subtitle != null) {
            // It's a Pin gift
            durationText.visibility = View.VISIBLE
            timerIcon.visibility = View.VISIBLE
            
            val sub = gift.subtitle.lowercase()
            val timeValue = when {
                sub.contains("hour") -> {
                    val h = sub.filter { it.isDigit() }.toIntOrNull() ?: 1
                    String.format("%02d:00:00", h)
                }
                sub.contains("mins") -> {
                    val m = sub.filter { it.isDigit() }.toIntOrNull() ?: 0
                    String.format("00:%02d:00", m)
                }
                else -> gift.subtitle
            }
            durationText.text = timeValue
        } else {
            durationText.visibility = View.GONE
            timerIcon.visibility = View.GONE
        }
        
        // Only show message input for gifts 500 and above
        messageInput.visibility = if (gift.cost >= 500) View.VISIBLE else View.GONE

        val animUrl = "https://files.kick.com/kicks/gifts/${gift.id.replace("_", "-")}.webp"
        Glide.with(this)
            .load(animUrl)
            .placeholder(R.drawable.ic_loyalty)
            .into(giftImage)

        view.findViewById<android.widget.EditText>(R.id.edtGiftMessage).setText("")
    }

    class GiftAdapter(
        private val items: List<GiftItem>,
        private val onItemClick: (GiftItem) -> Unit
    ) : RecyclerView.Adapter<GiftAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val image: ImageView = view.findViewById(R.id.giftImage)
            val title: TextView = view.findViewById(R.id.giftTitle)
            val subtitle: TextView = view.findViewById(R.id.giftSubtitle)
            val cost: TextView = view.findViewById(R.id.giftCost)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_gift_shop, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.title.text = item.title
            
            if (item.subtitle != null) {
                holder.subtitle.visibility = View.VISIBLE
                holder.subtitle.text = item.subtitle
            } else {
                holder.subtitle.visibility = View.GONE
            }

            val formattedCost = NumberFormat.getNumberInstance(Locale.US).format(item.cost)
            holder.cost.text = formattedCost
            
            // Load Image from URL
            val animUrl = "https://files.kick.com/kicks/gifts/${item.id.replace("_", "-")}.webp"
            Glide.with(holder.itemView.context)
                .load(animUrl)
                .placeholder(R.drawable.ic_loyalty)
                .into(holder.image)
            
            holder.itemView.setOnClickListener {
                onItemClick(item)
            }
        }

        override fun getItemCount() = items.size
    }
}
