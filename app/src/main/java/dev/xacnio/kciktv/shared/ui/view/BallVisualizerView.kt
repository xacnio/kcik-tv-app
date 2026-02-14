/**
 * File: BallVisualizerView.kt
 *
 * Description: Implementation of Ball Visualizer View functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

class BallVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val balls = mutableListOf<Ball>()
    private val particles = mutableListOf<Particle>()
    
    private val greenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF53FC18.toInt()
        style = Paint.Style.FILL
    }
    private val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF4D4D.toInt()
        style = Paint.Style.FILL
    }
    
    private val ballRadius = 8f * resources.displayMetrics.density
    private val gravity = 0.4f
    private val dampening = 0.5f
    private val friction = 0.95f
    private val restThreshold = 0.3f
    
    private var isAnimating = false
    private var initialized = false

    data class Ball(
        var x: Float,
        var y: Float,
        var isGreen: Boolean,
        var velocityX: Float = 0f,
        var velocityY: Float = 0f,
        var restFrames: Int = 0 // Frames at rest
    ) {
        val isResting: Boolean get() = restFrames > 10
    }
    
    data class Particle(
        var x: Float,
        var y: Float,
        var velocityX: Float,
        var velocityY: Float,
        var alpha: Float = 1f,
        val color: Int,
        val size: Float
    )
    
    fun updatePercentages(greenPercent: Int) {
        val targetGreen = greenPercent.coerceIn(0, 100)
        
        val currentGreen = balls.count { it.isGreen }
        
        if (!initialized && width > 0 && height > 0) {
            initialized = true
            balls.clear()
            
            repeat(targetGreen) { i ->
                addBall(true, i)
            }
            repeat(100 - targetGreen) { i ->
                addBall(false, targetGreen + i)
            }
        } else if (initialized) {
            val greenDiff = targetGreen - currentGreen
            
            if (greenDiff > 0) {
                var toConvert = greenDiff
                val iterator = balls.iterator()
                while (iterator.hasNext() && toConvert > 0) {
                    val ball = iterator.next()
                    if (!ball.isGreen) {
                        iterator.remove()
                        toConvert--
                    }
                }
                repeat(greenDiff) { _ ->
                    addBall(true, 0)
                }
            } else if (greenDiff < 0) {
                var toConvert = -greenDiff
                val iterator = balls.iterator()
                while (iterator.hasNext() && toConvert > 0) {
                    val ball = iterator.next()
                    if (ball.isGreen) {
                        iterator.remove()
                        toConvert--
                    }
                }
                repeat(-greenDiff) { _ ->
                    addBall(false, 0)
                }
            }
        }
        
        startAnimationIfNeeded()
    }

    private fun addBall(isGreen: Boolean, index: Int) {
        if (width <= 0 || height <= 0) return
        
        val startX = Random.nextFloat() * (width - ballRadius * 2) + ballRadius
        val startY = -ballRadius * 2 - (index % 8) * ballRadius * 3 - Random.nextFloat() * ballRadius * 2
        
        balls.add(Ball(
            x = startX,
            y = startY,
            isGreen = isGreen,
            velocityX = Random.nextFloat() * 1.5f - 0.75f,
            velocityY = Random.nextFloat() * 1f
        ))
    }
    
    private fun createExplosion(centerX: Float, centerY: Float) {
        val explosionRadius = ballRadius * 6f // Explosion radius
        val explosionForce = 18f // Explosion force
        
        balls.forEach { ball ->
            val dx = ball.x - centerX
            val dy = ball.y - centerY
            val dist = sqrt(dx * dx + dy * dy)
            
            if (dist < explosionRadius && dist > 0.1f) {
                // Force decreasing by distance
                val forceFactor = (1f - (dist / explosionRadius)) * explosionForce
                val nx = dx / dist
                val ny = dy / dist
                
                // Push ball out
                ball.velocityX += nx * forceFactor
                ball.velocityY += ny * forceFactor - 2f // Push slightly up
                ball.restFrames = 0 // Reset rest state
            }
        }
        
        // Add particles for visual effect (optional)
        repeat(12) { _ ->
            val angle = Random.nextFloat() * Math.PI.toFloat() * 2
            val speed = Random.nextFloat() * 6f + 3f
            particles.add(Particle(
                x = centerX,
                y = centerY,
                velocityX = kotlin.math.cos(angle) * speed,
                velocityY = kotlin.math.sin(angle) * speed - 2f,
                color = 0xFFFFFFFF.toInt(), // White particles
                size = ballRadius * (0.15f + Random.nextFloat() * 0.2f)
            ))
        }
        
        startAnimationIfNeeded()
    }
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val touchX = event.x
            val touchY = event.y
            
            // Create explosion at touch point
            createExplosion(touchX, touchY)
            return false // Return false to allow parent click listener
        }
        return false
    }
    
    fun clear() {
        balls.clear()
        particles.clear()
        initialized = false
        isAnimating = false
        invalidate()
    }
    
    private fun startAnimationIfNeeded() {
        if (!isAnimating && (balls.isNotEmpty() || particles.isNotEmpty())) {
            isAnimating = true
            invalidate()
        }
    }
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (width <= 0 || height <= 0) return
        
        updatePhysics()
        updateParticles()
        
        // Draw balls
        balls.forEach { ball ->
            val paint = if (ball.isGreen) greenPaint else redPaint
            canvas.drawCircle(ball.x, ball.y, ballRadius, paint)
        }
        
        // Draw particles
        val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        particles.forEach { particle ->
            particlePaint.color = particle.color
            particlePaint.alpha = (particle.alpha * 255).toInt()
            canvas.drawCircle(particle.x, particle.y, particle.size, particlePaint)
        }
        
        // Animasyon devam etmeli mi?
        val hasMovingBalls = balls.any { !it.isResting || it.y < 0 }
        val hasParticles = particles.isNotEmpty()
        
        if (hasMovingBalls || hasParticles) {
            postInvalidateOnAnimation()
        } else {
            isAnimating = false
        }
    }
    
    private fun updateParticles() {
        val iterator = particles.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            p.velocityY += gravity * 0.8f
            p.x += p.velocityX
            p.y += p.velocityY
            p.alpha -= 0.03f
            
            if (p.alpha <= 0 || p.y > height + 50) {
                iterator.remove()
            }
        }
    }
    
    private fun updatePhysics() {
        val bottomBound = height - ballRadius
        val rightBound = width - ballRadius
        val leftBound = ballRadius
        
        balls.forEach { ball ->
            // Resting ball check
            if (ball.isResting) {
                // Check support underneath
                val hasSupport = ball.y >= bottomBound - 0.5f || balls.any { other ->
                    other != ball &&
                    other.y > ball.y + ballRadius * 0.5f &&
                    abs(other.x - ball.x) < ballRadius * 1.8f &&
                    abs(other.y - ball.y) < ballRadius * 2.2f
                }
                if (!hasSupport) {
                    ball.restFrames = 0
                }
                return@forEach
            }
            
            // Gravity
            ball.velocityY += gravity
            ball.velocityX *= friction
            ball.velocityY *= 0.995f
            
            // Update position
            ball.x += ball.velocityX
            ball.y += ball.velocityY
            
            // Ground collision
            if (ball.y > bottomBound) {
                ball.y = bottomBound
                ball.velocityY *= -dampening
                ball.velocityX *= 0.8f
            }
            
            // Wall collisions
            if (ball.x > rightBound) {
                ball.x = rightBound
                ball.velocityX *= -dampening
            } else if (ball.x < leftBound) {
                ball.x = leftBound
                ball.velocityX *= -dampening
            }
            
            // Rest check
            if (abs(ball.velocityX) < restThreshold && abs(ball.velocityY) < restThreshold && ball.y >= bottomBound - 1) {
                ball.restFrames++
                if (ball.isResting) {
                    ball.velocityX = 0f
                    ball.velocityY = 0f
                }
            } else {
                ball.restFrames = 0
            }
        }
        
        resolveCollisions()
    }
    
    private fun resolveCollisions() {
        val minDist = ballRadius * 2
        val minDistSq = minDist * minDist
        
        // Sadece 2 iterasyon yeterli
        repeat(2) { _ ->
            for (i in balls.indices) {
                val b1 = balls[i]
                for (j in i + 1 until balls.size) {
                    val b2 = balls[j]
                    
                    val dx = b1.x - b2.x
                    val dy = b1.y - b2.y
                    val distSq = dx * dx + dy * dy
                    
                    if (distSq < minDistSq && distSq > 0.001f) {
                        val dist = sqrt(distSq)
                        val overlap = (minDist - dist) / 2f + 0.1f
                        val nx = dx / dist
                        val ny = dy / dist
                        
                        // If both balls at rest, just separate, no velocity transfer
                        if (b1.isResting && b2.isResting) {
                            // Minimal separation
                            val sepX = nx * overlap * 0.5f
                            val sepY = ny * overlap * 0.5f
                            b1.x += sepX
                            b2.x -= sepX
                            // Separate Y only upwards
                            if (b1.y < b2.y) {
                                b1.y -= abs(sepY) * 0.3f
                            } else {
                                b2.y -= abs(sepY) * 0.3f
                            }
                            continue
                        }
                        
                        // Hareket eden top varsa normal fizik
                        if (!b1.isResting) {
                            b1.x += nx * overlap
                            b1.y += ny * overlap
                        }
                        if (!b2.isResting) {
                            b2.x -= nx * overlap
                            b2.y -= ny * overlap
                        }
                        
                        // Velocity transfer
                        if (!b1.isResting || !b2.isResting) {
                            val dvx = b1.velocityX - b2.velocityX
                            val dvy = b1.velocityY - b2.velocityY
                            val dot = dvx * nx + dvy * ny
                            
                            if (dot < 0) {
                                val restitution = 0.25f
                                val impulse = dot * restitution
                                
                                if (!b1.isResting) {
                                    b1.velocityX -= nx * impulse
                                    b1.velocityY -= ny * impulse
                                }
                                if (!b2.isResting) {
                                    b2.velocityX += nx * impulse
                                    b2.velocityY += ny * impulse
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        isAnimating = false
    }
}
