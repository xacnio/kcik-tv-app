/**
 * File: CpuMonitor.kt
 *
 * Description: Lightweight CPU usage sampler with per-thread breakdown and a main-thread
 * mini-profiler. Reads the process total from `/proc/self/stat` and each thread's CPU
 * from `/proc/self/task/<tid>/stat` (utime + stime, in clock ticks), converting deltas
 * into a percentage of one core's wall-clock time.
 *
 * The sampler runs on its OWN background thread (HandlerThread), not the main thread —
 * otherwise a main-thread stack snapshot would just catch this sampler itself. From the
 * background thread it repeatedly snapshots the main thread's stack and tallies the
 * hottest frame, naming whatever is pinning the UI thread.
 *
 * Purely a diagnostic; it changes no playback behaviour. Logs to logcat under the
 * "CpuMonitor" tag with a caller-supplied label so states are easy to compare.
 *
 * Author: Xacnio
 */
package dev.xacnio.kciktv.shared.util

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

object CpuMonitor {
    private const val TAG = "CpuMonitor"
    private const val SAMPLE_INTERVAL_MS = 3000L
    private const val TOP_THREADS = 6
    // Main-thread mini-profiler: how many stack snapshots per reporting window and the
    // gap between them. 24 snapshots * 60ms ≈ 1.4s of profiling inside each 3s window.
    private const val STACK_SAMPLES = 24
    private const val STACK_SAMPLE_GAP_MS = 60L
    private const val TOP_FRAMES = 8
    private const val APP_PREFIX = "dev.xacnio.kciktv"

    private val clockTicksPerSec: Long = try {
        Os.sysconf(OsConstants._SC_CLK_TCK)
    } catch (e: Exception) {
        100L // POSIX default; safe fallback
    }
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private val mainThread = android.os.Looper.getMainLooper().thread

    // Dedicated background thread so stack snapshots observe the *main* thread doing its
    // real work instead of observing our own sampler running on main.
    private val samplerThread = HandlerThread("CpuMonitor").apply { start() }
    private val handler = Handler(samplerThread.looper)

    private var label: String = ""
    private var lastCpuTicks = 0L
    private var lastWallMs = 0L
    private var running = false

    // tid -> last cumulative cpu ticks, for per-thread deltas between samples.
    private val lastThreadTicks = HashMap<Int, Long>()

    private val sampler = object : Runnable {
        override fun run() {
            if (!running) return
            sampleOnce()
            handler.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    /**
     * Begins periodic logging tagged with [label] (e.g. "foreground/chat"). Idempotent:
     * a new call re-seeds the baseline and replaces the previous label.
     */
    fun start(label: String) {
        handler.post {
            this.label = label
            lastCpuTicks = readProcessCpuTicks()
            lastWallMs = SystemClock.elapsedRealtime()
            lastThreadTicks.clear()
            seedThreadBaseline()
            if (!running) {
                running = true
                handler.postDelayed(sampler, SAMPLE_INTERVAL_MS)
            }
            Log.d(TAG, "[$label] monitoring started (cores=$cores, clkTck=$clockTicksPerSec)")
        }
    }

    fun stop() {
        handler.post {
            if (!running) return@post
            // Emit a final sample so the last window before stopping is captured.
            sampleOnce()
            running = false
            handler.removeCallbacks(sampler)
            Log.d(TAG, "[$label] monitoring stopped")
        }
    }

    private fun sampleOnce() {
        val nowTicks = readProcessCpuTicks()
        val nowWall = SystemClock.elapsedRealtime()
        val deltaTicks = nowTicks - lastCpuTicks
        val deltaWallMs = nowWall - lastWallMs
        if (deltaTicks >= 0 && deltaWallMs > 0) {
            val pctOfOneCore = ticksToPct(deltaTicks, deltaWallMs)
            Log.d(
                TAG,
                "[$label] TOTAL=%.1f%% (1 core) / %.1f%% (device) over %dms"
                    .format(pctOfOneCore, pctOfOneCore / cores, deltaWallMs)
            )
            logThreadBreakdown(deltaWallMs)
        }
        lastCpuTicks = nowTicks
        lastWallMs = nowWall
        // Profile the main thread last; this blocks the sampler thread (not main) for
        // ~STACK_SAMPLES*GAP and naturally paces the next 3s window.
        profileMainThread()
    }

    /**
     * Repeatedly snapshots the main thread from this background thread and tallies which
     * call frames appear most often. The most frequent app frame is what pins the UI
     * thread. Runs on the sampler thread, so it observes main doing its real work.
     */
    private fun profileMainThread() {
        val hotAppFrame = HashMap<String, Int>()
        val allFrames = HashMap<String, Int>()
        var idle = 0
        var sampled = 0

        repeat(STACK_SAMPLES) {
            if (!running) return@repeat
            val stack = try { mainThread.stackTrace } catch (e: Exception) { null }
            if (stack != null && stack.isNotEmpty()) {
                sampled++
                val topMethod = stack[0].methodName
                if (topMethod == "nativePollOnce" || topMethod == "epollWait") {
                    idle++
                } else {
                    val appFrame = stack.firstOrNull { frameInApp(it) }
                    if (appFrame != null) {
                        hotAppFrame[frameKey(appFrame)] = (hotAppFrame[frameKey(appFrame)] ?: 0) + 1
                    }
                    allFrames[frameKey(stack[0])] = (allFrames[frameKey(stack[0])] ?: 0) + 1
                }
            }
            SystemClock.sleep(STACK_SAMPLE_GAP_MS)
        }

        Log.d(TAG, "[$label]   main-profile: $sampled samples, $idle idle")
        if (hotAppFrame.isNotEmpty()) {
            Log.d(TAG, "[$label]   hottest app frames:")
            hotAppFrame.entries.sortedByDescending { it.value }.take(TOP_FRAMES).forEach {
                Log.d(TAG, "[$label]     %3d  %s".format(it.value, it.key))
            }
        }
        if (allFrames.isNotEmpty()) {
            Log.d(TAG, "[$label]   hottest top frames (any):")
            allFrames.entries.sortedByDescending { it.value }.take(TOP_FRAMES).forEach {
                Log.d(TAG, "[$label]     %3d  %s".format(it.value, it.key))
            }
        }
    }

    private fun frameInApp(f: StackTraceElement): Boolean =
        f.className.startsWith(APP_PREFIX)

    private fun frameKey(f: StackTraceElement): String =
        "${f.className}.${f.methodName}(${f.fileName}:${f.lineNumber})"

    /** Seeds per-thread baselines without logging (used at start). */
    private fun seedThreadBaseline() {
        forEachThread { tid, _, ticks -> lastThreadTicks[tid] = ticks }
    }

    /** Logs the top [TOP_THREADS] threads by CPU delta over the sample window. */
    private fun logThreadBreakdown(deltaWallMs: Long) {
        data class ThreadCpu(val tid: Int, val name: String, val pct: Double)

        val results = ArrayList<ThreadCpu>()
        forEachThread { tid, name, ticks ->
            val prev = lastThreadTicks[tid]
            if (prev != null) {
                val d = ticks - prev
                if (d > 0) {
                    results.add(ThreadCpu(tid, name, ticksToPct(d, deltaWallMs)))
                }
            }
            lastThreadTicks[tid] = ticks
        }

        results.sortByDescending { it.pct }
        for (t in results.take(TOP_THREADS)) {
            Log.d(TAG, "[$label]   %5.1f%%  tid=%-6d %s".format(t.pct, t.tid, t.name))
        }
    }

    /** Converts a clock-tick delta over [deltaWallMs] into a percentage of one core. */
    private fun ticksToPct(deltaTicks: Long, deltaWallMs: Long): Double {
        val cpuMs = deltaTicks * 1000.0 / clockTicksPerSec
        return cpuMs / deltaWallMs * 100.0
    }

    /**
     * Iterates every live thread of this process, invoking [block] with the thread id,
     * its name (comm), and its cumulative CPU ticks (utime + stime). Threads that die
     * mid-iteration are silently skipped.
     */
    private inline fun forEachThread(block: (tid: Int, name: String, ticks: Long) -> Unit) {
        val tids = File("/proc/self/task").list() ?: return
        for (tidStr in tids) {
            val tid = tidStr.toIntOrNull() ?: continue
            try {
                RandomAccessFile("/proc/self/task/$tidStr/stat", "r").use { raf ->
                    val line = raf.readLine() ?: return@use
                    val open = line.indexOf('(')
                    val close = line.lastIndexOf(')')
                    if (open < 0 || close < 0 || close < open) return@use
                    val name = line.substring(open + 1, close)
                    // After comm, index 0 = state (field 3). utime=field14 -> index 11,
                    // stime=field15 -> index 12.
                    val fields = line.substring(close + 2).split(" ")
                    val utime = fields[11].toLong()
                    val stime = fields[12].toLong()
                    block(tid, name, utime + stime)
                }
            } catch (e: Exception) {
                // Thread exited between listing and read, or stat unreadable; skip.
            }
        }
    }

    /** utime (field 14) + stime (field 15) from /proc/self/stat, in clock ticks. */
    private fun readProcessCpuTicks(): Long {
        return try {
            RandomAccessFile("/proc/self/stat", "r").use { raf ->
                val line = raf.readLine() ?: return 0L
                // comm (field 2) may contain spaces/parentheses; parse after the last ')'.
                val fields = line.substring(line.lastIndexOf(')') + 2).split(" ")
                val utime = fields[11].toLong()
                val stime = fields[12].toLong()
                utime + stime
            }
        } catch (e: Exception) {
            Log.w(TAG, "failed to read /proc/self/stat: ${e.message}")
            0L
        }
    }
}
