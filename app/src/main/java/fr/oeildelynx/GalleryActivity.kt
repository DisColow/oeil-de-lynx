package fr.oeildelynx

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import android.content.ContentUris
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import android.graphics.Bitmap
import android.util.LruCache
import java.io.File
import java.util.concurrent.Executors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Galerie plein écran des clips partagés dans Movies et des anciens clips privés de l’application. */
class GalleryActivity : ComponentActivity() {
    /**
     * Les clips actuels utilisent une URI MediaStore. [legacyFile] ne sert qu’aux clips
     * enregistrés dans le dossier privé par les versions précédentes.
     */
    private data class Capture(
        val uri: Uri,
        val name: String,
        val startMs: Long,
        val durationMs: Long,
        val legacyFile: File? = null
    )

    private val captures = mutableListOf<Capture>()
    private val selected = linkedSetOf<String>()
    private lateinit var root: LinearLayout
    private lateinit var list: ListView
    private lateinit var deleteButton: View
    private lateinit var selectionCount: TextView
    private var player: ClipPlayer? = null
    private var listAdapter: BaseAdapter? = null
    private val background = LynxUi.BACKGROUND
    private val card = LynxUi.CARD
    private val accent = Color.rgb(140, 210, 162)
    private val thumbnailLoader = Executors.newSingleThreadExecutor()
    private var seen: MutableSet<String> = mutableSetOf()
    // Clip en lecture (navigation par flèches) ; null sur la liste.
    private var playingIndex = -1
    private var navigated = false
    // Suppressions refusées par Android (clips créés avant une réinstallation) : confirmation système.
    private val deleteRequest = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()) {
        reloadCaptures()
    }
    private val thumbnails = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, backFromPlayer)
        enableImmersiveMode()
        buildGallery()
        reloadCaptures()
        val focus = intent.getStringExtra(EXTRA_FOCUS_NAME)
        val index = focus?.let { name -> captures.indexOfFirst { it.name == name } } ?: -1
        if (index >= 0) list.setSelection(index) else restoreScroll()
    }

    override fun onPause() {
        if (player == null) saveScroll()
        super.onPause()
    }

    /** Mémorise le clip en haut de la liste (et son décalage) : retrouvé au retour de la lecture et à la prochaine ouverture. */
    private fun saveScroll() {
        if (!::list.isInitialized) return
        val first = list.firstVisiblePosition
        ClipMemory.saveScroll(this, SCOPE, captures.getOrNull(first)?.name, list.getChildAt(0)?.top ?: 0)
    }

    private fun restoreScroll() {
        val (name, offset) = ClipMemory.scroll(this, SCOPE) ?: return
        val index = captures.indexOfFirst { it.name == name }
        if (index >= 0) list.setSelectionFromTop(index, offset)
    }

    override fun onResume() {
        super.onResume()
        enableImmersiveMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveMode()
    }

    private fun buildGallery() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(this@GalleryActivity.background) }
        selectionCount = TextView(this).apply {
            textSize = 12f; setTextColor(LynxUi.ON_ACCENT); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; visibility = View.GONE
            setPadding(dp(12), dp(6), dp(12), dp(6)); background = LynxUi.rounded(this@GalleryActivity, LynxUi.ACCENT, 14)
        }
        val menu = LynxUi.iconButton(this, R.drawable.ic_more, "Plus d’options", 0x66223029) { showMenu() }
        root.addView(LynxUi.header(this, "Galerie", "Clips de surveillance", selectionCount, menu) { finish() })

        list = ListView(this).apply {
            divider = null; dividerHeight = dp(10); setPadding(dp(16), dp(2), dp(16), dp(16)); clipToPadding = false
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        }
        val empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(32), 0, dp(32), dp(60))
            addView(ImageView(this@GalleryActivity).apply {
                setImageResource(R.drawable.ic_gallery); imageTintList = android.content.res.ColorStateList.valueOf(LynxUi.ACCENT)
                val pad = dp(22); setPadding(pad, pad, pad, pad); background = LynxUi.circle(0xff17261d.toInt())
            }, LinearLayout.LayoutParams(dp(80), dp(80)))
            addView(LynxUi.title(this@GalleryActivity, "Aucun clip pour l’instant", 19f).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(6)) })
            addView(LynxUi.body(this@GalleryActivity, "Les vidéos enregistrées lors d’un mouvement apparaîtront ici.").apply { gravity = Gravity.CENTER })
        }
        (list.parent as? ViewGroup)?.removeView(list)
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(empty, LinearLayout.LayoutParams(-1, 0, 1f))
        list.emptyView = empty

        deleteButton = LynxUi.button(this, "Supprimer la sélection", LynxUi.DANGER, R.drawable.ic_trash) { confirmDelete() }.apply { visibility = View.GONE }
        root.addView(deleteButton, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(6), dp(16), dp(20)) })
        setContentView(root)
        listAdapter = createAdapter()
        list.adapter = listAdapter
    }

    private fun reloadCaptures() {
        captures.clear()
        // Interroge uniquement le dossier de l’application ; les autres vidéos de Movies sont exclues.
        val columns = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        try {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                columns,
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf("${android.os.Environment.DIRECTORY_MOVIES}/Œil de Lynx/"),
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    // Durée parfois encore inconnue (0) juste après l’enregistrement : la lire dans le fichier.
                    val stored = cursor.getLong(durationColumn)
                    val duration = if (stored > 0) stored else duration(uri)
                    captures.add(Capture(uri, cursor.getString(nameColumn) ?: "Vidéo", cursor.getLong(dateColumn) * 1000L, duration))
                }
            }
        } catch (_: Exception) { }

        // Conserve l’accès aux clips créés par les anciennes versions dans le dossier privé de l’application.
        val legacy = File(getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), "Evenements")
        legacy.listFiles { file -> file.isFile && file.extension.equals("mp4", true) }?.forEach { file ->
            val alreadyPublished = captures.any { it.name == file.name }
            if (!alreadyPublished) {
                val started = file.nameWithoutExtension.substringAfter("lynx_").toLongOrNull() ?: file.lastModified()
                captures.add(Capture(Uri.fromFile(file), file.name, started, duration(file), file))
            }
        }
        captures.sortByDescending { it.startMs }
        seen = ClipMemory.seen(this, SCOPE, captures.map { it.name })
        listAdapter?.notifyDataSetChanged()
    }

    private fun createAdapter() = object : BaseAdapter() {
        override fun getCount() = captures.size
        override fun getItem(position: Int) = captures[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val capture = captures[position]
            val key = capture.name
            val isSelected = selected.contains(key)
            val row = LinearLayout(this@GalleryActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(14), dp(10))
                background = if (isSelected) LynxUi.outlined(this@GalleryActivity, 0xff1d3326.toInt(), LynxUi.ACCENT, 20)
                    else LynxUi.rounded(this@GalleryActivity, LynxUi.CARD, 20)
            }
            // Miniature aux coins arrondis, avec un badge lecture (ou coche en sélection).
            val frame = FrameLayout(this@GalleryActivity).apply {
                background = LynxUi.rounded(this@GalleryActivity, 0xff222a25.toInt(), 14); clipToOutline = true
            }
            val thumb = ImageView(this@GalleryActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP; tag = key
            }
            bindThumbnail(thumb, capture)
            frame.addView(thumb, FrameLayout.LayoutParams(-1, -1))
            frame.addView(ImageView(this@GalleryActivity).apply {
                setImageResource(if (isSelected) R.drawable.ic_check else R.drawable.ic_play)
                imageTintList = android.content.res.ColorStateList.valueOf(if (isSelected) LynxUi.ON_ACCENT else Color.WHITE)
                val pad = dp(7); setPadding(pad, pad, pad, pad)
                background = LynxUi.circle(if (isSelected) LynxUi.ACCENT else 0x99000000.toInt())
            }, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
            if (capture.name !in seen && !isSelected) frame.addView(View(this@GalleryActivity).apply {
                background = LynxUi.circle(LynxUi.ACCENT)
            }, FrameLayout.LayoutParams(dp(10), dp(10), Gravity.TOP or Gravity.START).apply { setMargins(dp(7), dp(7), 0, 0) })
            row.addView(frame, LinearLayout.LayoutParams(dp(112), dp(80)))

            val details = LinearLayout(this@GalleryActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
            val day = SimpleDateFormat("EEE d MMM", Locale.FRANCE)
            val time = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)
            details.addView(TextView(this@GalleryActivity).apply {
                text = day.format(Date(capture.startMs)).replaceFirstChar { it.titlecase(Locale.FRANCE) }
                textSize = 15f; setTextColor(LynxUi.TEXT); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            details.addView(TextView(this@GalleryActivity).apply {
                text = "${time.format(Date(capture.startMs))} → ${time.format(Date(capture.startMs + capture.durationMs))}"
                textSize = 13f; setTextColor(LynxUi.MUTED); setPadding(0, dp(3), 0, 0)
            })
            val chips = LinearLayout(this@GalleryActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            chips.addView(LinearLayout(this@GalleryActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(3), dp(10), dp(3))
                background = LynxUi.rounded(this@GalleryActivity, 0xff1f3328.toInt(), 10)
                addView(LynxUi.icon(this@GalleryActivity, R.drawable.ic_timer, LynxUi.ACCENT, 13))
                addView(TextView(this@GalleryActivity).apply {
                    text = formatDuration(capture.durationMs); textSize = 11.5f; setTextColor(LynxUi.ACCENT); setPadding(dp(5), 0, 0, 0)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                })
            })
            chips.addView(ClipMemory.chip(this@GalleryActivity, capture.name in seen), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
            details.addView(chips, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
            row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            row.setOnClickListener {
                if (selected.isNotEmpty()) toggleSelection(capture)
                else play(capture)
            }
            row.setOnLongClickListener { toggleSelection(capture); true }
            return row
        }
    }

    /**
     * Miniatures chargées hors du thread principal et gardées en cache : la liste reste fluide.
     * On prend une image à ~1 s du début, la toute première étant souvent noire (capteur qui démarre).
     */
    private fun bindThumbnail(view: ImageView, capture: Capture) {
        thumbnails.get(capture.name)?.let { view.setImageBitmap(it); return }
        view.setImageDrawable(null)
        thumbnailLoader.execute {
            val bitmap = loadThumbnail(capture) ?: return@execute
            thumbnails.put(capture.name, bitmap)
            runOnUiThread { if (view.tag == capture.name) view.setImageBitmap(bitmap) }
        }
    }

    private fun loadThumbnail(capture: Capture): Bitmap? {
        val frame = try {
            MediaMetadataRetriever().use { retriever ->
                if (capture.legacyFile != null) retriever.setDataSource(capture.legacyFile.absolutePath)
                else retriever.setDataSource(this, capture.uri)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val atUs = minOf(1_000L, duration / 2).coerceAtLeast(0L) * 1000L
                retriever.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, dp(204), dp(152))
                    ?: retriever.getFrameAtTime(0)
            }
        } catch (_: Exception) { null }
        if (frame != null || capture.legacyFile != null) return frame
        // Dernier recours : miniature calculée par la médiathèque Android.
        return try { contentResolver.loadThumbnail(capture.uri, Size(dp(204), dp(152)), null) } catch (_: Exception) { null }
    }

    private fun toggleSelection(capture: Capture) {
        if (!selected.add(capture.name)) selected.remove(capture.name)
        deleteButton.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        selectionCount.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        selectionCount.text = "${selected.size} sélectionné(s)"
        listAdapter?.notifyDataSetChanged()
    }

    private fun confirmDelete() {
        // Supprimer une entrée MediaStore la retire de la liste et des applications Photos compatibles.
        AlertDialog.Builder(this).setTitle("Supprimer les vidéos ?")
            .setMessage("${selected.size} élément(s) seront supprimés définitivement.")
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Supprimer") { _: DialogInterface, _: Int ->
                val chosen = captures.filter { selected.contains(it.name) }
                selected.clear(); deleteButton.visibility = View.GONE; selectionCount.visibility = View.GONE
                deleteCaptures(chosen)
            }.show()
    }

    private fun showMenu() {
        LynxUi.sheet(this, "Galerie", listOf(
            LynxUi.SheetItem(R.drawable.ic_check, "Tout marquer comme vu", "${captures.count { it.name !in seen }} clip(s) non lu(s)") {
                seen.addAll(captures.map { it.name }); ClipMemory.save(this, SCOPE, seen); listAdapter?.notifyDataSetChanged()
            },
            LynxUi.SheetItem(R.drawable.ic_trash, "Supprimer tous les enregistrements", "${captures.size} clip(s)", danger = true) { confirmDeleteAll() }
        ))
    }

    private fun confirmDeleteAll() {
        if (captures.isEmpty()) { Toast.makeText(this, "La galerie est déjà vide.", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setTitle("Supprimer tous les enregistrements ?")
            .setMessage("Les ${captures.size} clip(s) de la galerie seront supprimés définitivement de ce téléphone (et de Photos). " +
                "Les clips présents sur d’autres téléphones ne sont pas touchés.")
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Tout supprimer") { _, _ ->
                selected.clear(); deleteButton.visibility = View.GONE; selectionCount.visibility = View.GONE
                deleteCaptures(captures.toList())
            }.show()
    }

    /**
     * Supprimer une entrée MediaStore la retire de la liste et des applications Photos compatibles.
     * Les clips qu’Android ne laisse pas supprimer directement (créés avant une réinstallation de
     * l’application) passent par la confirmation système.
     */
    private fun deleteCaptures(targets: List<Capture>) {
        val refused = mutableListOf<Uri>()
        targets.forEach { capture ->
            try {
                if (capture.legacyFile != null) capture.legacyFile.delete()
                else contentResolver.delete(capture.uri, null, null)
            } catch (_: SecurityException) { if (capture.legacyFile == null) refused += capture.uri } catch (_: Exception) { }
        }
        reloadCaptures()
        if (refused.isNotEmpty() && android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                val request = MediaStore.createDeleteRequest(contentResolver, refused)
                deleteRequest.launch(androidx.activity.result.IntentSenderRequest.Builder(request.intentSender).build())
            } catch (_: Exception) { }
        }
    }

    private fun play(capture: Capture) {
        if (player == null) { saveScroll(); navigated = false }
        playingIndex = captures.indexOf(capture)
        if (seen.add(capture.name)) ClipMemory.save(this, SCOPE, seen)
        player?.release()
        root.removeAllViews()
        val video = ClipPlayer(this).apply {
            setBackgroundColor(Color.BLACK)
            onError = { Toast.makeText(this@GalleryActivity, "Impossible de lire cette vidéo.", Toast.LENGTH_SHORT).show() }
        }
        player = video
        val position = if (playingIndex >= 0) " · ${playingIndex + 1}/${captures.size}" else ""
        root.addView(LynxUi.header(this, "Lecture", SimpleDateFormat("EEE d MMM · HH:mm:ss", Locale.FRANCE).format(Date(capture.startMs)) + position,
            LynxUi.iconButton(this, R.drawable.ic_rotate, "Pivoter la vidéo", 0x66223029) { video.rotateQuarter() }) { showList() })
        // Le lecteur occupe toute la place disponible et garde les proportions de la vidéo ;
        // flèches sur les bords : clip précédent (plus récent, à gauche) ou suivant (plus ancien, à droite), comme dans la liste.
        val stage = FrameLayout(this)
        stage.addView(video, FrameLayout.LayoutParams(-1, -1))
        LynxUi.clipArrows(this, stage, hasPrevious = playingIndex > 0, hasNext = playingIndex in 0 until captures.size - 1,
            onPrevious = { navigated = true; play(captures[playingIndex - 1]) },
            onNext = { navigated = true; play(captures[playingIndex + 1]) })
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        val info = TextView(this).apply {
            val formatter = SimpleDateFormat("EEEE d MMMM yyyy · HH:mm:ss", Locale.getDefault())
            text = "Début de la capture\n${formatter.format(Date(capture.startMs))}"
            textSize = 13f; setTextColor(0xffd1dad4.toInt()); gravity = Gravity.CENTER; setPadding(dp(16), dp(18), dp(16), dp(22))
        }
        root.addView(info)
        video.play(capture.uri)
    }

    private fun showList() {
        player?.release(); player = null
        val last = captures.getOrNull(playingIndex)?.name
        playingIndex = -1
        root.removeAllViews(); setContentView(root)
        buildGallery()
        reloadCaptures()
        // Retour à la même position ; après navigation par flèches, sur le dernier clip regardé.
        val index = if (navigated && last != null) captures.indexOfFirst { it.name == last } else -1
        if (index >= 0) list.setSelectionFromTop(index, dp(40)) else restoreScroll()
    }

    private fun duration(uri: Uri): Long = try {
        MediaMetadataRetriever().use { it.setDataSource(this, uri); it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L }
    } catch (_: Exception) { 0L }

    private fun duration(file: File): Long = try {
        MediaMetadataRetriever().use { it.setDataSource(file.absolutePath); it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L }
    } catch (_: Exception) { 0L }

    private fun formatDuration(millis: Long): String {
        val total = millis.coerceAtLeast(0) / 1000
        return String.format(Locale.getDefault(), "%02d:%02d", total / 60, total % 60)
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = radius.toFloat(); setColor(color) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** Retour (geste ou bouton) : depuis la lecture, revient à la liste ; sinon quitte la galerie. Compatible Android 16. */
    private val backFromPlayer = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() { if (player != null) showList() else finish() }
    }

    override fun onDestroy() { player?.release(); thumbnailLoader.shutdownNow(); super.onDestroy() }

    companion object {
        const val EXTRA_FOCUS_NAME = "focus_capture_name"
        private const val SCOPE = "local"
    }
}
