package dev.xacnio.kciktv

import android.animation.ValueAnimator
import android.util.Log
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.amazonaws.ivs.player.Cue
import com.amazonaws.ivs.player.Player
import com.amazonaws.ivs.player.PlayerException
import com.amazonaws.ivs.player.Quality
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import jp.wasabeef.glide.transformations.BlurTransformation
import dev.xacnio.kciktv.data.chat.KcikChatWebSocket
import dev.xacnio.kciktv.data.model.ChannelItem
import dev.xacnio.kciktv.data.model.ChatMessage
import dev.xacnio.kciktv.data.model.LivestreamResponse
import dev.xacnio.kciktv.data.model.LiveStreamsResponse
import dev.xacnio.kciktv.data.prefs.AppPreferences
import dev.xacnio.kciktv.data.repository.AuthRepository
import dev.xacnio.kciktv.data.repository.ChannelRepository
import dev.xacnio.kciktv.data.repository.UpdateRepository
import dev.xacnio.kciktv.data.repository.LoginResult
import dev.xacnio.kciktv.data.model.GithubRelease
import dev.xacnio.kciktv.databinding.ActivityPlayerBinding

import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.withContext
import dev.xacnio.kciktv.ui.adapter.ChannelSidebarAdapter
import dev.xacnio.kciktv.ui.adapter.ChatAdapter
import dev.xacnio.kciktv.ui.adapter.GenericSelectionAdapter
import dev.xacnio.kciktv.ui.adapter.SelectionItem
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import dev.xacnio.kciktv.data.server.LocalLoginServer
import dev.xacnio.kciktv.util.QRUtils
import java.net.NetworkInterface
import java.net.InetAddress
import java.util.Collections
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.TimeZone
import java.util.Locale

class PlayerActivity : FragmentActivity() {
    
    private var lastSettingsFocusId: String? = null
    private var lastSubSettingsFocusId: String? = null
    private var originalThemeColor: Int = 0

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var prefs: AppPreferences
    private var ivsPlayer: Player? = null
    
    private var streamCreatedAtMillis: Long? = null
    private var serverClockOffset: Long = 0L // Offset to sync local clock with server
    private val uptimeHandler = Handler(Looper.getMainLooper())
    private val uptimeRunnable = object : Runnable {
        override fun run() {
            updateUptimeDisplay()
            uptimeHandler.postDelayed(this, 1000L)
        }
    }
    private val repository = ChannelRepository()
    private val authRepository = AuthRepository()
    private val updateRepository = UpdateRepository()
    private var loginServer: LocalLoginServer? = null
    private var loadingJob: Job? = null
    
    private var allChannels = mutableListOf<ChannelItem>()
    private var currentChannelIndex = 0
    private var nextCursor: String? = null
    private var isChatVisible = false
    private var isStatsVisible = false
    
    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideAllOverlays() }
    private val statsHandler = Handler(Looper.getMainLooper())
    
    private enum class MenuState { NONE, INFO, QUICK_MENU, SIDEBAR, DRAWER, LOGIN, QUALITY, SEARCH }
    private var currentState = MenuState.NONE
    
    private enum class ListMode { GLOBAL, FOLLOWING, SEARCH }
    private var currentListMode = ListMode.GLOBAL
    
    private var sidebarContext: String = ""
    private var channelSidebarAdapter: ChannelSidebarAdapter? = null

    private val refreshHandler = Handler(Looper.getMainLooper())
    
    // Channel number input
    private var channelInputBuffer = StringBuilder()
    private val channelInputHandler = Handler(Looper.getMainLooper())
    private val channelInputRunnable = Runnable { processChannelInput() }
    
    // Volume overlay
    private lateinit var audioManager: AudioManager
    private val volumeHandler = Handler(Looper.getMainLooper())
    private val hideVolumeRunnable = Runnable { hideVolumeOverlay() }
    
    // Retry mechanism
    private var retryCount = 0
    private val maxRetryCount = 5
    private val retryDelayMs = 3000L
    private val retryHandler = Handler(Looper.getMainLooper())
    
    // Stability Watchdog
    private val stabilityHandler = Handler(Looper.getMainLooper())
    private var zeroBitrateCount = 0
    private val stabilityRunnable = object : Runnable {
        override fun run() {
            val player = ivsPlayer
            if (player != null && player.state == Player.State.PLAYING) {
                val statsStr = player.statistics.toString()
                val bitrate = Regex("bitRate=([\\d.]+)").find(statsStr)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                if (bitrate <= 0) {
                    zeroBitrateCount++
                    if (zeroBitrateCount >= 10) { // 10 seconds of 0 bitrate while playing
                        Log.w("PlayerActivity", "Stability watchdog triggered: 0 bitrate for 10s. Retrying...")
                        zeroBitrateCount = 0
                        scheduleRetry()
                        return
                    }
                } else {
                    zeroBitrateCount = 0
                }
                stabilityHandler.postDelayed(this, 1000L)
            }
        }
    }
    private val zapHandler = Handler(Looper.getMainLooper())
    private val zapDelayMs: Long get() = prefs.zapDelay.toLong()
    
    private var backPressedTime = 0L
    
    // Tier 1: Active Channel Refresh (Every 30 seconds - Hardcoded for "more frequent")
    private val activeChannelRefreshRunnable = object : Runnable {
        override fun run() {
            refreshActiveChannelDetails()
            refreshHandler.postDelayed(this, 30000L) // 30 seconds
        }
    }
    
    // Tier 2: Global List Refresh (User defined interval)
    private val globalListRefreshRunnable = object : Runnable {
        override fun run() {
            refreshGlobalList()
            if (prefs.autoRefreshInterval != -1) {
                val interval = prefs.autoRefreshInterval * 60000L
                refreshHandler.postDelayed(this, interval)
            }
        }
    }
    
    // Chat
    private var chatAdapter: ChatAdapter? = null
    private var chatWebSocket: KcikChatWebSocket? = null
    private val chatHandler = Handler(Looper.getMainLooper())
    private val pendingChatMessages = mutableListOf<ChatMessage>()
    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = AppPreferences(this)
        applyLocale()
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Restore last list mode
        try {
            currentListMode = ListMode.valueOf(prefs.lastListMode)
            // If Following but not logged in, fallback to Global
            if (currentListMode == ListMode.FOLLOWING && !prefs.isLoggedIn) {
                currentListMode = ListMode.GLOBAL
            }
        } catch (e: Exception) {
            currentListMode = ListMode.GLOBAL
        }

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        
        setupQuickMenu()
        setupRecyclerView()
        setupChat()
        initializeIVSPlayer()
        applySettings() // Call after setup
        setupDrawer()
        setupSearch()
        updateLanguageFilterButtonText()
        loadInitialChannels()
        
        checkForUpdates()
    }

    private fun setupSearch() {
        binding.searchEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                performSearch(binding.searchEditText.text.toString())
                true
            } else false
        }
    }

    private fun setupRecyclerView() {
        binding.sidebarRecyclerView.layoutManager = LinearLayoutManager(this)
        channelSidebarAdapter = ChannelSidebarAdapter(mutableListOf(), 0, prefs.themeColor, { pos ->
            currentChannelIndex = pos
            playCurrentChannelInternal()
        }, { loadMoreChannels() })
        binding.sidebarRecyclerView.adapter = channelSidebarAdapter
    }
    
    private fun setupDrawer() {
        val drawerWidth = 200f * resources.displayMetrics.density
        binding.drawerPanel.translationX = -drawerWidth
        binding.drawerPanel.visibility = View.GONE
        
        binding.btnTogglePassword.setOnClickListener {
            val isPassword = binding.loginPasswordInput.inputType and android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD != 0
            if (isPassword) {
                binding.loginPasswordInput.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                binding.btnTogglePassword.setImageResource(R.drawable.ic_eye)
            } else {
                binding.loginPasswordInput.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                binding.btnTogglePassword.setImageResource(R.drawable.ic_eye_off)
            }
            // Move cursor to end
            binding.loginPasswordInput.setSelection(binding.loginPasswordInput.text.length)
        }
        
        binding.btnSortViewersDesc.setOnClickListener { switchGlobalSortMode("viewer_count_desc") }
        binding.btnSortViewersAsc.setOnClickListener { switchGlobalSortMode("viewer_count_asc") }
        binding.btnSortFeatured.setOnClickListener { switchGlobalSortMode("featured") }
        binding.btnListFollowing.setOnClickListener { switchListMode(ListMode.FOLLOWING) }
        binding.btnDrawerMyChannel.setOnClickListener { playMyChannel() }
        binding.btnDrawerSearch.setOnClickListener { showSearchPanel() }
        binding.btnDrawerLanguageFilter.setOnClickListener { showLanguageFilterSidebar() }
        binding.btnDrawerSettings.setOnClickListener { 
            hideDrawer(immediate = true)
            showSettingsSidebar() 
        }
        
        binding.drawerLoginBtn.setOnClickListener {
            if (prefs.isLoggedIn) {
                // Logout logic
                prefs.clearAuth()
                updateDrawerUserInfo()
                Toast.makeText(this, getString(R.string.logged_out), Toast.LENGTH_SHORT).show()
                if (currentListMode == ListMode.FOLLOWING) {
                    switchListMode(ListMode.GLOBAL)
                }
            } else {
                hideDrawer(immediate = true)
                showLoginPanel()
            }
        }
        
        // Login panel setup
        binding.loginSubmitBtn.setOnClickListener { performLogin() }
        binding.loginCancelBtn.setOnClickListener { hideLoginPanel() }
        
        updateDrawerUserInfo()
    }

    private fun switchListMode(mode: ListMode) {
        if (currentListMode == mode && allChannels.isNotEmpty()) {
            hideDrawer()
            return
        }
        
        if (mode == ListMode.FOLLOWING && !prefs.isLoggedIn) {
            Toast.makeText(this, getString(R.string.not_logged_in), Toast.LENGTH_SHORT).show()
            return
        }
        
        currentListMode = mode
        prefs.lastListMode = mode.name 
        updateDrawerUserInfo()
        allChannels.clear()
        currentChannelIndex = 0
        channelSidebarAdapter?.replaceChannels(allChannels, 0)
        
        hideDrawer()
        loadInitialChannels()
    }

    private fun switchGlobalSortMode(sortMode: String) {
        if (currentListMode == ListMode.GLOBAL && prefs.globalSortMode == sortMode && allChannels.isNotEmpty()) {
            hideDrawer()
            return
        }

        currentListMode = ListMode.GLOBAL
        prefs.lastListMode = ListMode.GLOBAL.name
        prefs.globalSortMode = sortMode
        
        updateDrawerUserInfo()
        allChannels.clear()
        currentChannelIndex = 0
        channelSidebarAdapter?.replaceChannels(allChannels, 0)
        
        hideDrawer()
        loadInitialChannels()
    }


    private fun updateDrawerUserInfo() {
        val isLoggedIn = prefs.isLoggedIn
        val themeColor = prefs.themeColor

        if (isLoggedIn) {
            binding.drawerUsername.text = prefs.username ?: getString(R.string.guest)
            binding.drawerStatus.text = getString(R.string.logged_in)
            binding.drawerStatus.setTextColor(themeColor)
            binding.drawerLoginText.text = getString(R.string.logout)
            binding.drawerLoginIcon.setImageResource(R.drawable.ic_logout)
            binding.drawerLoginIcon.setColorFilter(Color.RED)
            
            Glide.with(this)
                .load(prefs.profilePic)
                .placeholder(R.drawable.ic_user)
                .error(R.drawable.ic_user)
                .circleCrop()
                .into(binding.drawerProfileImage)
            
            binding.drawerProfileImage.strokeColor = android.content.res.ColorStateList.valueOf(themeColor)

            // If profile pic is missing, try to fetch it from channel API as fallback
            if (prefs.profilePic.isNullOrEmpty() && !prefs.username.isNullOrEmpty()) {
                lifecycleScope.launch {
                    repository.getChannelDetails(prefs.username!!).onSuccess { details ->
                        val pic = details.user?.profilePic
                        if (!pic.isNullOrEmpty()) {
                            prefs.profilePic = pic
                            Glide.with(this@PlayerActivity)
                                .load(pic)
                                .placeholder(R.drawable.ic_user)
                                .circleCrop()
                                .into(binding.drawerProfileImage)
                        }
                    }
                }
            }
                
            binding.btnListFollowing.visibility = View.VISIBLE
        } else {
            binding.drawerUsername.text = getString(R.string.guest)
            binding.drawerStatus.text = getString(R.string.not_logged_in)
            binding.drawerStatus.setTextColor(Color.GRAY)
            binding.drawerLoginText.text = getString(R.string.login)
            binding.drawerLoginIcon.setImageResource(R.drawable.ic_login)
            binding.drawerLoginIcon.setColorFilter(themeColor)
            binding.drawerProfileImage.setImageResource(R.drawable.ic_user)
            binding.drawerProfileImage.strokeColor = android.content.res.ColorStateList.valueOf(themeColor)
            
            binding.btnListFollowing.visibility = View.VISIBLE
            
            if (currentListMode == ListMode.FOLLOWING) {
                currentListMode = ListMode.GLOBAL
                prefs.lastListMode = ListMode.GLOBAL.name
            }
        }

        // Apply theme and focus behavior to all buttons
        val sort = prefs.globalSortMode
        applyThemeToDynamicButton(binding.btnSortViewersDesc, themeColor, currentListMode == ListMode.GLOBAL && sort == "viewer_count_desc")
        applyThemeToDynamicButton(binding.btnSortViewersAsc, themeColor, currentListMode == ListMode.GLOBAL && sort == "viewer_count_asc")
        applyThemeToDynamicButton(binding.btnSortFeatured, themeColor, currentListMode == ListMode.GLOBAL && sort == "featured")
        
        applyThemeToDynamicButton(binding.btnListFollowing, themeColor, currentListMode == ListMode.FOLLOWING)
        applyThemeToDynamicButton(binding.btnDrawerMyChannel, themeColor, false)
        applyThemeToDynamicButton(binding.btnDrawerSearch, themeColor, currentListMode == ListMode.SEARCH)
        applyThemeToDynamicButton(binding.btnDrawerLanguageFilter, themeColor, sidebarContext == "LANGUAGE_FILTER")
        applyThemeToDynamicButton(binding.btnDrawerSettings, themeColor, false)
        applyThemeToDynamicButton(binding.drawerLoginBtn, themeColor, false)
    }

    private fun applyThemeToDynamicButton(view: View, color: Int, isActive: Boolean = false) {
        view.setOnFocusChangeListener { v, hasFocus ->
            updateButtonStyle(v, color, isActive, hasFocus)
        }
        updateButtonStyle(view, color, isActive, view.hasFocus())
    }

    private fun updateButtonStyle(view: View, color: Int, isActive: Boolean, isFocused: Boolean) {
        val bg = GradientDrawable()
        val density = resources.displayMetrics.density
        bg.cornerRadius = 14f * density // Consistent with others
        
        if (isFocused) {
            // Background: 30% alpha of theme color
            val alphaColor = (color and 0x00FFFFFF) or 0x4D000000 
            bg.setColor(alphaColor)
            // Border: Solid theme color
            bg.setStroke((2.5f * density).toInt(), color)
            
            view.setAllChildrenColor(Color.WHITE)
            // Bold text on focus if it's a menu item using children coloring
            (view as? ViewGroup)?.let { group ->
                for (i in 0 until group.childCount) {
                    val child = group.getChildAt(i)
                    if (child is TextView) child.typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
            }
        } else {
            bg.setStroke(0, Color.TRANSPARENT)
            if (isActive) {
                // Subtle indicator for current selection (15% alpha)
                val alphaColor = (color and 0x00FFFFFF) or 0x26000000 
                bg.setColor(alphaColor)
                view.setAllChildrenColor(color)
            } else {
                bg.setColor(Color.TRANSPARENT)
                view.setAllChildrenColor(Color.parseColor("#BBBBBB")) // greyColor
            }
            
            // Normal weight text
            (view as? ViewGroup)?.let { group ->
                for (i in 0 until group.childCount) {
                    val child = group.getChildAt(i)
                    if (child is TextView) child.typeface = android.graphics.Typeface.DEFAULT
                }
            }
        }
        
        view.background = bg
    }

    private fun showDrawer(focusType: String = "default") {
        val drawerWidth = 200f * resources.displayMetrics.density
        val isSidebarVisible = binding.sidebarMenu.visibility == View.VISIBLE
        
        // Hide only non-menu overlays
        binding.quickMenuOverlay.visibility = View.GONE
        binding.channelInfoOverlay.visibility = View.GONE
        binding.qualityPopup.visibility = View.GONE
        binding.searchPanel.visibility = View.GONE
        binding.errorPanel.visibility = View.GONE
        
        binding.menuScrim.visibility = View.VISIBLE
        binding.drawerPanel.visibility = View.VISIBLE
        currentState = MenuState.DRAWER
        
        if (prefs.animationsEnabled) {
            binding.menuScrim.animate().alpha(1f).setDuration(300).start()
            binding.drawerPanel.animate()
                .translationX(0f)
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .start()
            
            if (isSidebarVisible) {
                binding.sidebarMenu.animate().translationX(drawerWidth).setDuration(300).start()
            }
        } else {
            binding.menuScrim.alpha = 1f
            binding.drawerPanel.translationX = 0f
            if (isSidebarVisible) {
                binding.sidebarMenu.translationX = drawerWidth
            }
        }
        
        binding.drawerPanel.post {
            when (focusType) {
                "settings" -> binding.btnDrawerSettings.requestFocus()
                "login" -> binding.drawerLoginBtn.requestFocus()
                else -> {
                    when (currentListMode) {
                        ListMode.GLOBAL -> binding.btnSortViewersDesc.requestFocus()
                        ListMode.FOLLOWING -> binding.btnListFollowing.requestFocus()
                        ListMode.SEARCH -> binding.btnDrawerSearch.requestFocus()
                    }
                }
            }
        }
    }

    private fun hideDrawer(immediate: Boolean = false, animateSidebar: Boolean = true) {
        val drawerWidth = 200f * resources.displayMetrics.density
        val sidebarVisible = binding.sidebarMenu.visibility == View.VISIBLE
        
        if (immediate || !prefs.animationsEnabled) {
            binding.drawerPanel.visibility = View.GONE
            binding.drawerPanel.translationX = -drawerWidth
            if (animateSidebar && sidebarVisible) binding.sidebarMenu.translationX = 0f
            else if (!sidebarVisible) binding.menuScrim.visibility = View.GONE
        } else {
            binding.drawerPanel.animate()
                .translationX(-drawerWidth)
                .setDuration(250)
                .withEndAction { 
                    binding.drawerPanel.visibility = View.GONE 
                    if (!sidebarVisible) binding.menuScrim.visibility = View.GONE
                }
                .start()
            if (animateSidebar && sidebarVisible) {
                binding.sidebarMenu.animate().translationX(0f).setDuration(250).start()
            } else if (!sidebarVisible) {
                binding.menuScrim.animate().alpha(0f).setDuration(250).start()
            }
        }
        
        if (binding.drawerLanguageContent.visibility == View.VISIBLE) {
            hideLanguageFilter()
        }
        
        if (sidebarVisible) {
            currentState = MenuState.SIDEBAR
            binding.sidebarRecyclerView.requestFocus()
        } else {
            currentState = MenuState.NONE
            sidebarContext = ""
        }
    }

    private fun showLoginPanel() {
        hideAllOverlays()
        currentState = MenuState.LOGIN
        binding.loginPanel.visibility = View.VISIBLE
        binding.loginEmailInput.requestFocus()
        
        // Reset form
        binding.loginErrorText.visibility = View.GONE
        binding.loginOtpContainer.visibility = View.GONE
        binding.loginLoadingSpinner.visibility = View.GONE
        binding.loginSubmitText.visibility = View.VISIBLE
        
        // Setup and start QR Server
        startQrLoginServer()
        
        if (prefs.animationsEnabled) {
            binding.loginPanel.alpha = 0f
            binding.loginPanel.animate().alpha(1f).setDuration(200).start()
        } else {
            binding.loginPanel.alpha = 1f
        }
    }

    private fun startQrLoginServer() {
        val ip = getLocalIpAddress()
        if (ip == null) {
            binding.qrLoadingSpinner.visibility = View.GONE
            binding.qrLoginUrl.text = getString(R.string.ip_not_found)
            return
        }

        binding.qrLoadingSpinner.visibility = View.VISIBLE
        binding.loginQrImage.setImageBitmap(null)
        
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                loginServer?.stop()
                loginServer = LocalLoginServer(7171, authRepository) { token, user, pic ->
                    runOnUiThread {
                        prefs.saveAuth(token, user, pic)
                        updateDrawerUserInfo()
                        Toast.makeText(this@PlayerActivity, getString(R.string.login_success, user), Toast.LENGTH_SHORT).show()
                        hideLoginPanel()
                    }
                }
                loginServer?.start()
                
                val loginUrl = loginServer?.getLoginUrl(ip) ?: ""
                val qrBitmap = QRUtils.generateQRCode(loginUrl, 512)
                
                runOnUiThread {
                    binding.qrLoadingSpinner.visibility = View.GONE
                    binding.loginQrImage.setImageBitmap(qrBitmap)
                    binding.qrLoginUrl.text = loginUrl
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    binding.qrLoadingSpinner.visibility = View.GONE
                    binding.qrLoginUrl.text = getString(R.string.server_error, e.message)
                }
            }
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = java.util.Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val sAddr = addr.hostAddress
                        val isIPv4 = sAddr?.indexOf(':')?.let { it < 0 } ?: false
                        if (isIPv4 && sAddr != null) return sAddr
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return null
    }

    private fun hideLoginPanel() {
        if (currentState != MenuState.LOGIN) return
        
        loginServer?.stop()
        loginServer = null
        
        showChannelSidebar(requestFocus = false)
        
        if (prefs.animationsEnabled) {
            binding.loginPanel.animate().alpha(0f).setDuration(200).withEndAction { 
                binding.loginPanel.visibility = View.GONE 
                showDrawer(focusType = "login")
            }.start()
        } else {
            binding.loginPanel.visibility = View.GONE
            showDrawer(focusType = "login")
        }
    }

    private fun performLogin() {
        val email = binding.loginEmailInput.text.toString()
        val password = binding.loginPasswordInput.text.toString()
        val otp = binding.loginOtpInput.text.toString().takeIf { binding.loginOtpContainer.visibility == View.VISIBLE }

        if (email.isEmpty() || password.isEmpty()) {
            binding.loginErrorText.text = getString(R.string.login_fields_required)
            binding.loginErrorText.visibility = View.VISIBLE
            return
        }

        binding.loginLoadingSpinner.visibility = View.VISIBLE
        binding.loginSubmitText.visibility = View.GONE
        binding.loginErrorText.visibility = View.GONE

        lifecycleScope.launch {
            val result = authRepository.login(email, password, otp)
            
            binding.loginLoadingSpinner.visibility = View.GONE
            binding.loginSubmitText.visibility = View.VISIBLE
            
            when (result) {
                is LoginResult.Success -> {
                    val token = result.token
                    val user = result.user
                    prefs.saveAuth(token, user?.username ?: email, user?.profilePic ?: user?.profilePicture)
                    updateDrawerUserInfo()
                    
                    Toast.makeText(this@PlayerActivity, getString(R.string.login_success, user?.username ?: email), Toast.LENGTH_SHORT).show()
                    hideLoginPanel()
                }
                is LoginResult.TwoFARequired -> {
                    binding.loginOtpContainer.visibility = View.VISIBLE
                    binding.loginOtpInput.requestFocus()
                    binding.loginErrorText.text = getString(R.string.two_fa_required)
                    binding.loginErrorText.visibility = View.VISIBLE
                }
                is LoginResult.Error -> {
                    binding.loginErrorText.text = result.message
                    binding.loginErrorText.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun setupChat() {
        chatAdapter = ChatAdapter()
        binding.chatRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@PlayerActivity).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
            itemAnimator = null // Disable animations for performance
        }
        
        // Initialize chat panel as hidden (translated off screen)
        binding.chatPanel.translationX = 280f * resources.displayMetrics.density
        binding.chatPanel.visibility = View.GONE
    }

    private fun applySettings() {
        val alphaFloat = prefs.infoTransparency / 100f
        val alphaInt = (alphaFloat * 255).toInt()
        
        // Background alpha (only gradient)
        binding.channelInfoOverlay.background?.mutate()?.alpha = alphaInt
        binding.quickMenuOverlay.background?.mutate()?.alpha = alphaInt
        
        // Ensure View itself is opaque so text/images are crystal clear
        binding.channelInfoOverlay.alpha = 1.0f
        binding.quickMenuOverlay.alpha = 1.0f
        
        val themeColor = prefs.themeColor
        binding.channelNumber.setTextColor(themeColor)
        binding.sidebarTitle.setTextColor(themeColor)
        
        // Info Overlay Tints
        binding.qualityBadge.backgroundTintList = ColorStateList.valueOf(themeColor)
        binding.fpsBadge.backgroundTintList = ColorStateList.valueOf(themeColor)
        binding.viewerIcon.setColorFilter(themeColor)
        binding.sidebarLoadingBar.indeterminateTintList = ColorStateList.valueOf(themeColor)
        
        // Apply to Quick Menu Buttons
        applyThemeToQuickButton(binding.btnQualityQuick, themeColor)
        applyThemeToQuickButton(binding.btnRefreshQuick, themeColor)
        applyThemeToQuickButton(binding.btnStatsQuick, themeColor)
        
        // Update Adapter Themes
        // Update Sidebar themes
        channelSidebarAdapter?.updateThemeColor(themeColor)
        (binding.sidebarRecyclerView.adapter as? GenericSelectionAdapter)?.updateThemeColor(themeColor)
        chatAdapter?.updateThemeColor(themeColor)
        
        // Update Drawer theme
        updateDrawerUserInfo()
        
        // Update language adapter if visible
        if (binding.drawerLanguageContent.visibility == View.VISIBLE) {
            (binding.drawerLanguageRecyclerView.adapter as? GenericSelectionAdapter)?.updateThemeColor(themeColor)
        }
    }

    private fun applyThemeToQuickButton(view: View, color: Int) {
        view.background = null // Remove any legacy rectangle background
        val greyColor = Color.parseColor("#BBBBBB")
        
        view.setOnFocusChangeListener { v, hasFocus ->
            // Find the FrameLayout (selection background) which is the first child in our new XML
            val selectionBg = (v as? ViewGroup)?.getChildAt(0) as? FrameLayout
            if (selectionBg != null) {
                val bg = GradientDrawable()
                bg.shape = GradientDrawable.OVAL
                if (hasFocus) {
                    bg.setColor(color)
                    v.setAllChildrenColor(Color.WHITE)
                } else {
                    bg.setColor(Color.TRANSPARENT)
                    v.setAllChildrenColor(greyColor)
                }
                selectionBg.background = bg
            }
        }
        // Initial state
        view.background = null
        view.setAllChildrenColor(greyColor)
    }

    private fun View.setAllChildrenColor(color: Int) {
        if (this is android.view.ViewGroup) {
            for (i in 0 until childCount) {
                getChildAt(i).setAllChildrenColor(color)
            }
        } else if (this is TextView) {
            this.setTextColor(color)
        } else if (this is ImageView) {
            this.setColorFilter(color)
        }
    }

    private fun hideInternalSpinners(view: View) {
        if (view is android.widget.ProgressBar) {
            view.visibility = View.GONE
        } else if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                hideInternalSpinners(view.getChildAt(i))
            }
        }
    }

    private fun setupQuickMenu() {
        binding.btnQualityQuick.setOnClickListener { toggleQualityMenu() }
        binding.btnRefreshQuick.setOnClickListener { hideAllOverlays(); playCurrentChannel() }
        binding.btnStatsQuick.setOnClickListener { toggleStats() }
    }
    
    private fun toggleStats() {
        isStatsVisible = !isStatsVisible
        binding.statsOverlay.visibility = if (isStatsVisible) View.VISIBLE else View.GONE
        if (isStatsVisible) {
            startStatsUpdate()
        } else {
            stopStatsUpdate()
        }
    }
    
    private val statsRunnable = object : Runnable {
        override fun run() {
            updateStats()
            if (isStatsVisible) {
                statsHandler.postDelayed(this, 1000L)
            }
        }
    }
    
    private fun startStatsUpdate() {
        statsHandler.removeCallbacks(statsRunnable)
        statsHandler.post(statsRunnable)
    }
    
    private fun stopStatsUpdate() {
        statsHandler.removeCallbacks(statsRunnable)
    }
    
    private fun updateStats() {
        val player = ivsPlayer ?: return
        val quality = player.quality
        val stats = player.statistics
        // log stats
        Log.d("Stats", "Stats: $stats")
        
        val statsStr = stats.toString()
        val resolution = "${quality.width}x${quality.height}"
        
        fun parseField(key: String): String {
            return Regex("$key=([\\d.]+)").find(statsStr)?.groupValues?.get(1) ?: "0"
        }
        
        val rawBitrate = parseField("bitRate").toDoubleOrNull() ?: 0.0
        val bitrate = String.format("%.2f Mbps", rawBitrate / 1000000.0)
        val frameRate = parseField("frameRate")
        val decoded = parseField("decodedFrames").toLongOrNull() ?: 0L
        val dropped = parseField("droppedFrames").toLongOrNull() ?: 0L
        val rendered = parseField("renderedFrames").toLongOrNull() ?: 0L
        
        val dropPercent = if (decoded > 0) String.format("%.2f%%", (dropped.toDouble() / (decoded + dropped).toDouble()) * 100.0) else "0.00%"
        val latency = String.format("%.2fs", player.liveLatency / 1000.0)
        
        // Calculate buffer health: bufferedPosition - current position
        val bufferMs = player.bufferedPosition - player.position
        val buffer = String.format("%.2fs", (if (bufferMs > 0) bufferMs else 0L) / 1000.0)
        
        val yesStr = getString(R.string.yes)
        val noStr = getString(R.string.no)

        val content = """
            ${getString(R.string.stat_resolution)}:      $resolution
            ${getString(R.string.stat_fps)}:             $frameRate fps
            ${getString(R.string.stat_bitrate)}:         $bitrate
            ${getString(R.string.stat_codec)}:           H.264
            
            ${getString(R.string.stat_dropped_frames)}:  $dropped / $decoded ($dropPercent)
            ${getString(R.string.stat_decoded_frames)}:  $decoded
            ${getString(R.string.stat_rendered_frames)}: $rendered
            
            ${getString(R.string.stat_buffer_health)}:   $buffer
            ${getString(R.string.stat_latency)}:         $latency
            ${getString(R.string.stat_playback_rate)}:   ${player.playbackRate}x
            
            ${getString(R.string.stat_ready_state)}:     ${player.state}
            ${getString(R.string.stat_volume)}:          ${(player.volume * 100).toInt()}%
            ${getString(R.string.stat_muted)}:           ${if (player.isMuted) yesStr else noStr}
        """.trimIndent()
        
        binding.statsContent.text = content
    }
    
    private fun initializeIVSPlayer() {
        binding.playerView.setControlsEnabled(false)
        ivsPlayer = binding.playerView.player
        
        ivsPlayer?.addListener(object : Player.Listener() {
            override fun onStateChanged(state: Player.State) {
                // Force hide any sub-views (like IVS's own ProgressBar)
                if (state == Player.State.BUFFERING) {
                    hideInternalSpinners(binding.playerView)
                    binding.sidebarLoadingBar.visibility = View.VISIBLE
                    stopStabilityWatchdog()
                } else if (state == Player.State.PLAYING) {
                    // Give the player a tiny bit of time (150ms) to swap the first frame into the surface
                    // This prevents seeing the last frame of the previous channel
                    binding.playerView.postDelayed({
                        if (ivsPlayer?.state == Player.State.PLAYING) {
                            binding.playerView.visibility = View.VISIBLE
                            binding.loadingThumbnailView.visibility = View.GONE
                            binding.sidebarLoadingBar.visibility = View.GONE
                            hideError()
                            resetRetryCount()
                            startStabilityWatchdog()
                        }
                    }, 150)
                } else if (state == Player.State.READY) {
                    binding.sidebarLoadingBar.visibility = View.GONE
                    stopStabilityWatchdog()
                } else {
                    stopStabilityWatchdog()
                }
            }
            override fun onError(exception: PlayerException) {
                scheduleRetry()
            }
            override fun onQualityChanged(quality: Quality) {
                // Resolution Badge
                binding.qualityBadge.text = if (quality.height >= 1080) "1080P" 
                                          else if (quality.height >= 720) "720P"
                                          else "${quality.height}P"
                
                // FPS Badge
                binding.fpsBadge.text = String.format("%.0f FPS", quality.framerate)
                binding.fpsBadge.visibility = if (quality.framerate > 0) View.VISIBLE else View.GONE
            }
            override fun onCue(cue: Cue) {}
            override fun onDurationChanged(duration: Long) {}
            override fun onMetadata(type: String, data: ByteBuffer) {
                try {
                    val metadataString = java.nio.charset.StandardCharsets.UTF_8.decode(data).toString()
                    Log.d("PlayerActivity", "Metadata content: $metadataString")
                    
                    if (metadataString.trim().startsWith("{")) {
                        val json = org.json.JSONObject(metadataString)
                        
                        // 1. Sync System Clock with Server Time (Primary source for sync)
                        val serverTimeSec = when {
                            json.has("X-SERVER-TIME") -> json.optDouble("X-SERVER-TIME", 0.0)
                            json.has("X-TIMESTAMP") -> json.optDouble("X-TIMESTAMP", 0.0)
                            else -> 0.0
                        }
                        
                        // Also try to parse START-DATE which is ISO string
                        var serverTimeFromDate = 0L
                        if (json.has("START-DATE")) {
                            parseIsoDate(json.getString("START-DATE"))?.let { serverTimeFromDate = it }
                        }
                        
                        val finalServerTimeMillis = if (serverTimeSec > 0) (serverTimeSec * 1000).toLong() else serverTimeFromDate
                        
                        if (finalServerTimeMillis > 0) {
                            // Calculate how much our system clock is off/behind
                            serverClockOffset = finalServerTimeMillis - System.currentTimeMillis()
                            Log.d("PlayerActivity", "Synced offset: ${serverClockOffset}ms (Server: $finalServerTimeMillis, Sys: ${System.currentTimeMillis()})")
                        }
                        
                        // 2. Direct Uptime (if available in segments)
                        val streamTime = when {
                            json.has("STREAM-TIME") -> json.optDouble("STREAM-TIME", -1.0)
                            json.has("X-STREAM-TIME") -> json.optDouble("X-STREAM-TIME", -1.0)
                            else -> -1.0
                        }
                        
                        if (streamTime >= 0) {
                            lifecycleScope.launch(Dispatchers.Main) {
                                binding.streamTimeBadge.text = formatStreamTime(streamTime.toLong())
                                binding.streamTimeBadge.visibility = View.VISIBLE
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("PlayerActivity", "Metadata parse error", e)
                }
            }
            override fun onRebuffering() {}
            override fun onSeekCompleted(position: Long) {}
            override fun onVideoSizeChanged(width: Int, height: Int) {}
            override fun onAnalyticsEvent(name: String, properties: String) {}
        })
    }
    
    private fun loadInitialChannels() {
        nextCursor = null
        val languages = prefs.streamLanguages.toList().takeIf { it.isNotEmpty() }
        lifecycleScope.launch {
            val result = if (currentListMode == ListMode.FOLLOWING && prefs.authToken != null) {
                repository.getFollowingLiveStreams(prefs.authToken!!)
            } else if (prefs.globalSortMode == "featured") {
                repository.getFeaturedStreams(languages)
            } else {
                repository.getFilteredLiveStreams(languages, nextCursor, prefs.globalSortMode)
            }

            result.onSuccess { data: dev.xacnio.kciktv.data.repository.ChannelListData ->
                val filtered = data.channels.filter { !prefs.isCategoryBlocked(it.categoryName) }
                nextCursor = data.nextCursor
                if (filtered.isNotEmpty()) {
                    allChannels.clear()
                    allChannels.addAll(filtered)
                    currentChannelIndex = 0 // Always start from first channel on initial load
                    channelSidebarAdapter?.replaceChannels(allChannels, 0)
                    playCurrentChannel(useZapDelay = false)
                    showInfoOverlay() // Show info for the first channel automatically
                } else if (nextCursor != null && currentListMode == ListMode.GLOBAL) {
                    loadInitialChannels()
                } else if (filtered.isEmpty()) {
                    showError(getString(R.string.list_empty))
                }
            }.onFailure {
                showError(getString(R.string.channels_load_failed))
            }
        }
    }

    private fun loadMoreChannels() {
        if (currentListMode == ListMode.SEARCH || nextCursor == null) return
        val currentCursor = nextCursor
        val languages = prefs.streamLanguages.toList().takeIf { it.isNotEmpty() }
        lifecycleScope.launch {
            val result = if (currentListMode == ListMode.FOLLOWING && prefs.authToken != null) {
                repository.getFollowingLiveStreams(prefs.authToken!!, currentCursor)
            } else if (prefs.globalSortMode == "featured") {
                Result.success(dev.xacnio.kciktv.data.repository.ChannelListData(emptyList(), null)) // No pagination for featured
            } else {
                repository.getFilteredLiveStreams(languages, currentCursor, prefs.globalSortMode)
            }

            result.onSuccess { data: dev.xacnio.kciktv.data.repository.ChannelListData ->
                val filtered = data.channels.filter { !prefs.isCategoryBlocked(it.categoryName) }
                val firstNewItemIndex = allChannels.size
                nextCursor = data.nextCursor
                if (filtered.isNotEmpty()) {
                    allChannels.addAll(filtered)
                    channelSidebarAdapter?.addChannels(filtered)
                    
                    // Focus on the first newly loaded item
                    binding.sidebarRecyclerView.post {
                        binding.sidebarRecyclerView.scrollToPosition(firstNewItemIndex)
                        binding.sidebarRecyclerView.postDelayed({
                            binding.sidebarRecyclerView.findViewHolderForAdapterPosition(firstNewItemIndex)?.itemView?.requestFocus()
                        }, 100)
                    }
                } else if (nextCursor != null) {
                    loadMoreChannels()
                }
            }
        }
    }
    
    private fun playCurrentChannel(useZapDelay: Boolean = false, showInfo: Boolean = true) {
        if (allChannels.isEmpty()) return
        
        // Cancel any pending zap or load
        zapHandler.removeCallbacksAndMessages(null)
        hideError()
        resetRetryCount()
        
        // Reset refresh timer
        refreshHandler.removeCallbacks(activeChannelRefreshRunnable)
        refreshHandler.postDelayed(activeChannelRefreshRunnable, 30000L)
        
        // Update selection in sidebar
        channelSidebarAdapter?.setCurrentIndex(currentChannelIndex)
        
        // Safety check for index
        if (currentChannelIndex < 0 || currentChannelIndex >= allChannels.size) {
            currentChannelIndex = 0
        }
        
        if (allChannels.isEmpty()) return
        val channel = allChannels[currentChannelIndex]
        
        ivsPlayer?.pause()
        // RESET VIDEO VIEW IMMEDIATELY (Prevent frozen frame)
        binding.playerView.visibility = View.GONE
        
        // Reset uptime
        streamCreatedAtMillis = null
        binding.streamTimeBadge.visibility = View.GONE
        uptimeHandler.removeCallbacks(uptimeRunnable)
        
        // Ensure thumbnail is visible as placeholder
        binding.loadingThumbnailView.visibility = View.VISIBLE
        
        // Show Info Overlay so user sees where they are
        updateChannelUI(channel)
        if (showInfo) showInfoOverlay() // Show info immediately on explicit play/list change

        if (!channel.isLive) {
            updateChannelUIForOffline(channel)
            reconnectChatIfNeeded()
            return
        }

        // Show thumbnail as placeholder immediately with Blur Effect
        if (channel.thumbnailUrl != null) {
            binding.loadingThumbnailView.visibility = View.VISIBLE
            Glide.with(this)
                .load(channel.thumbnailUrl)
                .apply(RequestOptions.bitmapTransform(BlurTransformation(25, 4)))
                .placeholder(ColorDrawable(Color.BLACK))
                .into(binding.loadingThumbnailView)
        } else {
            binding.loadingThumbnailView.visibility = View.GONE
        }

        // Reconnect chat if needed
        reconnectChatIfNeeded()

        val playAction = Runnable {
            loadingJob?.cancel()
            loadingJob = lifecycleScope.launch {
                repository.getStreamUrl(channel.slug).onSuccess { url ->
                    ivsPlayer?.pause()
                    ivsPlayer?.load(Uri.parse(url))
                    ivsPlayer?.play()
                }.onFailure {
                    scheduleRetry()
                }
            }
        }

        if (useZapDelay) {
            zapHandler.postDelayed(playAction, zapDelayMs)
        } else {
            playAction.run()
        }
    }
    
    private fun updateChannelUI(channel: ChannelItem) {
        binding.channelName.text = channel.username
        binding.streamTitle.text = channel.title
        binding.channelNumber.text = String.format("%02d", currentChannelIndex + 1)
        binding.viewerCount.text = formatViewerCount(channel.viewerCount)
        binding.categoryName.text = channel.categoryName ?: getString(R.string.live_stream)
        Glide.with(this).load(channel.profilePicUrl).circleCrop().into(binding.profileImage)
        
        // Restore visibility after offline state
        binding.viewerCount.visibility = View.VISIBLE
        binding.categoryName.visibility = View.VISIBLE
        binding.qualityBadge.visibility = View.VISIBLE
        binding.offlineBannerView.visibility = View.GONE
        binding.viewerIcon.visibility = View.VISIBLE
        
        // Initialize Quality/FPS badges from current player state
        ivsPlayer?.quality?.let { quality ->
            binding.qualityBadge.text = if (quality.height >= 1080) "1080P" 
                                      else if (quality.height >= 720) "720P"
                                      else "${quality.height}P"
            binding.fpsBadge.text = String.format("%.0f FPS", quality.framerate)
            binding.fpsBadge.visibility = if (quality.framerate > 0) View.VISIBLE else View.GONE
        } ?: run {
            binding.qualityBadge.text = "AUTO"
            binding.fpsBadge.visibility = View.GONE
        }
        
        // Enable Marquee
        binding.channelName.isSelected = true
        binding.streamTitle.isSelected = true
        binding.categoryName.isSelected = true

        // Initialize uptime from initial channel data if available
        channel.startTimeMillis?.let {
            streamCreatedAtMillis = it
            updateUptimeDisplay()
        } ?: run {
            binding.streamTimeBadge.visibility = View.GONE
        }
    }

    private fun formatViewerCount(count: Int): String = when {
        count >= 1000000 -> String.format("%.1fM", count / 1000000.0)
        count >= 1000 -> String.format("%.1fK", count / 1000.0)
        else -> count.toString()
    }

    private fun showInfoOverlay() {
        hideHandler.removeCallbacks(hideRunnable)
        
        val isAlreadyVisible = binding.channelInfoOverlay.visibility == View.VISIBLE && binding.channelInfoOverlay.translationY == 0f

        // Don't close sidebar/drawer for info overlay, they can coexist
        val isMenuOpen = currentState == MenuState.SIDEBAR || currentState == MenuState.DRAWER
        if (!isMenuOpen && currentState != MenuState.INFO) {
            hideAllOverlays()
        }
        
        binding.channelInfoOverlay.visibility = View.VISIBLE
        binding.channelInfoOverlay.translationX = 0f // Reset horizontal translation
        
        if (isAlreadyVisible) {
            // Clean up any ongoing animations and stay at final position
            binding.channelInfoOverlay.animate().cancel()
            binding.channelInfoOverlay.translationY = 0f
            binding.channelInfoOverlay.alpha = 1f
        } else if (prefs.animationsEnabled) {
            binding.channelInfoOverlay.translationY = 220.toPx()
            binding.channelInfoOverlay.alpha = 0f
            binding.channelInfoOverlay.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(400)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .withStartAction {
                    uptimeHandler.removeCallbacks(uptimeRunnable)
                    uptimeHandler.post(uptimeRunnable)
                }
                .start()
        } else {
            binding.channelInfoOverlay.translationY = 0f
            binding.channelInfoOverlay.alpha = 1f
            uptimeHandler.removeCallbacks(uptimeRunnable)
            uptimeHandler.post(uptimeRunnable)
        }
        
        // Only change state if we are not in a menu (Sidebar/Drawer has higher priority for keys)
        if (!isMenuOpen) {
            currentState = MenuState.INFO
        }
        resetHideTimer()
    }

    private fun showQuickMenu() {
        hideHandler.removeCallbacks(hideRunnable) 
        hideAllOverlays()
        binding.quickMenuOverlay.visibility = View.VISIBLE
        // No bringToFront, rely on XML elevation (10dp)
        
        if (prefs.animationsEnabled) {
            binding.quickMenuOverlay.translationY = 50f
            binding.quickMenuOverlay.alpha = 0f
            binding.quickMenuOverlay.animate().translationY(0f).alpha(1f).setDuration(300).start()
        } else {
            binding.quickMenuOverlay.translationY = 0f
            binding.quickMenuOverlay.alpha = 1f
        }
        
        currentState = MenuState.QUICK_MENU
        
        // Quality available only for live streams
        val currentChannel = allChannels.getOrNull(currentChannelIndex)
        val isLive = currentChannel?.isLive == true
        
        binding.btnQualityQuick.visibility = if (isLive) View.VISIBLE else View.GONE
        binding.btnStatsQuick.visibility = if (isLive) View.VISIBLE else View.GONE
        binding.btnRefreshQuick.visibility = View.VISIBLE
        
        if (isLive) {
            binding.btnQualityQuick.requestFocus()
        } else {
            binding.btnRefreshQuick.requestFocus()
        }
    }

    private fun showChannelSidebar(requestFocus: Boolean = true) {
        hideHandler.removeCallbacks(hideRunnable)
        
        // Hide only non-menu overlays
        binding.quickMenuOverlay.visibility = View.GONE
        binding.channelInfoOverlay.visibility = View.GONE
        binding.qualityPopup.visibility = View.GONE
        binding.searchPanel.visibility = View.GONE
        
        sidebarContext = "channels"
        binding.sidebarTitle.text = getString(R.string.sidebar_channels)
        
        val drawerWidth = 200f * resources.displayMetrics.density
        val isDrawerVisible = binding.drawerPanel.visibility == View.VISIBLE
        val targetX = if (isDrawerVisible) drawerWidth else 0f
        
        channelSidebarAdapter?.setCurrentIndex(currentChannelIndex)
        
        if (prefs.animationsEnabled) {
            binding.sidebarMenu.translationX = targetX - 100f
            binding.sidebarMenu.alpha = 0f
            binding.menuScrim.alpha = binding.menuScrim.alpha // Keep current alpha if already visible
        } else {
            binding.sidebarMenu.translationX = targetX
            binding.sidebarMenu.alpha = 1f
            binding.menuScrim.alpha = 1f
        }
        
        binding.sidebarMenu.visibility = View.VISIBLE
        binding.menuScrim.visibility = View.VISIBLE
        currentState = MenuState.SIDEBAR
        
        if (prefs.animationsEnabled) {
            binding.sidebarMenu.animate().translationX(targetX).alpha(1f).setDuration(200).start()
            binding.menuScrim.animate().alpha(1f).setDuration(200).start()
        }

        binding.sidebarRecyclerView.apply {
            adapter = channelSidebarAdapter
            post {
                val lm = layoutManager as? LinearLayoutManager
                lm?.scrollToPositionWithOffset(currentChannelIndex, 200)
                if (requestFocus) {
                    findViewHolderForAdapterPosition(currentChannelIndex)?.itemView?.requestFocus()
                }
            }
        }
    }

    private fun playCurrentChannelInternal() {
        // From sidebar, don't show info as sidebar is already open/closing
        playCurrentChannel(useZapDelay = false, showInfo = false)
    }

    private fun showSidebar(title: String, items: List<SelectionItem>, context: String, animate: Boolean = true, initialFocusId: String? = null, onFocused: ((SelectionItem) -> Unit)? = null, onSelected: (SelectionItem) -> Unit) {
        hideHandler.removeCallbacks(hideRunnable)
        if (sidebarContext != context) hideAllOverlays() // Only hide if context changed to prevent flicker
        
        sidebarContext = context
        binding.sidebarTitle.text = title
        
        // Preserve scroll position if refreshing same context
        val layoutManager = binding.sidebarRecyclerView.layoutManager as? LinearLayoutManager
        val savedState = if (!animate && currentState == MenuState.SIDEBAR) layoutManager?.onSaveInstanceState() else null

        val drawerWidth = 200f * resources.displayMetrics.density
        val targetX = if (binding.drawerPanel.visibility == View.VISIBLE) drawerWidth else 0f

        if (animate && prefs.animationsEnabled) {
            binding.sidebarMenu.translationX = targetX - 100f
            binding.sidebarMenu.alpha = 0f
            binding.sidebarMenu.animate().translationX(targetX).alpha(1f).setDuration(300).start()
            
            binding.menuScrim.alpha = 0f
            binding.menuScrim.animate().alpha(1f).duration = 300
        } else {
            binding.sidebarMenu.translationX = targetX
            binding.sidebarMenu.alpha = 1f
            binding.menuScrim.alpha = 1f
        }
        
        binding.sidebarMenu.visibility = View.VISIBLE
        binding.menuScrim.visibility = View.VISIBLE
        
        currentState = MenuState.SIDEBAR
        binding.sidebarRecyclerView.apply {
            val currentAdapter = this.adapter as? GenericSelectionAdapter
            val isRefresh = !animate && sidebarContext == context && currentAdapter != null
            
            if (!isRefresh) {
                this.layoutManager = LinearLayoutManager(this@PlayerActivity)
                this.adapter = GenericSelectionAdapter(items, prefs.themeColor, onFocused) { onSelected(it) }
            } else {
                currentAdapter?.updateItems(items)
            }
            
            if (savedState != null) {
                (this.layoutManager as LinearLayoutManager).onRestoreInstanceState(savedState)
            }
            
            if (initialFocusId != null) {
                val index = items.indexOfFirst { it.id == initialFocusId }
                if (index != -1) {
                    val lm = this.layoutManager as LinearLayoutManager
                    if (isRefresh) {
                        // For refreshes, do it immediately to prevent focus jumping back to index 0
                        lm.scrollToPositionWithOffset(index, 200)
                        post { // View might need a frame to bind
                            findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
                        }
                    } else {
                        post {
                            lm.scrollToPositionWithOffset(index, 200)
                            findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
                        }
                    }
                } else {
                    requestFocus()
                }
            } else if (savedState == null && !isRefresh) {
                requestFocus()
            }
        }
    }

    internal fun hideAllOverlays() {
        if (currentState == MenuState.NONE) {
            sidebarContext = ""
            return
        }
        
        val animDuration = 250L
        if (prefs.animationsEnabled) {
            binding.menuScrim.animate().alpha(0f).setDuration(animDuration).withEndAction { binding.menuScrim.visibility = View.GONE }.start()
            
            if (binding.sidebarMenu.visibility == View.VISIBLE) 
                binding.sidebarMenu.animate().translationX(-400f).alpha(0f).setDuration(animDuration).withEndAction { binding.sidebarMenu.visibility = View.GONE }.start()
            
            if (binding.channelInfoOverlay.visibility == View.VISIBLE)
                 binding.channelInfoOverlay.animate().translationY(200.toPx()).alpha(0f).setDuration(animDuration).withEndAction { binding.channelInfoOverlay.visibility = View.GONE }.start()
            
            if (binding.quickMenuOverlay.visibility == View.VISIBLE)
                 binding.quickMenuOverlay.animate().translationY(50f).alpha(0f).setDuration(animDuration).withEndAction { binding.quickMenuOverlay.visibility = View.GONE }.start()
            
            if (binding.drawerPanel.visibility == View.VISIBLE)
                binding.drawerPanel.animate().translationX(-200f * resources.displayMetrics.density).setDuration(animDuration).withEndAction { binding.drawerPanel.visibility = View.GONE }.start()
            
            if (binding.loginPanel.visibility == View.VISIBLE)
                binding.loginPanel.animate().alpha(0f).setDuration(animDuration).withEndAction { binding.loginPanel.visibility = View.GONE }.start()
                
            if (binding.qualityPopup.visibility == View.VISIBLE)
                binding.qualityPopup.animate().alpha(0f).translationY(20f).setDuration(animDuration).withEndAction { binding.qualityPopup.visibility = View.GONE }.start()
        } else {
            binding.channelInfoOverlay.visibility = View.GONE
            binding.quickMenuOverlay.visibility = View.GONE
            binding.sidebarMenu.visibility = View.GONE
            binding.menuScrim.visibility = View.GONE
            binding.drawerPanel.visibility = View.GONE
            binding.loginPanel.visibility = View.GONE
            binding.qualityPopup.visibility = View.GONE
            binding.sidebarMenu.translationX = 0f
        }
        
        uptimeHandler.removeCallbacks(uptimeRunnable)
        currentState = MenuState.NONE
        sidebarContext = ""
    }
    
    private fun resetHideTimer() {
        hideHandler.removeCallbacks(hideRunnable)
        if (currentState == MenuState.INFO && prefs.infoDelay > 0) {
            hideHandler.postDelayed(hideRunnable, prefs.infoDelay * 1000L)
        }
    }

    private fun toggleQualityMenu() {
        if (currentState == MenuState.QUALITY) {
            hideAllOverlays()
            binding.btnQualityQuick.requestFocus()
            return
        }

        val qList = ivsPlayer?.qualities ?: return
        val currentQ = ivsPlayer?.quality
        val isAuto = ivsPlayer?.isAutoQualityMode == true
        
        val items = mutableListOf<SelectionItem>()
        items.add(SelectionItem("auto", getString(R.string.auto), isAuto))
        qList.toList().sortedByDescending { it.height }.forEach { q ->
            items.add(SelectionItem(q.name, q.name.uppercase(), !isAuto && currentQ?.name == q.name, q))
        }

        binding.qualityRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@PlayerActivity)
            adapter = GenericSelectionAdapter(items, prefs.themeColor) { item ->
                if (item.id == "auto") ivsPlayer?.setAutoQualityMode(true)
                else (item.payload as? Quality)?.let { ivsPlayer?.setQuality(it) }
                hideAllOverlays()
                binding.btnQualityQuick.requestFocus()
            }
        }

        binding.qualityPopup.visibility = View.VISIBLE
        if (prefs.animationsEnabled) {
            binding.qualityPopup.alpha = 0f
            binding.qualityPopup.translationY = 20f
            binding.qualityPopup.animate().alpha(1f).translationY(0f).setDuration(200).start()
        } else {
            binding.qualityPopup.alpha = 1f
            binding.qualityPopup.translationY = 0f
        }

        currentState = MenuState.QUALITY
        
        binding.qualityRecyclerView.post {
            val lm = binding.qualityRecyclerView.layoutManager as? LinearLayoutManager
            lm?.scrollToPositionWithOffset(0, 0)
            binding.qualityRecyclerView.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private fun showSettingsSidebar() {
        val items = listOf(
            SelectionItem("lang", getString(R.string.setting_language)),
            SelectionItem("delay", getString(R.string.setting_info_delay)),
            SelectionItem("trans", getString(R.string.setting_transparency)),
            SelectionItem("filter", getString(R.string.setting_filter)),
            SelectionItem("theme", getString(R.string.setting_theme)),
            SelectionItem("zap", getString(R.string.setting_zap_delay)),
            SelectionItem("anim", getString(R.string.setting_animations), prefs.animationsEnabled),
            SelectionItem("refresh", getString(R.string.setting_refresh_interval)),
            SelectionItem("update_channel", getString(R.string.setting_update_channel)),
            SelectionItem("about", getString(R.string.setting_about))
        )
        // Pass false to animate to keep same context without flicker
        showSidebar(getString(R.string.settings), items, "settings", animate = sidebarContext != "settings", initialFocusId = lastSettingsFocusId) { item ->
            lastSettingsFocusId = item.id
            when (item.id) {
                "lang" -> { lastSubSettingsFocusId = null; showLanguageSidebar() }
                "delay" -> { lastSubSettingsFocusId = null; showDelaySidebar() }
                "zap" -> { lastSubSettingsFocusId = null; showZapDelaySidebar() }
                "trans" -> { lastSubSettingsFocusId = null; showTransparencySidebar() }
                "filter" -> { lastSubSettingsFocusId = null; showFilterSidebar() }
                "theme" -> { lastSubSettingsFocusId = null; showThemeSidebar() }
                "anim" -> {
                    prefs.animationsEnabled = !prefs.animationsEnabled
                    (binding.sidebarRecyclerView.adapter as? GenericSelectionAdapter)?.toggleItemSelection(item.id)
                }
                "refresh" -> { lastSubSettingsFocusId = null; showRefreshSidebar() }
                "update_channel" -> { lastSubSettingsFocusId = null; showUpdateChannelSidebar() }
                "about" -> { lastSubSettingsFocusId = null; showAboutSidebar() }
            }
        }
    }

    private fun showAboutSidebar() {
        val version = BuildConfig.VERSION_NAME
        val items = listOf(
            SelectionItem("app_name", getString(R.string.app_name), false),
            SelectionItem("version", getString(R.string.app_version_label, version), false),
            SelectionItem("check_update", getString(R.string.check_update), false),
            SelectionItem("dev", getString(R.string.developer_name), false),
            SelectionItem("github", getString(R.string.visit_github), false),
            SelectionItem("coffee", getString(R.string.support_developer), false)
        )
        // Pass false to animate to keep same context without flicker
        showSidebar(getString(R.string.setting_about), items, "settings_sub", animate = sidebarContext != "settings_sub", initialFocusId = lastSubSettingsFocusId) { item ->
            lastSubSettingsFocusId = item.id
            if (item.id == "github") {
                val url = getString(R.string.github_url)
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                startActivity(intent)
            } else if (item.id == "coffee") {
                val url = "https://buymeacoffee.com/xacnio"
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                startActivity(intent)
            } else if (item.id == "version" || item.id == "check_update") {
                checkForUpdates(manual = true)
            }
        }
    }
    private fun showRefreshSidebar() {
        val options = listOf(
            -1 to getString(R.string.only_manual),
            1 to getString(R.string.x_minutes, 1),
            2 to getString(R.string.x_minutes, 2),
            5 to getString(R.string.x_minutes, 5),
            10 to getString(R.string.x_minutes, 10)
        )
        val items = options.map { (min, label) -> 
            SelectionItem(min.toString(), label, prefs.autoRefreshInterval == min, min) 
        }
        showSidebar(getString(R.string.sidebar_title_refresh), items, "settings_sub", animate = sidebarContext != "settings_sub", initialFocusId = lastSubSettingsFocusId) { item ->
            lastSubSettingsFocusId = item.id
            prefs.autoRefreshInterval = item.payload as Int
            startRefreshTimer()
            (binding.sidebarRecyclerView.adapter as? GenericSelectionAdapter)?.updateSingleSelection(item.id)
        }
    }
    private fun showThemeSidebar(keepFocusId: String? = null) {
        if (sidebarContext != "settings_theme") {
            originalThemeColor = prefs.themeColor
        }
        
        val colors = listOf(
            getString(R.string.kciktv_cyan) to 0xFF00F2FF.toInt(),
            getString(R.string.premium_emerald) to 0xFF10B981.toInt(),
            getString(R.string.premium_sapphire) to 0xFF3B82F6.toInt(),
            getString(R.string.premium_ruby) to 0xFFEF4444.toInt(),
            getString(R.string.premium_gold) to 0xFFF59E0B.toInt(),
            getString(R.string.premium_violet) to 0xFF8B5CF6.toInt(),
            getString(R.string.premium_teal) to 0xFF14B8A6.toInt(),
            getString(R.string.premium_rose) to 0xFFF43F5E.toInt()
        )
        val items = colors.map { (name, color) -> SelectionItem(color.toString(), name, prefs.themeColor == color, color) }
        showSidebar(
            getString(R.string.sidebar_title_theme), 
            items, 
            "settings_theme", 
            animate = sidebarContext != "settings_theme", 
            initialFocusId = keepFocusId ?: lastSubSettingsFocusId,
            onFocused = { item ->
                val newColor = item.payload as Int
                prefs.themeColor = newColor
                applySettings()
            }
        ) { item ->
            lastSubSettingsFocusId = item.id
            val finalColor = item.payload as Int
            prefs.themeColor = finalColor
            originalThemeColor = finalColor // Mark as selected
            applySettings()
            (binding.sidebarRecyclerView.adapter as? GenericSelectionAdapter)?.updateSingleSelection(item.id)
        }
    }

    private fun showFilterSidebar() {
        val categories = listOf("Knight Online", "Just Chatting", "Pools, Hot Tubs & Beaches", "Counter-Strike 2", "League of Legends", "Grand Theft Auto V")
        val items = categories.map { cat -> SelectionItem(cat, cat, prefs.isCategoryBlocked(cat), cat, showCheckbox = true) }
        
        // Pass animate = false if already in settings_sub context to prevent flicker
        val shouldAnimate = sidebarContext != "settings_sub"
        
        showSidebar(getString(R.string.sidebar_title_filter_hide), items, "settings_sub", shouldAnimate, initialFocusId = lastSubSettingsFocusId) { item ->
            lastSubSettingsFocusId = item.id
            // Toggle blocking
            val isBlocked = prefs.isCategoryBlocked(item.id)
            if (isBlocked) prefs.removeBlockedCategory(item.id) else prefs.addBlockedCategory(item.id)
            
            (binding.sidebarRecyclerView.adapter as? GenericSelectionAdapter)?.toggleItemSelection(item.id)
        }
    }
    private fun showLanguageSidebar() {
        val items = listOf(
            SelectionItem("system", getString(R.string.system_default), prefs.languageRaw == "system"),
            SelectionItem("tr", "Türkçe", prefs.languageRaw == "tr"),
            SelectionItem("en", "English", prefs.languageRaw == "en")
        )
        showSidebar(getString(R.string.sidebar_title_language), items, "settings_sub", animate = sidebarContext != "settings_sub", initialFocusId = lastSubSettingsFocusId) { item -> 
            lastSubSettingsFocusId = item.id
            setLanguage(item.id)
        }
    }
    private fun showDelaySidebar() {
        val delays = listOf(3, 5, 10, 20, 0)
        val items = delays.map { SelectionItem(it.toString(), if (it == 0) getString(R.string.only_manual) else getString(R.string.x_seconds, it), prefs.infoDelay == it, it) }
        showSidebar(getString(R.string.sidebar_title_delay), items, "settings_sub", animate = sidebarContext != "settings_sub", initialFocusId = lastSubSettingsFocusId) { item -> 
            lastSubSettingsFocusId = item.id
            prefs.infoDelay = item.payload as Int
            (binding.sidebarRecyclerView.adapter as? GenericSelectionAdapter)?.updateSingleSelection(item.id)
        }
    }
    private fun showTransparencySidebar() {
        val levels = listOf(50, 60, 70, 80, 90, 100)
        val items = levels.map { SelectionItem(it.toString(), "%$it", prefs.infoTransparency == it, it) }
        showSidebar(getString(R.string.sidebar_title_transparency), items, "settings_sub", animate = sidebarContext != "settings_sub", initialFocusId = lastSubSettingsFocusId) { item -> 
            lastSubSettingsFocusId = item.id
            prefs.infoTransparency = item.payload as Int
            applySettings()
            (binding.sidebarRecyclerView.adapter as? GenericSelectionAdapter)?.updateSingleSelection(item.id)
        }
    }
    private fun showZapDelaySidebar() {
        val current = prefs.zapDelay
        val items = listOf(
            SelectionItem("0", getString(R.string.instant_ms), current == 0),
            SelectionItem("300", getString(R.string.fast_ms), current == 300),
            SelectionItem("700", getString(R.string.normal_ms), current == 700),
            SelectionItem("1200", getString(R.string.slow_ms), current == 1200)
        )
        showSidebar(getString(R.string.sidebar_title_zap), items, "settings_sub", animate = sidebarContext != "settings_sub", initialFocusId = lastSubSettingsFocusId) { item ->
            lastSubSettingsFocusId = item.id
            prefs.zapDelay = item.id.toInt()
            (binding.sidebarRecyclerView.adapter as? GenericSelectionAdapter)?.updateSingleSelection(item.id)
        }
    }

    private fun showUpdateChannelSidebar() {
        val items = listOf(
            SelectionItem("stable", getString(R.string.stable), prefs.updateChannel == "stable"),
            SelectionItem("beta", getString(R.string.beta), prefs.updateChannel == "beta")
        )
        showSidebar(getString(R.string.sidebar_title_update_channel), items, "settings_sub", animate = sidebarContext != "settings_sub", initialFocusId = lastSubSettingsFocusId) { item ->
            lastSubSettingsFocusId = item.id
            prefs.updateChannel = item.id
            (binding.sidebarRecyclerView.adapter as? GenericSelectionAdapter)?.updateSingleSelection(item.id)
        }
    }

    private fun checkForUpdates(manual: Boolean = false) {
        if (manual) showError(getString(R.string.checking_updates))
        
        lifecycleScope.launch {
            updateRepository.getLatestRelease(prefs.updateChannel).onSuccess { release ->
                if (release != null) {
                    val currentVersion = BuildConfig.VERSION_NAME
                    val newVersion = release.tagName.replace("v", "")
                    
                    if (newVersion != currentVersion) {
                        if (manual) hideError()
                        showSidebar(
                            getString(R.string.update_available, release.tagName),
                            listOf(
                                SelectionItem("update", getString(R.string.update_now)),
                                SelectionItem("cancel", getString(R.string.cancel))
                            ),
                            "update_prompt"
                        ) { item ->
                            if (item.id == "update") {
                                downloadAndInstallApk(release)
                            } else {
                                hideAllOverlays()
                            }
                        }
                    } else if (manual) {
                        showError(getString(R.string.up_to_date))
                        Handler(Looper.getMainLooper()).postDelayed({ hideError() }, 2000)
                    }
                }
            }.onFailure {
                if (manual) {
                    showError("Update check failed: ${it.message}")
                    Handler(Looper.getMainLooper()).postDelayed({ hideError() }, 2000)
                }
            }
        }
    }

    private fun downloadAndInstallApk(release: GithubRelease) {
        val asset = release.assets.find { it.name.endsWith(".apk") } ?: return
        
        showError(getString(R.string.downloading))
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(asset.downloadUrl).build()
                val response = client.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val apkFile = File(cacheDir, "update.apk")
                    val fos = FileOutputStream(apkFile)
                    fos.write(response.body?.bytes() ?: return@launch)
                    fos.close()
                    
                    withContext(Dispatchers.Main) {
                        hideError()
                        installApk(apkFile)
                    }
                } else {
                    withContext(Dispatchers.Main) { showError(getString(R.string.download_failed)) }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { showError(getString(R.string.download_failed) + ": ${e.message}") }
            }
        }
    }

    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun applyLocale() {
        val lang = prefs.language
        Log.d("PlayerActivity", "Applying locale: $lang (Raw: ${prefs.languageRaw})")
        val locale = java.util.Locale(lang)
        java.util.Locale.setDefault(locale)
        
        val resources = resources
        val config = resources.configuration
        config.setLocale(locale)
        
        // Update both activity and application context resources
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
        @Suppress("DEPRECATION")
        applicationContext.resources.updateConfiguration(config, resources.displayMetrics)
    }

    private fun setLanguage(lang: String) {
        prefs.language = lang
        applyLocale()
        recreate()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Isolate Drawer, Login, and Quality states from global interactions
        if (currentState == MenuState.DRAWER || currentState == MenuState.LOGIN || currentState == MenuState.QUALITY || currentState == MenuState.SEARCH) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (currentState == MenuState.DRAWER || currentState == MenuState.QUALITY) return true // Trap/Ignore
                    return super.onKeyDown(keyCode, event)
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (currentState == MenuState.DRAWER || currentState == MenuState.QUALITY || currentState == MenuState.SEARCH) {
                        if (currentState == MenuState.DRAWER) {
                            if (binding.sidebarMenu.visibility == View.VISIBLE) {
                                hideDrawer()
                            } else {
                                showChannelSidebar()
                            }
                        }
                        return true
                    }
                    return super.onKeyDown(keyCode, event)
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val root = when (currentState) {
                        MenuState.DRAWER -> binding.drawerPanel
                        MenuState.LOGIN -> binding.loginPanel
                        MenuState.QUALITY -> binding.qualityPopup
                        MenuState.SEARCH -> binding.searchPanel
                        else -> binding.root
                    }
                    val focused = root.findFocus()
                    if (focused != null) {
                        val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_UP) View.FOCUS_UP else View.FOCUS_DOWN
                        val nextFocus = android.view.FocusFinder.getInstance().findNextFocus(
                            root, focused, direction
                        )
                        if (nextFocus != null) {
                            nextFocus.requestFocus()
                            return true // Consume event: successfully moved focus within panel
                        } else {
                            return true // Consume event: trap focus at boundaries
                        }
                    }
                    return super.onKeyDown(keyCode, event)
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    return super.onKeyDown(keyCode, event)
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (binding.drawerLanguageContent.visibility == View.VISIBLE) {
                        hideLanguageFilter()
                    }
                    else if (currentState == MenuState.DRAWER) hideDrawer()
                    else if (currentState == MenuState.LOGIN) hideLoginPanel()
                    else if (currentState == MenuState.SEARCH) hideSearchPanel()
                    else if (currentState == MenuState.QUALITY) {
                        // Close quality popup and return to quick menu state
                        if (prefs.animationsEnabled) {
                            binding.qualityPopup.animate().alpha(0f).translationY(20f).setDuration(200).withEndAction { 
                                binding.qualityPopup.visibility = View.GONE 
                            }.start()
                        } else {
                            binding.qualityPopup.visibility = View.GONE
                        }
                        currentState = MenuState.QUICK_MENU
                        binding.btnQualityQuick.requestFocus()
                    }
                    return true
                }
                // Block all other keys (Channel switch, numbers, volume etc)
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
                KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN -> return true
            }
        }

        if (currentState == MenuState.SIDEBAR) {
            val layoutManager = binding.sidebarRecyclerView.layoutManager as? LinearLayoutManager
            val focusView = binding.sidebarRecyclerView.focusedChild
            if (focusView != null && layoutManager != null) {
                val position = layoutManager.getPosition(focusView)
                val totalCount = binding.sidebarRecyclerView.adapter?.itemCount ?: 0
                val repeatCount = event?.repeatCount ?: 0
                
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (position == 0) {
                            // At the beginning: only wrap-around on first press
                            if (repeatCount == 0) {
                                binding.sidebarRecyclerView.scrollToPosition(totalCount - 1)
                                binding.sidebarRecyclerView.post { 
                                    binding.sidebarRecyclerView.findViewHolderForAdapterPosition(totalCount - 1)?.itemView?.requestFocus() 
                                }
                            }
                            // Even if held down, consume event (prevent sticking)
                            return true
                        } else {
                            // Normal up movement
                            val newPos = position - 1
                            binding.sidebarRecyclerView.scrollToPosition(newPos)
                            binding.sidebarRecyclerView.post { 
                                binding.sidebarRecyclerView.findViewHolderForAdapterPosition(newPos)?.itemView?.requestFocus() 
                            }
                            return true
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (position == totalCount - 1) {
                            // At the end: only wrap-around on first press
                            if (repeatCount == 0) {
                                binding.sidebarRecyclerView.scrollToPosition(0)
                                binding.sidebarRecyclerView.post { 
                                    binding.sidebarRecyclerView.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() 
                                }
                            }
                            // Even if held down, consume event (prevent sticking)
                            return true
                        } else {
                            // Normal down movement
                            val newPos = position + 1
                            binding.sidebarRecyclerView.scrollToPosition(newPos)
                            binding.sidebarRecyclerView.post { 
                                binding.sidebarRecyclerView.findViewHolderForAdapterPosition(newPos)?.itemView?.requestFocus() 
                            }
                            return true
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (sidebarContext.startsWith("settings")) return true // Trap Left in settings to prevent closing
                        showDrawer()
                        return true
                    }
                }
            }
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (currentState == MenuState.INFO) {
                    nextChannel()
                    return true
                }
                if (currentState == MenuState.NONE) {
                    nextChannel()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (currentState == MenuState.INFO) {
                    previousChannel()
                    return true
                }
                if (currentState == MenuState.NONE) {
                    previousChannel()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (currentState == MenuState.NONE || currentState == MenuState.INFO) {
                    if (!isChatVisible) showChat() else hideChat()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (currentState == MenuState.NONE || currentState == MenuState.INFO) {
                    showChannelSidebar()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (currentState == MenuState.NONE) {
                    showInfoOverlay()
                    return true
                } else if (currentState == MenuState.INFO) {
                    showQuickMenu() // OK while info is visible opens Quick Menu
                    return true
                }
            }
            // CH+ / CH- buttons
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                nextChannel()
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                previousChannel()
                return true
            }
            // Numeric keys (0-9)
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                val digit = keyCode - KeyEvent.KEYCODE_0
                handleChannelNumberInput(digit)
                return true
            }
            KeyEvent.KEYCODE_NUMPAD_0, KeyEvent.KEYCODE_NUMPAD_1, KeyEvent.KEYCODE_NUMPAD_2,
            KeyEvent.KEYCODE_NUMPAD_3, KeyEvent.KEYCODE_NUMPAD_4, KeyEvent.KEYCODE_NUMPAD_5,
            KeyEvent.KEYCODE_NUMPAD_6, KeyEvent.KEYCODE_NUMPAD_7, KeyEvent.KEYCODE_NUMPAD_8,
            KeyEvent.KEYCODE_NUMPAD_9 -> {
                val digit = keyCode - KeyEvent.KEYCODE_NUMPAD_0
                handleChannelNumberInput(digit)
                return true
            }
            // Vol+ / Vol-
            KeyEvent.KEYCODE_VOLUME_UP -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
                showVolumeOverlay()
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
                showVolumeOverlay()
                return true
            }
            KeyEvent.KEYCODE_VOLUME_MUTE -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
                showVolumeOverlay()
                return true
            }
            // Info / Guide / Menu buttons (toggle)
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_PROG_RED -> {
                if (currentState == MenuState.INFO) hideAllOverlays() else showInfoOverlay()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (isChatVisible) {
                    hideChat()
                    return true
                }
                when (currentState) {
                    MenuState.SIDEBAR -> { 
                        if (sidebarContext == "settings_sub" || sidebarContext == "settings_theme") {
                            if (sidebarContext == "settings_theme") {
                                prefs.themeColor = originalThemeColor
                                applySettings()
                            }
                            showSettingsSidebar()
                        } else if (sidebarContext == "settings") {
                            showChannelSidebar(requestFocus = false)
                            showDrawer(focusType = "settings") // Drawer focus wins
                        } else {
                            hideAllOverlays()
                        }
                        return true 
                    }
                    MenuState.QUICK_MENU -> { hideAllOverlays(); return true }
                    MenuState.INFO -> { hideAllOverlays(); return true }
                    MenuState.NONE -> {
                        if (backPressedTime + 2000 > System.currentTimeMillis()) finishAffinity()
                        else { 
                            Toast.makeText(this, getString(R.string.press_again_to_exit), Toast.LENGTH_SHORT).show()
                            backPressedTime = System.currentTimeMillis() 
                        }
                        return true
                    }
                    MenuState.LOGIN -> {
                        hideLoginPanel()
                        return true
                    }
                    MenuState.SEARCH -> {
                        showChannelSidebar(requestFocus = false)
                        showDrawer()
                        return true
                    }
                    MenuState.QUALITY -> {
                        showDrawer()
                        return true
                    }
                    else -> { hideAllOverlays(); return true }
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun nextChannel() { 
        if (currentListMode == ListMode.SEARCH) return
        currentChannelIndex = (currentChannelIndex + 1) % allChannels.size; 
        playCurrentChannel(useZapDelay = true) 
    }
    private fun previousChannel() { 
        if (currentListMode == ListMode.SEARCH) return
        currentChannelIndex = if (currentChannelIndex > 0) currentChannelIndex - 1 else allChannels.size - 1; 
        playCurrentChannel(useZapDelay = true) 
    }
    private fun refreshActiveChannelDetails() {
        if (allChannels.isEmpty() || currentChannelIndex >= allChannels.size) return
        val activeChannel = allChannels[currentChannelIndex]
        lifecycleScope.launch {
            repository.getLiveStreamDetails(activeChannel.slug).onSuccess { details: LivestreamResponse ->
                // Check safety again inside coroutine as list might have cleared during API call
                if (allChannels.isEmpty() || currentChannelIndex >= allChannels.size) return@onSuccess
                
                // Use details.viewers if available, otherwise keep current count
                val viewers = details.viewers ?: activeChannel.viewerCount
                binding.viewerCount.text = formatViewerCount(viewers)
                binding.streamTitle.text = details.sessionTitle ?: activeChannel.title
                
                // Update underlying data list
                val newStartTime = details.createdAt?.let { parseIsoDate(it) } ?: activeChannel.startTimeMillis
                val updatedChannel = activeChannel.copy(
                    viewerCount = viewers,
                    title = details.sessionTitle ?: activeChannel.title,
                    startTimeMillis = newStartTime
                )
                allChannels[currentChannelIndex] = updatedChannel
                
                // Update UI elements directly for current channel
                binding.viewerCount.text = formatViewerCount(viewers)
                binding.streamTitle.text = updatedChannel.title
                
                // Parse created_at for uptime
                details.createdAt?.let { dateStr ->
                    streamCreatedAtMillis = parseIsoDate(dateStr)
                }

                // Re-enable Marquee
                binding.streamTitle.isSelected = true
            }
        }
    }

    private fun refreshGlobalList() {
        if (allChannels.isEmpty()) return
        
        // Save the slug of the currently watched channel
        val currentSlug = allChannels.getOrNull(currentChannelIndex)?.slug
        
        val languages = prefs.streamLanguages.toList().takeIf { it.isNotEmpty() }
        lifecycleScope.launch {
            val result = if (currentListMode == ListMode.FOLLOWING && prefs.authToken != null) {
                repository.getFollowingLiveStreams(prefs.authToken!!)
            } else if (prefs.globalSortMode == "featured") {
                repository.getFeaturedStreams(languages)
            } else {
                repository.getFilteredLiveStreams(languages, null, prefs.globalSortMode)
            }

            result.onSuccess { data: dev.xacnio.kciktv.data.repository.ChannelListData ->
                val filtered = data.channels.filter { !prefs.isCategoryBlocked(it.categoryName) }
                if (filtered.isNotEmpty()) {
                    // Check if the watched channel is in the new data
                    val newIndex = if (currentSlug != null) {
                        filtered.indexOfFirst { it.slug == currentSlug }
                    } else -1
                    
                    // CRITICAL FIX: 
                    // If the watched channel is NOT on the first page and we have already loaded more (paginated)
                    // channels, we don't reset the list completely and throw the user to the beginning.
                    if (newIndex == -1 && allChannels.size > filtered.size) {
                        Log.d("PlayerActivity", "Active channel not in first page, skipping global list reset to preserve position.")
                        return@onSuccess
                    }

                    // Update the list completely
                    allChannels.clear()
                    allChannels.addAll(filtered)
                    
                    if (newIndex != -1) {
                        currentChannelIndex = newIndex
                        val currentChannel = allChannels[currentChannelIndex]
                        
                        // Update UI
                        binding.viewerCount.text = formatViewerCount(currentChannel.viewerCount)
                        binding.streamTitle.text = currentChannel.title
                        binding.categoryName.text = currentChannel.categoryName ?: getString(R.string.live_stream)
                        binding.channelNumber.text = String.format("%02d", currentChannelIndex + 1)
                    } else {
                        // If the channel really doesn't exist and we are already near the beginning, go back to top
                        currentChannelIndex = 0.coerceAtMost(allChannels.size - 1)
                        if (allChannels.isNotEmpty()) {
                            playCurrentChannel()
                        }
                    }
                    
                    // Update adapter
                    channelSidebarAdapter?.replaceChannels(allChannels, currentChannelIndex)
                }
            }
        }
    }

    private fun startRefreshTimer() {
        refreshHandler.removeCallbacks(activeChannelRefreshRunnable)
        refreshHandler.removeCallbacks(globalListRefreshRunnable)
        
        // Always refresh active channel every 30s while player is active
        refreshHandler.postDelayed(activeChannelRefreshRunnable, 30000L)
        
        // Refresh list based on settings
        if (prefs.autoRefreshInterval != -1) {
            val interval = prefs.autoRefreshInterval * 60000L
            refreshHandler.postDelayed(globalListRefreshRunnable, interval)
        }
    }

    private fun showError(msg: String) {
        binding.errorPanel.visibility = View.VISIBLE
        binding.errorText.text = msg
    }
    
    private fun hideError() {
        binding.errorPanel.visibility = View.GONE
    }
    
    private fun scheduleRetry() {
        // Cancel previous retry
        retryHandler.removeCallbacksAndMessages(null)
        
        // Check maximum retry count
        if (retryCount >= maxRetryCount) {
            showError(getString(R.string.connection_failed))
            markCurrentChannelOffline()
            return
        }
        
        retryCount++
        showError(getString(R.string.stream_error_retrying, retryCount, maxRetryCount))
        
        // Retry after 3 seconds
        retryHandler.postDelayed({
            retryCurrentChannel()
        }, retryDelayMs)
    }
    
    private fun resetRetryCount() {
        retryCount = 0
        retryHandler.removeCallbacksAndMessages(null)
    }
    
    private fun retryCurrentChannel() {
        if (allChannels.isEmpty()) return
        val channel = allChannels[currentChannelIndex]
        
        lifecycleScope.launch {
            // First get the current details of the channel (for Offline banner etc.)
            repository.getChannelDetails(channel.slug).onSuccess { fullDetails ->
                val isLive = fullDetails.livestream != null
                
                if (isLive) {
                    // Stream still active, get the stream URL
                    repository.getStreamUrl(channel.slug).onSuccess { url ->
                        ivsPlayer?.load(Uri.parse(url))
                        ivsPlayer?.play()
                    }.onFailure {
                        scheduleRetry()
                    }
                } else {
                    // Channel is truly offline
                    resetRetryCount()
                    markCurrentChannelWithOfflineBanner(fullDetails)
                }
            }.onFailure {
                // If we get an API error, fall back to default offline state
                resetRetryCount()
                markCurrentChannelOffline()
            }
        }
    }

    private fun markCurrentChannelWithOfflineBanner(details: dev.xacnio.kciktv.data.model.ChannelDetailResponse) {
        if (allChannels.isEmpty()) return
        val currentIndex = currentChannelIndex
        val channel = allChannels[currentIndex]
        
        // Preserve current banner if API returns null
        val offlineBanner = details.offlineBannerImage?.src ?: details.offlineBannerImage?.url ?: channel.offlineBannerUrl
        
        val offlineChannel = channel.copy(
            isLive = false,
            viewerCount = 0,
            title = details.livestream?.sessionTitle ?: getString(R.string.stream_offline),
            categoryName = details.livestream?.categories?.firstOrNull()?.name,
            offlineBannerUrl = offlineBanner
        )
        allChannels[currentIndex] = offlineChannel
        updateChannelUIForOffline(offlineChannel)
        
        ivsPlayer?.pause()
        binding.playerView.visibility = View.GONE
        channelSidebarAdapter?.notifyItemChanged(currentIndex)
        showError(getString(R.string.stream_offline))
    }
    
    private fun markCurrentChannelOffline() {
        if (allChannels.isEmpty()) return
        
        val channel = allChannels[currentChannelIndex]
        
        // Update channel as offline
        val offlineChannel = channel.copy(
            isLive = false,
            viewerCount = 0,
            title = "",
            categoryName = null
        )
        allChannels[currentChannelIndex] = offlineChannel
        
        // Update UI - Show offline state
        updateChannelUIForOffline(offlineChannel)
        
        // Stop player
        ivsPlayer?.pause()
        binding.playerView.visibility = View.GONE
        
        // Update adapter if sidebar is open
        channelSidebarAdapter?.notifyItemChanged(currentChannelIndex)
        
        showError(getString(R.string.stream_offline))
    }
    private fun updateChannelUIForOffline(channel: ChannelItem) {
        binding.channelName.text = channel.username
        binding.streamTitle.text = getString(R.string.stream_offline)
        binding.streamTitle.isSelected = false
        binding.channelNumber.text = String.format("%02d", currentChannelIndex + 1)
        
        // Hide ALL live-only components explicitly
        binding.viewerCount.visibility = View.GONE
        binding.viewerIcon.visibility = View.GONE
        binding.categoryName.visibility = View.GONE
        binding.qualityBadge.visibility = View.GONE
        binding.fpsBadge.visibility = View.GONE
        
        binding.sidebarLoadingBar.visibility = View.GONE
        binding.loadingThumbnailView.visibility = View.GONE 
        
        Glide.with(this).load(channel.profilePicUrl).circleCrop().into(binding.profileImage)
        
        // Ensure banner is behind menus (Menus are 10dp, Banner 1dp is fine)
        binding.playerView.visibility = View.GONE
        binding.offlineBannerView.visibility = View.VISIBLE
        
        if (!channel.offlineBannerUrl.isNullOrEmpty()) {
            val currentBannerUrl = binding.offlineBannerView.tag as? String
            
            // Only reload if the URL has actually changed
            if (currentBannerUrl != channel.offlineBannerUrl) {
                binding.offlineBannerView.tag = channel.offlineBannerUrl
                Glide.with(this)
                    .load(channel.offlineBannerUrl)
                    .centerCrop()
                    .placeholder(binding.offlineBannerView.drawable ?: ColorDrawable(Color.BLACK))
                    .error(ColorDrawable(Color.BLACK))
                    .into(binding.offlineBannerView)
            }
        } else {
            // Use profile pic with blur if banner is null
            val profileTag = "profile_blur_${channel.profilePicUrl}"
            val currentTag = binding.offlineBannerView.tag as? String
            
            if (currentTag != profileTag) {
                binding.offlineBannerView.tag = profileTag
                Glide.with(this)
                    .load(channel.profilePicUrl)
                    .centerCrop()
                    .transform(BlurTransformation(25, 3))
                    .placeholder(binding.offlineBannerView.drawable ?: ColorDrawable(Color.BLACK))
                    .into(binding.offlineBannerView)
            }
        }
    }
    private fun Int.toPx(): Float = this * resources.displayMetrics.density
    
    // ==================== Channel Number Input ====================
    
    private fun handleChannelNumberInput(digit: Int) {
        channelInputBuffer.append(digit)
        updateChannelInputOverlay()
        
        // Cancel previous timer
        channelInputHandler.removeCallbacks(channelInputRunnable)
        
        // Process after 1.5 seconds (no digit limit)
        channelInputHandler.postDelayed(channelInputRunnable, 1500)
    }
    
    private fun updateChannelInputOverlay() {
        binding.channelInputOverlay.visibility = View.VISIBLE
        binding.channelInputText.text = channelInputBuffer.toString()
        
        // Animation
        if (prefs.animationsEnabled) {
            binding.channelInputOverlay.alpha = 0f
            binding.channelInputOverlay.scaleX = 1.2f
            binding.channelInputOverlay.scaleY = 1.2f
            binding.channelInputOverlay.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(150)
                .start()
        } else {
            binding.channelInputOverlay.alpha = 1f
        }
    }
    
    private fun processChannelInput() {
        channelInputHandler.removeCallbacks(channelInputRunnable)
        
        val input = channelInputBuffer.toString()
        channelInputBuffer.clear()
        
        val targetChannel = input.toIntOrNull()
        if (targetChannel != null && targetChannel in 1..allChannels.size) {
            currentChannelIndex = targetChannel - 1
            playCurrentChannel()
        } else {
            Toast.makeText(this, getString(R.string.channel_not_found, input), Toast.LENGTH_SHORT).show()
        }
        
        hideChannelInputOverlay()
    }
    
    private fun hideChannelInputOverlay() {
        if (prefs.animationsEnabled) {
            binding.channelInputOverlay.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction { binding.channelInputOverlay.visibility = View.GONE }
                .start()
        } else {
            binding.channelInputOverlay.visibility = View.GONE
        }
    }
    
    // ==================== Volume Indicator ====================
    
    private fun showVolumeOverlay() {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val volumePercent = ((currentVolume.toFloat() / maxVolume) * 100).toInt()
        
        // Update volume icon
        val isMuted = currentVolume == 0
        binding.volumeIcon.setImageResource(if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume)
        
        // Update bar height (120dp max)
        val barHeight = (120 * resources.displayMetrics.density * currentVolume / maxVolume).toInt()
        val params = binding.volumeBarFill.layoutParams
        params.height = barHeight
        binding.volumeBarFill.layoutParams = params
        
        // Set bar color to theme color
        val fillDrawable = binding.volumeBarFill.background as? GradientDrawable
        fillDrawable?.setColor(prefs.themeColor)
        
        // Show percentage
        binding.volumeText.text = "$volumePercent%"
        
        // Show overlay
        binding.volumeOverlay.visibility = View.VISIBLE
        
        if (prefs.animationsEnabled) {
            binding.volumeOverlay.alpha = 0f
            binding.volumeOverlay.translationX = 50f
            binding.volumeOverlay.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(200)
                .start()
        } else {
            binding.volumeOverlay.alpha = 1f
            binding.volumeOverlay.translationX = 0f
        }
        
        // hide after 2 seconds
        volumeHandler.removeCallbacks(hideVolumeRunnable)
        volumeHandler.postDelayed(hideVolumeRunnable, 2000)
    }
    
    private fun hideVolumeOverlay() {
        if (prefs.animationsEnabled) {
            binding.volumeOverlay.animate()
                .alpha(0f)
                .translationX(50f)
                .setDuration(200)
                .withEndAction { binding.volumeOverlay.visibility = View.GONE }
                .start()
        } else {
            binding.volumeOverlay.visibility = View.GONE
        }
    }
    
    // ==================== Chat ====================
    
    private fun toggleChat() {
        hideAllOverlays()
        if (isChatVisible) {
            hideChat()
        } else {
            showChat()
        }
    }
    
    private fun showChat() {
        if (allChannels.isEmpty()) return
        
        isChatVisible = true
        val channel = allChannels[currentChannelIndex]
        
        // Make panel visible
        binding.chatPanel.visibility = View.VISIBLE
        
        // Animation values
        val chatWidthPx = (300f * resources.displayMetrics.density).toInt()
        
        if (prefs.animationsEnabled) {
            // Chat slide in from right
            binding.chatPanel.translationX = chatWidthPx.toFloat()
            binding.chatPanel.animate()
                .translationX(0f)
                .setDuration(300)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
            
            // Player container smooth shrink with margin animation
            val marginAnimator = ValueAnimator.ofInt(0, chatWidthPx)
            marginAnimator.duration = 300
            marginAnimator.interpolator = AccelerateDecelerateInterpolator()
            marginAnimator.addUpdateListener { animation ->
                val animatedValue = animation.animatedValue as Int
                val lp = binding.playerContainer.layoutParams as FrameLayout.LayoutParams
                lp.marginEnd = animatedValue
                binding.playerContainer.layoutParams = lp
            }
            marginAnimator.start()
        } else {
            binding.chatPanel.translationX = 0f
            val lp = binding.playerContainer.layoutParams as FrameLayout.LayoutParams
            lp.marginEnd = chatWidthPx
            binding.playerContainer.layoutParams = lp
        }
        
        // Start chat connection
        connectToChat(channel.slug)
    }
    
    private fun hideChat() {
        isChatVisible = false
        
        // Close chat connection
        chatWebSocket?.disconnect()
        chatWebSocket = null
        
        val chatWidthPx = (300f * resources.displayMetrics.density).toInt()
        val currentMargin = (binding.playerContainer.layoutParams as FrameLayout.LayoutParams).marginEnd
        
        if (prefs.animationsEnabled) {
            // Chat slide out to right
            binding.chatPanel.animate()
                .translationX(chatWidthPx.toFloat())
                .setDuration(250)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    binding.chatPanel.visibility = View.GONE
                    chatAdapter?.clearMessages()
                }
                .start()
            
            // Player container smooth grow with margin animation
            val marginAnimator = ValueAnimator.ofInt(currentMargin, 0)
            marginAnimator.duration = 250
            marginAnimator.interpolator = AccelerateDecelerateInterpolator()
            marginAnimator.addUpdateListener { animation ->
                val animatedValue = animation.animatedValue as Int
                val lp = binding.playerContainer.layoutParams as FrameLayout.LayoutParams
                lp.marginEnd = animatedValue
                binding.playerContainer.layoutParams = lp
            }
            marginAnimator.start()
        } else {
            binding.chatPanel.visibility = View.GONE
            binding.chatPanel.translationX = chatWidthPx.toFloat()
            chatAdapter?.clearMessages()
            val lp = binding.playerContainer.layoutParams as FrameLayout.LayoutParams
            lp.marginEnd = 0
            binding.playerContainer.layoutParams = lp
        }
    }
    
    private fun connectToChat(slug: String) {
        android.util.Log.d("PlayerActivity", "connectToChat called for slug: $slug")
        
        // First get chat info
        lifecycleScope.launch {
            repository.getChatInfo(slug).onSuccess { chatInfo ->
                android.util.Log.d("PlayerActivity", "Got channelId: ${chatInfo.channelId}, chatroomId: ${chatInfo.chatroomId}, badges: ${chatInfo.subscriberBadges.size}")
                
                // Subscriber badges'i adapter'a gönder
                chatHandler.post {
                    chatAdapter?.setSubscriberBadges(chatInfo.subscriberBadges)
                }
                
                // Load chat history first (with channelId)
                repository.getChatHistory(chatInfo.channelId).onSuccess { historyMessages ->
                    android.util.Log.d("PlayerActivity", "Got ${historyMessages.size} history messages")
                    chatHandler.post {
                        if (historyMessages.isNotEmpty()) {
                            android.util.Log.d("PlayerActivity", "Submitting ${historyMessages.size} messages to adapter")
                            chatAdapter?.submitList(historyMessages) {
                                binding.chatRecyclerView.scrollToPosition(historyMessages.size - 1)
                            }
                        } else {
                            android.util.Log.d("PlayerActivity", "No history messages to show")
                        }
                    }
                }.onFailure { error ->
                    android.util.Log.e("PlayerActivity", "Chat history failed: ${error.message}")
                }
                
                // Create WebSocket connection (with chatroomId)
                chatWebSocket = KcikChatWebSocket(
                    onMessageReceived = { message ->
                        chatHandler.post {
                            addChatMessage(message)
                        }
                    },
                    onConnectionStateChanged = { _ ->
                        chatHandler.post {
                            updateChatConnectionIndicator()
                        }
                    }
                )
                chatWebSocket?.connect(chatInfo.chatroomId)
            }.onFailure { error ->
                android.util.Log.e("PlayerActivity", "getChatInfo failed: ${error.message}")
                chatHandler.post {
                    updateChatConnectionIndicator()
                }
            }
        }
    }
    
    private fun addChatMessage(message: ChatMessage) {
        val currentList = chatAdapter?.currentList?.toMutableList() ?: mutableListOf()
        currentList.add(message)
        
        // Keep only last 100 messages for performance
        val trimmedList = if (currentList.size > 100) {
            currentList.takeLast(100)
        } else {
            currentList
        }
        
        chatAdapter?.submitList(trimmedList) {
            // Scroll to bottom
            binding.chatRecyclerView.scrollToPosition(trimmedList.size - 1)
        }
    }
    
    private fun updateChatConnectionIndicator() {
        // UI element removed
    }
    
    private fun reconnectChatIfNeeded() {
        if (isChatVisible && allChannels.isNotEmpty()) {
            chatWebSocket?.disconnect()
            chatAdapter?.clearMessages()
            val channel = allChannels[currentChannelIndex]
            connectToChat(channel.slug)
        }
    }
    
    override fun onPause() { 
        super.onPause()
        ivsPlayer?.pause()
        refreshHandler.removeCallbacks(activeChannelRefreshRunnable)
        refreshHandler.removeCallbacks(globalListRefreshRunnable)
        retryHandler.removeCallbacksAndMessages(null) // Cancel retries
        chatWebSocket?.disconnect()
    }
    
    override fun onResume() { 
        super.onResume()
        ivsPlayer?.play()
        startRefreshTimer()
        // Reconnect chat if it was visible
        if (isChatVisible && allChannels.isNotEmpty()) {
            val channel = allChannels[currentChannelIndex]
            connectToChat(channel.slug)
        }
    }
    override fun onDestroy() { 
        super.onDestroy()
        hideHandler.removeCallbacks(hideRunnable)
        channelInputHandler.removeCallbacks(channelInputRunnable)
        volumeHandler.removeCallbacks(hideVolumeRunnable)
        retryHandler.removeCallbacksAndMessages(null)
        chatHandler.removeCallbacksAndMessages(null)
        chatWebSocket?.disconnect()
        chatWebSocket = null
        loginServer?.stop()
        loginServer = null
        ivsPlayer?.release()
        ivsPlayer = null 
    }

    private fun formatStreamTime(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    private fun parseIsoDate(dateStr: String): Long? {
        return try {
            // Kick uses ISO-8601 (e.g., 2026-01-08T20:06:13.629Z)
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.parse(dateStr)?.time
        } catch (e: Exception) {
            null
        }
    }

    private fun updateUptimeDisplay() {
        val start = streamCreatedAtMillis ?: return
        val correctedNow = System.currentTimeMillis() + serverClockOffset
        val diffSeconds = (correctedNow - start) / 1000
        
        if (diffSeconds >= 0) {
            binding.streamTimeBadge.text = formatStreamTime(diffSeconds)
            binding.streamTimeBadge.visibility = View.VISIBLE
        }
    }

    private fun startStabilityWatchdog() {
        stabilityHandler.removeCallbacks(stabilityRunnable)
        zeroBitrateCount = 0
        stabilityHandler.postDelayed(stabilityRunnable, 5000L) // Wait 5s before first check
    }

    private fun stopStabilityWatchdog() {
        stabilityHandler.removeCallbacks(stabilityRunnable)
        zeroBitrateCount = 0
    }

    private fun getStreamLanguages(): List<Pair<String, String>> {
        val codes = resources.getStringArray(R.array.stream_language_codes)
        val names = resources.getStringArray(R.array.stream_language_names)
        return codes.zip(names)
    }

    private fun handleLanguageToggle(code: String) {
        val current = prefs.streamLanguages.toMutableSet()
        if (current.contains(code)) {
            current.remove(code)
        } else {
            current.add(code)
        }
        prefs.streamLanguages = current
        showLanguageFilterSidebar() // Re-populate with updated selections
        updateLanguageFilterButtonText()
        if (currentListMode == ListMode.GLOBAL || prefs.globalSortMode == "featured") {
            loadInitialChannels()
        }
    }

    private fun showLanguageFilterSidebar() {
        // We stay in MenuState.DRAWER but switch sub-views
        sidebarContext = "LANGUAGE_FILTER"
        
        val selectedCodes = prefs.streamLanguages
        val items = getStreamLanguages().map { (code, name) ->
            SelectionItem(
                id = code,
                title = name,
                isSelected = selectedCodes.contains(code),
                showCheckbox = true
            )
        }

        binding.drawerLanguageRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@PlayerActivity)
            adapter = GenericSelectionAdapter(items, prefs.themeColor) { item ->
                handleLanguageToggle(item.id)
            }
        }

        binding.drawerMainContent.visibility = View.GONE
        binding.drawerLanguageContent.visibility = View.VISIBLE
        
        binding.drawerLanguageRecyclerView.post {
            binding.drawerLanguageRecyclerView.requestFocus()
        }
    }

    private fun hideLanguageFilter() {
        binding.drawerLanguageContent.visibility = View.GONE
        binding.drawerMainContent.visibility = View.VISIBLE
        sidebarContext = ""
        binding.btnDrawerLanguageFilter.requestFocus()
    }

    private fun updateLanguageFilterButtonText() {
        val count = prefs.streamLanguages.size
        val baseText = getString(R.string.stream_language)
        binding.txtDrawerLanguageFilter.text = if (count > 0) {
            getString(R.string.language_selected_format, baseText, count)
        } else {
            baseText
        }
    }

    private fun showSearchPanel() {
        hideAllOverlays()
        binding.searchPanel.visibility = View.VISIBLE
        binding.searchPanel.alpha = 0f
        binding.searchPanel.animate().alpha(1f).setDuration(300).start()
        binding.searchEditText.requestFocus()
        currentState = MenuState.SEARCH
    }

    private fun hideSearchPanel() {
        binding.searchPanel.animate().alpha(0f).setDuration(300).withEndAction { 
            binding.searchPanel.visibility = View.GONE 
        }.start()
        currentState = MenuState.NONE
        // Hide keyboard
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchEditText.windowToken, 0)
    }

    private fun performSearch(slug: String) {
        if (slug.isBlank()) return
        
        binding.searchLoading.visibility = View.VISIBLE
        binding.searchErrorText.visibility = View.GONE
        
        lifecycleScope.launch {
            repository.getChannelDetails(slug.trim().lowercase()).onSuccess { detail ->
                val channelItem = ChannelItem(
                    id = detail.id?.toString() ?: "0",
                    slug = detail.slug ?: slug,
                    username = detail.user?.username ?: slug,
                    title = detail.livestream?.sessionTitle ?: "Live Stream",
                    viewerCount = detail.livestream?.viewerCount ?: 0,
                    thumbnailUrl = detail.livestream?.thumbnail?.src,
                    profilePicUrl = detail.user?.profilePic,
                    playbackUrl = detail.playbackUrl,
                    categoryName = detail.livestream?.categories?.firstOrNull()?.name,
                    language = "tr",
                    isLive = detail.livestream != null,
                    startTimeMillis = detail.livestream?.createdAt?.let { parseIsoDate(it) }
                )
                
                currentListMode = ListMode.SEARCH
                allChannels.clear()
                allChannels.add(channelItem)
                currentChannelIndex = 0
                channelSidebarAdapter?.replaceChannels(allChannels, 0)
                
                binding.searchLoading.visibility = View.GONE
                hideSearchPanel()
                hideDrawer(immediate = true)
                playCurrentChannel(showInfo = true)
                
            }.onFailure {
                binding.searchLoading.visibility = View.GONE
                binding.searchErrorText.text = getString(R.string.search_no_result)
                binding.searchErrorText.visibility = View.VISIBLE
            }
        }
    }

    private fun playMyChannel() {
        val myUsername = prefs.username
        if (myUsername != null) {
            performSearch(myUsername)
        } else {
            Toast.makeText(this, getString(R.string.not_logged_in), Toast.LENGTH_SHORT).show()
        }
    }
}
