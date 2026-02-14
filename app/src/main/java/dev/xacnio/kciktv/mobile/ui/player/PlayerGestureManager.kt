/**
 * File: PlayerGestureManager.kt
 *
 * Description: Manages touch gestures on the player view.
 * It handles single taps for toggling overlays, double taps (disabled/handled elsewhere),
 * and complex swipe gestures for volume/brightness control or seeking, delegating touch events appropriately.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.player

import android.annotation.SuppressLint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import dev.xacnio.kciktv.mobile.MobilePlayerActivity

class PlayerGestureManager(private val activity: MobilePlayerActivity) {

    lateinit var playerGestureDetector: GestureDetector

    @SuppressLint("ClickableViewAccessibility")
    fun setupPlayerGestureDetector() {
        // Initialize Gesture Detector for Rewind and Player Controls
        playerGestureDetector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // Ignore taps if an overlay was just hidden (e.g. closing pinned message)
                if (System.currentTimeMillis() - activity.overlayManager.lastOverlayHideTime < 500) {
                    return false
                }

                // Helper function to check if tap is within a view's bounds
                fun isTapOnView(view: View): Boolean {
                    if (view.visibility != View.VISIBLE) return false
                    val location = IntArray(2)
                    view.getLocationOnScreen(location)
                    val x = e.rawX
                    val y = e.rawY
                    return x >= location[0] && x <= location[0] + view.width &&
                           y >= location[1] && y <= location[1] + view.height
                }
                
                // Check if tap is on overlay elements (pinned message, prediction, poll, pinned gifts)
                if (isTapOnView(activity.binding.chatOverlayContainer) ||
                    isTapOnView(activity.binding.predictionContainer) ||
                    isTapOnView(activity.binding.pinnedGiftsBlur)) {
                    // Tap is on overlay element, don't toggle video overlay
                    return false
                }
                
                if (activity.binding.playPauseOverlay.visibility == View.VISIBLE || activity.binding.videoOverlay.visibility == View.VISIBLE) {
                    activity.hideOverlay()
                    activity.stopProgressUpdater()
                } else {
                    activity.showOverlay()
                    activity.startProgressUpdater()
                }
                return true
            }
            
            override fun onDoubleTap(e: MotionEvent): Boolean {
                 // Double tap rewind disabled
                 return super.onDoubleTap(e)
            }

            override fun onLongPress(e: MotionEvent) {
                 try {
                     // Handle Theatre Mode resizing on long press
                     if (activity.fullscreenToggleManager.isTheatreMode) {
                         activity.fullscreenToggleManager.toggleTheatreFitMode()
                     }
                 } catch (e: Exception) {
                     // Safety catch
                 }
            }
            
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (e1 == null) return false
                
                val deltaX = e2.rawX - e1.rawX
                val deltaY = e2.rawY - e1.rawY
                
                val isLandscapeConfig = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                val isFullscreen = activity.isFullscreen
                val isSideChatVisible = activity.fullscreenToggleManager.isSideChatVisible
                
                // Use root width for accurate dimensions in split-screen/landscape
                var screenWidth = activity.binding.root.width
                var screenHeight = activity.binding.root.height
                if (screenWidth == 0) screenWidth = activity.resources.displayMetrics.widthPixels
                if (screenHeight == 0) {
                    screenHeight = activity.resources.displayMetrics.heightPixels
                }
                
                val isVisualLandscape = screenWidth > screenHeight
                val effectiveLandscape = isLandscapeConfig || isFullscreen || isVisualLandscape
                
                val edgeMargin = 24 * activity.resources.displayMetrics.density // Reduced to 24dp (minimal system edge)
                
                if (effectiveLandscape) {
                     android.util.Log.d("PlayerGesture", "onScroll: deltaX=$deltaX, deltaY=$deltaY, isSideChatVisible=$isSideChatVisible, effectiveLandscape=true (conf=$isLandscapeConfig, full=$isFullscreen, vis=$isVisualLandscape)")
                }

                // In landscape, prioritize horizontal swipes for chat (split-view)
                if (effectiveLandscape) {
                    if (kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY) * 1.1) {
                        // Open Chat (Swipe Left)
                        // Ignore if swipe starts from the right edge (Nav Gesture protection)
                        if (deltaX < -20 && !isSideChatVisible) {
                            android.util.Log.d("PlayerGesture", "onScroll: Open Chat Triggered")
                            // Removing edge check to allow opening chat if app receives the touch
                            activity.fullscreenToggleManager.showSideChat()
                            return true
                        }
                        // Hide Chat (Swipe Right)
                        if (deltaX > 20 && isSideChatVisible) {
                            android.util.Log.d("PlayerGesture", "onScroll: Hide Chat Triggered")
                            activity.fullscreenToggleManager.hideSideChat()
                            return true
                        }
                        // Consume horizontal moves anyway to block overlay tap
                        return true
                    } else if (kotlin.math.abs(deltaY) > kotlin.math.abs(deltaX) * 1.1) {
                        // Vertical Swipe
                        
                        // Swipe Down -> Exit Fullscreen if in fullscreen
                        if (deltaY > 50) {
                            // Helper to ignore top edge swipe (Notification Shade)
                            if (e1.rawY < edgeMargin) {
                                return false
                            }

                            // Block Swipe Down in Theatre Mode
                            if (activity.fullscreenToggleManager.isTheatreMode) {
                                return true
                            }
                            
                            if (isFullscreen) {
                                 activity.fullscreenToggleManager.exitFullscreen()
                                 return true
                            }
                        }
                        
                        // Swipe Up -> Go Back (Close Player)
                        if (deltaY < -50 && !isFullscreen && !effectiveLandscape) {
                             activity.onBackPressedDispatcher.onBackPressed()
                             return true
                        }
                    }
                }
                return false
            }
            
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                 if (e1 == null) return false
                 
                 val deltaX = e2.rawX - e1.rawX
                 val deltaY = e2.rawY - e1.rawY
                 
                 val isLandscapeConfig = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                 val isFullscreen = activity.isFullscreen
                 
                 var screenWidth = activity.binding.root.width
                 var screenHeight = activity.binding.root.height
                 if (screenWidth == 0) screenWidth = activity.resources.displayMetrics.widthPixels
                 if (screenHeight == 0) screenHeight = activity.resources.displayMetrics.heightPixels
                 
                 val isVisualLandscape = screenWidth > screenHeight
                 val effectiveLandscape = isLandscapeConfig || isFullscreen || isVisualLandscape
                 
                 if (effectiveLandscape) {
                     android.util.Log.d("PlayerGesture", "onFling: deltaX=$deltaX, deltaY=$deltaY, effectiveLandscape=true")
                 }

                // In landscape, prioritize horizontal swipes for chat (split-view)
                if (effectiveLandscape) {
                     if (kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY)) {
                         
                         val edgeMargin = 24 * activity.resources.displayMetrics.density // 24dp margin
                         
                         if (deltaX < -30 && !activity.fullscreenToggleManager.isSideChatVisible) {
                             android.util.Log.d("PlayerGesture", "onFling: Open Chat Triggered")
                             // Removing edge check to allow opening chat if app receives the touch
                             activity.fullscreenToggleManager.showSideChat()
                             return true
                         }
                         if (deltaX > 30 && activity.fullscreenToggleManager.isSideChatVisible) {
                             android.util.Log.d("PlayerGesture", "onFling: Hide Chat Triggered")
                             activity.fullscreenToggleManager.hideSideChat()
                             return true
                         }
                         return true
                     }
                 }

                 // Helper function to check if gesture started on overlay elements
                 fun isOnOverlayElement(): Boolean {
                     fun checkView(view: View): Boolean {
                         if (view.visibility != View.VISIBLE) return false
                         val location = IntArray(2)
                         view.getLocationOnScreen(location)
                         val x = e1.rawX
                         val y = e1.rawY
                         return x >= location[0] && x <= location[0] + view.width &&
                                y >= location[1] && y <= location[1] + view.height
                     }
                     return checkView(activity.binding.chatOverlayContainer) ||
                            checkView(activity.binding.predictionContainer) ||
                            checkView(activity.binding.pinnedGiftsBlur)
                 }
                 
                 // Don't handle fling if it started on overlay elements (except side chat which we handle above)
                 if (isOnOverlayElement()) return false
                 
                 // Swipe Down (Vertical > Horizontal, Downwards) to Open Profile
                 // Swipe Down (Vertical > Horizontal, Downwards)
                 // Ignore if starting from top edge (Notification Shade)
                 val topEdgeMargin = 24 * activity.resources.displayMetrics.density
                 if (e1.rawY < topEdgeMargin) {
                     return false
                 }
                 
                 if (kotlin.math.abs(deltaY) > kotlin.math.abs(deltaX) && deltaY > 150 && kotlin.math.abs(velocityY) > 100) {
                      // Block Swipe Down in Theatre Mode
                      if (activity.fullscreenToggleManager.isTheatreMode) {
                          return true
                      }

                      // If Fullscreen, exit fullscreen
                      if (activity.isFullscreen) {
                          activity.fullscreenToggleManager.exitFullscreen()
                          return true
                      }
                      // Portrait swipe-down is handled by DragToMiniPlayerManager via touch events
                  }
                 return false
            }

        })

        val touchListener = View.OnTouchListener { _, event ->
            val dragManager = activity.dragToMiniPlayerManager
            
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Always notify drag manager of touch start
                    dragManager.onTouchDown(event.rawX, event.rawY)
                    // Always pass to gesture detector so it can track onDown
                    playerGestureDetector.onTouchEvent(event)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragManager.isDragToMiniActive) {
                        // Drag-to-mini is actively handling - don't pass to gesture detector
                        dragManager.onTouchMove(event.rawX, event.rawY)
                    } else {
                        // Check if drag manager wants to start handling this
                        val handled = dragManager.onTouchMove(event.rawX, event.rawY)
                        if (!handled) {
                            // Drag manager isn't interested, pass to gesture detector
                            playerGestureDetector.onTouchEvent(event)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragManager.isDragToMiniActive) {
                        // Drag-to-mini was active, let it handle the release
                        dragManager.onTouchUp(event.rawY)
                    } else {
                        // Normal flow - pass to gesture detector
                        playerGestureDetector.onTouchEvent(event)
                        // Overlay auto-hide timer
                        if (event.action == MotionEvent.ACTION_UP) {
                            if (activity.binding.videoOverlay.visibility == View.VISIBLE) {
                                activity.mainHandler.removeCallbacks(activity.getHideOverlayRunnable())
                                activity.mainHandler.postDelayed(activity.getHideOverlayRunnable(), 3000)
                            }
                        }
                    }
                }
                else -> {
                    playerGestureDetector.onTouchEvent(event)
                }
            }
            true
        }
        
        activity.binding.playerView.setOnTouchListener(touchListener)
        activity.binding.playPauseOverlay.setOnTouchListener(touchListener)
        activity.binding.videoOverlay.setOnTouchListener(touchListener)
        activity.binding.sideChatContainer.setOnTouchListener(touchListener)
        activity.binding.videoContainer.setOnTouchListener(touchListener)
        
        // Setup Rewind Button
        activity.binding.rewindButton.setOnClickListener {
            activity.handleRewindRequest()
        }
    }
}
