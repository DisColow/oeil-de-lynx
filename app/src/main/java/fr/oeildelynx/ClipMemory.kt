package fr.oeildelynx

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Mémoire des galeries : clips déjà regardés (lu / non lu) et position de défilement.
 * [scope] distingue la galerie locale ("local") de la galerie de chaque caméra ("cam_<id>").
 */
internal object ClipMemory {
    private fun prefs(context: Context) = context.getSharedPreferences("lynx_gallery", Context.MODE_PRIVATE)

    /**
     * Clips déjà vus. À la toute première ouverture (aucune mémoire enregistrée), tous les clips présents
     * sont considérés comme vus : seuls les suivants apparaîtront « non lus ».
     */
    fun seen(context: Context, scope: String, present: Collection<String>): MutableSet<String> {
        val key = "seen_$scope"
        val stored = prefs(context).getStringSet(key, null)
        val result = if (stored == null) present.toMutableSet() else stored.filter { it in present }.toMutableSet()
        // On oublie les clips supprimés pour que la mémoire ne grossisse pas indéfiniment.
        if (stored == null || result.size != stored.size) save(context, scope, result)
        return result
    }

    fun save(context: Context, scope: String, seen: Set<String>) = prefs(context).edit().putStringSet("seen_$scope", HashSet(seen)).apply()

    fun forget(context: Context, scope: String) = prefs(context).edit().remove("seen_$scope").remove("scroll_$scope").apply()

    /** Position de défilement : clip affiché en haut et son décalage en pixels. */
    fun saveScroll(context: Context, scope: String, firstKey: String?, offset: Int) {
        val edit = prefs(context).edit()
        if (firstKey == null) edit.remove("scroll_$scope") else edit.putString("scroll_$scope", "$offset|$firstKey")
        edit.apply()
    }

    fun scroll(context: Context, scope: String): Pair<String, Int>? {
        val raw = prefs(context).getString("scroll_$scope", null) ?: return null
        val offset = raw.substringBefore('|').toIntOrNull() ?: return null
        return raw.substringAfter('|') to offset
    }

    /** Pastille « Non lu » (accent) ou « Vu » (discrète). */
    fun chip(context: Context, seen: Boolean) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        val dp = { v: Int -> LynxUi.dp(context, v) }
        setPadding(dp(8), dp(3), dp(10), dp(3))
        background = LynxUi.rounded(context, if (seen) LynxUi.CARD_HIGH else LynxUi.ACCENT, 10)
        if (seen) addView(LynxUi.icon(context, R.drawable.ic_check, LynxUi.MUTED, 12))
        else addView(android.view.View(context).apply { background = LynxUi.circle(LynxUi.ON_ACCENT) }, LinearLayout.LayoutParams(dp(6), dp(6)))
        addView(TextView(context).apply {
            text = if (seen) "Vu" else "Non lu"; textSize = 11.5f; setPadding(dp(5), 0, 0, 0)
            setTextColor(if (seen) LynxUi.MUTED else LynxUi.ON_ACCENT); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        contentDescription = if (seen) "Déjà regardé" else "Pas encore regardé"
    }
}
