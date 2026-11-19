package com.tymur.raccoontoy.core

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Phone (3) Glyph Matrix geometry: 25x25 grid, 489 physical LEDs in a circle. */
object GlyphMask {
    const val SIZE = 25
    const val PIXELS = SIZE * SIZE

    /** First lit column for each row (mirror-symmetric), taken from Nothing's preview spec. */
    private val ROW_START = intArrayOf(
        9, 7, 5, 4, 3, 2, 2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 1, 1, 2, 2, 3, 4, 5, 7, 9
    )

    fun isLed(x: Int, y: Int): Boolean =
        y in 0 until SIZE && x >= ROW_START[y] && x < SIZE - ROW_START[y]
}

/** Grayscale source frames, downscaled once so that re-cropping is cheap. */
class LumaFrames(
    val width: Int,
    val height: Int,
    val frames: List<ByteArray>,
    val delaysMs: IntArray,
) {
    val count get() = frames.size

    companion object {
        const val MAX_SIDE = 200
        const val MAX_FRAMES = 300

        fun fromGif(bytes: ByteArray): LumaFrames {
            val frames = ArrayList<ByteArray>()
            val delays = ArrayList<Int>()
            var outW = 0
            var outH = 0
            GifDecoder.decode(bytes, MAX_FRAMES) { argb, w, h, delay ->
                if (outW == 0) {
                    val scale = min(1.0, MAX_SIDE.toDouble() / max(w, h))
                    outW = max(1, (w * scale).roundToInt())
                    outH = max(1, (h * scale).roundToInt())
                }
                frames += boxDownscaleLuma(argb, w, h, outW, outH)
                // Browsers treat 0–10 ms delays as 100 ms; do the same.
                delays += if (delay <= 10) 100 else delay
            }
            return LumaFrames(outW, outH, frames, delays.toIntArray())
        }

        /** Area-average downscale + Rec.709 luma, alpha composited over black. */
        private fun boxDownscaleLuma(argb: IntArray, w: Int, h: Int, ow: Int, oh: Int): ByteArray {
            val out = ByteArray(ow * oh)
            for (oy in 0 until oh) {
                val y0 = oy * h / oh
                val y1 = max(y0 + 1, (oy + 1) * h / oh)
                for (ox in 0 until ow) {
                    val x0 = ox * w / ow
                    val x1 = max(x0 + 1, (ox + 1) * w / ow)
                    var sum = 0L
                    for (y in y0 until y1) {
                        val row = y * w
                        for (x in x0 until x1) {
                            val c = argb[row + x]
                            val a = c ushr 24
                            val l = (2126 * ((c shr 16) and 0xFF) + 7152 * ((c shr 8) and 0xFF) +
                                722 * (c and 0xFF)) / 10000
                            sum += l * a / 255
                        }
                    }
                    out[oy * ow + ox] = (sum / ((y1 - y0) * (x1 - x0))).toInt().toByte()
                }
            }
            return out
        }
    }
}

data class ConvertOptions(
    /** 1.0 = fit the short side of the GIF into the circle; >1 zooms in. */
    val zoom: Float = 1.0f,
    /** -1..1, shifts the crop window inside the GIF. */
    val panX: Float = 0f,
    val panY: Float = 0f,
    val invert: Boolean = false,
    /** 0..1 overall LED brightness. */
    val brightness: Float = 1.0f,
    /** Gamma applied after auto-levels; >1 darkens mid-tones (more contrast on LEDs). */
    val contrast: Float = 1.6f,
)

/** Final 25x25 frames, values 0..255 per LED, ready for GlyphMatrixManager.setMatrixFrame(int[]). */
class MatrixFrames(val frames: List<IntArray>, val delaysMs: IntArray) {
    val count get() = frames.size

    companion object {
        /** Placeholder shown when the bundled GIF is missing: a slow breathing ring. */
        fun placeholder(): MatrixFrames {
            val n = 24
            val c = (GlyphMask.SIZE - 1) / 2.0
            val frames = List(n) { i ->
                val radius = 3.0 + 8.0 * (i.toDouble() / n)
                IntArray(GlyphMask.PIXELS) { p ->
                    val x = p % GlyphMask.SIZE
                    val y = p / GlyphMask.SIZE
                    if (!GlyphMask.isLed(x, y)) return@IntArray 0
                    val d = kotlin.math.hypot(x - c, y - c)
                    val v = 1.0 - kotlin.math.abs(d - radius) / 1.5
                    (max(0.0, v) * 255 * (1.0 - i.toDouble() / n)).roundToInt()
                }
            }
            return MatrixFrames(frames, IntArray(n) { 70 })
        }
    }
}

object MatrixConverter {
    private const val S = GlyphMask.SIZE
    private const val SUB = 3 // 3x3 supersampling per LED

    fun convert(src: LumaFrames, opt: ConvertOptions): MatrixFrames {
        val w = src.width
        val h = src.height
        val zoom = opt.zoom.coerceIn(1f, 5f)
        val side = min(w, h) / zoom
        val cx = w / 2f + opt.panX.coerceIn(-1f, 1f) * (w - side) / 2f
        val cy = h / 2f + opt.panY.coerceIn(-1f, 1f) * (h - side) / 2f
        val left = cx - side / 2f
        val top = cy - side / 2f
        val step = side / S

        // 1) sample every frame to 25x25 floats
        val raw = src.frames.map { luma ->
            FloatArray(GlyphMask.PIXELS) { p ->
                val gx = p % S
                val gy = p / S
                if (!GlyphMask.isLed(gx, gy)) return@FloatArray 0f
                var sum = 0f
                for (sy in 0 until SUB) for (sx in 0 until SUB) {
                    val x = left + (gx + (sx + 0.5f) / SUB) * step - 0.5f
                    val y = top + (gy + (sy + 0.5f) / SUB) * step - 0.5f
                    sum += bilinear(luma, w, h, x, y)
                }
                sum / (SUB * SUB)
            }
        }

        // 2) global auto-levels (same for all frames, so the animation doesn't flicker)
        val hist = IntArray(256)
        var total = 0
        for (f in raw) for (p in 0 until GlyphMask.PIXELS) {
            if (GlyphMask.isLed(p % S, p / S)) { hist[f[p].roundToInt().coerceIn(0, 255)]++; total++ }
        }
        var lo = percentile(hist, total, 0.02).toFloat()
        var hi = percentile(hist, total, 0.98).toFloat()
        if (hi - lo < 8f) { lo = 0f; hi = 255f }

        // 3) map to LED brightness
        val gamma = opt.contrast.coerceIn(0.5f, 4f).toDouble()
        val bright = opt.brightness.coerceIn(0f, 1f) * 255f
        val frames = raw.map { f ->
            IntArray(GlyphMask.PIXELS) { p ->
                if (!GlyphMask.isLed(p % S, p / S)) return@IntArray 0
                var v = ((f[p] - lo) / (hi - lo)).coerceIn(0f, 1f)
                if (opt.invert) v = 1f - v
                (v.toDouble().pow(gamma) * bright).roundToInt().coerceIn(0, 255)
            }
        }
        return MatrixFrames(frames, src.delaysMs.copyOf())
    }

    private fun percentile(hist: IntArray, total: Int, q: Double): Int {
        val target = (total * q).toLong()
        var acc = 0L
        for (i in 0..255) { acc += hist[i]; if (acc > target) return i }
        return 255
    }

    private fun bilinear(d: ByteArray, w: Int, h: Int, x: Float, y: Float): Float {
        val xc = x.coerceIn(0f, (w - 1).toFloat())
        val yc = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = floor(xc).toInt()
        val y0 = floor(yc).toInt()
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val fx = xc - x0
        val fy = yc - y0
        fun at(xx: Int, yy: Int) = (d[yy * w + xx].toInt() and 0xFF).toFloat()
        val top = at(x0, y0) + (at(x1, y0) - at(x0, y0)) * fx
        val bot = at(x0, y1) + (at(x1, y1) - at(x0, y1)) * fx
        return top + (bot - top) * fy
    }
}
