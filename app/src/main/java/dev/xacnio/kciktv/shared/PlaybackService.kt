/**
 * File: PlaybackService.kt
 *
 * Description: Background service handling Playback tasks.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import dev.xacnio.kciktv.mobile.MobilePlayerActivity

class PlaybackService : Service() {
    
    companion object {
        const val ACTION_STOP_PLAYBACK = "dev.xacnio.kciktv.STOP_PLAYBACK"
    }
    
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle STOP action (from notification delete intent)
        if (intent?.action == "STOP") {
            Log.d("PlaybackService", "STOP action received - stopping service")
            notifyStopPlayback()
            stopSelf()
            return START_NOT_STICKY
        }
        
        val notification = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra("notification", Notification::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<Notification>("notification")
        }
        val notificationId = intent?.getIntExtra("notificationId", 42) ?: 42
        
        if (notification != null) {
            startForeground(notificationId, notification)
        }
        
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d("PlaybackService", "Task removed - stopping playback")
        notifyStopPlayback()
        
        // Remove foreground notification
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        
        // Explicitly cancel the notification just in case
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.cancel(43) // Match NOTIFICATION_ID used in MobilePlayerActivity

        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("PlaybackService", "Service destroyed - stopping playback")
        notifyStopPlayback()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
    
    private fun notifyStopPlayback() {
        val intent = Intent(ACTION_STOP_PLAYBACK).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }
}
