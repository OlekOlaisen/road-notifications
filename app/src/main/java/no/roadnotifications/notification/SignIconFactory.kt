package no.roadnotifications.notification

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import no.roadnotifications.R
import no.roadnotifications.data.VegObjektEntity
import no.roadnotifications.data.VegObjektType

object SignIconFactory {
    private const val ICON_SIZE_PX = 192

    fun largeIconBitmap(context: Context, vegObjekt: VegObjektEntity): Bitmap {
        val signResId = signDrawableRes(vegObjekt)
        if (signResId != 0) {
            return drawableBitmap(context, signResId)
        }
        return when (vegObjekt.type) {
            VegObjektType.FART.name -> speedLimitBitmap(vegObjekt.verdi)
            else -> drawableBitmap(context, R.drawable.ic_sign_generic)
        }
    }

    fun smallIconRes(vegObjekt: VegObjektEntity): Int {
        val signResId = signDrawableRes(vegObjekt)
        if (signResId != 0) {
            return signResId
        }
        return when (vegObjekt.type) {
            VegObjektType.FART.name -> R.drawable.ic_notification_fart
            else -> R.drawable.ic_notification
        }
    }

    private fun signDrawableRes(vegObjekt: VegObjektEntity): Int {
        val verdi = vegObjekt.verdi?.trim().orEmpty()
        return when (vegObjekt.type) {
            VegObjektType.FART.name -> speedLimitDrawableRes(verdi)
            VegObjektType.FORKJOERSVEI.name -> R.drawable.sign_206
            VegObjektType.SLUTT_FORKJOERSVEI.name -> R.drawable.sign_208
            VegObjektType.STOPP.name -> R.drawable.sign_204
            VegObjektType.VIKEPLIKT.name -> R.drawable.sign_202
            VegObjektType.FARLIG_SVING.name -> farligSvingDrawableRes(verdi)
            VegObjektType.FARLIG_VEGKRYSS.name -> R.drawable.sign_124
            VegObjektType.SMALERE_VEG.name -> smalereVegDrawableRes(verdi)
            VegObjektType.TUNNEL.name -> R.drawable.sign_122
            VegObjektType.JERNBANE.name -> R.drawable.sign_134
            VegObjektType.FERJEKAI.name -> R.drawable.sign_120
            VegObjektType.VILTFARE.name -> wildlifeDrawableRes(verdi)
            VegObjektType.FOTOBOKS.name -> R.drawable.sign_556
            VegObjektType.STREKNINGS_ATK.name -> R.drawable.sign_556_2
            VegObjektType.BOM.name -> R.drawable.sign_792_30
            else -> 0
        }
    }

    private fun speedLimitDrawableRes(verdi: String): Int {
        val speedText = verdi.takeWhile { character -> character.isDigit() }
        return when (speedText) {
            "30" -> R.drawable.sign_362_30
            "40" -> R.drawable.sign_362_40
            "50" -> R.drawable.sign_362_50
            "60" -> R.drawable.sign_362_60
            "70" -> R.drawable.sign_362_70
            "80" -> R.drawable.sign_362_80
            "90" -> R.drawable.sign_362_90
            "100" -> R.drawable.sign_362_100
            "110" -> R.drawable.sign_362_110
            else -> 0
        }
    }

    private fun farligSvingDrawableRes(verdi: String): Int {
        return when (verdi) {
            "100.1" -> R.drawable.sign_100_1
            "100.2" -> R.drawable.sign_100_2
            "102.1" -> R.drawable.sign_102_1
            "102.2" -> R.drawable.sign_102_2
            else -> R.drawable.sign_100_1
        }
    }

    private fun smalereVegDrawableRes(verdi: String): Int {
        return when (verdi) {
            "106.2" -> R.drawable.sign_106_2
            "106.3" -> R.drawable.sign_106_3
            else -> R.drawable.sign_106_1
        }
    }

    private fun wildlifeDrawableRes(verdi: String): Int {
        return when (verdi.uppercase()) {
            "ELG" -> R.drawable.sign_146_1
            "HJORT" -> R.drawable.sign_146_2
            "REIN" -> R.drawable.sign_146_3
            "RADYR" -> R.drawable.sign_146_4
            else -> R.drawable.sign_146_5
        }
    }

    private fun speedLimitBitmap(verdi: String?): Bitmap {
        val speedText = verdi
            ?.trim()
            ?.takeWhile { character -> character.isDigit() }
            ?.ifBlank { "?" }
            ?: "?"
        val bitmap = Bitmap.createBitmap(ICON_SIZE_PX, ICON_SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val centerX = ICON_SIZE_PX / 2f
        val centerY = ICON_SIZE_PX / 2f
        val radius = ICON_SIZE_PX / 2f - 4f

        val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E30613")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(centerX, centerY, radius, redPaint)

        val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(centerX, centerY, radius * 0.72f, whitePaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = when (speedText.length) {
                1 -> radius * 1.05f
                2 -> radius * 0.95f
                else -> radius * 0.75f
            }
        }
        val textY = centerY - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(speedText, centerX, textY, textPaint)
        return bitmap
    }

    private fun drawableBitmap(context: Context, drawableRes: Int): Bitmap {
        val drawable = ContextCompat.getDrawable(context, drawableRes)
            ?: return speedLimitBitmap("?")
        return drawableToBitmap(drawable, ICON_SIZE_PX, ICON_SIZE_PX)
    }

    private fun drawableToBitmap(drawable: Drawable, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val intrinsicWidth = drawable.intrinsicWidth.coerceAtLeast(1)
        val intrinsicHeight = drawable.intrinsicHeight.coerceAtLeast(1)
        val scale = minOf(
            width / intrinsicWidth.toFloat(),
            height / intrinsicHeight.toFloat(),
        )
        val drawWidth = (intrinsicWidth * scale).toInt().coerceAtLeast(1)
        val drawHeight = (intrinsicHeight * scale).toInt().coerceAtLeast(1)
        val left = (width - drawWidth) / 2
        val top = (height - drawHeight) / 2
        drawable.setBounds(left, top, left + drawWidth, top + drawHeight)
        drawable.draw(canvas)
        return bitmap
    }
}
