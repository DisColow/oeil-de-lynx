package fr.oeildelynx

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import androidx.annotation.DrawableRes
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

/**
 * HUD caméra et commandes. Le [MonitorService] de premier plan garde la session
 * caméra/enregistrement ; cette Activity ne possède que la surface d’aperçu visible.
 */
class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var mask: LinearLayout
    private lateinit var maskButtonIcon: ImageView
    private lateinit var maskButtonLabel: TextView
    private lateinit var previewControl: LinearLayout
    private lateinit var recordButtonIcon: ImageView
    private lateinit var recordInner: View
    private lateinit var recordRing: View
    private lateinit var statusDot: View
    private lateinit var recordButtonLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var recBadge: LinearLayout
    private var provider: ProcessCameraProvider? = null
    private var previewCamera: androidx.camera.core.Camera? = null
    // L’état de l’interface est conservé dans SharedPreferences si Android recrée l’Activity.
    private var surveillance = false
    private var previewShown = true
    private var frontCamera = false
    private var permissionForStart = false
    private var monitorReceiverRegistered = false
    private var liveViewers = 0
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("lynx", MODE_PRIVATE) }
    private val autoHide = Runnable { setPreviewVisible(false) }
    // Le service confirme l’état réel de CameraX ; le HUD se corrige aussi si la liaison échoue.
    private val monitorStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            surveillance = intent.getBooleanExtra(MonitorService.EXTRA_MONITORING_ACTIVE, false)
            frontCamera = prefs.getBoolean("front_camera", frontCamera)
            if (surveillance) {
                syncControls()
                showRecordingState()
                return
            }

            handler.removeCallbacks(autoHide)
            previewShown = true
            mask.visibility = View.GONE
            MonitorService.updatePreviewSurface(null)
            bindPreview()
            syncControls()
            intent.getStringExtra(MonitorService.EXTRA_MONITOR_MESSAGE)?.let { toast(it) }
        }
    }

    // Nombre de téléphones spectateurs qui regardent le direct, affiché dans la pastille d’état.
    private val liveViewersReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            liveViewers = intent.getIntExtra(MonitorService.EXTRA_LIVE_VIEWERS, 0)
            syncControls()
        }
    }

    // Demande les permissions ensemble, puis reprend l’action demandée par l’utilisateur.
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val granted = requiredPermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            if (permissionForStart) startMonitoring() else openPreview()
            permissionForStart = false
        } else Toast.makeText(this, "Autorise la caméra et le micro pour utiliser la surveillance.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Installe le splash AndroidX avant l’initialisation de l’Activity pour garder le même rendu dès API 29.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableImmersiveMode()
        frontCamera = prefs.getBoolean("front_camera", false)
        surveillance = prefs.getBoolean("monitoring_active", false)
        previewShown = !surveillance || prefs.getBoolean("preview_shown", false)
        buildHud()
        ContextCompat.registerReceiver(
            this, monitorStateReceiver, IntentFilter(MonitorService.ACTION_MONITOR_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, liveViewersReceiver, IntentFilter(MonitorService.ACTION_LIVE_VIEWERS_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        monitorReceiverRegistered = true
        if (surveillance) {
            // Rattache la nouvelle surface d’aperçu au service déjà actif après recréation de l’Activity.
            setPreviewVisible(previewShown, scheduleHide = previewShown)
            MonitorService.updatePreviewSurface(previewView.surfaceProvider)
            // Si Android a tué le service avec le processus, relance la surveillance voulue par l’utilisateur.
            ContextCompat.startForegroundService(
                this, Intent(this, MonitorService::class.java).putExtra(MonitorService.EXTRA_FRONT_CAMERA, frontCamera)
            )
            showRecordingState()
        } else openPreview()
    }

    /** Construit en code l’aperçu et les commandes pour occuper tout l’écran. */
    private fun buildHud() {
        val root = FrameLayout(this).apply { setBackgroundColor(LynxUi.BACKGROUND) }
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))

        // Le masque opaque recouvre l’image sans arrêter la session caméra de surveillance.
        mask = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(LynxUi.BACKGROUND); visibility = View.GONE
        }
        mask.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_eye_off); imageTintList = ColorStateList.valueOf(LynxUi.ACCENT)
            val pad = dp(22); setPadding(pad, pad, pad, pad); background = LynxUi.circle(0xff17261d.toInt())
        }, LinearLayout.LayoutParams(dp(84), dp(84)))
        mask.addView(LynxUi.title(this, "Aperçu masqué", 21f).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(6)) })
        mask.addView(LynxUi.body(this, "La surveillance continue : mouvements détectés\net clips enregistrés comme d’habitude.").apply { gravity = Gravity.CENTER })
        root.addView(mask, FrameLayout.LayoutParams(-1, -1))

        // Barre du haut : marque, état, menu.
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(34), dp(14), dp(28)); background = scrim(fromTop = true)
        }
        top.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_cat_eye_monochrome); imageTintList = ColorStateList.valueOf(LynxUi.ACCENT)
            background = LynxUi.rounded(this@MainActivity, 0xcc102018.toInt(), 12)
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        val branding = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(11), 0, dp(8), 0) }
        branding.addView(TextView(this).apply {
            text = "Œil de Lynx"; textSize = 17f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); maxLines = 1
        })
        branding.addView(TextView(this).apply { text = "Caméra de surveillance"; textSize = 11.5f; setTextColor(0xffc3cec7.toInt()); maxLines = 1 })
        top.addView(branding, LinearLayout.LayoutParams(0, -2, 1f))
        val (chip, parts) = LynxUi.chip(this, "PRÊT", LynxUi.ACCENT, 0x99101713.toInt())
        statusDot = parts.first; statusLabel = parts.second
        top.addView(chip)
        top.addView(LynxUi.iconButton(this, R.drawable.ic_tune, "Menu", 0x55101713) { showMenu() }.apply {
            (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(10)
        })
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // Pastille REC clignotante pendant la surveillance.
        recBadge = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(6), dp(14), dp(6))
            background = LynxUi.rounded(this@MainActivity, 0xd9200f10.toInt(), 20); visibility = View.GONE
        }
        recBadge.addView(View(this).apply { background = LynxUi.circle(LynxUi.DANGER) }, LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(8) })
        recBadge.addView(TextView(this).apply {
            text = "REC"; textSize = 12f; letterSpacing = .14f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        root.addView(recBadge, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dp(104) })

        // Barre du bas : galerie, grand bouton de surveillance, changement de caméra.
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(40), dp(20), dp(22)); background = scrim(fromTop = false)
        }
        val previewAction = cameraHudButton("Masquer l’aperçu") { togglePreview() }
        maskButtonIcon = previewAction.first; maskButtonLabel = previewAction.second; previewControl = previewAction.third
        bottom.addView(previewControl, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(18) })

        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
        controls.addView(sideButton(R.drawable.ic_gallery, "Galerie") { openGallery() }, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(recordButton(), LinearLayout.LayoutParams(-2, -2))
        controls.addView(sideButton(R.drawable.ic_flip_camera, "Avant / arrière") { switchCamera() }, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(controls, LinearLayout.LayoutParams(-1, -2))
        bottom.addView(TextView(this).apply {
            text = "Un clip est enregistré dès qu’un mouvement est détecté, jusqu’à 10 s après la fin du mouvement."
            textSize = 11.5f; gravity = Gravity.CENTER; setTextColor(0xffb3c0b8.toInt()); setPadding(dp(12), dp(16), dp(12), 0)
        })
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
        syncControls()
    }

    /** Dégradé sombre derrière les barres, pour rester lisible sur n’importe quelle image. */
    private fun scrim(fromTop: Boolean) = GradientDrawable(
        if (fromTop) GradientDrawable.Orientation.TOP_BOTTOM else GradientDrawable.Orientation.BOTTOM_TOP,
        intArrayOf(0xf20b110e.toInt(), 0xb30b110e.toInt(), 0x000b110e)
    )

    /** Bouton latéral rond avec libellé dessous. */
    private fun sideButton(@DrawableRes iconRes: Int, label: String, action: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        addView(LynxUi.iconButton(this@MainActivity, iconRes, label, 0x66223029, 56) { action() })
        addView(TextView(this@MainActivity).apply {
            text = label; textSize = 11.5f; gravity = Gravity.CENTER; setTextColor(0xffd5ded8.toInt()); maxLines = 1; setPadding(0, dp(7), 0, 0)
        })
    }

    /** Grand bouton Démarrer / Arrêter : anneau blanc, disque vert (prêt) ou rouge (surveillance). */
    private fun recordButton() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(10), 0, dp(10), 0)
        val ring = FrameLayout(this@MainActivity).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33ffffff); setStroke(dp(3), Color.WHITE) }
            contentDescription = "Démarrer la surveillance"
            setOnClickListener { toggleMonitoring() }
        }
        recordInner = View(this@MainActivity)
        ring.addView(recordInner, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
        recordButtonIcon = ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_play) }
        ring.addView(recordButtonIcon, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
        recordRing = ring
        addView(ring, LinearLayout.LayoutParams(dp(80), dp(80)))
        recordButtonLabel = TextView(this@MainActivity).apply {
            textSize = 12.5f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, dp(8), 0, 0)
        }
        addView(recordButtonLabel)
    }

    /** Pastille « Masquer / Afficher l’aperçu », utilisable pendant la surveillance. */
    private fun cameraHudButton(label: String, action: () -> Unit): Triple<ImageView, TextView, LinearLayout> {
        val iconView = AppCompatImageView(this).apply {
            setImageResource(R.drawable.ic_camera)
            contentDescription = label
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val labelView = TextView(this).apply {
            text = label; textSize = 13f; setTextColor(Color.WHITE); maxLines = 1
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(9), dp(18), dp(9))
            background = LynxUi.pressable(this@MainActivity, 0x80223029.toInt(), 22)
            addView(iconView, LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(9) }); addView(labelView)
            setOnClickListener { action() }
        }
        return Triple(iconView, labelView, container)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun requiredPermissions() = buildList {
        add(Manifest.permission.CAMERA); add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Lie une session CameraX limitée à l’aperçu avant le démarrage de la surveillance. */
    private fun openPreview() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionForStart = false; permissionRequest.launch(requiredPermissions().toTypedArray()); return
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({ try { provider = future.get(); bindPreview() } catch (_: Exception) { toast("Impossible d’ouvrir la caméra.") } }, ContextCompat.getMainExecutor(this))
    }

    private fun bindPreview() {
        val cameraProvider = provider ?: return
        try {
            cameraProvider.unbindAll()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            previewCamera = cameraProvider.bindToLifecycle(this, selector(), preview).also { Exposure.apply(this, it) }
        } catch (_: Exception) { toast("Impossible d’afficher l’aperçu caméra.") }
    }

    private fun selector() = if (frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

    private fun togglePreview() {
        if (!surveillance) return
        setPreviewVisible(!previewShown, scheduleHide = !previewShown)
    }

    /**
     * Masque ou affiche l’aperçu et met à jour la surface CameraX du service.
     * Le masque ne sert qu’à l’interface ; ImageAnalysis reste lié pour détecter les mouvements.
     */
    private fun setPreviewVisible(
        visible: Boolean,
        scheduleHide: Boolean = visible,
        hideDelayMs: Long = PREVIEW_TIMEOUT_MS
    ) {
        previewShown = visible
        prefs.edit().putBoolean("preview_shown", visible).apply()
        mask.visibility = if (visible) View.GONE else View.VISIBLE
        maskButtonIcon.setImageResource(if (visible) R.drawable.ic_camera else R.drawable.ic_camera_off)
        maskButtonIcon.contentDescription = if (visible) "Caméra visible" else "Caméra masquée"
        maskButtonLabel.text = if (visible) "Masquer l’aperçu" else "Afficher l’aperçu"
        MonitorService.updatePreviewSurface(previewView.surfaceProvider)
        handler.removeCallbacks(autoHide)
        if (visible && surveillance && scheduleHide) handler.postDelayed(autoHide, hideDelayMs)
    }

    private fun switchCamera() {
        frontCamera = !frontCamera
        prefs.edit().putBoolean("front_camera", frontCamera).apply()
        if (surveillance) {
            // Demande au service de délier puis relier CameraX sans détruire la session de surveillance.
            startService(Intent(this, MonitorService::class.java)
                .setAction(MonitorService.ACTION_SWITCH_CAMERA)
                .putExtra(MonitorService.EXTRA_FRONT_CAMERA, frontCamera))
        } else bindPreview()
    }

    private fun toggleMonitoring() {
        if (surveillance) {
            prefs.edit().putBoolean("monitoring_active", false).putBoolean("preview_shown", true).apply()
            stopService(Intent(this, MonitorService::class.java)); surveillance = false
            handler.removeCallbacks(autoHide); mask.visibility = View.GONE; previewShown = true
            MonitorService.updatePreviewSurface(null); bindPreview(); syncControls()
        } else if (requiredPermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) startMonitoring()
        else { permissionForStart = true; permissionRequest.launch(requiredPermissions().toTypedArray()) }
    }

    private fun startMonitoring() {
        if (!androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            toast("Les notifications d’Œil de Lynx sont désactivées : active-les dans les réglages Android pour être prévenu des mouvements.")
        }
        prefs.edit().putBoolean("monitoring_active", true).putBoolean("preview_shown", true).apply()
        provider?.unbindAll()
        // Transmet la surface PreviewView au service avant qu’il lie les cas d’usage CameraX.
        MonitorService.updatePreviewSurface(previewView.surfaceProvider)
        try {
            ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java).putExtra(MonitorService.EXTRA_FRONT_CAMERA, frontCamera))
        } catch (_: Exception) {
            prefs.edit().putBoolean("monitoring_active", false).putBoolean("preview_shown", true).apply()
            surveillance = false
            MonitorService.updatePreviewSurface(null)
            bindPreview()
            syncControls()
            toast("Impossible de démarrer la surveillance. Réessaie depuis l’application.")
            return
        }
        surveillance = true; setPreviewVisible(true, hideDelayMs = STARTUP_PREVIEW_TIMEOUT_MS); syncControls(); showRecordingState()
    }

    private fun syncControls() {
        if (!::recordButtonIcon.isInitialized) return
        recordButtonIcon.setImageResource(if (surveillance) R.drawable.ic_stop else R.drawable.ic_play)
        recordButtonIcon.imageTintList = ColorStateList.valueOf(if (surveillance) Color.WHITE else LynxUi.ON_ACCENT)
        recordInner.background = LynxUi.circle(if (surveillance) LynxUi.DANGER else LynxUi.ACCENT)
        recordRing.contentDescription = if (surveillance) "Arrêter la surveillance" else "Démarrer la surveillance"
        recordButtonLabel.text = if (surveillance) "Arrêter" else "Démarrer"
        previewControl.isEnabled = surveillance
        // Hors surveillance l’aperçu est toujours visible : la pastille n’a pas d’usage.
        previewControl.visibility = if (surveillance) View.VISIBLE else View.INVISIBLE
        if (!surveillance) {
            maskButtonIcon.setImageResource(R.drawable.ic_camera)
            maskButtonIcon.contentDescription = "Caméra visible"
            maskButtonLabel.text = "Masquer l’aperçu"
        }
        recBadge.visibility = if (surveillance) View.VISIBLE else View.GONE
        if (!surveillance) liveViewers = 0
        if (::statusLabel.isInitialized) statusLabel.text = when {
            surveillance && liveViewers > 0 -> "EN DIRECT · $liveViewers"
            surveillance -> "SURVEILLANCE"
            else -> "PRÊT"
        }
        if (::statusDot.isInitialized) statusDot.background = LynxUi.circle(when {
            surveillance && liveViewers > 0 -> LynxUi.WARNING
            surveillance -> LynxUi.DANGER
            else -> LynxUi.ACCENT
        })
    }

    private fun showRecordingState() {
        if (!surveillance) return
        recBadge.animate().cancel(); recBadge.alpha = 1f
        recBadge.animate().alpha(.35f).setDuration(650).withEndAction {
            if (surveillance) recBadge.animate().alpha(1f).setDuration(650).withEndAction { showRecordingState() }
        }
    }

    /** Menu Réglages : réglages des clips, appairage côté caméra et mode spectateur. */
    private fun showMenu() {
        val soundOn = prefs.getBoolean(MonitorService.PREF_SOUND_TRIGGER, false)
        val durations = mapOf(30_000 to "30 secondes", 90_000 to "1 min 30", 180_000 to "3 minutes", 300_000 to "5 minutes", 600_000 to "10 minutes")
        val items = listOf(
            LynxUi.SheetItem(R.drawable.ic_sun, "Exposition", Exposure.range(this).let { Exposure.label(it.index, it.step) }) { showExposure() },
            LynxUi.SheetItem(R.drawable.ic_motion, "Sensibilité au mouvement", MonitorService.motionSensitivity(this).label) { showSensitivity() },
            LynxUi.SheetItem(R.drawable.ic_mic, "Déclencher aussi sur le son", if (soundOn) "Oui" else "Non · mouvement seulement") {
                prefs.edit().putBoolean(MonitorService.PREF_SOUND_TRIGGER, !soundOn).apply()
                val change = if (soundOn) "Seul le mouvement déclenche un clip." else "Le son déclenche aussi un clip."
                toast(change + if (surveillance) " Appliqué au prochain démarrage de la surveillance." else "")
            },
            LynxUi.SheetItem(R.drawable.ic_timer, "Durée maximale des clips", "${durations[prefs.getInt("max_clip_ms", 600_000)] ?: "10 minutes"} · découpe les mouvements très longs") { showRules() },
            LynxUi.SheetItem(R.drawable.ic_qr, "Appairer un téléphone spectateur", "QR code pour le direct et les alertes") { startActivity(Intent(this, PairingActivity::class.java)) },
            LynxUi.SheetItem(R.drawable.ic_eye, "Regarder une caméra", "Ce téléphone devient spectateur") { startActivity(Intent(this, ViewerActivity::class.java)) }
        )
        LynxUi.sheet(this, "Réglages", items, sections = mapOf(0 to "Caméra et détection", 4 to "Direct entre téléphones"))
    }

    /** Curseur de correction d’exposition, appliqué en direct à l’aperçu ou à la surveillance. */
    private fun showExposure() {
        val range = Exposure.range(this)
        if (!range.supported) { toast("Cette caméra ne permet pas de régler l’exposition."); return }
        val value = TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(4)) }
        val seek = SeekBar(this).apply { max = range.max - range.min; progress = range.index - range.min }
        fun show(index: Int) { value.text = Exposure.label(index, range.step) }
        show(range.index)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val index = range.min + progress
                show(index)
                if (fromUser) applyExposure(index)
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), 0)
            addView(value); addView(seek)
            addView(TextView(this@MainActivity).apply {
                text = "Vers la gauche : image plus sombre. Vers la droite : plus claire (utile la nuit)."
                textSize = 12f; setPadding(0, dp(8), 0, 0)
            })
        }
        AlertDialog.Builder(this).setTitle("Exposition").setView(content)
            .setNeutralButton("Auto") { _, _ -> applyExposure(0) }
            .setPositiveButton("OK", null).show()
    }

    private fun applyExposure(index: Int) {
        Exposure.setIndex(this, index)
        if (surveillance) {
            startService(Intent(this, MonitorService::class.java).setAction(MonitorService.ACTION_SET_EXPOSURE).putExtra(MonitorService.EXTRA_EXPOSURE, index))
        } else previewCamera?.let { Exposure.apply(this, it) }
    }

    /** Part de l’image qui doit bouger : élevée = petit mouvement suffit, faible = moins de fausses alertes. */
    private fun showSensitivity() {
        val levels = MotionDetector.Sensitivity.entries
        val labels = arrayOf(
            "Faible · grands mouvements seulement",
            "Normale · une personne dans la pièce",
            "Élevée · petits mouvements, plus de fausses alertes"
        )
        AlertDialog.Builder(this).setTitle("Sensibilité au mouvement")
            .setSingleChoiceItems(labels, levels.indexOf(MonitorService.motionSensitivity(this))) { dialog, which ->
                prefs.edit().putString(MonitorService.PREF_SENSITIVITY, levels[which].name).apply(); dialog.dismiss()
            }.setNegativeButton("Fermer", null).show()
    }

    private fun showRules() {
        // Enregistre la durée en millisecondes ; le service relit cette limite pendant chaque clip.
        val durations = intArrayOf(30_000, 90_000, 180_000, 300_000, 600_000)
        val labels = arrayOf("30 secondes", "1 minute 30", "3 minutes", "5 minutes", "10 minutes")
        val selected = durations.indexOf(prefs.getInt("max_clip_ms", 600_000)).coerceAtLeast(0)
        // Explication au-dessus des choix : un message d’AlertDialog masquerait la liste, d’où une vue dédiée.
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(4), dp(24), 0) }
        content.addView(LynxUi.body(this,
            "Un clip s’arrête normalement 10 secondes après la fin du mouvement. Cette durée maximale ne sert que " +
                "si le mouvement ne s’arrête pas (passage prolongé, rideau ou arbre qui bouge, lumière qui clignote) : " +
                "le clip est alors coupé à cette durée, et un nouveau clip démarre aussitôt si le mouvement continue. " +
                "Rien n’est perdu, la vidéo est simplement découpée en plusieurs fichiers.", 13.5f, LynxUi.TEXT))
        content.addView(LynxUi.body(this,
            "Plus court : fichiers plus légers, plus faciles à consulter et à télécharger depuis le téléphone spectateur. " +
                "Plus long : moins de fichiers lors d’une longue activité. Un clip pèse environ 10 à 20 Mo par minute.", 12.5f)
            .apply { setPadding(0, dp(10), 0, dp(6)) })
        val group = RadioGroup(this)
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        labels.forEachIndexed { index, label ->
            group.addView(androidx.appcompat.widget.AppCompatRadioButton(this).apply {
                id = View.generateViewId(); text = label; textSize = 15f; setTextColor(LynxUi.TEXT)
                isChecked = index == selected; setPadding(dp(8), dp(10), 0, dp(10))
                setOnClickListener { prefs.edit().putInt("max_clip_ms", durations[index]).apply(); dialog.dismiss() }
            })
        }
        content.addView(group)
        dialog = AlertDialog.Builder(this).setTitle("Durée maximale des clips").setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("Fermer", null).show()
    }

    private fun openGallery() = startActivity(Intent(this, GalleryActivity::class.java))
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onResume() {
        super.onResume()
        enableImmersiveMode()
        surveillance = prefs.getBoolean("monitoring_active", surveillance)
        frontCamera = prefs.getBoolean("front_camera", frontCamera)
        syncControls()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveMode()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (monitorReceiverRegistered) {
            unregisterReceiver(monitorStateReceiver)
            unregisterReceiver(liveViewersReceiver)
        }
        super.onDestroy()
    }

    companion object {
        // Après Démarrer, l’aperçu initial reste visible moins longtemps qu’un aperçu réactivé manuellement.
        private const val STARTUP_PREVIEW_TIMEOUT_MS = 60_000L
        private const val PREVIEW_TIMEOUT_MS = 90_000L
    }
}
