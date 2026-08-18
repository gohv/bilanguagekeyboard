// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.polyboard

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import kotlin.math.ceil

/**
 * Renders the language pair ("EN → FR") as a [Drawable] so it can be handed to the ordinary toolbar
 * `ImageButton` that upstream creates.
 *
 * This is the trick that keeps the language chip cheap: upstream's `createToolbarKey` always builds
 * an `ImageButton`, which cannot show text, and changing that would mean touching the toolbar
 * construction, pinning and customisation paths. Drawing the text into a Drawable instead means the
 * chip is a normal toolbar key that can be reordered, pinned and removed like every other one.
 */
class PairChipDrawable(
    private val label: String,
    color: Int,
    private val textSizePx: Float,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = textSizePx
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val bounds = Rect()

    init {
        paint.getTextBounds(label, 0, label.length, bounds)
    }

    override fun draw(canvas: Canvas) {
        val b = getBounds()
        // vertical centring on the text's own metrics, not the font's, so short labels sit true
        val baseline = b.centerY() - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(label, b.centerX().toFloat(), baseline, paint)
    }

    override fun getIntrinsicWidth(): Int = ceil(paint.measureText(label)).toInt() + (textSizePx / 2f).toInt()

    override fun getIntrinsicHeight(): Int = ceil(paint.fontSpacing).toInt()

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }

    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }

    @Deprecated("required by Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** e.g. "EN → FR" */
        fun label(source: String, target: String): String =
            source.uppercase() + " → " + target.uppercase()
    }
}
