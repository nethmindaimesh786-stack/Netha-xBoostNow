package com.netha.keyboardultra

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.random.Random

/** Small, touch-through particle layer. It stops drawing when detached or disabled. */
class FallingEffectView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val random = Random(31)
    private var effectName = "None"
    private var particles = mutableListOf<Particle>()
    private var frame = 0

    private data class Particle(
        var x: Float,
        var y: Float,
        var speed: Float,
        var drift: Float,
        val size: Float,
        val symbol: String,
        val color: Int
    )

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setEffect(name: String) {
        if (effectName == name) return
        effectName = name
        particles.clear()
        if (name != "None") createParticles()
        invalidate()
    }

    private fun createParticles() {
        val symbols = when (effectName) {
            "Flowers" -> listOf("✿", "❀", "✾")
            "Gems" -> listOf("◆", "◇", "♦")
            "Leaves" -> listOf("❧", "🍃")
            "Hearts" -> listOf("♥", "♡")
            "Roses" -> listOf("❀", "✿", "🌹")
            "Sakura" -> listOf("❁", "✿", "❀")
            "Sparkles" -> listOf("✦", "✧", "✩")
            else -> listOf("✦")
        }
        val colors = when (effectName) {
            "Gems" -> listOf(0xFF7DEBFF.toInt(), 0xFFC49AFF.toInt(), 0xFFFFE38A.toInt())
            "Leaves" -> listOf(0xFF8BE28E.toInt(), 0xFFB7E78A.toInt(), 0xFF4FBA86.toInt())
            "Hearts" -> listOf(0xFFFF7899.toInt(), 0xFFFFB0C4.toInt(), 0xFFFFE0E8.toInt())
            "Roses" -> listOf(0xFFFF8FAE.toInt(), 0xFFFFC0D1.toInt(), 0xFFFFE0E7.toInt())
            "Sakura", "Flowers" -> listOf(0xFFFFB9D5.toInt(), 0xFFFFE0ED.toInt(), 0xFFDDBBFF.toInt())
            else -> listOf(0xFFFFF3A6.toInt(), 0xFF9DEBFF.toInt(), 0xFFD7B4FF.toInt())
        }
        repeat(13) {
            particles.add(
                Particle(
                    x = random.nextFloat().coerceIn(0.03f, 0.97f),
                    y = random.nextFloat(),
                    speed = 0.0045f + random.nextFloat() * 0.0045f,
                    drift = (random.nextFloat() - 0.5f) * 0.0018f,
                    size = 13f + random.nextFloat() * 9f,
                    symbol = symbols[random.nextInt(symbols.size)],
                    color = colors[random.nextInt(colors.size)]
                )
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (effectName == "None" || width <= 0 || height <= 0) return
        frame++
        particles.forEach { particle ->
            particle.y += particle.speed
            particle.x += particle.drift
            if (particle.y > 1.06f) {
                particle.y = -0.06f
                particle.x = random.nextFloat().coerceIn(0.03f, 0.97f)
            }
            if (particle.x < 0.02f || particle.x > 0.98f) particle.drift *= -1f
            paint.color = particle.color
            paint.alpha = 205
            paint.textSize = particle.size * resources.displayMetrics.density
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(particle.symbol, particle.x * width, particle.y * height, paint)
        }
        // Keep requesting frames so particles continue falling smoothly.
        postInvalidateDelayed(33)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (effectName != "None") invalidate()
    }
}
