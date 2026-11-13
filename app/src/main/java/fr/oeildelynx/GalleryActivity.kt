package fr.oeildelynx

import android.app.Activity
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
class GalleryActivity : Activity() {
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
    private val thumbnails = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveMode()
        buildGallery()
        reloadCaptures()
        intent.getStringExtra(EXTRA_FOCUS_NAME)?.let { name ->
            val index = captures.indexOfFirst { it.name == name }
            if (index >= 0) list.setSelection(index)
        }
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
        root.addView(LynxUi.header(this, "Galerie", "Clips de surveillance", selectionCount) { finish() })

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
            details.addView(LinearLayout(this@GalleryActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(3), dp(10), dp(3))
                background = LynxUi.rounded(this@GalleryActivity, 0xff1f3328.toInt(), 10)
                addView(LynxUi.icon(this@GalleryActivity, R.drawable.ic_timer, LynxUi.ACCENT, 13))
                addView(TextView(this@GalleryActivity).apply {
                    text = formatDuration(capture.durationMs); textSize = 11.5f; setTextColor(LynxUi.ACCENT); setPadding(dp(5), 0, 0, 0)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                })
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
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
                captures.filter { selected.contains(it.name) }.forEach { capture ->
                    try {
                        if (capture.legacyFile != null) capture.legacyFile.delete()
                        else contentResolver.delete(capture.uri, null, null)
                    } catch (_: Exception) { }
                }
                selected.clear(); deleteButton.visibility = View.GONE; selectionCount.visibility = View.GONE
                reloadCaptures()
            }.show()
    }

    private fun play(capture: Capture) {
        player?.release()
        root.removeAllViews()
        val video = ClipPlayer(this).apply {
            setBackgroundColor(Color.BLACK)
            onError = { Toast.makeText(this@GalleryActivity, "Impossible de lire cette vidéo.", Toast.LENGTH_SHORT).show() }
        }
        player = video
        root.addView(LynxUi.header(this, "Lecture", SimpleDateFormat("EEE d MMM · HH:mm:ss", Locale.FRANCE).format(Date(capture.startMs)),
            LynxUi.iconButton(this, R.drawable.ic_rotate, "Pivoter la vidéo", 0x66223029) { video.rotateQuarter() }) { showList() })
        // Le lecteur occupe toute la place disponible et garde les proportions de la vidéo.
        root.addView(video, LinearLayout.LayoutParams(-1, 0, 1f))
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
        root.removeAllViews(); setContentView(root)
        buildGallery()
        reloadCaptures()
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

    override fun onBackPressed() {
        if (player != null) showList() else super.onBackPressed()
    }

    override fun onDestroy() { player?.release(); thumbnailLoader.shutdownNow(); super.onDestroy() }

    companion object { const val EXTRA_FOCUS_NAME = "focus_capture_name" }
}
