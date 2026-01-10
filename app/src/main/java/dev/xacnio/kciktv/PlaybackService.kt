package dev.xacnio.kciktv

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

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
        
        val notification = intent?.getParcelableExtra<Notification>("notification")
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
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("PlaybackService", "Service destroyed - stopping playback")
        notifyStopPlayback()
        stopForeground(true)
    }
    
    private fun notifyStopPlayback() {
        val intent = Intent(ACTION_STOP_PLAYBACK).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }
}
