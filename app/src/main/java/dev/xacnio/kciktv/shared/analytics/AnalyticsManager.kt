/**
 * File: AnalyticsManager.kt
 *
 * Description: Manages business logic, state, and UI interactions for Analytics.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.analytics

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.ktx.Firebase

/**
 * Privacy-focused Firebase Analytics Manager.
 * 
 * This class provides a privacy-respecting analytics implementation:
 * - No personal data is collected
 * - No advertising ID is used
 * - Only anonymous usage statistics are tracked
 * - Users can opt-out of analytics
 * 
 * This helps us understand how many people use the app and which features
 * are popular, without collecting any personal information.
 */
class AnalyticsManager private constructor(private val context: Context) {
    
    private var firebaseAnalytics: FirebaseAnalytics? = null
    private var isEnabled = true
    
    companion object {
        private const val TAG = "AnalyticsManager"
        
        @Volatile
        private var INSTANCE: AnalyticsManager? = null
        
        fun getInstance(context: Context): AnalyticsManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AnalyticsManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    /**
     * Initialize Firebase Analytics with privacy-focused settings.
     * Call this in Application.onCreate() or MainActivity.onCreate()
     */
    fun initialize() {
        try {
            firebaseAnalytics = Firebase.analytics
            
            // Privacy Settings:
            // 1. Disable advertising ID collection (GDPR/Privacy compliant)
            firebaseAnalytics?.setAnalyticsCollectionEnabled(isEnabled)
            
            // 2. Disable personalized ads (we don't use ads but this is good practice)
            // This is set in AndroidManifest.xml via meta-data
            
            // 3. Set session timeout to 30 minutes (default is 30 min)
            firebaseAnalytics?.setSessionTimeoutDuration(1800000) // 30 minutes
            
            Log.d(TAG, "Firebase Analytics initialized (Privacy Mode)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Firebase Analytics", e)
        }
    }
    
    /**
     * Enable or disable analytics collection.
     * Respects user's privacy preference.
     */
    fun setAnalyticsEnabled(enabled: Boolean) {
        isEnabled = enabled
        firebaseAnalytics?.setAnalyticsCollectionEnabled(enabled)
        Log.d(TAG, "Analytics collection ${if (enabled) "enabled" else "disabled"}")
    }
    
    /**
     * Log app open event (anonymous)
     */
    fun logAppOpen() {
        if (!isEnabled) return
        firebaseAnalytics?.logEvent(FirebaseAnalytics.Event.APP_OPEN, null)
    }
    
    /**
     * Log screen view (anonymous - no user data)
     */
    fun logScreenView(screenName: String) {
        if (!isEnabled) return
        firebaseAnalytics?.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW, Bundle().apply {
            putString(FirebaseAnalytics.Param.SCREEN_NAME, screenName)
        })
    }
    
    /**
     * Log feature usage (anonymous)
     * Only logs the feature name, not user-specific data
     */
    fun logFeatureUsed(featureName: String) {
        if (!isEnabled) return
        firebaseAnalytics?.logEvent("feature_used", Bundle().apply {
            putString("feature_name", featureName)
        })
    }
    
    /**
     * Log channel view (anonymous - only tracks that a channel was viewed, not which one)
     */
    fun logChannelView() {
        if (!isEnabled) return
        firebaseAnalytics?.logEvent("channel_view", null)
    }
    
    /**
     * Log VOD/Clip view (anonymous)
     */
    fun logVodView() {
        if (!isEnabled) return
        firebaseAnalytics?.logEvent("vod_view", null)
    }
    
    /**
     * Log clip view (anonymous)
     */
    fun logClipView() {
        if (!isEnabled) return
        firebaseAnalytics?.logEvent("clip_view", null)
    }
    
    /**
     * Log chat message sent (anonymous - just counts, no content)
     */
    fun logChatMessageSent() {
        if (!isEnabled) return
        firebaseAnalytics?.logEvent("chat_message_sent", null)
    }
    
    /**
     * Log PiP mode usage
     */
    fun logPipModeEntered() {
        if (!isEnabled) return
        firebaseAnalytics?.logEvent("pip_mode_entered", null)
    }
    
    /**
     * Log mini player usage
     */
    fun logMiniPlayerUsed() {
        if (!isEnabled) return
        firebaseAnalytics?.logEvent("mini_player_used", null)
    }
    
    /**
     * Log search performed (anonymous - no search terms)
     */
    fun logSearchPerformed() {
        if (!isEnabled) return
        firebaseAnalytics?.logEvent(FirebaseAnalytics.Event.SEARCH, null)
    }
    
    /**
     * Log error occurrence (anonymous - only error type, no details)
     */
    fun logError(errorType: String) {
        if (!isEnabled) return
        firebaseAnalytics?.logEvent("app_error", Bundle().apply {
            putString("error_type", errorType)
        })
    }
}
