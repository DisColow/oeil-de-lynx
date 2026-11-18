package fr.oeildelynx

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import androidx.annotation.DrawableRes
import android.graphics.Bitmap
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/** Affiche le direct d’une caméra appairée, en plein écran, avec reconnexion automatique. */
class LiveActivity : ComponentActivity(), LiveClient.Listener {
    private lateinit var image: ImageView
    private lateinit var title: TextView
    private lateinit var state: TextView
    private lateinit var recBadge: View
    private lateinit var waiting: View
    private lateinit var waitingText: TextView
    private lateinit var stateDot: View
    private var client: LiveClient? = null
    private var shownBitmap: Bitmap? = null
    private var lastStatus: Map<String, String> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveMode()
        // Le téléphone spectateur reste allumé pendant qu’on regarde.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val dp = { value: Int -> LynxUi.dp(this, value) }
        val cameraId = intent.getStringExtra(EXTRA_CAMERA_ID)
        val camera = cameraId?.let { ViewerStore.find(this, it) }
        if (camera == null) { finish(); return }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(image, FrameLayout.LayoutParams(-1, -1))
        // Toucher l’image : la caméra règle sa luminosité sur cette zone (comme l’appareil photo).
        @Suppress("ClickableViewAccessibility")
        image.setOnTouchListener { _, event -> if (event.actionMasked == android.view.MotionEvent.ACTION_UP) meterAt(event.x, event.y); true }
        meterMarker = View(this).apply {
            background = GradientDrawable().apply { setStroke(LynxUi.dp(this@LiveActivity, 2), LynxUi.WARNING); cornerRadius = LynxUi.dp(this@LiveActivity, 8).toFloat() }
            visibility = View.GONE
        }
        root.addView(meterMarker, FrameLayout.LayoutParams(dp(64), dp(64)))
        // En attente de la première image : œil de la marque + état de la connexion.
        waitingText = LynxUi.body(this, "Connexion à ${camera.name}…", 14f).apply { gravity = Gravity.CENTER; setPadding(dp(32), dp(18), dp(32), 0) }
        waiting = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(LynxUi.BACKGROUND)
            addView(ImageView(this@LiveActivity).apply {
                setImageResource(R.drawable.ic_cat_eye_monochrome); imageTintList = ColorStateList.valueOf(LynxUi.ACCENT)
                background = LynxUi.circle(0xff17261d.toInt())
            }, LinearLayout.LayoutParams(dp(88), dp(88)))
            addView(waitingText)
            // Caméra en veille : pas d’image, mais on peut la réveiller d’ici.
            standbyButton = LynxUi.button(this@LiveActivity, "Reprendre la surveillance", iconRes = R.drawable.ic_play) {
                client?.sendRequest(mapOf("op" to "standby", "value" to "0"))
                waitingText.text = "Réveil de la caméra…"
            }.apply { visibility = View.GONE }
            addView(standbyButton, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(22) })
        }
        root.addView(waiting, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(30), dp(14), dp(26))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xe60b110e.toInt(), 0x990b110e.toInt(), 0x000b110e))
        }
        top.addView(LynxUi.iconButton(this, R.drawable.ic_back, "Retour", 0x00000000) { finish() })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, dp(6), 0) }
        title = LynxUi.title(this, camera.name, 17f).apply { maxLines = 1 }
        stateDot = View(this).apply { background = LynxUi.circle(LynxUi.MUTED) }
        state = TextView(this).apply { text = "Connexion…"; textSize = 12f; setTextColor(LynxUi.MUTED); maxLines = 1 }
        texts.addView(title)
        texts.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(3), 0, 0)
            addView(stateDot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(6) })
            addView(state)
        })
        top.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        recBadge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(5), dp(11), dp(5)); background = LynxUi.rounded(this@LiveActivity, 0xd9200f10.toInt(), 14); visibility = View.GONE
            addView(View(this@LiveActivity).apply { background = LynxUi.circle(LynxUi.DANGER) }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(6) })
            addView(TextView(this@LiveActivity).apply { text = "REC"; textSize = 11.5f; letterSpacing = .12f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD })
        }
        top.addView(recBadge)
        val (battery, updateBattery) = LynxUi.batteryChip(this)
        showBattery = updateBattery
        top.addView(battery, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        top.addView(iconButton(R.drawable.ic_gallery, "Galerie de la caméra") {
            startActivity(Intent(this, RemoteGalleryActivity::class.java).putExtra(EXTRA_CAMERA_ID, camera.id))
        })
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // Bas de l’écran : bouton « Parler » (talkie-walkie), puis son, rotation et exposition.
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(30), dp(16), dp(22))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xe60b110e.toInt(), 0x990b110e.toInt(), 0x000b110e))
        }
        // « Parler » et « Sons » côte à côte : les deux font sortir un son sur la caméra.
        panel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(talkButton(), LinearLayout.LayoutParams(-2, dp(54)))
            addView(LinearLayout(this@LiveActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), 0, dp(20), 0)
                background = LynxUi.pressable(this@LiveActivity, LynxUi.CARD_HIGH, 27)
                contentDescription = "Jouer un son sur la caméra"
                addView(LynxUi.icon(this@LiveActivity, R.drawable.ic_volume, LynxUi.TEXT, 22).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(8) })
                addView(TextView(this@LiveActivity).apply { text = "Sons"; textSize = 15f; setTextColor(LynxUi.TEXT); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) })
                setOnClickListener { showSounds() }
            }, LinearLayout.LayoutParams(-2, dp(54)).apply { leftMargin = dp(10) })
        }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(18) })
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val sound = control(R.drawable.ic_volume, "Son") { setSound(!soundOn) }
        soundIcon = sound.second; soundLabel = sound.third
        bottom.addView(sound.first, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(control(R.drawable.ic_rotate, "Pivoter") { rotateImage() }.first, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(control(R.drawable.ic_sun, "Exposition") { showExposure() }.first, LinearLayout.LayoutParams(0, -2, 1f))
        val alarm = control(R.drawable.ic_bell, "Alarme") { toggleAlarm() }
        alarmIcon = alarm.second; alarmLabel = alarm.third
        bottom.addView(alarm.first, LinearLayout.LayoutParams(0, -2, 1f))
        val motion = control(R.drawable.ic_motion, "Continu") { setMotionOnly(!motionOnly) }
        motionIcon = motion.second; motionLabel = motion.third
        bottom.addView(motion.first, LinearLayout.LayoutParams(0, -2, 1f))

        // Mode « sur mouvement » sans mouvement : bandeau au-dessus de la dernière image reçue.
        motionWaiting = LynxUi.chip(this, "En attente d’un mouvement · « Sur mouvement » pour revenir au continu", LynxUi.WARNING, 0xcc101713.toInt()).first.apply { visibility = View.GONE }
        root.addView(motionWaiting, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dp(104) })
        panel.addView(bottom, LinearLayout.LayoutParams(-1, -2))
        root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)

        cameraKey = camera.id
        client = LiveClient(applicationContext, camera.id, this).apply {
            extraRotation = viewerPrefs.getInt("rotation_${camera.id}", 0)
            audioEnabled = viewerPrefs.getBoolean("audio_${camera.id}", true)
            motionOnly = viewerPrefs.getBoolean("motiononly_${camera.id}", false)
        }
        motionOnly = client?.motionOnly == true
        renderMotionOnly()
        soundOn = client?.audioEnabled == true
        renderSound()
    }

    private val micPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        Toast.makeText(this, if (granted) "Micro autorisé : maintiens « Parler » appuyé pour parler." else "Le micro est nécessaire pour parler à travers la caméra.", Toast.LENGTH_LONG).show()
    }
    private lateinit var talkLabel: TextView
    private lateinit var talkPill: LinearLayout

    /**
     * Talkie-walkie : tant que le bouton est maintenu, la voix part vers le haut-parleur du téléphone
     * caméra (volume au maximum là-bas). Relâcher arrête l’envoi.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun talkButton(): View {
        talkLabel = TextView(this).apply {
            text = "Maintenir pour parler"; textSize = 15f; setTextColor(LynxUi.ON_ACCENT)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        talkPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(LynxUi.dp(this@LiveActivity, 20), 0, LynxUi.dp(this@LiveActivity, 24), 0)
            background = LynxUi.rounded(this@LiveActivity, LynxUi.ACCENT, 27)
            contentDescription = "Parler à travers la caméra (maintenir)"
            addView(LynxUi.icon(this@LiveActivity, R.drawable.ic_mic, LynxUi.ON_ACCENT, 22).apply {
                (layoutParams as LinearLayout.LayoutParams).rightMargin = LynxUi.dp(this@LiveActivity, 10)
            })
            addView(talkLabel)
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> startTalking()
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> stopTalking()
                }
                true
            }
        }
        return talkPill
    }

    private fun startTalking() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(android.Manifest.permission.RECORD_AUDIO); return
        }
        if (client?.startTalking() != true) {
            Toast.makeText(this, "Impossible de parler : direct non connecté ou micro occupé.", Toast.LENGTH_SHORT).show(); return
        }
        talkLabel.text = "Vous parlez… relâcher pour arrêter"
        talkLabel.setTextColor(Color.WHITE)
        talkPill.background = LynxUi.rounded(this, LynxUi.DANGER, 27)
        (talkPill.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(Color.WHITE)
    }

    private fun stopTalking() {
        client?.stopTalking()
        if (!::talkLabel.isInitialized) return
        talkLabel.text = "Maintenir pour parler"
        talkLabel.setTextColor(LynxUi.ON_ACCENT)
        talkPill.background = LynxUi.rounded(this, LynxUi.ACCENT, 27)
        (talkPill.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(LynxUi.ON_ACCENT)
    }

    private var exposureReport: TextView? = null
    private lateinit var standbyButton: View
    private var showBattery: ((Int, Boolean) -> Unit)? = null
    private lateinit var meterMarker: View

    /** Point touché → coordonnées dans l’image de la caméra (rotation annulée) → commande « meter ». */
    private fun meterAt(x: Float, y: Float) {
        val drawable = image.drawable ?: return
        if (lastStatus.isEmpty()) return
        val dw = drawable.intrinsicWidth.toFloat(); val dh = drawable.intrinsicHeight.toFloat()
        if (dw <= 0f || dh <= 0f) return
        val scale = minOf(image.width / dw, image.height / dh)
        val left = (image.width - dw * scale) / 2; val top = (image.height - dh * scale) / 2
        val u = (x - left) / (dw * scale); val v = (y - top) / (dh * scale)
        if (u !in 0f..1f || v !in 0f..1f) return
        val (sx, sy) = MeteringZone.toSource(u, v, client?.lastFrameRotation ?: 0)
        client?.sendRequest(mapOf("op" to "meter", "x" to sx.toString(), "y" to sy.toString()))
        val size = LynxUi.dp(this, 64)
        meterMarker.layoutParams = (meterMarker.layoutParams as FrameLayout.LayoutParams).apply { leftMargin = (x - size / 2).toInt(); topMargin = (y - size / 2).toInt() }
        meterMarker.visibility = View.VISIBLE
        meterMarker.alpha = 1f
        meterMarker.animate().cancel()
        meterMarker.animate().alpha(.35f).setStartDelay(1500).setDuration(600).start()
        Toast.makeText(this, "Luminosité réglée sur cette zone · ☀ › Auto pour annuler", Toast.LENGTH_SHORT).show()
    }
    private lateinit var alarmIcon: ImageView
    private lateinit var alarmLabel: TextView

    /** Sirène de la caméra : confirmation avant de la déclencher, arrêt immédiat sinon. */
    private fun toggleAlarm() {
        if (lastStatus["alarm"] == "1") { client?.sendRequest(mapOf("op" to "alarm", "value" to "0")); return }
        if (lastStatus.isEmpty()) { Toast.makeText(this, "Direct non connecté.", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setTitle("Déclencher l’alarme ?")
            .setMessage("Une sirène stridente va retentir sur la caméra, volume au maximum, même en silencieux, pendant 2 minutes ou jusqu’à ce que tu l’arrêtes.")
            .setPositiveButton("Déclencher") { _, _ -> client?.sendRequest(mapOf("op" to "alarm", "value" to "1")) }
            .setNegativeButton("Annuler", null).show()
    }

    /**
     * Panneau de sons dissuasifs (feulement, bombe d’air, aboiements…) joués au volume maximum sur
     * la caméra. Il reste ouvert pour enchaîner plusieurs sons.
     */
    private fun showSounds() {
        if (lastStatus.isEmpty()) { Toast.makeText(this, "Direct non connecté.", Toast.LENGTH_SHORT).show(); return }
        val dp = { value: Int -> LynxUi.dp(this, value) }
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(24))
            background = GradientDrawable().apply { setColor(LynxUi.SURFACE); val r = dp(28).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
        }
        box.addView(View(this).apply { background = LynxUi.rounded(this@LiveActivity, LynxUi.OUTLINE, 3) },
            LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(14) })
        box.addView(LynxUi.title(this, "Sons sur la caméra", 18f))
        box.addView(LynxUi.body(this, "Joués au volume maximum, même en silencieux. Toucher pour jouer.", 12.5f).apply { setPadding(0, dp(4), 0, dp(12)) })
        val effects = DeterrentSounds.Effect.entries
        effects.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { effect ->
                row.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(12), dp(12))
                    background = LynxUi.pressable(this@LiveActivity, LynxUi.CARD_HIGH, 16)
                    contentDescription = effect.label
                    addView(TextView(this@LiveActivity).apply { text = effect.label; textSize = 14.5f; setTextColor(LynxUi.TEXT); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) })
                    addView(LynxUi.body(this@LiveActivity, effect.description, 11.5f).apply { setPadding(0, dp(3), 0, 0) })
                    setOnClickListener {
                        client?.sendRequest(mapOf("op" to "fx", "id" to effect.id))
                        // Retour visuel bref : le son part sur la caméra, rien ne se joue ici.
                        animate().cancel(); alpha = .5f; animate().alpha(1f).setDuration(500).start()
                    }
                }, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
            box.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        // Crédits obligatoires (licences libres des enregistrements, dont CC BY-SA).
        box.addView(TextView(this).apply {
            text = "Crédits des sons"; textSize = 13f; setTextColor(LynxUi.ACCENT); setPadding(dp(6), dp(14), dp(6), dp(4))
            setOnClickListener { showSoundCredits() }
        })
        dialog.setContentView(android.widget.ScrollView(this).apply { addView(box) })
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    private fun showSoundCredits() {
        AlertDialog.Builder(this).setTitle("Crédits des sons").setMessage(SOUND_CREDITS).setPositiveButton("OK", null).show()
    }

    private val viewerPrefs by lazy { getSharedPreferences("lynx_viewer", MODE_PRIVATE) }
    private var cameraKey = ""
    private var soundOn = true
    private lateinit var soundIcon: ImageView
    private lateinit var soundLabel: TextView

    /** Bouton rond + libellé, comme sur l’écran caméra. */
    private fun control(@DrawableRes icon: Int, label: String, action: () -> Unit): Triple<LinearLayout, ImageView, TextView> {
        val button = LynxUi.iconButton(this, icon, label, 0x66223029, 52) { action() }
        val text = TextView(this).apply { this.text = label; textSize = 11.5f; gravity = Gravity.CENTER; setTextColor(0xffd5ded8.toInt()); setPadding(0, LynxUi.dp(this@LiveActivity, 6), 0, 0) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; addView(button); addView(text) }
        return Triple(box, button, text)
    }

    private fun setSound(on: Boolean) {
        soundOn = on
        viewerPrefs.edit().putBoolean("audio_$cameraKey", on).apply()
        client?.audioEnabled = on
        renderSound()
    }

    private fun renderSound() {
        if (!::soundIcon.isInitialized) return
        soundIcon.setImageResource(if (soundOn) R.drawable.ic_volume else R.drawable.ic_volume_off)
        soundIcon.contentDescription = if (soundOn) "Couper le son" else "Activer le son"
        soundLabel.text = if (soundOn) "Son activé" else "Son coupé"
    }

    /** Tourne l’image d’un quart de tour (mémorisé pour cette caméra), au cas où la caméra se trompe de sens. */
    private fun rotateImage() {
        val next = ((client?.extraRotation ?: 0) + 90) % 360
        client?.extraRotation = next
        viewerPrefs.edit().putInt("rotation_$cameraKey", next).apply()
        Toast.makeText(this, if (next == 0) "Rotation d’origine" else "Image tournée de $next°", Toast.LENGTH_SHORT).show()
    }

    private fun iconButton(@DrawableRes icon: Int, description: String, action: () -> Unit) =
        LynxUi.iconButton(this, icon, description, 0x66223029) { action() }.apply {
            (layoutParams as LinearLayout.LayoutParams).leftMargin = LynxUi.dp(this@LiveActivity, 8)
        }

    /** Règle l’exposition de la caméra à distance ; la plage vient de l’état envoyé par la caméra. */
    private fun showExposure() {
        val status = lastStatus
        val min = status["expmin"]?.toIntOrNull(); val max = status["expmax"]?.toIntOrNull()
        val step = status["expstep"]?.toFloatOrNull() ?: 0f
        if (min == null || max == null) { Toast.makeText(this, "Connexion en cours, ou caméra à mettre à jour (1.7.0).", Toast.LENGTH_LONG).show(); return }
        if (max <= min) { Toast.makeText(this, "Cette caméra ne permet pas de régler l’exposition.", Toast.LENGTH_LONG).show(); return }
        val current = status["exp"]?.toIntOrNull() ?: 0
        val value = TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER; text = Exposure.label(current, step) }
        val seek = SeekBar(this).apply { this.max = max - min; progress = current - min }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) { value.text = Exposure.label(min + progress, step) }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            // Une seule commande au relâchement : évite d’inonder la connexion pendant le glissement.
            override fun onStopTrackingTouch(bar: SeekBar) { client?.sendRequest(mapOf("op" to "exposure", "value" to (min + bar.progress).toString())) }
        })
        val pad = LynxUi.dp(this, 20)
        // Ce que la caméra a réellement fait du réglage : mis à jour à chaque état reçu.
        val report = LynxUi.body(this, status["expreport"] ?: "", 12.5f).apply { setPadding(0, LynxUi.dp(this@LiveActivity, 10), 0, 0) }
        exposureReport = report
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0); addView(value); addView(seek); addView(report) }
        AlertDialog.Builder(this).setTitle("Exposition de la caméra").setView(content)
            .setOnDismissListener { exposureReport = null }
            .setNeutralButton("Auto") { _, _ ->
                // Tout automatique : correction à 0 et mesure de la lumière sur toute l’image.
                client?.sendRequest(mapOf("op" to "exposure", "value" to "0"))
                client?.sendRequest(mapOf("op" to "meter", "reset" to "1"))
                meterMarker.visibility = View.GONE
            }
            .setPositiveButton("OK", null).show()
    }

    override fun onStart() {
        super.onStart()
        if (::motionWaiting.isInitialized) { motionWaiting.removeCallbacks(motionWatch); motionWaiting.post(motionWatch) }
        client?.start()
    }

    override fun onStop() {
        if (::motionWaiting.isInitialized) motionWaiting.removeCallbacks(motionWatch)
        stopTalking()
        // Pas de flux en arrière-plan : économise la batterie et les données mobiles.
        client?.stop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        enableImmersiveMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveMode()
    }

    override fun onState(text: String, connected: Boolean) = runOnUiThread {
        state.text = text
        state.setTextColor(if (connected) LynxUi.ACCENT else LynxUi.MUTED)
        stateDot.background = LynxUi.circle(if (connected) LynxUi.ACCENT else LynxUi.WARNING)
        if (waiting.visibility == View.VISIBLE) waitingText.text = text
        if (!connected) recBadge.visibility = View.GONE
    }

    override fun onFrame(bitmap: Bitmap) = runOnUiThread {
        if (isDestroyed) { bitmap.recycle(); return@runOnUiThread }
        val previous = shownBitmap
        image.setImageBitmap(bitmap)
        shownBitmap = bitmap
        waiting.visibility = View.GONE
        previous?.takeIf { it !== bitmap }?.recycle()
        lastFrameAt = android.os.SystemClock.elapsedRealtime()
        motionWaiting.visibility = View.GONE
    }

    private var motionOnly = false
    private var lastFrameAt = 0L
    private lateinit var motionIcon: ImageView
    private lateinit var motionLabel: TextView
    private lateinit var motionWaiting: View
    private val motionWatch = object : Runnable {
        override fun run() {
            // Plus d’image depuis 2 s en mode « sur mouvement » : on l’indique, l’image reste la dernière reçue.
            val idle = motionOnly && lastFrameAt > 0 && android.os.SystemClock.elapsedRealtime() - lastFrameAt > 2_000
            motionWaiting.visibility = if (idle) View.VISIBLE else View.GONE
            motionWaiting.postDelayed(this, 1_000)
        }
    }

    private fun setMotionOnly(on: Boolean) {
        motionOnly = on
        viewerPrefs.edit().putBoolean("motiononly_$cameraKey", on).apply()
        client?.motionOnly = on
        renderMotionOnly()
        if (!on) {
            // Retour au direct continu : si aucune image n’arrive vite, on se reconnecte
            // (la connexion annonce alors le bon mode à la caméra, même si la commande s’est perdue).
            val askedAt = android.os.SystemClock.elapsedRealtime()
            motionWaiting.postDelayed({
                if (!motionOnly && !isDestroyed && lastFrameAt < askedAt) client?.reconnect()
            }, 3_000)
        }
        Toast.makeText(this, if (on) "Direct sur mouvement : images seulement quand la caméra détecte un mouvement." else "Direct continu.", Toast.LENGTH_SHORT).show()
    }

    private fun renderMotionOnly() {
        if (!::motionIcon.isInitialized) return
        motionIcon.background = LynxUi.pressableCircle(if (motionOnly) LynxUi.ACCENT_STRONG else 0x66223029)
        motionLabel.text = if (motionOnly) "Sur mouvement" else "Continu"
        motionIcon.contentDescription = if (motionOnly) "Direct seulement sur mouvement" else "Direct continu"
        if (!motionOnly) motionWaiting.visibility = View.GONE
    }

    override fun onStatus(fields: Map<String, String>) = runOnUiThread {
        lastStatus = fields
        val asleep = fields["standby"] == "1"
        if (::standbyButton.isInitialized) {
            standbyButton.visibility = if (asleep) View.VISIBLE else View.GONE
            if (asleep) {
                waiting.visibility = View.VISIBLE
                waitingText.text = if (fields["standbywhy"] == MonitorService.REASON_BATTERY)
                    "Caméra en veille : batterie faible. Elle reprendra seule une fois branchée." else "Caméra en veille : caméra et micro coupés."
            }
        }
        fields["bat"]?.toIntOrNull()?.let { showBattery?.invoke(it, fields["chg"] == "1") }
        if (fields["meter"] == "0" && ::meterMarker.isInitialized) meterMarker.visibility = View.GONE
        fields["expreport"]?.let { exposureReport?.text = it }
        val alarmOn = fields["alarm"] == "1"
        if (::alarmIcon.isInitialized) {
            alarmIcon.background = LynxUi.pressableCircle(if (alarmOn) LynxUi.DANGER else 0x66223029)
            alarmLabel.text = if (alarmOn) "Arrêter alarme" else "Alarme"
        }
        recBadge.visibility = if (fields["rec"] == "1") View.VISIBLE else View.GONE
        fields["n"]?.takeIf { it.isNotBlank() }?.let { title.text = it }
    }

    companion object {
        const val EXTRA_CAMERA_ID = "camera_id"

        /** Sources des enregistrements (Wikimedia Commons), découpés, amplifiés et convertis. */
        private const val SOUND_CREDITS =
            "Enregistrements issus de Wikimedia Commons, modifiés (découpe, amplification, conversion) :\n\n" +
            "• Feulement : « Cat hissing » — Zabuhailo — CC0\n" +
            "• Bombe d’air : « Can of compressed air » — stilgar — domaine public\n" +
            "• Aboiements : « Barking of a dog » — Amada44 — CC BY-SA 3.0 (creativecommons.org/licenses/by-sa/3.0)\n" +
            "• Coups à la porte : « Knock on door » — Asud12 — CC0\n" +
            "• Sifflet : « referee-whistle-blow-gymnasium » — SpliceSound — CC0\n" +
            "• Emballage froissé : « Food wrapper crumpling » — stilgar — domaine public\n" +
            "• Corne de brume : « Fog signal Bremerhaven » — Tvabutzku1234 — CC0\n\n" +
            "Les autres sons sont générés par l’application."
    }
}
