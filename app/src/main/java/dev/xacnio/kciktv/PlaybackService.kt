package dev.xacnio.kciktv

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder

class PlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = intent?.getParcelableExtra<Notification>("notification")
        val notificationId = intent?.getIntExtra("notificationId", 42) ?: 42
        
        if (notification != null) {
            startForeground(notificationId, notification)
        }
        
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(true)
    }
}
