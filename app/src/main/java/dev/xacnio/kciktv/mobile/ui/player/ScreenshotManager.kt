/**
 * File: ScreenshotManager.kt
 *
 * Description: Manages capturing screenshots of the video content.
 * It attempts to capture the underlying SurfaceView or TextureView, provides visual/haptic feedback,
 * and saves the resulting image to the device's MediaStore gallery.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.player

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R

/**
 * Manages video screenshot capture and saving to gallery.
 */
class ScreenshotManager(private val activity: MobilePlayerActivity) {

    companion object {
        private const val TAG = "ScreenshotManager"
    }

    /**
     * Captures a screenshot of the current video frame and saves it to gallery.
     */
    fun captureVideoScreenshot() {
        val playerView = activity.binding.playerView

        // Vibrate for feedback
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = activity.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                val vibrator = vibratorManager.defaultVibrator
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                val vibrator = activity.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (e: Exception) {}

        // Flash effect
        val flashView = View(activity).apply {
            setBackgroundColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        activity.binding.videoContainer.addView(flashView)
        flashView.animate().alpha(0f).setDuration(400).withEndAction {
            activity.binding.videoContainer.removeView(flashView)
        }.start()

        // Get SurfaceView child from PlayerView
        val surfaceView = findSurfaceView(playerView as ViewGroup)

        if (surfaceView != null && surfaceView.holder.surface.isValid) {
            // Get original video resolution for high quality screenshot
            var targetWidth = surfaceView.width
            var targetHeight = surfaceView.height

            // IVS Player resolution
            activity.ivsPlayer?.quality?.let { quality ->
                if (quality.width > 0 && quality.height > 0) {
                    targetWidth = quality.width
                    targetHeight = quality.height
                }
            }

            Log.d(TAG, "Capturing high-res screenshot: ${targetWidth}x${targetHeight}")
            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.view.PixelCopy.request(surfaceView, bitmap, { result: Int ->
                    if (result == android.view.PixelCopy.SUCCESS) {
                        saveScreenshotToGallery(bitmap)
                    } else {
                        activity.runOnUiThread { Toast.makeText(activity, activity.getString(R.string.screenshot_error, result), Toast.LENGTH_SHORT).show() }
                    }
                }, Handler(Looper.getMainLooper()))
            } else {
                Toast.makeText(activity, activity.getString(R.string.android_version_not_supported), Toast.LENGTH_SHORT).show()
            }
        } else {
            // Try fallback drawing (may not work for DRM or SurfaceView but good for UI)
            try {
                val bitmap = Bitmap.createBitmap(playerView.width, playerView.height, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                playerView.draw(canvas)
                saveScreenshotToGallery(bitmap)
            } catch (e: Exception) {
                Toast.makeText(activity, activity.getString(R.string.screenshot_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun findSurfaceView(viewGroup: ViewGroup): android.view.SurfaceView? {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child is android.view.SurfaceView) return child
            if (child is ViewGroup) {
                val found = findSurfaceView(child)
                if (found != null) return found
            }
        }
        return null
    }

    private fun saveScreenshotToGallery(bitmap: Bitmap) {
        val filename = "KCIKTV_${System.currentTimeMillis()}.jpg"

        try {
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/KCIKTV")
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val resolver = activity.contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

            uri?.let { imageUri ->
                resolver.openOutputStream(imageUri)?.use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(imageUri, contentValues, null, null)
                }

                activity.runOnUiThread { Toast.makeText(activity, activity.getString(R.string.screenshot_saved), Toast.LENGTH_SHORT).show() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save screenshot", e)
            activity.runOnUiThread { Toast.makeText(activity, activity.getString(R.string.save_error, e.message), Toast.LENGTH_SHORT).show() }
        }
    }
}
