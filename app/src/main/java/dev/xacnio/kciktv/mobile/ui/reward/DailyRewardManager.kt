/**
 * File: DailyRewardManager.kt
 *
 * Description: Shows the "Daily Reward" modal — fetches the user's current gamification
 * challenge, plays a slot-style spin reveal the first time a reward is seen, and shows the
 * reward rarity breakdown popup.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.reward

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import com.bumptech.glide.Glide
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.databinding.ActivityMobilePlayerBinding
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.shared.data.model.ChallengeDropTableEntry
import dev.xacnio.kciktv.shared.data.model.DailyChallenge
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import dev.xacnio.kciktv.shared.data.repository.ChannelRepository
import dev.xacnio.kciktv.shared.util.VibrationUtils
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class DailyRewardManager(
    private val activity: MobilePlayerActivity,
    private val binding: ActivityMobilePlayerBinding,
    private val prefs: AppPreferences,
    private val repository: ChannelRepository,
    private val lifecycleScope: LifecycleCoroutineScope
) {
    private companion object {
        const val TAG = "DailyRewardManager"

        // Rarest-to-most-common, matches the order Kick shows in its own rarity breakdown.
        val RARITY_ORDER = listOf("mythic", "legendary", "epic", "rare", "uncommon", "common")

        // Per-tick delay for the spin reveal; increasing values read as a slot machine settling.
        val SPIN_DELAYS_MS = listOf(50L, 50L, 60L, 60L, 70L, 80L, 90L, 110L, 130L, 160L, 200L, 260L)

        // Cards drawn for the locked-state marquee; rendered twice for a seamless loop.
        const val MARQUEE_CARD_COUNT = 40

        // Ceiling on marquee card size, since the reward area is now as tall as the screen.
        const val MARQUEE_MAX_ITEM_HEIGHT_DP = 250

        // Width-to-height of a collectible card, used to size the marquee items.
        const val CARD_ASPECT = 0.75f

        // Must match rewardCardShine's rotation in the layout; sweep distance is derived from it.
        const val SHINE_ROTATION_DEG = 35f

        // Bounded retry while waiting for Glide to decode the art the sweep is sized from.
        const val CARD_SHINE_RETRY_MS = 120L
        const val CARD_SHINE_MAX_ATTEMPTS = 12

        // Zoomed card: sits at a slight 3D tilt, drifts, and can be turned with a drag.
        const val CARD_REST_ROTATION_Y = -14f
        const val CARD_REST_ROTATION_X = 7f
        // Turn limits: past ~90° the flat card would show edge-on / mirrored art.
        const val CARD_MAX_YAW = 70f
        const val CARD_MAX_PITCH = 50f
        const val CARD_FLOAT_DP = 10
        const val CARD_DRAG_DEGREES_PER_DP = 0.55f
        // Delay before the hold-hover appears (so taps/drags don't trigger it), then time to cover the card.
        const val CARD_HOLD_DELAY_MS = 220L
        const val CARD_HOLD_MS = 1150L
        const val CARD_HOLD_RELEASE_MS = 240L
        const val CARD_SHATTER_MS = 760L
        // Low values exaggerate the perspective into a fisheye; this keeps the turn natural.
        const val CARD_CAMERA_DISTANCE = 12000f

        // Animated chest for the header button when a reward is claimable.
        // In assets, not res/drawable — aapt crunching would strip the APNG animation chunks.
        const val CTA_ASSET = "reward_available.png"
    }

    var currentDialog: android.app.Dialog? = null
    private var zoomDialog: android.app.Dialog? = null
    private var rarityPopup: PopupWindow? = null
    private var spinRunnable: Runnable? = null
    private var shineRunnable: Runnable? = null
    // Separate from shineRunnable since the modal and zoom dialog can shine at the same time.
    private var cardShineRunnable: Runnable? = null
    private var marqueeAnimator: android.animation.ValueAnimator? = null
    private var floatAnimator: android.animation.ValueAnimator? = null
    private var shatterView: dev.xacnio.kciktv.shared.ui.widget.ShatterView? = null
    private var shatterAnimator: android.animation.ValueAnimator? = null
    private var holdAnimator: android.animation.ValueAnimator? = null
    private val spinHandler = Handler(Looper.getMainLooper())
    private val rouletteOverlay by lazy { RouletteOverlay(activity, spinHandler) }

    // Cached across dialog opens for this activity session; the catalog is large and rarely changes.
    private var cachedCollectibles: List<dev.xacnio.kciktv.shared.data.model.CollectibleCard>? = null

    private var availabilityJob: kotlinx.coroutines.Job? = null
    private var ctaBytes: ByteArray? = null
    private var isCtaShowing = false

    private fun dpToPx(dp: Int): Int = (dp * activity.resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------
    // Header button state
    // ------------------------------------------------------------------

    /**
     * Checks whether today's reward is sitting there unclaimed and, if so, turns the header
     * button into the animated chest. Safe to call often; in-flight checks are replaced.
     */
    fun refreshRewardAvailability() {
        val token = prefs.authToken
        if (token == null) {
            setRewardAvailable(false)
            return
        }

        availabilityJob?.cancel()
        availabilityJob = lifecycleScope.launch {
            repository.getChallenges(token).onSuccess { challenges ->
                val challenge = challenges.firstOrNull { it.recurrence == "daily" } ?: challenges.firstOrNull()
                val available = challenge != null && challenge.isUnlocked && !challenge.isClaimed
                activity.runOnUiThread { setRewardAvailable(available) }
            }.onFailure {
                Log.e(TAG, "Failed to check daily reward availability", it)
            }
        }
    }

    private fun setRewardAvailable(available: Boolean) {
        if (isCtaShowing == available) return
        val button = binding.mobileDailyRewardBtn

        if (available) {
            val drawable = buildCtaDrawable()
            if (drawable == null) {
                // Fall back to the plain icon rather than showing nothing.
                setRewardAvailable(false)
                return
            }
            // The chest art is its own artwork, so give it the whole button.
            button.setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
            button.clearColorFilter()
            button.setImageDrawable(drawable)
            // Start only once the ImageView is the drawable's callback, or it renders blank.
            drawable.start()
            isCtaShowing = true
        } else {
            (button.drawable as? com.github.penfeizhou.animation.apng.APNGDrawable)?.stop()
            button.setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10))
            button.setImageResource(R.drawable.ic_daily_reward)
            isCtaShowing = false
        }
    }

    private fun buildCtaDrawable(): com.github.penfeizhou.animation.apng.APNGDrawable? {
        return try {
            val bytes = ctaBytes ?: activity.assets.open(CTA_ASSET).use { it.readBytes() }
                .also { ctaBytes = it }
            val loader = object : com.github.penfeizhou.animation.loader.ByteBufferLoader() {
                override fun getByteBuffer(): java.nio.ByteBuffer = java.nio.ByteBuffer.wrap(bytes)
            }
            com.github.penfeizhou.animation.apng.APNGDrawable(loader).apply { setAutoPlay(false) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build reward CTA drawable", e)
            null
        }
    }

    fun showDailyRewardDialog() {
        if (currentDialog?.isShowing == true) return
        val token = prefs.authToken ?: return

        val previousNavigationBarColor = activity.window.navigationBarColor
        activity.window.navigationBarColor = Color.BLACK

        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_daily_reward, null)
        // Plain Dialog, not MaterialAlertDialogBuilder — its content wrapper won't reliably
        // stretch to fill the screen, which a full-bleed scrim needs.
        val dialog = android.app.Dialog(activity, R.style.Theme_KcikTV_Dialog)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(dialogView)
        dialog.window?.apply {
            navigationBarColor = Color.BLACK
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.CENTER)
            // The layout paints its own scrim, so the window must not dim on top of it and must
            // extend under the system bars for that scrim to reach the screen edges.
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(this, false)
        }
        currentDialog = dialog

        // Scrim goes edge to edge; the content still has to clear the status and nav bars.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(dialogView) { view, windowInsets ->
            val bars = windowInsets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                bars.top + dpToPx(20),
                view.paddingRight,
                bars.bottom + dpToPx(20)
            )
            windowInsets
        }

        dialogView.findViewById<ImageButton>(R.id.btnCloseDailyReward).setOnClickListener {
            dialog.dismiss()
        }
        dialog.setOnDismissListener {
            activity.window.navigationBarColor = previousNavigationBarColor
            spinRunnable?.let { spinHandler.removeCallbacks(it) }
            spinRunnable = null
            cardShineRunnable?.let { spinHandler.removeCallbacks(it) }
            cardShineRunnable = null
            rarityPopup?.dismiss()
            rarityPopup = null
            zoomDialog?.dismiss()
            zoomDialog = null
            rouletteOverlay.dismiss()
            marqueeAnimator?.cancel()
            marqueeAnimator = null
            currentDialog = null
        }

        dialog.show()
        loadChallenge(dialogView, token)
    }

    private fun loadChallenge(dialogView: View, token: String) {
        lifecycleScope.launch {
            val result = repository.getChallenges(token)
            if (currentDialog == null) return@launch // Dialog was dismissed while loading.

            result.onSuccess { challenges ->
                val challenge = challenges.firstOrNull { it.recurrence == "daily" } ?: challenges.firstOrNull()
                if (challenge == null) {
                    showError(dialogView)
                } else {
                    populateChallenge(dialogView, challenge)
                }
            }.onFailure {
                Log.e(TAG, "Failed to load daily challenge", it)
                showError(dialogView)
            }
        }
    }

    /** Hides every mutually-exclusive card-frame view; each state function then shows only what it needs. */
    private fun resetCardStateViews(dialogView: View) {
        dialogView.findViewById<View>(R.id.rewardErrorText).visibility = View.GONE
        dialogView.findViewById<View>(R.id.rewardSpinSquare).visibility = View.GONE
        dialogView.findViewById<View>(R.id.rewardCardImage).visibility = View.INVISIBLE
        dialogView.findViewById<View>(R.id.rewardResultOverlay).visibility = View.GONE
        dialogView.findViewById<View>(R.id.rewardLockedOverlay).visibility = View.GONE
        dialogView.findViewById<View>(R.id.rewardLockedCardsViewport).visibility = View.GONE
        dialogView.findViewById<View>(R.id.rewardCardShineClip).visibility = View.INVISIBLE
        marqueeAnimator?.cancel()
        marqueeAnimator = null
        cardShineRunnable?.let { spinHandler.removeCallbacks(it) }
        cardShineRunnable = null
    }

    private fun showError(dialogView: View) {
        dialogView.findViewById<View>(R.id.rewardLoadingProgress).visibility = View.GONE
        dialogView.findViewById<View>(R.id.rewardErrorText).visibility = View.VISIBLE
        dialogView.findViewById<Button>(R.id.btnClaimDailyReward).visibility = View.GONE
        dialogView.findViewById<View>(R.id.rewardResetsAtText).visibility = View.GONE
        dialogView.findViewById<View>(R.id.rewardRarityChipsRow).visibility = View.GONE
    }

    private fun populateChallenge(dialogView: View, challenge: DailyChallenge) {
        dialogView.findViewById<View>(R.id.rewardLoadingProgress).visibility = View.GONE

        setupDescription(dialogView)
        buildRarityChips(dialogView, challenge.dropTable)
        setupResetsAt(dialogView, challenge.window.endsAt)

        when {
            challenge.isClaimed -> {
                if (prefs.lastAnimatedDailyRewardId == challenge.id) {
                    populateResolvedCard(dialogView, challenge, animate = false)
                } else {
                    runSpinThenReveal(dialogView, challenge)
                }
            }
            challenge.isUnlocked -> showReadyToClaimState(dialogView, challenge)
            else -> showLockedState(dialogView, challenge)
        }
    }

    // ------------------------------------------------------------------
    // Spin reveal
    // ------------------------------------------------------------------

    private fun runSpinThenReveal(dialogView: View, challenge: DailyChallenge) {
        resetCardStateViews(dialogView)
        val spinSquare = dialogView.findViewById<View>(R.id.rewardSpinSquare)
        spinSquare.alpha = 1f
        spinSquare.visibility = View.VISIBLE

        val winnerRarity = challenge.winner?.rarity?.lowercase(Locale.US) ?: "common"
        val rng = kotlin.random.Random.Default
        val sequence = MutableList(SPIN_DELAYS_MS.size) { RARITY_ORDER[rng.nextInt(RARITY_ORDER.size)] }
        sequence[sequence.lastIndex] = winnerRarity

        fun tick(index: Int) {
            applyRarityBackground(spinSquare, sequence[index])
            if (index < sequence.lastIndex) {
                val runnable = Runnable { tick(index + 1) }
                spinRunnable = runnable
                spinHandler.postDelayed(runnable, SPIN_DELAYS_MS[index])
            } else {
                val runnable = Runnable { revealResult(dialogView, challenge) }
                spinRunnable = runnable
                spinHandler.postDelayed(runnable, 150L)
            }
        }
        tick(0)
    }

    private fun revealResult(dialogView: View, challenge: DailyChallenge) {
        spinRunnable = null
        val spinSquare = dialogView.findViewById<View>(R.id.rewardSpinSquare)
        spinSquare.animate().alpha(0f).setDuration(150).withEndAction {
            spinSquare.visibility = View.GONE
            spinSquare.alpha = 1f
        }.start()

        prefs.lastAnimatedDailyRewardId = challenge.id
        populateResolvedCard(dialogView, challenge, animate = true)
    }

    private fun populateResolvedCard(dialogView: View, challenge: DailyChallenge, animate: Boolean) {
        val cardImage = dialogView.findViewById<ImageView>(R.id.rewardCardImage)
        val resultOverlay = dialogView.findViewById<View>(R.id.rewardResultOverlay)
        val resultTitle = dialogView.findViewById<TextView>(R.id.rewardResultTitleText)
        val consolationRow = dialogView.findViewById<View>(R.id.rewardConsolationRow)
        val consolationText = dialogView.findViewById<TextView>(R.id.rewardConsolationText)
        val consolationBadge = dialogView.findViewById<ImageView>(R.id.rewardConsolationBadge)
        val duplicateNote = dialogView.findViewById<View>(R.id.rewardDuplicateNoteText)

        challenge.winner?.cardUrl?.let { Glide.with(activity).load(it).into(cardImage) }
        cardImage.visibility = View.VISIBLE
        cardImage.setOnClickListener { showEnlargedCard(challenge) }
        cardImage.setOnLongClickListener {
            simulateRoulette(dialogView, challenge)
            true
        }

        val consolation = challenge.consolation
        if (consolation != null) {
            resultTitle.text = activity.getString(R.string.daily_reward_already_have)
            consolationRow.visibility = View.VISIBLE

            // Level number is baked into the badge image next to this text, so only show watch-time.
            val minutes = consolation.watchTimeMinutes
            consolationText.text = if (minutes >= 60) {
                activity.getString(R.string.daily_reward_consolation_hours_minutes, minutes / 60, minutes % 60)
            } else {
                activity.getString(R.string.daily_reward_consolation_minutes, minutes)
            }

            val badgeUrl = consolation.nextLevelBadge?.imageUrl
            if (!badgeUrl.isNullOrEmpty()) {
                consolationBadge.visibility = View.VISIBLE
                Glide.with(activity).load(badgeUrl).into(consolationBadge)
            } else {
                consolationBadge.visibility = View.GONE
            }
            duplicateNote.visibility = View.VISIBLE
        } else {
            resultTitle.text = activity.getString(R.string.daily_reward_congrats)
            consolationRow.visibility = View.GONE
            duplicateNote.visibility = View.GONE
        }

        if (animate) {
            // The winner flips into place, then the light sweep starts riding across it.
            cardImage.alpha = 0f
            cardImage.scaleX = 0.8f
            cardImage.scaleY = 0.8f
            cardImage.rotationY = -90f
            cardImage.cameraDistance = 12000f * activity.resources.displayMetrics.density
            cardImage.animate()
                .alpha(1f).scaleX(1f).scaleY(1f).rotationY(0f)
                .setDuration(460)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .withEndAction { startCardShine(dialogView) }
                .start()

            resultOverlay.alpha = 0f
            resultOverlay.visibility = View.VISIBLE
            resultOverlay.animate().alpha(1f).setStartDelay(300).setDuration(220).start()
        } else {
            cardImage.alpha = 1f
            cardImage.scaleX = 1f
            cardImage.scaleY = 1f
            cardImage.rotationY = 0f
            resultOverlay.alpha = 1f
            resultOverlay.visibility = View.VISIBLE
            startCardShine(dialogView)
        }

        dialogView.findViewById<Button>(R.id.btnClaimDailyReward).apply {
            text = activity.getString(R.string.daily_reward_claimed_button)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#3A3A3A"))
            setTextColor(Color.parseColor("#AAAAAA"))
            isEnabled = false
            setOnClickListener(null)
        }
    }

    /** Starts (or restarts) the looping light sweep across the revealed card. */
    private fun startCardShine(dialogView: View, attempt: Int = 0) {
        cardShineRunnable?.let { spinHandler.removeCallbacks(it) }
        cardShineRunnable = null

        val image = dialogView.findViewById<ImageView>(R.id.rewardCardImage) ?: return
        val clip = dialogView.findViewById<View>(R.id.rewardCardShineClip) ?: return
        val shine = dialogView.findViewById<View>(R.id.rewardCardShine) ?: return

        val availWidth = image.width - image.paddingLeft - image.paddingRight
        val availHeight = image.height - image.paddingTop - image.paddingBottom
        if (availWidth <= 0 || availHeight <= 0) return

        // Measure the real drawable rather than assuming a fixed card size — a card off the usual
        // aspect would get a clip box bigger than its art, letting the light spill past its edges.
        val drawable = image.drawable
        if (drawable == null || drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) {
            // Glide may still be decoding; the art can't be measured until it lands.
            if (attempt < CARD_SHINE_MAX_ATTEMPTS) {
                val retry = Runnable { startCardShine(dialogView, attempt + 1) }
                cardShineRunnable = retry
                spinHandler.postDelayed(retry, CARD_SHINE_RETRY_MS)
            }
            return
        }

        // fitCenter: the art scales to whichever axis runs out first, and centres in the leftover.
        val scale = minOf(
            availWidth.toFloat() / drawable.intrinsicWidth,
            availHeight.toFloat() / drawable.intrinsicHeight
        )
        val drawnWidth = (drawable.intrinsicWidth * scale).toInt()
        val drawnHeight = (drawable.intrinsicHeight * scale).toInt()
        if (drawnWidth <= 0 || drawnHeight <= 0) return

        clip.layoutParams = clip.layoutParams.apply {
            width = drawnWidth
            height = drawnHeight
        }
        clip.requestLayout()
        clip.visibility = View.VISIBLE

        sweepCardShine(shine, drawnWidth, drawnHeight)
    }

    private fun sweepCardShine(shine: View, clipWidth: Int, clipHeight: Int) {
        val travel = shineTravel(clipWidth, clipHeight)
        if (travel <= 0f) return

        shine.translationX = -travel
        shine.visibility = View.VISIBLE
        shine.animate()
            .translationX(travel)
            .setDuration(850)
            .setInterpolator(android.view.animation.LinearInterpolator())
            .withEndAction {
                shine.visibility = View.INVISIBLE
                val runnable = Runnable { sweepCardShine(shine, clipWidth, clipHeight) }
                cardShineRunnable = runnable
                spinHandler.postDelayed(runnable, 2200L)
            }
            .start()
    }

    /**
     * How far the light travels each way: exactly corner to corner (half-width + half-height·tan
     * of the rotation), no further. The bar's own width isn't added — its edges are the
     * transparent ends of the gradient, so including them just leaves the light sitting off the card.
     */
    private fun shineTravel(clipWidth: Int, clipHeight: Int): Float {
        val radians = Math.toRadians(SHINE_ROTATION_DEG.toDouble())
        val tiltReach = clipHeight / 2f * kotlin.math.tan(radians).toFloat()
        return clipWidth / 2f + tiltReach
    }

    // ------------------------------------------------------------------
    // Tap-to-zoom card reveal
    // ------------------------------------------------------------------

    private fun showEnlargedCard(challenge: DailyChallenge) {
        showCardZoom(challenge.winner?.cardUrl ?: return)
    }

    /** Opens a card full-screen with the flip-in + looping shine effect. Shared with the Collectibles page. */
    fun showCardZoom(cardUrl: String) {
        if (zoomDialog?.isShowing == true) return

        val zoomView = LayoutInflater.from(activity).inflate(R.layout.dialog_daily_reward_card_zoom, null)
        // Plain Dialog, not MaterialAlertDialogBuilder — its content wrapper won't reliably
        // stretch to fill the screen.
        val dialog = android.app.Dialog(activity, R.style.Theme_KcikTV_Dialog)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(zoomView)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.CENTER)
        }
        zoomDialog = dialog
        dialog.setOnDismissListener {
            shineRunnable?.let { spinHandler.removeCallbacks(it) }
            shineRunnable = null
            floatAnimator?.cancel()
            floatAnimator = null
            shatterAnimator?.cancel()
            shatterAnimator = null
            holdAnimator?.cancel()
            holdAnimator = null
            shatterView = null // the whole dialog view goes with the window
            zoomDialog = null
        }
        dialog.show()

        val cardFrame = zoomView.findViewById<View>(R.id.zoomCardFrame)
        val cardImage = zoomView.findViewById<ImageView>(R.id.zoomCardImage)
        val shine = zoomView.findViewById<View>(R.id.zoomShineView)
        Glide.with(activity).load(cardUrl).into(cardImage)

        fun close() {
            floatAnimator?.cancel()
            floatAnimator = null
            cardFrame.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(160).withEndAction {
                dialog.dismiss()
            }.start()
        }
        zoomView.findViewById<View>(R.id.zoomScrimRoot).setOnClickListener { close() }

        // Entrance: flips open while scaling/fading in, settling at a slight 3D tilt. Once it
        // lands the light sweep and idle drift start, and it becomes draggable.
        cardFrame.alpha = 0f
        cardFrame.scaleX = 0.6f
        cardFrame.scaleY = 0.6f
        cardFrame.rotationY = -90f
        cardFrame.cameraDistance = CARD_CAMERA_DISTANCE * activity.resources.displayMetrics.density
        cardFrame.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .rotationY(CARD_REST_ROTATION_Y)
            .rotationX(CARD_REST_ROTATION_X)
            .setDuration(460)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                runShineSweep(shine, cardFrame)
                startCardFloat(cardFrame)
                attachCardGestures(
                    card = cardFrame,
                    onTap = { close() },
                    onHoldStart = { x, y -> startHoldBubble(zoomView, cardFrame, shine, dialog, x, y) },
                    onHoldRelease = { releaseHoldBubble(zoomView) }
                )
            }
            .start()
    }

    /**
     * Slow vertical drift so the card reads as floating. Driven by a sine wave rather than a
     * REVERSE animator between -amplitude/+amplitude — that form starts at one extreme and would
     * snap the card the instant the entrance animation finished; sin(0) picks up at 0 instead.
     */
    private fun startCardFloat(card: View) {
        floatAnimator?.cancel()
        val amplitude = dpToPx(CARD_FLOAT_DP).toFloat()
        floatAnimator = android.animation.ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
            duration = 3200L
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { anim ->
                card.translationY = kotlin.math.sin(anim.animatedValue as Float) * amplitude
            }
            start()
        }
    }

    /**
     * Grows the hold hover out from where the finger landed. Once it has covered the card the
     * hold is complete and the card breaks; letting go before that calls [releaseHoldBubble].
     */
    private fun startHoldBubble(
        zoomView: View,
        card: View,
        shine: View,
        dialog: android.app.Dialog,
        touchX: Float,
        touchY: Float
    ) {
        if (shatterView != null) return
        val bubble = zoomView.findViewById<dev.xacnio.kciktv.shared.ui.widget.HoldBubbleView>(
            R.id.zoomHoldBubble
        ) ?: return

        card.animate().cancel()
        bubble.setCenter(touchX, touchY)
        bubble.radius = 0f
        bubble.visibility = View.VISIBLE

        holdAnimator?.cancel()
        holdAnimator = android.animation.ValueAnimator.ofFloat(0f, bubble.radiusToCover()).apply {
            duration = CARD_HOLD_MS
            interpolator = android.view.animation.AccelerateInterpolator(1.3f)
            addUpdateListener { anim -> bubble.radius = anim.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // onAnimationEnd fires on cancel too, so only a run that actually reached full
                    // coverage may break the card — otherwise letting go would destroy it.
                    if (!cancelled) shatterCard(zoomView, card, shine, bubble, dialog)
                }
            })
            start()
        }
    }

    /** Let go before the hover filled the card: it shrinks away and the card is left alone. */
    private fun releaseHoldBubble(zoomView: View) {
        if (shatterView != null) return // already breaking; too late to call it off
        val bubble = zoomView.findViewById<dev.xacnio.kciktv.shared.ui.widget.HoldBubbleView>(
            R.id.zoomHoldBubble
        ) ?: return

        holdAnimator?.cancel()
        floatAnimator?.resume()

        val from = bubble.radius
        if (from <= 0f) {
            bubble.visibility = View.INVISIBLE
            return
        }
        holdAnimator = android.animation.ValueAnimator.ofFloat(from, 0f).apply {
            duration = CARD_HOLD_RELEASE_MS
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { anim -> bubble.radius = anim.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    bubble.visibility = View.INVISIBLE
                    holdAnimator = null
                }
            })
            start()
        }
    }

    /** The hover covered the card: tear it apart and close. */
    private fun shatterCard(
        zoomView: View,
        card: View,
        shine: View,
        bubble: dev.xacnio.kciktv.shared.ui.widget.HoldBubbleView,
        dialog: android.app.Dialog
    ) {
        floatAnimator?.cancel()
        floatAnimator = null

        // Otherwise these get baked into the snapshot the shards are cut from.
        shineRunnable?.let { spinHandler.removeCallbacks(it) }
        shineRunnable = null
        shine.visibility = View.INVISIBLE
        bubble.visibility = View.INVISIBLE
        bubble.radius = 0f

        val root = zoomView.findViewById<android.widget.FrameLayout>(R.id.zoomScrimRoot)
        val snapshot = snapshotView(card)
        if (root == null || snapshot == null) {
            dialog.dismiss()
            return
        }

        VibrationUtils.lightTick(activity)

        val shatter = dev.xacnio.kciktv.shared.ui.widget.ShatterView(activity).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                card.width, card.height, Gravity.CENTER
            )
            // Inherit the card's live 3D transform — draw() alone would render it flat.
            rotationX = card.rotationX
            rotationY = card.rotationY
            translationY = card.translationY
            cameraDistance = card.cameraDistance
            setSource(snapshot)
        }
        root.addView(shatter)
        shatterView = shatter
        card.visibility = View.INVISIBLE

        shatterAnimator?.cancel()
        shatterAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CARD_SHATTER_MS
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { anim -> shatter.progress = anim.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    dialog.dismiss()
                }
            })
            start()
        }
    }

    /** Renders a view exactly as drawn, for the shatter to break into pieces. */
    private fun snapshotView(view: View): android.graphics.Bitmap? {
        if (view.width <= 0 || view.height <= 0) return null
        return try {
            android.graphics.Bitmap
                .createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                .also { view.draw(android.graphics.Canvas(it)) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to snapshot card for shatter", e)
            null
        }
    }

    /** One listener for all three card gestures: drag to turn it in 3D, tap to close, hold to shatter. */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachCardGestures(
        card: View,
        onTap: () -> Unit,
        onHoldStart: (Float, Float) -> Unit,
        onHoldRelease: () -> Unit
    ) {
        val touchSlop = android.view.ViewConfiguration.get(activity).scaledTouchSlop
        val degreesPerPx = CARD_DRAG_DEGREES_PER_DP / activity.resources.displayMetrics.density

        var downX = 0f
        var downY = 0f
        var startRotationY = 0f
        var startRotationX = 0f
        var dragging = false
        var holding = false
        var holdRunnable: Runnable? = null

        fun cancelPendingHold() {
            holdRunnable?.let { spinHandler.removeCallbacks(it) }
            holdRunnable = null
        }

        card.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startRotationY = view.rotationY
                    startRotationX = view.rotationX
                    dragging = false
                    holding = false
                    view.animate().cancel()
                    floatAnimator?.pause() // don't bob around under the finger

                    // Delay avoids flashing the bubble on a tap. Local coords since the bubble lives inside the card.
                    val localX = event.x
                    val localY = event.y
                    val runnable = Runnable {
                        holdRunnable = null
                        holding = true
                        onHoldStart(localX, localY)
                    }
                    holdRunnable = runnable
                    spinHandler.postDelayed(runnable, CARD_HOLD_DELAY_MS)
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    // Once the hover is filling, the gesture is committed — no turning it midway.
                    if (holding) return@setOnTouchListener true
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && kotlin.math.hypot(dx, dy) > touchSlop) {
                        dragging = true
                        cancelPendingHold() // moving means they're turning it, not holding it
                    }
                    if (dragging) {
                        // Clamped so the card never turns far enough to show its back.
                        view.rotationY = (startRotationY + dx * degreesPerPx)
                            .coerceIn(-CARD_MAX_YAW, CARD_MAX_YAW)
                        view.rotationX = (startRotationX - dy * degreesPerPx)
                            .coerceIn(-CARD_MAX_PITCH, CARD_MAX_PITCH)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    cancelPendingHold()
                    when {
                        holding -> {
                            holding = false
                            onHoldRelease()
                        }
                        !dragging -> onTap()
                        else -> {
                            view.animate()
                                .rotationY(CARD_REST_ROTATION_Y)
                                .rotationX(CARD_REST_ROTATION_X)
                                .setDuration(620)
                                .setInterpolator(OvershootInterpolator(1.1f))
                                .start()
                            floatAnimator?.resume()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    // Sweeps once, then reschedules itself after a pause; cancelled via shineRunnable on dismiss.
    private fun runShineSweep(shine: View, cardFrame: View) {
        val distance = (cardFrame.width + shine.width).toFloat()
        if (distance <= 0f) return

        shine.translationX = -distance
        shine.visibility = View.VISIBLE
        shine.animate()
            .translationX(distance)
            .setDuration(900)
            .setInterpolator(android.view.animation.LinearInterpolator())
            .withEndAction {
                shine.visibility = View.INVISIBLE
                val runnable = Runnable { runShineSweep(shine, cardFrame) }
                shineRunnable = runnable
                spinHandler.postDelayed(runnable, 1100L)
            }
            .start()
    }

    private fun showLockedState(dialogView: View, challenge: DailyChallenge) {
        resetCardStateViews(dialogView)
        dialogView.findViewById<View>(R.id.rewardLockedOverlay).visibility = View.VISIBLE

        val threshold = challenge.condition.threshold.coerceAtLeast(1)
        val progress = challenge.condition.progress.coerceIn(0, threshold)
        val remaining = threshold - progress

        dialogView.findViewById<TextView>(R.id.rewardLockedProgressText).text = if (remaining >= 60) {
            activity.getString(R.string.daily_reward_locked_progress_hours_minutes, remaining / 60, remaining % 60)
        } else {
            activity.getString(R.string.daily_reward_locked_progress_minutes, remaining)
        }
        dialogView.findViewById<ProgressBar>(R.id.rewardLockedProgressBar).progress =
            (progress * 100 / threshold).toInt()

        dialogView.findViewById<Button>(R.id.btnClaimDailyReward).apply {
            text = activity.getString(R.string.daily_reward_locked_button)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#3A3A3A"))
            setTextColor(Color.parseColor("#AAAAAA"))
            isEnabled = false
            setOnClickListener(null)
        }

        showCardsMarquee(dialogView, aboveProgressBar = true)
    }

    // ------------------------------------------------------------------
    // Collectible cards marquee — shows what's up for grabs before you've claimed
    // ------------------------------------------------------------------

    /**
     * @param aboveProgressBar true in the locked state, where the cards ride along the countdown's
     *        progress bar. When there's no countdown the cards just fill the frame instead.
     */
    private fun showCardsMarquee(dialogView: View, aboveProgressBar: Boolean) {
        // Set up front so the async load below can check it wasn't superseded by a state change.
        dialogView.findViewById<View>(R.id.rewardLockedCardsViewport).visibility = View.VISIBLE

        val cached = cachedCollectibles
        if (cached != null) {
            buildCardsMarquee(dialogView, cached, aboveProgressBar)
            return
        }

        val token = prefs.authToken ?: return
        lifecycleScope.launch {
            val result = repository.getCollectibles(token)
            if (currentDialog == null) return@launch // Dialog was dismissed while loading.
            result.onSuccess { cards ->
                cachedCollectibles = cards
                // Only draw if the marquee is still what should be on screen after this slow fetch.
                if (dialogView.findViewById<View>(R.id.rewardLockedCardsViewport).visibility == View.VISIBLE) {
                    buildCardsMarquee(dialogView, cards, aboveProgressBar)
                }
            }.onFailure {
                Log.e(TAG, "Failed to load collectibles", it)
            }
        }
    }

    private fun buildCardsMarquee(
        dialogView: View,
        cards: List<dev.xacnio.kciktv.shared.data.model.CollectibleCard>,
        aboveProgressBar: Boolean
    ) {
        // Reshuffled on every build, and capped since every entry is a live ImageView the whole
        // time — the full catalog is hundreds of cards.
        val urls = cards.mapNotNull { it.cardUrl }
            .filter { it.isNotEmpty() }
            .shuffled()
            .take(MARQUEE_CARD_COUNT)
        if (urls.isEmpty()) return

        val viewport = dialogView.findViewById<View>(R.id.rewardLockedCardsViewport)
        val strip = dialogView.findViewById<LinearLayout>(R.id.rewardLockedCardsStrip)
        val progressBar = dialogView.findViewById<View>(R.id.rewardLockedProgressBar)
        val lockedOverlay = dialogView.findViewById<View>(R.id.rewardLockedOverlay)
        viewport.visibility = View.VISIBLE

        // Built inside post() because the card size is derived from laid-out geometry.
        viewport.post {
            marqueeAnimator?.cancel()
            if (viewport.height <= 0) return@post
            strip.removeAllViews()

            val topInset = dpToPx(10) // keep clear of the frame's rounded corners
            val gapAboveLine = dpToPx(6)

            // With a countdown, cards ride the progress bar's line. Without one (it's GONE and
            // never laid out) they just fill the frame. Capped either way, since the frame is
            // now full-screen tall.
            val maxItemHeight = dpToPx(MARQUEE_MAX_ITEM_HEIGHT_DP)
            val itemHeight: Int
            val desiredCenterY: Float
            if (aboveProgressBar) {
                val lineY = lockedOverlay.top + progressBar.top + progressBar.height / 2f
                itemHeight = (lineY - gapAboveLine - topInset).toInt()
                    .coerceIn(dpToPx(60), maxItemHeight)
                desiredCenterY = lineY - gapAboveLine - itemHeight / 2f
            } else {
                itemHeight = (viewport.height - topInset * 2).coerceIn(dpToPx(60), maxItemHeight)
                desiredCenterY = viewport.height / 2f
            }
            val itemWidth = (itemHeight * CARD_ASPECT).toInt()
            val itemMargin = dpToPx(5)
            val pitch = itemWidth + itemMargin * 2

            // Two back-to-back copies so the loop can jump seamlessly from the first to the second.
            repeat(2) {
                urls.forEach { url ->
                    val itemView = ImageView(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(itemWidth, itemHeight).apply {
                            marginStart = itemMargin
                            marginEnd = itemMargin
                        }
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                    Glide.with(activity).load(dev.xacnio.kciktv.shared.util.CardImageUrl.sized(url)).into(itemView)
                    strip.addView(itemView)
                }
            }

            val singlePassWidth = urls.size * pitch
            if (singlePassWidth <= 0) return@post
            // A wrap_content child of a FrameLayout is measured AT_MOST, so without pinning its
            // true width the strip gets clamped to the viewport and clips away most cards.
            strip.layoutParams = strip.layoutParams.apply { width = singlePassWidth * 2 }
            strip.requestLayout()

            // The strip fills the viewport and centres its children, so shift from that baseline.
            strip.translationY = desiredCenterY - viewport.height / 2f
            strip.translationX = 0f

            val pxPerSecond = dpToPx(70)
            val durationMs = (singlePassWidth.toFloat() / pxPerSecond * 1000).toLong().coerceAtLeast(1000L)

            val animator = android.animation.ValueAnimator.ofFloat(0f, -singlePassWidth.toFloat()).apply {
                duration = durationMs
                repeatCount = android.animation.ValueAnimator.INFINITE
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener { anim -> strip.translationX = anim.animatedValue as Float }
            }
            marqueeAnimator = animator
            animator.start()
        }
    }

    // ------------------------------------------------------------------
    // Claim & roulette reel
    // ------------------------------------------------------------------

    private fun showReadyToClaimState(dialogView: View, challenge: DailyChallenge) {
        resetCardStateViews(dialogView)

        dialogView.findViewById<Button>(R.id.btnClaimDailyReward).apply {
            text = activity.getString(R.string.daily_reward_claim_button)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#53FC18"))
            setTextColor(Color.BLACK)
            isEnabled = true
            setOnClickListener { performClaim(dialogView, challenge) }
        }

        // No countdown in this state, so the cards get the whole frame to parade through.
        showCardsMarquee(dialogView, aboveProgressBar = false)
    }

    private fun performClaim(dialogView: View, challenge: DailyChallenge) {
        val token = prefs.authToken ?: return
        val claimButton = dialogView.findViewById<Button>(R.id.btnClaimDailyReward)
        claimButton.isEnabled = false
        claimButton.text = activity.getString(R.string.daily_reward_claiming_button)

        resetCardStateViews(dialogView)
        val loadingProgress = dialogView.findViewById<View>(R.id.rewardLoadingProgress)
        loadingProgress.visibility = View.VISIBLE

        lifecycleScope.launch {
            val result = repository.claimChallenge(challenge.id, token)
            if (currentDialog == null) return@launch // Dialog was dismissed while claiming.
            // The spinner stays up until runRouletteReel has warmed the image cache and the overlay is ready to spin.

            result.onSuccess { claimData ->
                // Claimed — the header button goes back to its plain icon.
                setRewardAvailable(false)
                // Keep the reel so the long-press simulation can replay it with real shield art.
                if (claimData.roulette.isNotEmpty()) {
                    prefs.lastRouletteReelJson = try {
                        com.google.gson.Gson().toJson(claimData.roulette)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to store roulette reel", e); null
                    }
                }
                if (claimData.roulette.isEmpty()) {
                    // No reel to animate (shouldn't normally happen) — go straight to the result.
                    loadingProgress.visibility = View.GONE
                    prefs.lastAnimatedDailyRewardId = challenge.id
                    populateResolvedCard(
                        dialogView,
                        challenge.copy(winner = claimData.winner, consolation = claimData.consolation),
                        animate = true
                    )
                } else {
                    runRouletteReel(dialogView, challenge, claimData)
                }
            }.onFailure {
                Log.e(TAG, "Failed to claim daily challenge", it)
                loadingProgress.visibility = View.GONE
                showReadyToClaimState(dialogView, challenge)
            }
        }
    }

    /**
     * Long-pressing an already-resolved card replays the reel from the last real claim (kept in
     * prefs). The claim endpoint rejects an already-claimed challenge, and the reel's shield art
     * exists nowhere else in the API, so this is the only way to see the spin again.
     */
    private fun simulateRoulette(dialogView: View, challenge: DailyChallenge) {
        val winner = challenge.winner
        val winnerId = winner?.id
        val stored = storedRouletteReel()
        if (winner == null || winnerId == null || stored == null || stored.none { it.id == winnerId }) {
            Log.w(TAG, "Nothing to simulate the roulette with")
            android.widget.Toast.makeText(
                activity,
                R.string.daily_reward_simulate_unavailable,
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        val claimData = dev.xacnio.kciktv.shared.data.model.ClaimChallengeData(
            challengeId = challenge.id,
            consolation = challenge.consolation,
            roulette = stored,
            winner = winner
        )
        resetCardStateViews(dialogView)
        runRouletteReel(dialogView, challenge, claimData)
    }

    private fun storedRouletteReel(): List<dev.xacnio.kciktv.shared.data.model.RouletteItem>? {
        val json = prefs.lastRouletteReelJson ?: return null
        return try {
            com.google.gson.Gson()
                .fromJson(json, Array<dev.xacnio.kciktv.shared.data.model.RouletteItem>::class.java)
                ?.toList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read stored roulette reel", e)
            null
        }
    }

    /**
     * Hands the reel to the full-screen overlay, which spins over a dark scrim and flies the
     * winner into this modal's card slot — the reveal here doesn't re-animate.
     */
    private fun runRouletteReel(
        dialogView: View,
        challenge: DailyChallenge,
        claimData: dev.xacnio.kciktv.shared.data.model.ClaimChallengeData
    ) {
        val cardImage = dialogView.findViewById<View>(R.id.rewardCardImage)
        val loadingProgress = dialogView.findViewById<View>(R.id.rewardLoadingProgress)
        val resolved = challenge.copy(
            winner = claimData.winner,
            consolation = claimData.consolation
        )

        // Pull every reel image down first, or cards pop in blank as they scroll into view.
        loadingProgress.visibility = View.VISIBLE
        rouletteOverlay.preloadReel(claimData.roulette, claimData.winner) {
            if (currentDialog == null) return@preloadReel // Dismissed while images were loading.
            loadingProgress.visibility = View.GONE

            rouletteOverlay.show(
                roulette = claimData.roulette,
                winner = claimData.winner,
                targetView = cardImage
            ) {
                // The modal is already resolved underneath; the flyer just lands on top of it.
            }

            // Settle the modal now, after the overlay's window is up so it isn't glimpsed. The
            // flyer targets this card slot, and measuring it before the result text is laid out
            // (while the slot's weight=1 still claims the whole frame) would size it far too large.
            prefs.lastAnimatedDailyRewardId = challenge.id
            populateResolvedCard(dialogView, resolved, animate = false)
        }
    }

    // ------------------------------------------------------------------
    // Description link, rarity chips & popup
    // ------------------------------------------------------------------

    private fun setupDescription(dialogView: View) {
        val descText = dialogView.findViewById<TextView>(R.id.rewardDescriptionText)
        val base = activity.getString(R.string.daily_reward_description)
        val link = activity.getString(R.string.daily_reward_view_rewards_link)
        val full = "$base $link"
        val linkStart = full.length - link.length

        val spannable = SpannableString(full)
        spannable.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                // The full catalog lives on its own page; the rarity odds stay on the chips row.
                currentDialog?.dismiss()
                activity.showCollectiblesScreen()
            }

            override fun updateDrawState(ds: TextPaint) {
                super.updateDrawState(ds)
                ds.isUnderlineText = true
                ds.color = Color.WHITE
            }
        }, linkStart, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        descText.text = spannable
        descText.movementMethod = LinkMovementMethod.getInstance()
        descText.highlightColor = Color.TRANSPARENT
    }

    private fun buildRarityChips(dialogView: View, dropTable: List<ChallengeDropTableEntry>) {
        val row = dialogView.findViewById<LinearLayout>(R.id.rewardRarityChipsRow)
        row.visibility = View.VISIBLE
        row.removeAllViews()
        val size = dpToPx(12)
        val margin = dpToPx(4)
        RARITY_ORDER.forEach { rarity ->
            val chip = View(activity)
            chip.layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = margin
                marginEnd = margin
            }
            applyRarityBackground(chip, rarity)
            row.addView(chip)
        }
        row.setOnClickListener { showRarityPopup(row, dropTable) }
    }

    private fun showRarityPopup(anchor: View, dropTable: List<ChallengeDropTableEntry>) {
        rarityPopup?.dismiss()

        val popupView = LayoutInflater.from(activity).inflate(R.layout.popup_reward_rarity, null)
        val container = popupView.findViewById<LinearLayout>(R.id.rarityRowsContainer)
        val dropByRarity = dropTable.associateBy { it.rarity.lowercase(Locale.US) }

        RARITY_ORDER.forEach { rarity ->
            val entry = dropByRarity[rarity] ?: return@forEach
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dpToPx(4)
                    bottomMargin = dpToPx(4)
                }
            }
            val square = View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(12), dpToPx(12)).apply {
                    marginEnd = dpToPx(8)
                }
            }
            applyRarityBackground(square, rarity)
            val nameText = TextView(activity).apply {
                text = activity.getString(rarityNameRes(rarity))
                setTextColor(Color.WHITE)
                textSize = 12f
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val percentText = TextView(activity).apply {
                text = formatPercent(entry.percent)
                setTextColor(Color.parseColor("#AAAAAA"))
                textSize = 12f
            }
            row.addView(square)
            row.addView(nameText)
            row.addView(percentText)
            container.addView(row)
        }

        val popupWidth = dpToPx(240)
        val popup = PopupWindow(popupView, popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.isOutsideTouchable = true
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.elevation = dpToPx(8).toFloat()
        rarityPopup = popup

        // Open upward — a drop-down would run off the bottom of the full-screen modal. Needs the
        // height measured first since it's WRAP_CONTENT.
        popupView.measure(
            View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val yOffset = -(popupView.measuredHeight + anchor.height + dpToPx(8))
        popup.showAsDropDown(anchor, 0, yOffset)
    }

    // ------------------------------------------------------------------
    // Formatting helpers
    // ------------------------------------------------------------------

    private fun setupResetsAt(dialogView: View, endsAtIso: String?) {
        val text = dialogView.findViewById<TextView>(R.id.rewardResetsAtText)
        val formatted = formatResetTime(endsAtIso)
        text.text = if (formatted != null) activity.getString(R.string.daily_reward_resets_at, formatted) else ""
    }

    private fun formatResetTime(iso: String?): String? {
        if (iso.isNullOrEmpty()) return null
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            parser.timeZone = TimeZone.getTimeZone("UTC")
            val date = parser.parse(iso) ?: return null
            SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(date)
        } catch (e: Exception) {
            null
        }
    }

    private fun formatPercent(value: Double): String {
        if (value <= 0.0) return "0%"
        val trimmed = BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros()
        return "${trimmed.toPlainString()}%"
    }

    private fun applyRarityBackground(view: View, rarity: String) {
        if (rarity == "mythic") {
            view.setBackgroundResource(R.drawable.bg_rarity_mythic)
        } else {
            view.setBackgroundResource(R.drawable.bg_rarity_square)
            (view.background.mutate() as? GradientDrawable)?.setColor(rarityColor(rarity))
        }
    }

    private fun rarityColor(rarity: String): Int = when (rarity.lowercase(Locale.US)) {
        "common" -> Color.parseColor("#9E9E9E")
        "uncommon" -> Color.parseColor("#4CAF50")
        "rare" -> Color.parseColor("#2D9CDB")
        "epic" -> Color.parseColor("#F5455C")
        "legendary" -> Color.parseColor("#FF9800")
        "mythic" -> Color.parseColor("#7B61FF")
        else -> Color.parseColor("#9E9E9E")
    }

    private fun rarityNameRes(rarity: String): Int = when (rarity.lowercase(Locale.US)) {
        "common" -> R.string.rarity_common
        "uncommon" -> R.string.rarity_uncommon
        "rare" -> R.string.rarity_rare
        "epic" -> R.string.rarity_epic
        "legendary" -> R.string.rarity_legendary
        "mythic" -> R.string.rarity_mythic
        else -> R.string.rarity_common
    }
}
