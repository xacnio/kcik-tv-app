/**
 * File: ShatterView.kt
 *
 * Description: Draws a bitmap broken into irregular shards that fly apart and fall, like a torn
 * up piece of paper. Driven externally via [progress]; it runs no animation of its own.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.view.View
import kotlin.random.Random

class ShatterView(context: Context) : View(context) {

    private companion object {
        const val COLS = 5
        const val ROWS = 6

        // How far a vertex may wander from its grid position, as a fraction of a cell. Enough
        // that edges read as torn rather than cut, not so much that shards fold over.
        const val JITTER = 0.34f

        // Downward drift over the burst, as a fraction of the card's height.
        const val FALL = 0.55f
    }

    private class Shard(
        val path: Path,
        val pivotX: Float,
        val pivotY: Float,
        val dx: Float,
        val dy: Float,
        val spin: Float,
        val delay: Float
    )

    private var source: Bitmap? = null
    private var destRect = Rect()
    private var fallPx = 0f
    private val shards = mutableListOf<Shard>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** 0 = intact, 1 = fully dispersed. */
    var progress: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    fun setSource(bitmap: Bitmap) {
        source = bitmap
        destRect = Rect(0, 0, bitmap.width, bitmap.height)
        fallPx = bitmap.height * FALL
        buildShards(bitmap.width.toFloat(), bitmap.height.toFloat())
        invalidate()
    }

    private fun buildShards(width: Float, height: Float) {
        shards.clear()
        val rng = Random.Default
        val cellWidth = width / COLS
        val cellHeight = height / ROWS

        // One shared jittered vertex grid, so neighbouring shards keep identical edges and the
        // card looks torn apart instead of sliced into rectangles. Border vertices are pinned so
        // the card's silhouette stays crisp right up until it breaks.
        val gridX = Array(ROWS + 1) { FloatArray(COLS + 1) }
        val gridY = Array(ROWS + 1) { FloatArray(COLS + 1) }
        for (row in 0..ROWS) {
            for (col in 0..COLS) {
                val onEdge = row == 0 || col == 0 || row == ROWS || col == COLS
                val jitterX = if (onEdge) 0f else (rng.nextFloat() - 0.5f) * cellWidth * JITTER * 2f
                val jitterY = if (onEdge) 0f else (rng.nextFloat() - 0.5f) * cellHeight * JITTER * 2f
                gridX[row][col] = col * cellWidth + jitterX
                gridY[row][col] = row * cellHeight + jitterY
            }
        }

        val centerX = width / 2f
        val centerY = height / 2f
        for (row in 0 until ROWS) {
            for (col in 0 until COLS) {
                val path = Path().apply {
                    moveTo(gridX[row][col], gridY[row][col])
                    lineTo(gridX[row][col + 1], gridY[row][col + 1])
                    lineTo(gridX[row + 1][col + 1], gridY[row + 1][col + 1])
                    lineTo(gridX[row + 1][col], gridY[row + 1][col])
                    close()
                }
                val pivotX = (gridX[row][col] + gridX[row][col + 1] +
                    gridX[row + 1][col + 1] + gridX[row + 1][col]) / 4f
                val pivotY = (gridY[row][col] + gridY[row][col + 1] +
                    gridY[row + 1][col + 1] + gridY[row + 1][col]) / 4f

                // Each shard is thrown along its own line out from the middle, so the card bursts
                // outward instead of every piece sliding the same way.
                val outX = (pivotX - centerX) / centerX
                val outY = (pivotY - centerY) / centerY
                shards.add(
                    Shard(
                        path = path,
                        pivotX = pivotX,
                        pivotY = pivotY,
                        dx = outX * width * (0.45f + rng.nextFloat() * 0.55f) +
                            (rng.nextFloat() - 0.5f) * cellWidth,
                        dy = outY * height * (0.30f + rng.nextFloat() * 0.45f) +
                            (rng.nextFloat() - 0.5f) * cellHeight,
                        spin = (rng.nextFloat() - 0.5f) * 280f,
                        delay = rng.nextFloat() * 0.18f
                    )
                )
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = source ?: return
        if (shards.isEmpty()) return

        for (shard in shards) {
            // Staggered starts keep the break from looking like one rigid explosion.
            val t = ((progress - shard.delay) / (1f - shard.delay)).coerceIn(0f, 1f)
            if (t >= 1f) continue

            canvas.save()
            val eased = 1f - (1f - t) * (1f - t) // ease-out: quick burst, slowing drift
            canvas.translate(shard.dx * eased, shard.dy * eased + fallPx * t * t)
            canvas.rotate(shard.spin * eased, shard.pivotX, shard.pivotY)
            // Clip to the shard, then paint the whole bitmap through it — each piece keeps the
            // art that was actually under it.
            canvas.clipPath(shard.path)
            paint.alpha = ((1f - t * t) * 255f).toInt().coerceIn(0, 255)
            canvas.drawBitmap(bitmap, null, destRect, paint)
            canvas.restore()
        }
    }
}
