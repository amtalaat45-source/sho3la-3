package com.example.studyvault

import androidx.camera.core.ImageProxy
import kotlin.math.sqrt

/**
 * Camera "spot lock" — the non-AR replacement for the virtual vault.
 *
 * While the phone lies on the desk, its camera sees a fixed scene (usually the ceiling/room).
 * We store a tiny 24x24 grayscale "fingerprint" of that scene when the user locks the spot.
 * During the session we compare live fingerprints to it with a brightness-independent
 * correlation. If the phone is lifted, moved or turned, the scene changes and the
 * correlation drops, which raises the same "phone removed" alarm flow.
 */
object SpotLock {
    const val N = 24

    /** Below this for several checks in a row = phone is no longer in its spot. */
    const val MISMATCH_BELOW = 0.85f
    /** Above this for several checks in a row = phone is back in its spot. */
    const val MATCH_ABOVE = 0.92f
    /** A reference with less contrast than this is too flat (blank ceiling / dark room). */
    const val MIN_REF_CONTRAST = 6f
    /** Live frames flatter than this (lights off, lens covered) are ignored, not treated as moved. */
    const val MIN_LIVE_CONTRAST = 3f
    /** While locking, every sample must be this similar to the average (phone must be still). */
    const val MIN_SAMPLE_STABILITY = 0.95f

    const val SAMPLES_NEEDED = 6
    const val CHECK_INTERVAL_MS = 500L
    const val BAD_CHECKS_TO_TRIGGER = 4   // ~2 seconds
    const val GOOD_CHECKS_TO_CLEAR = 3    // ~1.5 seconds

    @Volatile var reference: FloatArray? = null

    fun clear() {
        reference = null
    }

    /** Reduce a camera frame (luma plane) to an N x N grid of average brightness values. */
    fun signature(image: ImageProxy): FloatArray {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = image.width
        val h = image.height
        val out = FloatArray(N * N)
        for (gy in 0 until N) {
            val y0 = gy * h / N
            val y1 = (gy + 1) * h / N
            for (gx in 0 until N) {
                val x0 = gx * w / N
                val x1 = (gx + 1) * w / N
                var sum = 0L
                var cnt = 0
                var y = y0
                while (y < y1) {
                    var x = x0
                    while (x < x1) {
                        val idx = y * rowStride + x * pixelStride
                        if (idx < buf.limit()) {
                            sum += (buf.get(idx).toInt() and 0xFF)
                            cnt++
                        }
                        x += 4
                    }
                    y += 4
                }
                out[gy * N + gx] = if (cnt > 0) sum.toFloat() / cnt else 0f
            }
        }
        return out
    }

    /** Standard deviation of the fingerprint = how much detail the scene has. */
    fun contrast(a: FloatArray): Float {
        val m = a.average().toFloat()
        var s = 0f
        for (v in a) s += (v - m) * (v - m)
        return sqrt(s / a.size)
    }

    /** Pearson correlation: 1.0 = same scene, ignores overall brightness changes. */
    fun correlation(a: FloatArray, b: FloatArray): Float {
        val ma = a.average().toFloat()
        val mb = b.average().toFloat()
        var num = 0f
        var da = 0f
        var db = 0f
        for (i in a.indices) {
            val x = a[i] - ma
            val y = b[i] - mb
            num += x * y
            da += x * x
            db += y * y
        }
        val d = sqrt(da * db)
        return if (d < 1e-6f) 0f else num / d
    }

    fun average(samples: List<FloatArray>): FloatArray {
        val out = FloatArray(N * N)
        for (s in samples) for (i in out.indices) out[i] += s[i]
        for (i in out.indices) out[i] /= samples.size
        return out
    }
}
