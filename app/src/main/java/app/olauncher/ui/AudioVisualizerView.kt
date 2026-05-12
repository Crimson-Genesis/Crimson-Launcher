package app.olauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class AudioVisualizerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }

    private val amplitudes = mutableListOf<Float>()
    private val maxBars = 120

    private var staticAmplitudes: FloatArray? = null
    private var playbackProgress: Float = 0f
    
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FFFFFF.toInt() // semi-transparent
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }

    fun addAmplitude(amp: Float) {
        val normalized = java.lang.Math.min(amp / 15000f, 1f) // Max 16-bit PCM value, lowered divisor to make it taller
        amplitudes.add(normalized)
        if (amplitudes.size > maxBars) {
            amplitudes.removeAt(0)
        }
        invalidate()
    }
    
    fun setStaticAmplitudes(amps: FloatArray) {
        staticAmplitudes = amps
        invalidate()
    }
    
    fun setPlaybackProgress(progress: Float) {
        playbackProgress = progress
        invalidate()
    }

    fun clear() {
        amplitudes.clear()
        staticAmplitudes = null
        playbackProgress = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val w = width.toFloat()
        val h = height.toFloat()
        val centerY = h / 2f
        
        if (staticAmplitudes != null) {
            val amps = staticAmplitudes!!
            val barWidth = w / amps.size
            val spacing = 0f
            val actualBarWidth = barWidth - spacing
            
            highlightPaint.strokeWidth = actualBarWidth
            basePaint.strokeWidth = actualBarWidth
            
            val highlightedCount = (amps.size * playbackProgress).toInt()
            
            for (i in amps.indices) {
                val x = (i * barWidth) + (barWidth / 2f)
                val amp = amps[i]
                val barHeight = (amp * centerY * 0.9f).coerceAtLeast(2f)
                
                val p = if (i < highlightedCount) highlightPaint else basePaint
                canvas.drawLine(x, centerY - barHeight, x, centerY + barHeight, p)
            }
        } else {
            if (amplitudes.isEmpty()) return
            
            val barWidth = w / maxBars
            val spacing = 0f
            val actualBarWidth = barWidth - spacing
            
            highlightPaint.strokeWidth = actualBarWidth
            basePaint.strokeWidth = actualBarWidth
            
            val startX = w - (amplitudes.size * barWidth)
            
            for (i in amplitudes.indices) {
                val x = startX + (i * barWidth) + (barWidth / 2f)
                val amp = amplitudes[i]
                
                // Bar height relative to half of the view height
                val barHeight = (amp * centerY * 0.9f).coerceAtLeast(2f)
                
                canvas.drawLine(x, centerY - barHeight, x, centerY + barHeight, highlightPaint)
            }
        }
    }
}
