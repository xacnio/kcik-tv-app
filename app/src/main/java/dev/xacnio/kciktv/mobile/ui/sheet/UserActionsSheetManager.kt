/**
 * File: UserActionsSheetManager.kt
 *
 * Description: Manages business logic, state, and UI interactions for User Actions Sheet.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.sheet

import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.ChatMessage
import dev.xacnio.kciktv.shared.data.model.ChatSender
import dev.xacnio.kciktv.shared.data.model.MessageType
import dev.xacnio.kciktv.shared.ui.adapter.UserHistoryAdapter
import dev.xacnio.kciktv.shared.ui.utils.BadgeRenderUtils
import dev.xacnio.kciktv.shared.util.ShimmerDrawable
import dev.xacnio.kciktv.shared.util.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manager class for handling User Actions Bottom Sheet UI
 * Extracted from MobilePlayerActivity for better code organization
 */
class UserActionsSheetManager(private val activity: MobilePlayerActivity) {
    
    private val TAG = "UserActionsSheetManager"
    
    private val prefs get() = activity.prefs
    private val repository get() = activity.repository
    private val chatStateManager get() = activity.chatStateManager
    
    fun showUserActionsSheet(sender: ChatSender) {
        val sheetView = activity.layoutInflater.inflate(R.layout.bottom_sheet_user_actions, null)
        val userActionsSheet = BottomSheetDialog(activity).apply {
            setContentView(sheetView)
            
            setOnShowListener { dialog ->
                val d = dialog as BottomSheetDialog
                val bottomSheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                if (bottomSheet != null) {
                    val behavior = BottomSheetBehavior.from(bottomSheet)
                    
                    // Calculate 70% of screen height
                    val displayMetrics = activity.resources.displayMetrics
                    val screenHeight = displayMetrics.heightPixels
                    val sheetHeight = (screenHeight * 0.70).toInt()
                    
                    // Set fixed height and prevent full screen expansion
                    val layoutParams = bottomSheet.layoutParams
                    layoutParams.height = sheetHeight
                    bottomSheet.layoutParams = layoutParams
                    
                    // Configure behavior - fixed at 70%, no expanding
                    behavior.peekHeight = sheetHeight
                    behavior.maxHeight = sheetHeight
                    behavior.state = BottomSheetBehavior.STATE_EXPANDED
                    behavior.skipCollapsed = true
                    behavior.isDraggable = true
                    behavior.isFitToContents = true
                    
                    bottomSheet.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            }
        }

        val usernameText = sheetView.findViewById<TextView>(R.id.usernameSheet)
        val avatarView = sheetView.findViewById<ImageView>(R.id.userAvatarSheet)
        val avatarShimmer = sheetView.findViewById<View>(R.id.avatarShimmerSheet)
        val badgesContainer = sheetView.findViewById<LinearLayout>(R.id.badgesContainerSheet)
        val banSwitch = sheetView.findViewById<SwitchMaterial>(R.id.banSwitchSheet)
        val timeoutSection = sheetView.findViewById<View>(R.id.timeoutSectionSheet)
        val historyRecycler = sheetView.findViewById<RecyclerView>(R.id.userHistoryRecyclerSheet)
        val emptyHistoryText = sheetView.findViewById<TextView>(R.id.emptyHistoryTextSheet)
        val loadingProgress = sheetView.findViewById<ProgressBar>(R.id.userLoadingSheet)
        val loadAllHistoryBtn = sheetView.findViewById<TextView>(R.id.loadAllHistoryBtn)
        val historyLoadingSpinner = sheetView.findViewById<ProgressBar>(R.id.historyLoadingSpinner)
        val historyHeaderText = sheetView.findViewById<TextView>(R.id.historyHeaderText)
        
        // Ban status views
        val banStatusContainer = sheetView.findViewById<View>(R.id.banStatusContainerSheet)
        val banStatusTitle = sheetView.findViewById<TextView>(R.id.banStatusTitleSheet)
        val banStatusDetail = sheetView.findViewById<TextView>(R.id.banStatusDetailSheet)
        val unbanButton = sheetView.findViewById<MaterialButton>(R.id.unbanButtonSheet)
        
        val isModeratorOrOwner = activity.isModeratorOrOwner
        
        // Set history header text based on moderator status
        if (isModeratorOrOwner) {
            historyHeaderText.text = activity.getString(R.string.message_history_all)
        } else {
            historyHeaderText.text = activity.getString(R.string.message_history_session)
            loadAllHistoryBtn.visibility = View.GONE // Hide load all button for non-moderators
        }
        
        // Start shimmer animation on avatar placeholder (starts automatically in init)
        val shimmerDrawable = ShimmerDrawable()
        avatarShimmer.background = shimmerDrawable
        
        // Show loading spinner initially
        loadingProgress.visibility = View.VISIBLE
        
        // Initial user info
        usernameText.text = sender.username
        try {
            val color = sender.color?.let { android.graphics.Color.parseColor(it) } ?: 0xFF53FC18.toInt()
            usernameText.setTextColor(color)
        } catch (_: Exception) {
            usernameText.setTextColor(0xFF53FC18.toInt())
        }

        // Setup History Components with REVERSE layout
        // This makes old messages added at "end" (visually top) without scroll jumping
        lateinit var historyAdapter: UserHistoryAdapter
        lateinit var fetchAllHistory: () -> Unit
        val layoutManager = LinearLayoutManager(activity).apply {
            stackFromEnd = true  // Start from bottom
            reverseLayout = true // Reverse the order (newest at bottom visually)
        }
        historyRecycler.layoutManager = layoutManager
        historyRecycler.itemAnimator = null // Disable animations
        historyRecycler.isNestedScrollingEnabled = false
        
        // Prevent RecyclerView scroll from triggering BottomSheet drag
        historyRecycler.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: android.view.MotionEvent): Boolean {
                when (e.action) {
                    android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> {
                        // Disable parent scrolling when touching RecyclerView
                        rv.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                return false
            }
            override fun onTouchEvent(rv: RecyclerView, e: android.view.MotionEvent) {}
            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })

        // Resolve Channel Info safely
        var resolvedChannelId = 0L
        val currentSlug = activity.currentChannel?.let { item ->
            resolvedChannelId = item.id.toLongOrNull() ?: 0L
            item.slug
        } ?: ""

        // Helper to load history
        var historyCursor: String? = null
        var isHistoryLoading = false
        var userId = sender.id

        // Returns Pair(hasMore, loadedMessageCount)
        suspend fun loadHistoryChunk(): Pair<Boolean, Int> {
            if (resolvedChannelId == 0L || userId == 0L) {
                Log.e(TAG, "Aborting history load: ChannelId=$resolvedChannelId, UserId=$userId")
                return Pair(false, 0)
            }
            isHistoryLoading = true
            
            Log.d(TAG, "Fetching history via WebView for user $userId in channel $resolvedChannelId with cursor $historyCursor")

            // Use WebView Fetch
            val resultText = activity.fetchHistoryViaWebView(resolvedChannelId, userId, historyCursor)
            
            var success = false
            var loadedCount = 0
            resultText.onSuccess { result ->
                withContext(Dispatchers.Default) {
                    // Mapping Logic (Copied from Repository)
                    fun parseIsoDate(isoString: String?): Long {
                        if (isoString == null) return System.currentTimeMillis()
                        return try {
                            val cleaned = if (isoString.contains(".")) {
                                isoString.substringBefore(".") + "Z"
                            } else isoString
                            val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                            format.timeZone = java.util.TimeZone.getTimeZone("UTC")
                            format.parse(cleaned)?.time ?: System.currentTimeMillis()
                        } catch (_: Exception) {
                            try {
                                val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                                format.timeZone = java.util.TimeZone.getTimeZone("UTC")
                                format.parse(isoString)?.time ?: System.currentTimeMillis()
                            } catch (_: Exception) {
                                System.currentTimeMillis()
                            }
                        }
                    }

                    val historyData = result.data
                    val messageList = historyData?.messages ?: result.messages
                    val nextCursor = historyData?.cursor
                    
                    val mappedMessages = messageList?.mapNotNull { historyMsg ->
                        if (historyMsg.sender != null && historyMsg.content != null) {
                            ChatMessage(
                                id = historyMsg.id ?: "h_${historyMsg.createdAt}_${historyMsg.sender.id}",
                                content = historyMsg.content,
                                sender = ChatSender(
                                    id = historyMsg.sender.id ?: 0L,
                                    username = historyMsg.sender.username ?: activity.getString(R.string.anonymous_user),
                                    color = historyMsg.sender.identity?.color,
                                    badges = historyMsg.sender.identity?.badges?.map { badge ->
                                        dev.xacnio.kciktv.shared.data.model.ChatBadge(
                                            type = badge.type ?: "",
                                            text = badge.text,
                                            count = badge.count
                                        )
                                    }
                                ),
                                createdAt = parseIsoDate(historyMsg.createdAt),
                                messageRef = try {
                                    historyMsg.metadata?.let { metaStr ->
                                        org.json.JSONObject(metaStr).optString("message_ref", "")
                                    } ?: ""
                                } catch (_: Exception) { "" }
                            )
                        } else null
                    } ?: emptyList()

                    withContext(Dispatchers.Main) {
                        historyCursor = nextCursor
                        loadedCount = mappedMessages.size
                        
                        if (mappedMessages.isNotEmpty()) {
                            // Add messages - reverse layout handles scroll automatically
                            historyAdapter.addMessages(mappedMessages.reversed())
                            emptyHistoryText.visibility = View.GONE
                            historyRecycler.visibility = View.VISIBLE
                            // No scroll restore needed - reverseLayout keeps position stable
                        } else if (historyAdapter.itemCount == 0) {
                            emptyHistoryText.visibility = View.VISIBLE
                            historyRecycler.visibility = View.GONE
                        }
                        success = true
                    }
                }
            }.onFailure { e ->
                 Log.e(TAG, "WebView History fetch failed: ${e.message}")
                 withContext(Dispatchers.Main) {
                     if (historyAdapter.itemCount == 0) {
                        emptyHistoryText.text = activity.getString(R.string.history_load_failed_web)
                        emptyHistoryText.visibility = View.VISIBLE
                     }
                 }
            }
            isHistoryLoading = false
            return Pair(success && historyCursor != null, loadedCount)
        }

        // Loads history until at least 100 messages are fetched
        fun fetchHistoryBatch() {
            if (isHistoryLoading) return
            
            // Show loading in adapter
            historyAdapter.setLoading(true)
            loadAllHistoryBtn.isEnabled = false
            historyLoadingSpinner.visibility = View.VISIBLE
            
            activity.lifecycleScope.launch {
                var totalLoaded = 0
                val minMessages = 100
                
                while (totalLoaded < minMessages) {
                    val (hasMore, count) = loadHistoryChunk()
                    totalLoaded += count
                    if (!hasMore) break
                }
                
                withContext(Dispatchers.Main) {
                    historyAdapter.setLoading(false)
                    historyAdapter.setHasMore(historyCursor != null)
                    loadAllHistoryBtn.isEnabled = historyCursor != null
                    loadAllHistoryBtn.alpha = if (historyCursor != null) 1f else 0.5f
                    historyLoadingSpinner.visibility = View.GONE
                }
            }
        }
        
        // Loads ALL history (no limit)
        fetchAllHistory = {
            if (!isHistoryLoading) {
                historyAdapter.setLoading(true)
                loadAllHistoryBtn.isEnabled = false
                loadAllHistoryBtn.text = activity.getString(R.string.history_loading)
                historyLoadingSpinner.visibility = View.VISIBLE
                
                activity.lifecycleScope.launch {
                    while (true) {
                        val (hasMore, _) = loadHistoryChunk()
                        if (!hasMore) break
                    }
                    withContext(Dispatchers.Main) {
                        historyAdapter.setLoading(false)
                        historyAdapter.setHasMore(false) // All loaded
                        loadAllHistoryBtn.text = activity.getString(R.string.history_all_loaded)
                        loadAllHistoryBtn.alpha = 0.5f
                        historyLoadingSpinner.visibility = View.GONE
                    }
                }
            }
        }
        
        // Initialize adapter (callback not used anymore - button is in header)
        historyAdapter = UserHistoryAdapter(activity, emptyList())
        historyRecycler.adapter = historyAdapter
        
        // Button click listener
        loadAllHistoryBtn.setOnClickListener { fetchAllHistory() }

        // Initialize Data: Resolve ID if needed, then fetch
        if (!isModeratorOrOwner) {
            if (userId == 0L) {
                emptyHistoryText.text = activity.getString(R.string.user_id_not_found)
                emptyHistoryText.visibility = View.VISIBLE
            } else {
                // For non-moderators: Filter messages from current chat session
                val sessionMessages = activity.chatAdapter.currentList
                    .filter { it.sender.id == userId && it.type == MessageType.CHAT }
                    .sortedByDescending { it.createdAt }
                
                if (sessionMessages.isNotEmpty()) {
                    historyAdapter.addMessages(sessionMessages)
                    emptyHistoryText.visibility = View.GONE
                    historyRecycler.visibility = View.VISIBLE
                } else {
                    emptyHistoryText.text = activity.getString(R.string.no_messages_in_session)
                    emptyHistoryText.visibility = View.VISIBLE
                }
            }
        } else {
            // For moderators: Use API to fetch full history
            activity.lifecycleScope.launch {
                // Resolution block
                if (resolvedChannelId == 0L || userId == 0L) {
                    activity.runOnUiThread { loadingProgress.visibility = View.VISIBLE }
                    
                    // Resolve Channel ID if missing
                    if (resolvedChannelId == 0L && currentSlug.isNotEmpty()) {
                        repository.getChannelDetails(currentSlug).onSuccess { detail ->
                             resolvedChannelId = detail.id ?: 0L
                             Log.d(TAG, "Resolved Channel ID from slug $currentSlug -> $resolvedChannelId")
                        }
                    }

                    // Resolve User ID if missing (Critical for /user command)
                    if (userId == 0L && currentSlug.isNotEmpty()) {
                        repository.getChannelUserInfo(currentSlug, sender.username, prefs.authToken).onSuccess { u ->
                            userId = u.id ?: 0L
                            Log.d(TAG, "Resolved User ID for ${sender.username} -> $userId")
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    loadingProgress.visibility = View.GONE
                    if (resolvedChannelId != 0L && userId != 0L) {
                        fetchHistoryBatch()
                    } else {
                        emptyHistoryText.text = if (resolvedChannelId == 0L) activity.getString(R.string.channel_id_error) else activity.getString(R.string.user_id_not_found)
                        emptyHistoryText.visibility = View.VISIBLE
                    }
                }
            }
        }

        // Load more on scroll to top (reverse layout: visually top = last positions)
        // Only needed for moderators who load from API with pagination
        if (isModeratorOrOwner) {
            historyRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    // dy < 0 = scrolling up visually (finger moves down, content moves up)
                    // In reverse layout, this moves towards the END of the list (older messages at top)
                    if (dy < 0) {
                        val lastVisible = layoutManager.findLastVisibleItemPosition()
                        val totalItems = historyAdapter.itemCount
                        // If we're near the end of the list (visually top)
                        if (lastVisible >= totalItems - 3 && !isHistoryLoading && historyCursor != null) {
                            fetchHistoryBatch()
                        }
                    }
                    
                    // Also check if we're at the very top and can't scroll further
                    if (!recyclerView.canScrollVertically(-1) && !isHistoryLoading && historyCursor != null) {
                        fetchHistoryBatch()
                    }
                }
            })
        }

        // Moderation Permissions
        val canModerate = isModeratorOrOwner && prefs.isLoggedIn && !sender.username.equals(prefs.username, ignoreCase = true)
        banSwitch.visibility = if (canModerate) View.VISIBLE else View.GONE
        timeoutSection.visibility = if (canModerate) View.VISIBLE else View.GONE

        // Load detailed user info (Badges, dates, ban status)
        if (currentSlug.isNotEmpty()) {
            activity.lifecycleScope.launch {
                repository.getChannelUserInfo(currentSlug, sender.username, prefs.authToken).onSuccess { u ->
                    activity.runOnUiThread {
                        // Profile Pic
                        val profileUrl = if (!u.profilePic.isNullOrEmpty()) u.profilePic else {
                            val hash = sender.username.hashCode()
                            val index = (if (hash < 0) -hash else hash) % 6 + 1
                            "https://kick.com/img/default-profile-pictures/default-avatar-$index.webp"
                        }
                        Glide.with(activity)
                            .load(profileUrl)
                            .circleCrop()
                            .listener(object : RequestListener<android.graphics.drawable.Drawable> {
                                override fun onLoadFailed(
                                    e: GlideException?,
                                    model: Any?,
                                    target: Target<android.graphics.drawable.Drawable>,
                                    isFirstResource: Boolean
                                ): Boolean {
                                    shimmerDrawable.stop()
                                    avatarShimmer.visibility = View.GONE
                                    avatarView.visibility = View.VISIBLE
                                    loadingProgress.visibility = View.GONE
                                    return false
                                }
                                
                                override fun onResourceReady(
                                    resource: android.graphics.drawable.Drawable,
                                    model: Any,
                                    target: Target<android.graphics.drawable.Drawable>?,
                                    dataSource: DataSource,
                                    isFirstResource: Boolean
                                ): Boolean {
                                    shimmerDrawable.stop()
                                    avatarShimmer.visibility = View.GONE
                                    avatarView.visibility = View.VISIBLE
                                    loadingProgress.visibility = View.GONE
                                    return false
                                }
                            })
                            .into(avatarView)

                        // Badges
                        badgesContainer.removeAllViews()
                        val bSize = (16 * activity.resources.displayMetrics.density).toInt()
                        val m = (4 * activity.resources.displayMetrics.density).toInt()
                        u.badges?.forEach { badge ->
                            BadgeRenderUtils.renderSingleBadgeIntoSheet(
                                activity, badgesContainer, badge, bSize, m, chatStateManager.subscriberBadges
                            )
                        }

                        // Dates
                        val followText = sheetView.findViewById<TextView>(R.id.followDateSheet)
                        val subText = sheetView.findViewById<TextView>(R.id.subDurationSheet)
                        
                        u.followingSince?.let { fs ->
                            try {
                                val clean = fs.substringBefore(".")
                                val date = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).parse(clean)
                                if (date != null) {
                                    val formattedDate = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault()).format(date)
                                    followText.text = activity.getString(R.string.follow_date_format, formattedDate)
                                    followText.visibility = View.VISIBLE
                                }
                            } catch (_: Exception) {
                                followText.text = activity.getString(R.string.follow_date_format, fs)
                                followText.visibility = View.VISIBLE
                            }
                        }

                        if ((u.subscribedFor ?: 0) > 0) {
                            val durationStr = activity.getString(R.string.duration_months, u.subscribedFor)
                            subText.text = activity.getString(R.string.sub_duration_format, durationStr)
                            subText.visibility = View.VISIBLE
                        }

                        // Handle ban status display
                        if (u.isBanned && canModerate) {
                            // User is banned - show ban status and hide timeout section & ban switch
                            banStatusContainer?.visibility = View.VISIBLE
                            timeoutSection.visibility = View.GONE
                            banSwitch.visibility = View.GONE
                            
                            if (u.isPermanentBan) {
                                banStatusTitle?.text = activity.getString(R.string.ban_permanent)
                            } else {
                                banStatusTitle?.text = activity.getString(R.string.ban_timeout)
                            }
                            
                            // Set unban button text from strings
                            unbanButton?.text = activity.getString(R.string.action_unban)
                            
                            // Build detail text with ban info
                            val detailParts = mutableListOf<String>()
                            
                            // Remaining time for timeout
                            if (!u.isPermanentBan) {
                                u.banExpiresAt?.let { expiresAt ->
                                    try {
                                        val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                                        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
                                        val cleanedDate = expiresAt.substringBefore(".")
                                        val expirationDate = format.parse(cleanedDate)
                                        if (expirationDate != null) {
                                            val remainingMs = expirationDate.time - System.currentTimeMillis()
                                            if (remainingMs > 0) {
                                                val remainingStr = TimeUtils.formatRemainingTime(activity, remainingMs)
                                                detailParts.add(activity.getString(R.string.ban_remaining_format, remainingStr))
                                            }
                                        }
                                    } catch (_: Exception) {}
                                }
                            }
                            
                            // Banned by / Reason / Date Fallback
                            val banFallback = activity.currentChannelBans.find { it.bannedUser?.username.equals(sender.username, ignoreCase = true) }

                            // Banned by (Try banner object, then bannedBy object, then ban list fallback, then bannerId fallback)
                            val bannerName = u.banned?.banner?.username 
                                ?: u.banned?.bannedBy?.username 
                                ?: banFallback?.bannedBy?.username
                                ?: u.banned?.bannerId?.toString()
                            
                            if (bannerName != null) {
                                detailParts.add(activity.getString(R.string.ban_banned_by_format, bannerName))
                            }

                            // Ban Reason
                            val reason = u.banned?.reason ?: banFallback?.banInfo?.reason
                            reason?.let { r ->
                                if (r.isNotBlank() && !r.equals("No reason provided", ignoreCase = true)) {
                                    detailParts.add(activity.getString(R.string.ban_reason_format, r))
                                }
                            }
                            
                            // Ban date
                            val rawDate = u.banned?.createdAt ?: banFallback?.banInfo?.bannedAt
                            rawDate?.let { rd ->
                                try {
                                    val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                                    format.timeZone = java.util.TimeZone.getTimeZone("UTC")
                                    val cleanedDate = rd.substringBefore(".")
                                    val banDate = format.parse(cleanedDate)
                                    if (banDate != null) {
                                        val displayFormat = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
                                        detailParts.add(activity.getString(R.string.ban_date_format, displayFormat.format(banDate)))
                                    }
                                } catch (_: Exception) {}
                            }
                            
                            if (detailParts.isNotEmpty()) {
                                banStatusDetail?.text = detailParts.joinToString(" • ")
                                banStatusDetail?.visibility = View.VISIBLE
                            } else {
                                banStatusDetail?.visibility = View.GONE
                            }
                            
                            // Unban button functionality
                            unbanButton?.setOnClickListener {
                                unbanButton.isEnabled = false
                                activity.lifecycleScope.launch {
                                    val result = repository.unbanUser(currentSlug, sender.username, prefs.authToken ?: "")
                                    activity.runOnUiThread {
                                        unbanButton.isEnabled = true
                                        result.onSuccess {
                                            // Hide ban status and show timeout/ban controls again
                                            banStatusContainer?.visibility = View.GONE
                                            timeoutSection.visibility = View.VISIBLE
                                            banSwitch.visibility = View.VISIBLE
                                            banSwitch.isChecked = false
                                            Toast.makeText(activity, R.string.mod_unban_success, Toast.LENGTH_SHORT).show()
                                        }.onFailure {
                                            Toast.makeText(activity, R.string.mod_unban_failed, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        } else {
                            banStatusContainer?.visibility = View.GONE
                        }

                        // Ban Switch Initial State
                        var ignoreNextBanSwitchChange = false
                        if (canModerate && !u.isBanned) {
                            banSwitch.isChecked = u.banned != null
                            banSwitch.setOnCheckedChangeListener { _, isChecked ->
                                if (ignoreNextBanSwitchChange) {
                                    ignoreNextBanSwitchChange = false
                                    return@setOnCheckedChangeListener
                                }
                                
                                activity.lifecycleScope.launch {
                                    val result = if (isChecked) {
                                        repository.banUser(currentSlug, sender.username, prefs.authToken ?: "")
                                    } else {
                                        repository.unbanUser(currentSlug, sender.username, prefs.authToken ?: "")
                                    }
                                    
                                    result.onSuccess {
                                        activity.runOnUiThread {
                                            Toast.makeText(activity, if (isChecked) activity.getString(R.string.user_banned_success) else activity.getString(R.string.user_unbanned_success), Toast.LENGTH_SHORT).show()
                                        }
                                    }.onFailure { err ->
                                        activity.runOnUiThread {
                                            Toast.makeText(activity, activity.getString(R.string.operation_failed, err.message), Toast.LENGTH_SHORT).show()
                                            
                                            // Revert without triggering logic again
                                            ignoreNextBanSwitchChange = true
                                            banSwitch.isChecked = !isChecked
                                        }
                                    }
                                }
                            }
                        }

                    }
                }.onFailure {
                    // Stop shimmer and hide loading on failure
                    activity.runOnUiThread {
                        shimmerDrawable.stop()
                        avatarShimmer.visibility = View.GONE
                        avatarView.visibility = View.VISIBLE
                        loadingProgress.visibility = View.GONE
                    }
                }
            }
        } else {
            // If no slug available, hide loading indicators immediately
            shimmerDrawable.stop()
            avatarShimmer.visibility = View.GONE
            avatarView.visibility = View.VISIBLE
            loadingProgress.visibility = View.GONE
        }

        // Setup Timeout Buttons
        if (canModerate) {
            val timeoutMap = mapOf(
                R.id.btnTimeout1m to 1,
                R.id.btnTimeout5m to 5,
                R.id.btnTimeout1h to 60,
                R.id.btnTimeout1d to 1440,
                R.id.btnTimeout1w to 10080
            )
            timeoutMap.forEach { (id, dur) ->
                sheetView.findViewById<View>(id)?.setOnClickListener {
                    activity.lifecycleScope.launch {
                        repository.timeoutUser(currentSlug, sender.username, dur, prefs.authToken ?: "").onSuccess {
                            activity.runOnUiThread {
                                val durationText = TimeUtils.formatDuration(activity, dur)
                                Toast.makeText(activity, activity.getString(R.string.mod_timeout_success, durationText), Toast.LENGTH_SHORT).show()
                                userActionsSheet.dismiss()
                            }
                        }.onFailure { err ->
                            activity.runOnUiThread { Toast.makeText(activity, activity.getString(R.string.error_moderation_failed) + ": ${err.message}", Toast.LENGTH_SHORT).show() }
                        }
                    }
                }
            }
        }
        
        activity.trackBottomSheet(userActionsSheet)
        userActionsSheet.show()
    }
}
