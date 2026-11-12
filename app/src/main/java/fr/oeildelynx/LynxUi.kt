package fr.oeildelynx

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes

/**
 * Système visuel partagé : couleurs, typographie et composants (en-têtes, boutons, cartes,
 * pastilles d’état, feuille de menu). Tous les écrans sont construits en code avec ces éléments.
 */
internal object LynxUi {
    // Palette « forêt de nuit » : fonds vert très sombre, accent vert lynx.
    const val BACKGROUND = 0xff0b110e.toInt()
    const val SURFACE = 0xff141c17.toInt()
    const val CARD = 0xff19231d.toInt()
    const val CARD_HIGH = 0xff223029.toInt()
    const val OUTLINE = 0xff2b3a31.toInt()
    const val TEXT = 0xfff1f5f2.toInt()
    const val MUTED = 0xff98a79e.toInt()
    const val ACCENT = 0xff7fdca4.toInt()
    const val ACCENT_STRONG = 0xff2f8f63.toInt()
    const val ON_ACCENT = 0xff06210f.toInt()
    const val DANGER = 0xffe5484d.toInt()
    const val DANGER_SOFT = 0xff3a1d1f.toInt()
    const val WARNING = 0xfff0b44c.toInt()
    const val SCRIM = 0xcc0b110e.toInt()

    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun rounded(context: Context, color: Int, radiusDp: Int = 16) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = dp(context, radiusDp).toFloat(); setColor(color)
    }

    fun outlined(context: Context, color: Int, stroke: Int, radiusDp: Int = 16) = rounded(context, color, radiusDp).apply {
        setStroke(dp(context, 1), stroke)
    }

    fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    /** Fond avec retour visuel au toucher. */
    fun pressable(context: Context, color: Int, radiusDp: Int = 16) =
        RippleDrawable(ColorStateList.valueOf(0x33ffffff), rounded(context, color, radiusDp), rounded(context, Color.WHITE, radiusDp))

    fun pressableCircle(color: Int) =
        RippleDrawable(ColorStateList.valueOf(0x33ffffff), circle(color), circle(Color.WHITE))

    fun icon(context: Context, @DrawableRes res: Int, tint: Int = TEXT, sizeDp: Int = 24) = ImageView(context).apply {
        setImageResource(res); imageTintList = ColorStateList.valueOf(tint)
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
    }

    /** Bouton rond à icône (barres d’outils, HUD). */
    fun iconButton(context: Context, @DrawableRes res: Int, description: String, background: Int = 0x40ffffff, sizeDp: Int = 44, action: () -> Unit) =
        ImageView(context).apply {
            setImageResource(res); imageTintList = ColorStateList.valueOf(TEXT); contentDescription = description
            val pad = dp(context, (sizeDp - 22) / 2); setPadding(pad, pad, pad, pad)
            this.background = pressableCircle(background)
            layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
            setOnClickListener { action() }
        }

    fun title(context: Context, text: String, size: Float = 22f) = TextView(context).apply {
        this.text = text; textSize = size; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setTextColor(TEXT)
    }

    /** Petit titre de section en capitales, couleur d’accent. */
    fun overline(context: Context, text: String, color: Int = ACCENT) = TextView(context).apply {
        this.text = text.uppercase(); textSize = 11f; letterSpacing = .12f; setTextColor(color)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    /** En-tête : retour, titre et sous-titre. [trailing] ajoute des boutons à droite. */
    fun header(context: Context, title: String, subtitle: String, vararg trailing: View, onBack: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(context, 12), dp(context, 28), dp(context, 16), dp(context, 14))
        addView(iconButton(context, R.drawable.ic_back, "Retour", background = 0x00000000) { onBack() })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(context, 6), 0, 0, 0)
            addView(title(context, title))
            addView(overline(context, subtitle, MUTED).apply { textSize = 10.5f; setPadding(0, dp(context, 3), 0, 0); maxLines = 1 })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        trailing.forEach { view ->
            addView(view, LinearLayout.LayoutParams(view.layoutParams?.width ?: -2, view.layoutParams?.height ?: -2).apply { leftMargin = dp(context, 8) })
        }
    }

    enum class Style { PRIMARY, SECONDARY, DANGER }

    /** Bouton pleine largeur ; la couleur historique choisit le style (accent, carte, danger). */
    fun button(context: Context, label: String, color: Int = ACCENT_STRONG, @DrawableRes iconRes: Int = 0, action: () -> Unit): View {
        val style = when (color) { DANGER, 0xff9e3939.toInt() -> Style.DANGER; CARD, SURFACE, CARD_HIGH -> Style.SECONDARY; else -> Style.PRIMARY }
        val (bg, fg) = when (style) {
            Style.PRIMARY -> ACCENT to ON_ACCENT
            Style.SECONDARY -> CARD_HIGH to TEXT
            Style.DANGER -> DANGER_SOFT to 0xffff8a8d.toInt()
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            minimumHeight = dp(context, 52)
            setPadding(dp(context, 18), dp(context, 14), dp(context, 18), dp(context, 14))
            background = pressable(context, bg, 26)
            contentDescription = label
            if (iconRes != 0) addView(icon(context, iconRes, fg, 20).apply {
                (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(context, 10)
            })
            addView(TextView(context).apply {
                text = label; textSize = 15f; setTextColor(fg); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            setOnClickListener { action() }
        }
    }

    fun body(context: Context, text: String, size: Float = 14f, color: Int = MUTED) = TextView(context).apply {
        this.text = text; textSize = size; setTextColor(color); setLineSpacing(0f, 1.2f)
    }

    /** Carte : fond légèrement surélevé, coins arrondis. */
    fun card(context: Context, paddingDp: Int = 16) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(context, paddingDp); setPadding(pad, pad, pad, pad)
        background = rounded(context, CARD, 20)
    }

    /** Ligne de réglage : icône, titre, valeur courante, chevron. */
    fun row(
        context: Context, @DrawableRes iconRes: Int, title: String, subtitle: String? = null,
        danger: Boolean = false, chevron: Boolean = true, action: () -> Unit
    ) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(context, 14), dp(context, 12), dp(context, 12), dp(context, 12))
        minimumHeight = dp(context, 60)
        background = pressable(context, 0x00000000, 14)
        val tint = if (danger) DANGER else ACCENT
        addView(ImageView(context).apply {
            setImageResource(iconRes); imageTintList = ColorStateList.valueOf(tint)
            val pad = dp(context, 8); setPadding(pad, pad, pad, pad)
            background = rounded(context, if (danger) DANGER_SOFT else 0xff1f3328.toInt(), 12)
        }, LinearLayout.LayoutParams(dp(context, 38), dp(context, 38)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(context, 14), 0, dp(context, 8), 0)
            addView(TextView(context).apply { text = title; textSize = 15f; setTextColor(if (danger) 0xffff8a8d.toInt() else TEXT) })
            if (!subtitle.isNullOrBlank()) addView(TextView(context).apply {
                text = subtitle; textSize = 12.5f; setTextColor(MUTED); setPadding(0, dp(context, 2), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (chevron) addView(icon(context, R.drawable.ic_chevron_right, MUTED, 18))
        setOnClickListener { action() }
    }

    /** Pastille d’état : point coloré + libellé. */
    fun chip(context: Context, text: String, dotColor: Int, background: Int = 0x66101713): Pair<LinearLayout, Pair<View, TextView>> {
        val dot = View(context).apply { this.background = circle(dotColor) }
        val label = TextView(context).apply {
            this.text = text; textSize = 11f; letterSpacing = .06f; setTextColor(TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val chip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 11), dp(context, 7), dp(context, 12), dp(context, 7))
            this.background = rounded(context, background, 20)
            addView(dot, LinearLayout.LayoutParams(dp(context, 8), dp(context, 8)).apply { rightMargin = dp(context, 7) })
            addView(label)
        }
        return chip to (dot to label)
    }

    class SheetItem(@DrawableRes val icon: Int, val title: String, val subtitle: String? = null, val danger: Boolean = false, val action: () -> Unit)

    /** Feuille de menu ancrée en bas de l’écran, dans le style de l’application. */
    fun sheet(activity: Activity, title: String, items: List<SheetItem>, sections: Map<Int, String> = emptyMap()): Dialog {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 10), dp(activity, 10), dp(activity, 10), dp(activity, 24))
            background = GradientDrawable().apply {
                setColor(SURFACE)
                val r = dp(activity, 28).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        list.addView(View(activity).apply { background = rounded(activity, OUTLINE, 3) },
            LinearLayout.LayoutParams(dp(activity, 36), dp(activity, 4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(activity, 14) })
        list.addView(title(activity, title, 18f).apply { setPadding(dp(activity, 14), 0, 0, dp(activity, 6)) })
        items.forEachIndexed { index, item ->
            sections[index]?.let { list.addView(overline(activity, it, MUTED).apply { setPadding(dp(activity, 14), dp(activity, 14), 0, dp(activity, 4)) }) }
            list.addView(row(activity, item.icon, item.title, item.subtitle, item.danger) { dialog.dismiss(); item.action() })
        }
        dialog.setContentView(ScrollView(activity).apply { addView(list) })
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        return dialog
    }
}
