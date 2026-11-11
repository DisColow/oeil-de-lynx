package fr.oeildelynx

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** Petits éléments visuels partagés par les écrans du direct, dans le style du HUD. */
internal object LynxUi {
    const val BACKGROUND = 0xff0f1411.toInt()
    const val CARD = 0xff1b231e.toInt()
    const val ACCENT = 0xff8cd2a2.toInt()
    const val MUTED = 0xffaab5ae.toInt()
    const val DANGER = 0xff9e3939.toInt()

    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun rounded(context: Context, color: Int, radiusDp: Int = 14) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = dp(context, radiusDp).toFloat(); setColor(color)
    }

    /** En-tête avec retour, titre et sous-titre en capitales comme la galerie. */
    fun header(context: Context, title: String, subtitle: String, onBack: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(context, 16), dp(context, 24), dp(context, 16), dp(context, 14))
        addView(TextView(context).apply {
            text = "‹"; textSize = 36f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            contentDescription = "Retour"; setOnClickListener { onBack() }
        }, LinearLayout.LayoutParams(dp(context, 40), dp(context, 48)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(context, 8), 0, 0, 0)
            addView(TextView(context).apply { text = title; textSize = 23f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) })
            addView(TextView(context).apply { text = subtitle; textSize = 10f; letterSpacing = .14f; setTextColor(MUTED); setPadding(0, dp(context, 3), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    fun button(context: Context, label: String, color: Int = 0xff286b58.toInt(), action: () -> Unit) = TextView(context).apply {
        text = label; textSize = 15f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14))
        background = rounded(context, color); setOnClickListener { action() }
    }

    fun body(context: Context, text: String, size: Float = 14f, color: Int = MUTED) = TextView(context).apply {
        this.text = text; textSize = size; setTextColor(color); setLineSpacing(0f, 1.15f)
    }
}
