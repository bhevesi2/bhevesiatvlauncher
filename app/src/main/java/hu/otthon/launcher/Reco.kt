package hu.otthon.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.media.tv.TvContract
import android.net.Uri
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The streaming apps' recommendations, read the same way the Google TV home reads them: every
 * app publishes "preview channels" (rows of programmes) and a "Watch next" list to the TV's
 * listings provider. A launcher may read the searchable ones with READ_TV_LISTINGS.
 */
object Reco {
    data class Program(
        val id: Long,
        val pkg: String,
        val title: String,
        val subtitle: String,
        val description: String,
        val image: String?,
        /** Width / height of the picture. */
        val aspect: Float,
        val intent: String?,
        val progress: Float,
        val weight: Int
    )

    data class Row(val key: String, val pkg: String, val title: String, val programs: List<Program>, val link: String?)

    const val PERMISSION = "android.permission.READ_TV_LISTINGS"

    fun allowed(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    private fun Cursor.str(col: String): String? {
        val i = getColumnIndex(col)
        return if (i < 0 || isNull(i)) null else getString(i)
    }

    private fun Cursor.long(col: String): Long {
        val i = getColumnIndex(col)
        return if (i < 0 || isNull(i)) 0L else getLong(i)
    }

    private fun Cursor.int(col: String, def: Int): Int {
        val i = getColumnIndex(col)
        return if (i < 0 || isNull(i)) def else getInt(i)
    }

    /** Runs a query, trying a shorter list of columns if the provider refuses some of them. */
    private fun query(ctx: Context, uri: Uri, columns: Array<String>, optional: Array<String>): Cursor? {
        val cr = ctx.contentResolver
        return try {
            cr.query(uri, columns + optional, null, null, null)
        } catch (e: Exception) {
            try { cr.query(uri, columns, null, null, null) } catch (e2: Exception) { null }
        }
    }

    private fun aspect(code: Int): Float = when (code) {
        TvContract.PreviewPrograms.ASPECT_RATIO_3_2 -> 1.5f
        TvContract.PreviewPrograms.ASPECT_RATIO_4_3 -> 4f / 3f
        TvContract.PreviewPrograms.ASPECT_RATIO_1_1 -> 1f
        TvContract.PreviewPrograms.ASPECT_RATIO_2_3 -> 2f / 3f
        5 -> 1f / 1.441f   // ASPECT_RATIO_MOVIE_POSTER, added after API 26
        else -> 16f / 9f
    }

    private fun subtitle(c: Cursor): String {
        val parts = ArrayList<String>()
        val season = c.str(TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER)
        val episode = c.str(TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER)
        if (!season.isNullOrBlank() && !episode.isNullOrBlank()) parts.add("S$season E$episode")
        else if (!episode.isNullOrBlank()) parts.add("Episode $episode")
        c.str(TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE)?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        return parts.joinToString(" · ")
    }

    private val programColumns = arrayOf(
        TvContract.PreviewPrograms._ID,
        TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME,
        TvContract.PreviewPrograms.COLUMN_TITLE,
        TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE,
        TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
        TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
        TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
        TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
        TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI,
        TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
        TvContract.PreviewPrograms.COLUMN_INTENT_URI,
        TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS,
        TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS
    )

    private fun program(c: Cursor, weight: Int): Program? {
        val title = c.str(TvContract.PreviewPrograms.COLUMN_TITLE) ?: return null
        val pkg = c.str(TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME) ?: return null
        val dur = c.long(TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS)
        val pos = c.long(TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS)
        return Program(
            c.long(TvContract.PreviewPrograms._ID), pkg, title, subtitle(c),
            c.str(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION) ?: "",
            c.str(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI) ?: c.str(TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI),
            aspect(c.int(TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, 0)),
            c.str(TvContract.PreviewPrograms.COLUMN_INTENT_URI),
            if (dur > 0 && pos > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f,
            weight
        )
    }

    /**
     * Everything to show, apps in [order] first (the favorites), the rest after them.
     * Background thread only. Empty when the permission is missing or nothing is published.
     */
    fun load(ctx: Context, order: List<String>, hidden: Set<String>): List<Row> {
        if (!allowed(ctx)) return emptyList()
        val rows = ArrayList<Row>()

        // Continue watching
        query(
            ctx, TvContract.WatchNextPrograms.CONTENT_URI, programColumns,
            arrayOf(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS)
        )?.use { c ->
            val list = ArrayList<Pair<Long, Program>>()
            while (c.moveToNext()) {
                val p = program(c, 0) ?: continue
                if (p.pkg in hidden) continue
                list.add(Pair(c.long(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS), p))
            }
            val programs = list.sortedByDescending { it.first }.map { it.second }.distinctBy { it.pkg + it.title }.take(20)
            if (programs.isNotEmpty()) rows.add(Row("watchnext", "", "Continue watching", programs, null))
        }

        // the apps' own rows
        val channels = HashMap<Long, Triple<String, String, String?>>()
        query(
            ctx, TvContract.Channels.CONTENT_URI,
            arrayOf(TvContract.Channels._ID, TvContract.Channels.COLUMN_PACKAGE_NAME, TvContract.Channels.COLUMN_DISPLAY_NAME, TvContract.Channels.COLUMN_TYPE),
            arrayOf(TvContract.Channels.COLUMN_APP_LINK_INTENT_URI)
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.str(TvContract.Channels.COLUMN_TYPE) != TvContract.Channels.TYPE_PREVIEW) continue
                val pkg = c.str(TvContract.Channels.COLUMN_PACKAGE_NAME) ?: continue
                if (pkg in hidden) continue
                channels[c.long(TvContract.Channels._ID)] = Triple(
                    pkg, c.str(TvContract.Channels.COLUMN_DISPLAY_NAME) ?: "",
                    c.str(TvContract.Channels.COLUMN_APP_LINK_INTENT_URI)
                )
            }
        }
        val byChannel = HashMap<Long, ArrayList<Program>>()
        query(
            ctx, TvContract.PreviewPrograms.CONTENT_URI, programColumns,
            arrayOf(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, TvContract.PreviewPrograms.COLUMN_WEIGHT)
        )?.use { c ->
            while (c.moveToNext()) {
                val ch = c.long(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID)
                if (!channels.containsKey(ch)) continue
                val p = program(c, c.int(TvContract.PreviewPrograms.COLUMN_WEIGHT, 0)) ?: continue
                byChannel.getOrPut(ch) { ArrayList() }.add(p)
            }
        }
        val pm = ctx.packageManager
        val appRows = byChannel.mapNotNull { (ch, list) ->
            val (pkg, name, link) = channels[ch] ?: return@mapNotNull null
            val app = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
            val title = if (name.isBlank() || name.equals(app, true)) app else "$app  ·  $name"
            Row("ch:$ch", pkg, title, list.sortedByDescending { it.weight }.take(20), link)
        }
        fun rank(pkg: String) = order.indexOf(pkg).let { if (it < 0) Int.MAX_VALUE else it }
        rows.addAll(appRows.sortedWith(compareBy({ rank(it.pkg) }, { it.title.lowercase() })).take(14))
        return rows
    }

    /** Opens a programme in its app; falls back to the row's link, then to the app itself. */
    fun open(ctx: Context, p: Program?, row: Row): Boolean {
        val tries = ArrayList<Intent>()
        p?.intent?.let { u -> try { tries.add(Intent.parseUri(u, Intent.URI_INTENT_SCHEME)) } catch (e: Exception) { } }
        row.link?.let { u -> try { tries.add(Intent.parseUri(u, Intent.URI_INTENT_SCHEME)) } catch (e: Exception) { } }
        val pkg = p?.pkg ?: row.pkg
        ctx.packageManager.getLeanbackLaunchIntentForPackage(pkg)?.let { tries.add(it) }
        for (i in tries) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                return true
            } catch (e: Exception) { }
        }
        return false
    }

    // ---------- pictures ----------

    private val pictures = object : LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 80
    }

    fun cached(uri: String): Bitmap? = synchronized(pictures) { pictures[uri] }

    /** A programme's picture, made about [width] pixels wide. Background thread only. */
    fun picture(ctx: Context, uri: String, width: Int): Bitmap? {
        cached(uri)?.let { return it }
        fun open(): InputStream? {
            return if (uri.startsWith("http")) {
                val c = URL(uri).openConnection() as HttpURLConnection
                c.connectTimeout = 5000
                c.readTimeout = 10000
                if (c.responseCode != 200) { c.disconnect(); null } else c.inputStream
            } else {
                ctx.contentResolver.openInputStream(Uri.parse(uri))
            }
        }
        val bytes = try { open()?.use { it.readBytes() } } catch (e: Exception) { null } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width) sample *= 2
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        synchronized(pictures) { pictures[uri] = b }
        return b
    }
}

/** One recommendation: its picture, a progress line for things already started, glowing when focused. */
class RecoCard(ctx: Context, val program: Reco.Program, val row: Reco.Row, glow: Int) : FrameLayout(ctx) {
    val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val glass = rounded(0x33FFFFFF, ctx.dp(8f).toFloat())
    private val progress = program.progress
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isFocusable = true
        isClickable = true
        clipToOutline = true
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        background = glass
        setWillNotDraw(false)
        addView(image, LayoutParams(-1, -1))
        // the title stands in until the picture arrives
        val name = ctx.label(program.title, 11f, C.TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setPadding(ctx.dp(8f), ctx.dp(6f), ctx.dp(8f), ctx.dp(6f))
        }
        addView(name, LayoutParams(-1, -1))
        contentDescription = program.title
        FocusGlide.shape(this, ctx.dp(8f).toFloat(), 0f)
        FocusGlide.glow(this, glow)
        setOnFocusChangeListener { v, f ->
            v.animate().scaleX(if (f) 1.1f else 1f).scaleY(if (f) 1.1f else 1f).setDuration(160).start()
        }
    }

    fun setPicture(b: Bitmap) {
        image.setImageBitmap(b)
        getChildAt(1)?.visibility = View.GONE
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (progress > 0.01f) {
            val h = context.dp(3f).toFloat()
            bar.color = 0x66000000
            canvas.drawRect(0f, height - h, width.toFloat(), height.toFloat(), bar)
            bar.color = 0xFFE5484D.toInt()
            canvas.drawRect(0f, height - h, width * progress, height.toFloat(), bar)
        }
    }
}

/** The rows of recommendations that the home screen slides up to. */
class RecoPanel(private val act: android.app.Activity) : LinearLayout(act) {
    /** Called with the focused card, or null when focus leaves the cards. */
    var onCard: ((RecoCard?) -> Unit)? = null
    var onAllow: (() -> Unit)? = null
    var onHold: ((RecoCard) -> Unit)? = null
    private var signature = ""
    val firstFocusable: View? get() = findFirst(this)

    init {
        orientation = VERTICAL
        clipChildren = false
    }

    private fun findFirst(v: View): View? {
        if (v.isFocusable && v !is LinearLayout) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findFirst(v.getChildAt(i))?.let { return it }
        return null
    }

    /** Asks for the permission instead of showing rows. */
    fun showAsk() {
        if (signature == "ask") return
        signature = "ask"
        removeAllViews()
        addView(act.label("Recommendations", 13f, C.TEXT, bold = true), LayoutParams(-2, -2).apply { leftMargin = act.dp(48f) })
        addView(act.label("The launcher may show the rows your streaming apps publish, like the Google TV home does.", 11f, C.TEXT2),
            LayoutParams(-2, -2).apply { leftMargin = act.dp(48f); topMargin = act.dp(4f) })
        val b = act.pill("Allow", null) { onAllow?.invoke() }
        addView(b, LayoutParams(-2, -2).apply { leftMargin = act.dp(48f); topMargin = act.dp(12f) })
    }

    fun showEmpty() {
        if (signature == "empty") return
        signature = "empty"
        removeAllViews()
        addView(act.label("Your apps have not published any recommendations yet.", 12f, C.TEXT2),
            LayoutParams(-2, -2).apply { leftMargin = act.dp(48f) })
    }

    fun show(rows: List<Reco.Row>, glowOf: (String) -> Int) {
        val sig = rows.joinToString("|") { r -> r.key + ":" + r.programs.joinToString(",") { it.id.toString() } }
        if (sig == signature) return
        signature = sig
        val focusedKey = (act.currentFocus as? RecoCard)?.let { it.row.key + "/" + it.program.id }
        removeAllViews()
        val side = act.dp(48f)
        val cardH = act.dp(112f)
        for (row in rows) {
            val head = LinearLayout(act).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            if (row.pkg.isNotEmpty()) {
                try {
                    val icon = act.packageManager.getApplicationIcon(row.pkg)
                    head.addView(ImageView(act).apply { setImageDrawable(icon) }, LayoutParams(act.dp(16f), act.dp(16f)).apply { rightMargin = act.dp(7f) })
                } catch (e: Exception) { }
            }
            head.addView(act.label(row.title, 12f, C.TEXT, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            addView(head, LayoutParams(-1, -2).apply {
                leftMargin = side
                rightMargin = side
                topMargin = if (childCount > 0) act.dp(10f) else 0
            })
            val scroller = RowScroller(act)
            scroller.setPadding(side, act.dp(9f), side, act.dp(9f))
            val line = LinearLayout(act).apply { orientation = HORIZONTAL; clipChildren = false }
            scroller.addView(line, FrameLayout.LayoutParams(-2, cardH))
            for (p in row.programs) {
                val glow = if (p.pkg.isNotEmpty()) glowOf(p.pkg) else 0xFFE9E7E2.toInt()
                val card = RecoCard(act, p, row, glow)
                card.setOnClickListener { if (!Reco.open(act, p, row)) android.widget.Toast.makeText(act, "Can't open this", android.widget.Toast.LENGTH_SHORT).show() }
                card.setOnLongClickListener { onHold?.invoke(card); true }
                card.onFocusChangeListener.let { old ->
                    card.setOnFocusChangeListener { v, f ->
                        old?.onFocusChange(v, f)
                        if (f) onCard?.invoke(card)
                    }
                }
                val w = (cardH * p.aspect.coerceIn(0.6f, 1.8f)).toInt()
                line.addView(card, LayoutParams(w, cardH).apply { if (line.childCount > 0) leftMargin = act.dp(12f) })
                p.image?.let { uri ->
                    val cached = Reco.cached(uri)
                    if (cached != null) {
                        card.setPicture(cached)
                    } else {
                        val app = act.applicationContext
                        Bg.run({ Reco.picture(app, uri, w) }) { b -> if (b != null) card.setPicture(b) }
                    }
                }
            }
            addView(scroller, LayoutParams(-1, -2))
        }
        if (focusedKey != null) {
            allCards().firstOrNull { it.row.key + "/" + it.program.id == focusedKey }?.requestFocus()
        }
    }

    fun allCards(): List<RecoCard> {
        val out = ArrayList<RecoCard>()
        fun walk(v: View) {
            if (v is RecoCard) out.add(v)
            else if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(this)
        return out
    }
}
