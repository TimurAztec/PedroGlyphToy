package com.tymur.raccoontoy

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.tymur.raccoontoy.core.GlyphMask
import com.tymur.raccoontoy.core.MatrixFrames

/** On-screen emulation of the Glyph Matrix, animated with the same frame delays. */
class MatrixPreviewView(context: Context) : View(context) {

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val led = Paint(Paint.ANTI_ALIAS_FLAG)
    private var frames: MatrixFrames? = null
    private var index = 0
    var speed = 1f

    private val tick = object : Runnable {
        override fun run() {
            val f = frames ?: return
            index = (index + 1) % f.count
            invalidate()
            postDelayed(this, (f.delaysMs[index] / speed).toLong().coerceAtLeast(16L))
        }
    }

    fun show(f: MatrixFrames) {
        removeCallbacks(tick)
        frames = f
        index = 0
        invalidate()
        if (isAttachedToWindow) postDelayed(tick, f.delaysMs[0].toLong())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        frames?.let { postDelayed(tick, it.delaysMs[0].toLong()) }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w)
    }

    override fun onDraw(canvas: Canvas) {
        val size = width.toFloat()
        canvas.drawCircle(size / 2, size / 2, size / 2, bg)
        // Nothing preview spec: 512px background, 440px matrix, 13.05px LEDs on a 17.82px pitch.
        val scale = size / 512f
        val pitch = 17.8208f * scale
        val ledSize = 13.05f * scale
        val origin = 35.63f * scale
        val radius = ledSize * 0.18f
        val frame = frames?.frames?.getOrNull(index)
        for (y in 0 until GlyphMask.SIZE) for (x in 0 until GlyphMask.SIZE) {
            if (!GlyphMask.isLed(x, y)) continue
            val v = frame?.get(y * GlyphMask.SIZE + x) ?: 0
            // 0% → #1C1C1C, 100% → #FFFFFF
            val g = 0x1C + v * (0xFF - 0x1C) / 255
            led.color = Color.rgb(g, g, g)
            val l = origin + x * pitch
            val t = origin + y * pitch
            canvas.drawRoundRect(l, t, l + ledSize, t + ledSize, radius, radius, led)
        }
    }
}
