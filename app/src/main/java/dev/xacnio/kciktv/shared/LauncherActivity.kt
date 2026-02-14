/**
 * File: LauncherActivity.kt
 *
 * Description: Main Activity class for the Launcher screen.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.util.Log
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import android.view.View
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.tv.PlayerActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import dev.xacnio.kciktv.shared.data.repository.ChannelRepository
import dev.xacnio.kciktv.shared.data.util.PreloadCache
import dev.xacnio.kciktv.shared.data.api.RetrofitClient
import kotlinx.coroutines.async
/**
 * LauncherActivity - Routes users to appropriate UI based on device type.
 * - Android TV → PlayerActivity (Landscape, D-pad optimized)
 * - Mobile Phone/Tablet → MobilePlayerActivity (Portrait, Touch optimized)
 */
class LauncherActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Fix for "Black Screen" / Delay when returning from PiP or Background:
        // If the app is already running (we are not index 0 in the stack),
        // we act as a transparent trampoline to the existing activity.
        if (!isTaskRoot 
            && intent.hasCategory(Intent.CATEGORY_LAUNCHER) 
            && intent.action == Intent.ACTION_MAIN
        ) {
            // Activity is already transparent from Manifest theme, so just redirect.
            super.onCreate(savedInstanceState)
            
            val prefs = AppPreferences(this)
            val uiMode = prefs.uiMode
            val targetActivity = when (uiMode) {
                "tv" -> PlayerActivity::class.java
                "mobile" -> MobilePlayerActivity::class.java
                else -> if (isTvDevice()) PlayerActivity::class.java else MobilePlayerActivity::class.java
            }
            
            val newIntent = Intent(this, targetActivity)
            newIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            
            startActivity(newIntent)
            overridePendingTransition(0, 0)
            finish()
            overridePendingTransition(0, 0)
            return
        }

        // Restore Splash Theme for Cold Start
        setTheme(R.style.Theme_KCIKTV_Splash)
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        
        // Edge-to-edge for perfect black background including status/nav bars
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        setContentView(R.layout.activity_launcher)
        
        splashScreen.setOnExitAnimationListener { it.remove() }

        val k1 = findViewById<View>(R.id.textK1)
        val c = findViewById<View>(R.id.textC)
        val i = findViewById<View>(R.id.textI)
        val k2 = findViewById<View>(R.id.textK2)

        lifecycleScope.launch {
            // Start background tasks immediately in parallel
            val prefs = AppPreferences(this@LauncherActivity)
            val repository = ChannelRepository()
            
            // Task 1: Auth & Following Check
            val authJob = async {
                if (prefs.isLoggedIn) {
                    Log.d("Preload", "Launcher - Starting Auth & Following Check")
                    try {
                        val response = RetrofitClient.authService.getUser("Bearer ${prefs.authToken}")
                        if (response.isSuccessful && response.body() != null) {
                            Log.d("Preload", "Launcher - Auth Valid: ${response.body()?.username}")
                            PreloadCache.isAuthValid = true
                            
                            // If auth is valid, preload following immediately
                            try {
                                val followingResponse = repository.getFollowingLiveStreams(prefs.authToken!!)
                                PreloadCache.followingStreams = followingResponse.getOrNull()?.channels ?: emptyList()
                                Log.d("Preload", "Launcher - Following Count: ${PreloadCache.followingStreams?.size}")
                            } catch (e: Exception) {
                                Log.e("Preload", "Launcher - Following pre-fetch failed", e)
                                PreloadCache.followingStreams = emptyList() // Fallback to empty instead of null to allow progress
                            }
                        } else {
                            Log.w("Preload", "Launcher - Auth invalid (Code: ${response.code()}), clearing prefs")
                            prefs.clearAuth()
                            PreloadCache.isAuthValid = false
                            PreloadCache.followingStreams = emptyList()
                        }
                    } catch (e: Exception) {
                        Log.e("Preload", "Launcher - Auth check failed", e)
                        PreloadCache.isAuthValid = false
                        PreloadCache.followingStreams = emptyList()
                    }
                } else {
                    Log.d("Preload", "Launcher - User not logged in, skipping following pre-fetch")
                    PreloadCache.isAuthValid = false
                    PreloadCache.followingStreams = emptyList()
                }
            }

            // Warm up Home Data
            val dataJob = async {
                try {
                    val catTask = async { repository.getTopCategories() }
                    val featuredTask = async {
                        val langs = prefs.streamLanguages.toList()
                        repository.getFilteredLiveStreams(
                            languages = if (langs.isNotEmpty()) langs else null, 
                            sort = "featured"
                        )
                    }
                    
                    val catResult = catTask.await()
                    PreloadCache.categories = catResult.getOrNull()
                    
                    val featuredResult = featuredTask.await()
                    PreloadCache.featuredStreams = featuredResult.getOrNull()
                } catch (e: Exception) {
                    Log.e("Launcher", "Pre-fetch failed", e)
                }
            }

            // Play animation (Balanced sequence)
            delay(100)
            animateLetter(k1, isReversed = true)
            delay(120)
            animateLetter(c)
            delay(120)
            animateLetter(i)
            delay(120)
            animateLetter(k2)
            
            // Wait for background jobs with a max timeout of 4 seconds to prevent stuck splash
            kotlinx.coroutines.withTimeoutOrNull(4000) {
                authJob.await()
                dataJob.await()
            }
            
            Log.d("Preload", "Launcher - Data Ready: Categories=${PreloadCache.categories != null}, Streams=${PreloadCache.featuredStreams != null}, Following=${PreloadCache.followingStreams != null}")
            
            delay(100) 
            performFinalTransition()
        }
    }

    private fun animateLetter(view: View, isReversed: Boolean = false) {
        // Initial state: hidden and small
        view.alpha = 0f
        view.scaleX = if (isReversed) -0.5f else 0.5f
        view.scaleY = 0.5f
        view.visibility = View.VISIBLE

        // Smooth independent animation
        view.animate()
            .alpha(1f)
            .scaleX(if (isReversed) -1f else 1f)
            .scaleY(1f)
            .setDuration(350)
            .setInterpolator(android.view.animation.OvershootInterpolator())
            .start()
    }

    private suspend fun performFinalTransition() {
        val prefs = AppPreferences(this)
        val uiMode = prefs.uiMode
        val targetActivity = when (uiMode) {
            "tv" -> PlayerActivity::class.java
            "mobile" -> MobilePlayerActivity::class.java
            else -> if (isTvDevice()) PlayerActivity::class.java else MobilePlayerActivity::class.java
        }
        
        startActivity(Intent(this, targetActivity).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        
        overridePendingTransition(0, 0)
        finish()
        overridePendingTransition(0, 0)
    }

    /**
     * Check if running on Android TV
     */
    private fun isTvDevice(): Boolean {
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        return uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }
}
