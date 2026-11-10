package com.sanxis.fotosduplicadas

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.RecoverableSecurityException
import android.app.TimePickerDialog
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.TextUtils
import android.util.LruCache
import android.util.Size
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.AbsListView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridView
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.exifinterface.media.ExifInterface
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {

    companion object {
        const val REQ_PERM = 1
        const val REQ_TRASH = 2
        const val REQ_WRITE = 3
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    // ---------------------------------------------------------------- Paleta (claro / oscuro)

    private var dark = false
    private var cBg = 0
    private var cCard = 0
    private var cText = 0
    private var cSub = 0
    private var cAccent = 0
    private var cGreen = 0
    private var cRed = 0
    private var cLine = 0
    private var cTeal = 0
    private var cAmber = 0

    private fun initPalette() {
        dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        fun c(light: String, night: String) = Color.parseColor(if (dark) night else light)
        cBg = c("#F4F5FA", "#0F1115")
        cCard = c("#FFFFFF", "#1A1D24")
        cText = c("#15171C", "#EEF0F5")
        cSub = c("#6B7080", "#9AA0AE")
        cAccent = c("#4F46E5", "#818CF8")
        cGreen = c("#16A34A", "#22C55E")
        cRed = c("#DC2626", "#F87171")
        cLine = c("#E6E8F0", "#2A2E38")
        cTeal = c("#0D9488", "#2DD4BF")
        cAmber = c("#D97706", "#FBBF24")
    }

    // ---------------------------------------------------------------- Estado

    private val main = Handler(Looper.getMainLooper())
    private val thumbPool = Executors.newFixedThreadPool(3)
    private val thumbs = object : LruCache<Long, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }
    private lateinit var settings: Settings
    private lateinit var scanner: Scanner
    private var allPhotos: List<Photo> = emptyList()
    private var dupGroups = mutableListOf<Group>()
    private var blurGroups = mutableListOf<Group>()
    private var scanned = false
    private var tab = 0
    @Volatile private var busy = false
    private var pendingTrash: Set<Long> = emptySet()
    private var afterPerm: (() -> Unit)? = null
    private val groupAdapter = GroupAdapter()
    private val current: MutableList<Group> get() = if (tab == 0) dupGroups else blurGroups

    // Explorar
    private lateinit var analyzer: ContentAnalyzer
    private var explorePhotos: List<Photo> = emptyList()
    private var people: List<Person> = emptyList()
    private var analyzed = false
    @Volatile private var exploring = false
    private var page = 0
    private var pendingWrite: (() -> Unit)? = null
    private var gridListener: ((Set<Long>) -> Unit)? = null
    private val faceCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private lateinit var cleanPage: LinearLayout
    private lateinit var explorePage: ScrollView
    private lateinit var exploreBox: LinearLayout
    private lateinit var pageClean: TextView
    private lateinit var pageExplore: TextView
    private var exProgress: ProgressBar? = null
    private var exSub: TextView? = null

    private lateinit var heroTitle: TextView
    private lateinit var heroSub: TextView
    private lateinit var progress: ProgressBar
    private lateinit var scanBtn: TextView
    private lateinit var tabsBox: LinearLayout
    private lateinit var tabDup: TextView
    private lateinit var tabBlur: TextView
    private lateinit var simCard: LinearLayout
    private lateinit var simValue: TextView
    private lateinit var simBar: SeekBar
    private lateinit var actionsRow: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var deleteBtn: TextView

    // ---------------------------------------------------------------- Utilidades de vista

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private fun ui(f: () -> Unit) { main.post(f) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun csl(c: Int) = ColorStateList.valueOf(c)
    private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)
    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)
    private fun mb(b: Long): String =
        if (b >= (1L shl 30)) String.format(Locale.getDefault(), "%.2f GB", b / 1073741824.0)
        else String.format(Locale.getDefault(), "%.1f MB", b / 1048576.0)
    private fun mp(p: Photo): String = String.format(Locale.getDefault(), "%.1f MP", p.pixels / 1_000_000.0)
    private fun fmtDate(ms: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
    private val trashVerb: String get() = if (Build.VERSION.SDK_INT >= 30) "Mover a la papelera" else "Borrar"

    private fun round(color: Int, radius: Int, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke > 0) setStroke(dp(stroke), strokeColor)
    }

    private fun ripple(content: Drawable?, radius: Int): Drawable =
        RippleDrawable(csl(if (dark) 0x33FFFFFF else 0x22000000), content, round(Color.WHITE, radius))

    private fun tv(s: CharSequence = "", size: Float = 14f, color: Int = cText, medium: Boolean = false, bold: Boolean = false) =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            typeface = when {
                bold -> Typeface.create("sans-serif", Typeface.BOLD)
                medium -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
                else -> Typeface.create("sans-serif", Typeface.NORMAL)
            }
        }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(cCard, 20)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        if (!dark) elevation = dp(1).toFloat()
    }

    private fun chip(text: String, color: Int) = tv(text, 12f, color, medium = true).apply {
        background = round(withAlpha(color, 0x24), 10)
        setPadding(dp(10), dp(4), dp(10), dp(4))
    }

    private fun styleFilled(v: TextView, color: Int) {
        v.background = ripple(round(color, 16), 16)
        v.setTextColor(Color.WHITE)
    }

    private fun styleSoft(v: TextView, color: Int) {
        v.background = ripple(round(withAlpha(color, 0x24), 16), 16)
        v.setTextColor(color)
    }

    private fun pillButton(text: String, filled: Boolean, color: Int, onClick: () -> Unit) =
        tv(text, 15f, color, medium = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(15), dp(16), dp(15))
            if (filled) styleFilled(this, color) else styleSoft(this, color)
            setOnClickListener { onClick() }
        }

    private fun smallPill(text: String, onClick: () -> Unit) = tv(text, 13f, cAccent, medium = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(9), dp(14), dp(9))
        background = ripple(round(withAlpha(cAccent, 0x1F), 12), 12)
        setOnClickListener { onClick() }
    }

    // ---------------------------------------------------------------- Pantalla principal

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initPalette()
        settings = Settings(this)
        scanner = Scanner(contentResolver, filesDir)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom
                )
            }
            insets
        }

        // Barra superior fija: título, ajustes y selector de sección
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(6))
        }
        val top = row()
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(tv("Fotos duplicadas", 24f, cText, bold = true))
        titles.addView(chip("●  Privada · 100 % offline", cGreen), lp(WRAP, WRAP).apply { topMargin = dp(6) })
        top.addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f))
        val gear = tv("⚙", 22f, cText).apply {
            gravity = Gravity.CENTER
            background = ripple(round(cCard, 22), 22)
            if (!dark) elevation = dp(1).toFloat()
            contentDescription = "Ajustes"
            setOnClickListener { showSettings() }
        }
        top.addView(gear, LinearLayout.LayoutParams(dp(46), dp(46)))
        topBar.addView(top)
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = round(cLine, 16)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        pageClean = tabItem { selectPage(0) }
        pageExplore = tabItem { selectPage(1) }
        seg.addView(pageClean, LinearLayout.LayoutParams(0, WRAP, 1f))
        seg.addView(pageExplore, LinearLayout.LayoutParams(0, WRAP, 1f))
        topBar.addView(seg, lp(MATCH, WRAP).apply { topMargin = dp(14) })
        root.addView(topBar, MATCH, WRAP)

        val pages = FrameLayout(this)
        cleanPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val list = ListView(this).apply {
            divider = null
            dividerHeight = 0
            selector = ColorDrawable(Color.TRANSPARENT)
            setPadding(dp(16), dp(0), dp(16), dp(16))
            clipToPadding = false
            isVerticalScrollBarEnabled = false
        }
        list.addHeaderView(buildHeader(), null, false)
        list.adapter = groupAdapter
        cleanPage.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))

        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(12))
            setBackgroundColor(cBg)
            visibility = View.GONE
        }
        deleteBtn = pillButton("", true, cRed) { deleteSelected() }
        bottomBar.addView(deleteBtn, MATCH, WRAP)
        cleanPage.addView(bottomBar, MATCH, WRAP)
        pages.addView(cleanPage, FrameLayout.LayoutParams(MATCH, MATCH))

        exploreBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }
        explorePage = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(exploreBox)
            visibility = View.GONE
        }
        pages.addView(explorePage, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(pages, LinearLayout.LayoutParams(MATCH, 0, 1f))
        analyzer = ContentAnalyzer(this, scanner)
        selectPage(0)

        setContentView(root)
        updateUi()
    }

    override fun onDestroy() {
        scanner.cancel = true
        analyzer.cancel = true
        thumbPool.shutdownNow()
        super.onDestroy()
    }

    private fun buildHeader(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }

        // Tarjeta principal
        val hero = card().apply { setPadding(dp(20), dp(20), dp(20), dp(20)) }
        heroTitle = tv("", 30f, cText, bold = true)
        heroSub = tv("", 14f, cSub).apply { setLineSpacing(0f, 1.2f) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = csl(cAccent)
            progressBackgroundTintList = csl(cLine)
            visibility = View.GONE
        }
        scanBtn = pillButton("Buscar duplicadas", true, cAccent) { onScanClick() }
        hero.addView(heroTitle)
        hero.addView(heroSub, lp(MATCH, WRAP).apply { topMargin = dp(6) })
        hero.addView(progress, lp(MATCH, dp(10)).apply { topMargin = dp(16) })
        hero.addView(scanBtn, lp(MATCH, WRAP).apply { topMargin = dp(18) })
        box.addView(hero, lp(MATCH, WRAP).apply { topMargin = dp(6) })

        // Pestañas
        tabsBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = round(cLine, 16)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        tabDup = tabItem { selectTab(0) }
        tabBlur = tabItem { selectTab(1) }
        tabsBox.addView(tabDup, LinearLayout.LayoutParams(0, WRAP, 1f))
        tabsBox.addView(tabBlur, LinearLayout.LayoutParams(0, WRAP, 1f))
        box.addView(tabsBox, lp(MATCH, WRAP).apply { topMargin = dp(16) })

        // Control de parecido
        simCard = card()
        val sr = row()
        sr.addView(tv("Parecido entre fotos", 15f, cText, medium = true), LinearLayout.LayoutParams(0, WRAP, 1f))
        simValue = tv("", 14f, cAccent, medium = true)
        sr.addView(simValue)
        simCard.addView(sr)
        simBar = SeekBar(this).apply {
            max = 24
            progress = settings.threshold.coerceIn(0, 24)
            progressTintList = csl(cAccent)
            thumbTintList = csl(cAccent)
            progressBackgroundTintList = csl(cLine)
        }
        simBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                simValue.text = simLabel(value)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                settings.threshold = simBar.progress
                if (scanned && !busy) recluster()
            }
        })
        simCard.addView(simBar, lp(MATCH, WRAP).apply { topMargin = dp(10) })
        val hints = row()
        hints.addView(tv("Solo copias", 12f, cSub), LinearLayout.LayoutParams(0, WRAP, 1f))
        hints.addView(tv("Ráfagas", 12f, cSub))
        simCard.addView(hints, lp(MATCH, WRAP).apply { topMargin = dp(4) })
        simValue.text = simLabel(simBar.progress)
        box.addView(simCard, lp(MATCH, WRAP).apply { topMargin = dp(12) })

        // Acciones rápidas
        actionsRow = row()
        actionsRow.addView(smallPill("Auto-marcar") { autoMark() })
        actionsRow.addView(smallPill("Desmarcar todo") { unmarkAll() }, lp(WRAP, WRAP).apply { leftMargin = dp(8) })
        box.addView(actionsRow, lp(MATCH, WRAP).apply { topMargin = dp(14) })

        return box
    }

    private fun tabItem(onClick: () -> Unit) = tv("", 14f, cSub, medium = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(10), dp(8), dp(10))
        setOnClickListener { onClick() }
    }

    private fun styleTab(v: TextView, selected: Boolean, text: String) {
        v.text = text
        v.background = if (selected) round(cCard, 12) else null
        v.setTextColor(if (selected) cText else cSub)
    }

    private fun simLabel(v: Int) = when {
        v <= 4 -> "Solo copias exactas"
        v <= 9 -> "Casi idénticas"
        v <= 15 -> "Normal"
        else -> "Amplio (ráfagas)"
    }

    private fun selectTab(t: Int) {
        tab = t
        groupAdapter.notifyDataSetChanged()
        updateUi()
    }

    private fun hero(title: String, sub: String) {
        heroTitle.text = title
        heroSub.text = sub
    }

    private fun updateUi() {
        val hasBlur = blurGroups.isNotEmpty()
        if (!hasBlur && tab == 1) tab = 0
        val results = scanned && !busy
        val blurCount = blurGroups.sumOf { it.photos.size }

        tabsBox.visibility = if (results && hasBlur) View.VISIBLE else View.GONE
        styleTab(tabDup, tab == 0, "Duplicadas · ${dupGroups.size}")
        styleTab(tabBlur, tab == 1, "Borrosas · $blurCount")
        simCard.visibility = if (scanned && tab == 0) View.VISIBLE else View.GONE
        simBar.isEnabled = !busy
        actionsRow.visibility = if (results && current.isNotEmpty()) View.VISIBLE else View.GONE

        val n = current.sumOf { it.delete.size }
        val bytes = current.sumOf { it.reclaim() }
        if (!busy) {
            progress.visibility = View.GONE
            scanBtn.text = if (scanned) "Buscar de nuevo" else "Buscar duplicadas"
            styleFilled(scanBtn, cAccent)
            when {
                !scanned -> hero(
                    "Libera espacio sin perder fotos",
                    "Busco copias y fotos casi iguales y conservo la mejor de cada grupo. Todo se analiza en tu móvil."
                )
                tab == 1 -> hero(
                    "$blurCount posibles borrosas",
                    if (n > 0) "$n marcadas · ${mb(bytes)}" else "Revísalas y toca las que quieras borrar."
                )
                dupGroups.isEmpty() -> hero(
                    "Todo limpio ✓",
                    "No hay fotos repetidas con estos ajustes. Prueba a mover el control de parecido."
                )
                else -> hero(
                    mb(bytes),
                    "se pueden liberar · ${dupGroups.size} grupos · $n ${if (n == 1) "foto marcada" else "fotos marcadas"}"
                )
            }
        }
        bottomBar.visibility = if (results && n > 0) View.VISIBLE else View.GONE
        deleteBtn.text = "$trashVerb $n ${if (n == 1) "foto" else "fotos"} · ${mb(bytes)}"
    }

    private fun showBusy(title: String) {
        updateUi()
        heroTitle.text = title
        heroSub.text = ""
        progress.progress = 0
        progress.visibility = View.VISIBLE
        scanBtn.text = "Cancelar"
        styleSoft(scanBtn, cRed)
        bottomBar.visibility = View.GONE
        actionsRow.visibility = View.GONE
    }

    private fun progressUi(pct: Int, msg: String) = ui {
        progress.progress = pct * 10
        heroSub.text = msg
    }

    // ---------------------------------------------------------------- Permisos

    private fun perms(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        Build.VERSION.SDK_INT >= 30 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private fun hasPerms() = perms().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Permiso opcional para leer la ubicación GPS guardada en las fotos (Android 10+). */
    private fun hasLocPerm() = Build.VERSION.SDK_INT < 29 ||
        checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun allPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 29) perms() + Manifest.permission.ACCESS_MEDIA_LOCATION else perms()

    private fun ensurePerms(then: () -> Unit) {
        if (hasPerms()) then() else {
            afterPerm = then
            requestPermissions(allPerms(), REQ_PERM)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERM) return
        if (hasPerms()) {
            val f = afterPerm
            afterPerm = null
            f?.invoke()
        } else {
            toast("Sin permiso para leer tus fotos no puedo buscar duplicadas")
        }
    }

    // ---------------------------------------------------------------- Escaneo

    private fun onScanClick() {
        if (busy) { scanner.cancel = true; return }
        ensurePerms { startScan() }
    }

    private fun startScan() {
        if (busy) return
        busy = true
        scanner.cancel = false
        dupGroups = mutableListOf()
        blurGroups = mutableListOf()
        groupAdapter.notifyDataSetChanged()
        showBusy("Analizando…")
        Thread {
            var error: String? = null
            val ok = try { runScan() } catch (e: Throwable) { error = e.message ?: e.toString(); false }
            ui {
                busy = false
                if (ok) scanned = true
                else toast(if (error != null) "Error: $error" else "Búsqueda cancelada")
                groupAdapter.notifyDataSetChanged()
                updateUi()
            }
        }.start()
    }

    private fun runScan(): Boolean {
        val s = settings
        progressUi(0, "Leyendo la galería…")
        val photos = scanner.query(s)
        val cache = scanner.loadCache()
        val total = photos.size
        for ((i, p) in photos.withIndex()) {
            if (scanner.cancel) return false
            if (!scanner.applyCache(p, cache[p.id], s.blurry)) scanner.analyze(p, s.blurry)
            if (i % 25 == 0) progressUi(i * 75 / max(1, total), "Analizando ${i + 1} de $total fotos")
        }
        scanner.saveCache(photos)
        val valid = photos.filter { it.valid }
        progressUi(75, "Comparando fotos…")
        val dups = scanner.buildDuplicates(valid, s, { pct, msg -> progressUi(pct, msg) }, 75, 25) ?: return false
        val blur = if (s.blurry) scanner.buildBlurry(valid) else mutableListOf()
        ui {
            allPhotos = valid
            dupGroups = dups
            blurGroups = blur
        }
        return true
    }

    private fun recluster() {
        if (busy || allPhotos.isEmpty()) return
        busy = true
        scanner.cancel = false
        showBusy("Reagrupando…")
        val photos = allPhotos
        Thread {
            val res = try {
                scanner.buildDuplicates(photos, settings, { pct, msg -> progressUi(pct, msg) }, 0, 100)
            } catch (_: Throwable) { null }
            ui {
                busy = false
                if (res != null) dupGroups = res
                groupAdapter.notifyDataSetChanged()
                updateUi()
            }
        }.start()
    }

    // ---------------------------------------------------------------- Marcas

    private fun autoMark() {
        if (tab == 0) dupGroups.forEach { scanner.remark(it, settings) }
        else blurGroups.forEach { g -> g.photos.forEach { g.delete.add(it.id) } }
        groupAdapter.notifyDataSetChanged()
        updateUi()
    }

    private fun unmarkAll() {
        current.forEach { it.delete.clear() }
        groupAdapter.notifyDataSetChanged()
        updateUi()
    }

    /** Devuelve false si no se permite (dejaría el grupo sin ninguna foto). */
    private fun toggle(g: Group, p: Photo): Boolean {
        if (p.id in g.delete) {
            g.delete.remove(p.id)
        } else {
            if (g.kind != Kind.BLURRY && g.photos.count { it.id !in g.delete } <= 1) {
                toast("Tiene que quedar al menos una foto de cada grupo")
                return false
            }
            g.delete.add(p.id)
        }
        groupAdapter.notifyDataSetChanged()
        updateUi()
        return true
    }

    private fun notSame(g: Group) {
        scanner.addIgnored(g.photos.map { it.id })
        dupGroups.remove(g)
        groupAdapter.notifyDataSetChanged()
        updateUi()
        toast("Entendido: este grupo no volverá a aparecer")
    }

    // ---------------------------------------------------------------- Lista de grupos

    private fun loadThumb(p: Photo, iv: ImageView) {
        iv.tag = p.id
        val cached = thumbs.get(p.id)
        if (cached != null) { iv.setImageBitmap(cached); return }
        iv.setImageDrawable(null)
        try {
            thumbPool.execute {
                val bmp: Bitmap? = try {
                    if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(p.uri, Size(320, 320), null)
                    else scanner.decode(p.uri, 256)?.bmp
                } catch (_: Throwable) { null }
                if (bmp != null) {
                    thumbs.put(p.id, bmp)
                    ui { if (iv.tag == p.id) iv.setImageBitmap(bmp) }
                }
            }
        } catch (_: Exception) { }
    }

    inner class GroupAdapter : BaseAdapter() {
        override fun getCount(): Int = current.size
        override fun getItem(position: Int): Any = current[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val outer = (convertView as? FrameLayout) ?: FrameLayout(this@MainActivity).apply {
                setPadding(0, dp(6), 0, dp(6))
                clipToPadding = false
                addView(card(), FrameLayout.LayoutParams(MATCH, WRAP))
            }
            val c = outer.getChildAt(0) as LinearLayout
            c.removeAllViews()
            fillCard(c, current[position], position)
            return outer
        }
    }

    private fun fillCard(card: LinearLayout, g: Group, position: Int) {
        val (label, color) = when (g.kind) {
            Kind.COPIES -> "Copias" to cAccent
            Kind.SIMILAR -> "Parecidas" to cTeal
            Kind.BLURRY -> "Borrosas" to cAmber
            Kind.BROWSE -> "Fotos" to cAccent
        }
        val head = row()
        head.addView(chip(label, color))
        head.addView(
            tv("${g.photos.size} fotos", 14f, cText, medium = true),
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(10) }
        )
        val rec = g.reclaim()
        head.addView(tv(if (rec > 0) "−${mb(rec)}" else "", 14f, cRed, medium = true))
        card.addView(head)

        val strip = row().apply { gravity = Gravity.TOP }
        g.photos.forEachIndexed { i, p -> strip.addView(cell(g, p, i)) }
        card.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(strip)
        }, lp(MATCH, WRAP).apply { topMargin = dp(14) })

        if (g.kind != Kind.BLURRY) {
            val foot = row()
            foot.addView(
                tv(if (position == 0) "Toca para marcar · mantén para ampliar" else "", 12f, cSub),
                LinearLayout.LayoutParams(0, WRAP, 1f)
            )
            foot.addView(tv("No son iguales", 13f, cSub, medium = true).apply {
                setPadding(dp(12), dp(7), dp(12), dp(7))
                background = ripple(round(withAlpha(cSub, 0x1A), 10), 10)
                setOnClickListener { notSame(g) }
            })
            card.addView(foot, lp(MATCH, WRAP).apply { topMargin = dp(10) })
        } else if (position == 0) {
            card.addView(
                tv("Ordenadas de más a menos borrosa. Toca para marcar · mantén para ampliar.", 12f, cSub),
                lp(MATCH, WRAP).apply { topMargin = dp(10) }
            )
        }
    }

    private fun cell(g: Group, p: Photo, index: Int): View {
        val del = p.id in g.delete
        val best = p.id == g.bestId
        val side = dp(104)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(10), 0)
        }
        val ring = when {
            del -> cRed
            g.kind == Kind.BLURRY -> Color.TRANSPARENT
            else -> cGreen
        }
        val frame = FrameLayout(this).apply {
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = round(Color.TRANSPARENT, 16, 3, ring)
            foreground = ripple(null, 16)
        }
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = round(cLine, 13)
            clipToOutline = true
            alpha = if (del) 0.45f else 1f
        }
        frame.addView(iv, FrameLayout.LayoutParams(side, side))
        if (del || g.kind != Kind.BLURRY) {
            frame.addView(tv(if (del) "✕" else "✓", 12f, Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                background = round(if (del) cRed else cGreen, 11)
            }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply {
                setMargins(0, dp(6), dp(6), 0)
            })
        }
        if (best) {
            frame.addView(tv("★ Mejor", 10f, Color.WHITE, bold = true).apply {
                background = round(cAccent, 8)
                setPadding(dp(7), dp(3), dp(7), dp(3))
            }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply {
                setMargins(dp(6), 0, 0, dp(6))
            })
        }
        col.addView(frame)
        col.addView(tv("${mp(p)} · ${mb(p.size)}", 11f, cText, medium = true).apply {
            maxWidth = side + dp(6)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2), dp(7), 0, 0)
        })
        col.addView(tv(p.folder.ifEmpty { "—" }, 11f, cSub).apply {
            maxWidth = side + dp(6)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2), dp(1), 0, 0)
        })
        loadThumb(p, iv)
        frame.setOnClickListener { toggle(g, p) }
        frame.setOnLongClickListener { showDetail(g, index); true }
        return col
    }

    // ---------------------------------------------------------------- Visor a pantalla completa

    private fun showDetail(g: Group, start: Int, onClose: (() -> Unit)? = null) {
        if (g.photos.isEmpty()) return
        var idx = start.coerceIn(0, g.photos.size - 1)
        val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))

        val r = dp(24).toFloat()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E616181D"))
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(dp(18), dp(16), dp(18), dp(18))
        }
        val title = tv("", 15f, Color.WHITE, medium = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        val info = tv("", 13f, Color.parseColor("#B8BCC8")).apply { setLineSpacing(0f, 1.25f) }
        val markBtn = pillButton("", true, cGreen) {}
        val nav = row()
        fun circle(s: String) = tv(s, 26f, Color.WHITE, medium = true).apply {
            gravity = Gravity.CENTER
            background = ripple(round(Color.parseColor("#33FFFFFF"), 24), 24)
        }
        val prev = circle("‹")
        val next = circle("›")
        val metaBtn = tv("ⓘ  Datos", 13f, Color.WHITE, medium = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = ripple(round(Color.parseColor("#33FFFFFF"), 14), 14)
        }
        val open = tv("Abrir", 13f, Color.WHITE, medium = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        nav.addView(prev, LinearLayout.LayoutParams(dp(48), dp(48)))
        val mid = row().apply { gravity = Gravity.CENTER }
        mid.addView(metaBtn)
        mid.addView(open, lp(WRAP, WRAP).apply { leftMargin = dp(6) })
        nav.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f))
        nav.addView(next, LinearLayout.LayoutParams(dp(48), dp(48)))
        panel.addView(title)
        panel.addView(info, lp(MATCH, WRAP).apply { topMargin = dp(6) })
        panel.addView(markBtn, lp(MATCH, WRAP).apply { topMargin = dp(14) })
        panel.addView(nav, lp(MATCH, WRAP).apply { topMargin = dp(12) })
        root.addView(panel, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        val close = tv("✕", 18f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            background = ripple(round(Color.parseColor("#66000000"), 22), 22)
            setOnClickListener { d.dismiss() }
        }
        root.addView(close, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(16), dp(16), 0)
        })
        root.setOnApplyWindowInsetsListener { _, insets ->
            var top = 0
            var bottom = 0
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars())
                top = b.top; bottom = b.bottom
            } else {
                @Suppress("DEPRECATION")
                run { top = insets.systemWindowInsetTop; bottom = insets.systemWindowInsetBottom }
            }
            root.setPadding(0, top, 0, 0)
            panel.setPadding(dp(18), dp(16), dp(18), dp(18) + bottom)
            insets
        }

        fun render() {
            val p = g.photos[idx]
            val del = p.id in g.delete
            title.text = p.name
            info.text = buildString {
                append("${p.w} × ${p.h} px · ${mp(p)} · ${mb(p.size)}")
                append("\n${fmtDate(p.taken)} · ${p.folder.ifEmpty { "—" }}")
                if (p.sharp >= 0) append("\nNitidez: ${p.sharp.toInt()}")
                if (p.id == g.bestId) append("   ★ La mejor del grupo")
                append("\n${idx + 1} de ${g.photos.size}")
            }
            if (g.kind == Kind.BROWSE) {
                markBtn.text = if (del) "✓   Seleccionada" else "Seleccionar"
                if (del) styleFilled(markBtn, cAccent) else styleSoft(markBtn, Color.WHITE)
            } else {
                markBtn.text = if (del) "✕   Se borrará · tocar para conservar" else "✓   Se conserva · tocar para borrar"
                styleFilled(markBtn, if (del) cRed else cGreen)
            }
            prev.alpha = if (idx > 0) 1f else 0.3f
            next.alpha = if (idx < g.photos.size - 1) 1f else 0.3f
            val id = p.id
            iv.tag = id
            val small = thumbs.get(id)
            if (small != null) iv.setImageBitmap(small) else iv.setImageDrawable(null)
            try {
                thumbPool.execute {
                    val b = try { scanner.decodeForView(p.uri) } catch (_: Throwable) { null }
                    if (b != null) ui { if (iv.tag == id) iv.setImageBitmap(b) }
                }
            } catch (_: Exception) { }
        }

        fun go(delta: Int) {
            val n = idx + delta
            if (n in 0 until g.photos.size) { idx = n; render() }
        }

        markBtn.setOnClickListener { if (toggle(g, g.photos[idx])) render() }
        prev.setOnClickListener { go(-1) }
        next.setOnClickListener { go(1) }
        metaBtn.setOnClickListener { showMeta(g.photos[idx]) }
        open.setOnClickListener {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(g.photos[idx].uri, "image/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            } catch (_: Exception) { toast("No hay ningún visor de imágenes instalado") }
        }
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (abs(velocityX) > abs(velocityY) && abs(velocityX) > 600) {
                    go(if (velocityX < 0) 1 else -1)
                    return true
                }
                return false
            }
        })
        iv.setOnTouchListener { _, ev -> gd.onTouchEvent(ev) }

        d.setContentView(root)
        d.setOnDismissListener { groupAdapter.notifyDataSetChanged(); onClose?.invoke() }
        d.show()
        render()
    }

    // ---------------------------------------------------------------- Ajustes

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(4), dp(22), dp(8))
        }
        fun section(t: String) {
            box.addView(tv(t.uppercase(), 12f, cAccent, bold = true).apply { letterSpacing = 0.06f },
                lp(MATCH, WRAP).apply { topMargin = dp(18) })
        }
        fun spinner(items: Array<String>, sel: Int): Spinner {
            val sp = Spinner(this)
            sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
            sp.setSelection(sel.coerceIn(0, items.size - 1))
            box.addView(sp, lp(MATCH, WRAP).apply { topMargin = dp(4) })
            return sp
        }
        fun check(t: String, v: Boolean): CheckBox {
            val cb = CheckBox(this)
            cb.text = t
            cb.isChecked = v
            cb.textSize = 14f
            cb.setTextColor(cText)
            cb.buttonTintList = csl(cAccent)
            box.addView(cb, lp(MATCH, WRAP).apply { topMargin = dp(4) })
            return cb
        }

        section("Dónde buscar")
        val scopeSp = spinner(Settings.SCOPES, settings.scope)
        var chosen = settings.folders
        var excl = settings.excluded
        val folderRow = row()
        val chooseBtn = smallPill("Carpetas elegidas: ${chosen.size}") {}
        val exclBtn = smallPill("Excluidas: ${excl.size}") {}
        chooseBtn.setOnClickListener {
            pickFolders("Buscar solo en", chosen) {
                chosen = it
                chooseBtn.text = "Carpetas elegidas: ${it.size}"
                if (it.isNotEmpty()) scopeSp.setSelection(2)
            }
        }
        exclBtn.setOnClickListener {
            pickFolders("No buscar en", excl) {
                excl = it
                exclBtn.text = "Excluidas: ${it.size}"
            }
        }
        folderRow.addView(chooseBtn)
        folderRow.addView(exclBtn, lp(WRAP, WRAP).apply { leftMargin = dp(8) })
        box.addView(folderRow, lp(MATCH, WRAP).apply { topMargin = dp(8) })
        val minSp = spinner(Settings.MIN_LABELS, max(0, Settings.MIN_KB.indexOf(settings.minKb)))

        section("Qué cuenta como repetida")
        val winSp = spinner(Settings.WINDOW_LABELS, settings.timeWindow)
        val rotCb = check("Detectar también fotos giradas o volteadas", settings.rotated)

        section("Qué foto conservar")
        val keepSp = spinner(Settings.KEEP_LABELS, settings.keepRule)
        val camCb = check("Preferir la original de la cámara frente a copias (WhatsApp, descargas…)", settings.preferCamera)

        section("Extras")
        val blurCb = check("Buscar también fotos borrosas", settings.blurry)
        box.addView(smallPill("Volver a mostrar los grupos «No son iguales»") {
            scanner.clearIgnored()
            toast("Hecho. Se tendrán en cuenta en la próxima búsqueda.")
        }, lp(WRAP, WRAP).apply { topMargin = dp(12) })

        AlertDialog.Builder(this)
            .setTitle("Ajustes")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar") { _, _ ->
                val newMin = Settings.MIN_KB[minSp.selectedItemPosition.coerceIn(0, Settings.MIN_KB.size - 1)]
                val rescan = scopeSp.selectedItemPosition != settings.scope || chosen != settings.folders ||
                    excl != settings.excluded || newMin != settings.minKb || (blurCb.isChecked && !settings.blurry)
                val regroup = winSp.selectedItemPosition != settings.timeWindow || rotCb.isChecked != settings.rotated
                val remark = keepSp.selectedItemPosition != settings.keepRule || camCb.isChecked != settings.preferCamera

                settings.scope = scopeSp.selectedItemPosition
                settings.folders = chosen
                settings.excluded = excl
                settings.minKb = newMin
                settings.timeWindow = winSp.selectedItemPosition
                settings.rotated = rotCb.isChecked
                settings.keepRule = keepSp.selectedItemPosition
                settings.preferCamera = camCb.isChecked
                settings.blurry = blurCb.isChecked
                if (!blurCb.isChecked) blurGroups = mutableListOf()

                when {
                    !scanned || busy -> { groupAdapter.notifyDataSetChanged(); updateUi() }
                    rescan -> ensurePerms { startScan() }
                    regroup -> recluster()
                    remark -> {
                        dupGroups.forEach { scanner.remark(it, settings) }
                        groupAdapter.notifyDataSetChanged()
                        updateUi()
                    }
                    else -> { groupAdapter.notifyDataSetChanged(); updateUi() }
                }
            }
            .show()
    }

    private fun pickFolders(title: String, selected: Set<String>, cb: (Set<String>) -> Unit) {
        ensurePerms {
            Thread {
                val fs = try { scanner.folders() } catch (_: Exception) { emptyList() }
                ui {
                    if (fs.isEmpty()) {
                        toast("No he encontrado carpetas con fotos")
                    } else {
                        val names = fs.map { it.first }
                        val checked = BooleanArray(names.size) { names[it] in selected }
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(title)
                            .setMultiChoiceItems(fs.map { "${it.first}  (${it.second})" }.toTypedArray(), checked) { _, w, c ->
                                checked[w] = c
                            }
                            .setNegativeButton("Cancelar", null)
                            .setPositiveButton("Aceptar") { _, _ ->
                                cb(names.filterIndexed { i, _ -> checked[i] }.toSet())
                            }
                            .show()
                    }
                }
            }.start()
        }
    }

    // ---------------------------------------------------------------- Explorar

    private fun selectPage(p: Int) {
        page = p
        styleTab(pageClean, p == 0, "🧹  Limpiar")
        styleTab(pageExplore, p == 1, "🔎  Explorar")
        cleanPage.visibility = if (p == 0) View.VISIBLE else View.GONE
        explorePage.visibility = if (p == 1) View.VISIBLE else View.GONE
        if (p == 1) renderExplore()
    }

    private fun sectionTitle(t: String) = tv(t, 17f, cText, bold = true)

    private fun renderExplore() {
        if (!::exploreBox.isInitialized) return
        val box = exploreBox
        box.removeAllViews()

        // Tarjeta principal
        val hero = card().apply { setPadding(dp(20), dp(20), dp(20), dp(20)) }
        val title: String
        val sub: String
        when {
            exploring -> { title = "Analizando…"; sub = "" }
            !analyzed -> {
                title = "Reconoce personas y cosas"
                sub = "Detecto caras, agrupo por persona y clasifico tus fotos (comida, documentos, capturas, " +
                    "animales…). Todo dentro del móvil. La primera vez tarda unos minutos; después solo analiza las nuevas."
            }
            else -> {
                val gps = explorePhotos.count { it.hasGps }
                title = "${explorePhotos.size} fotos analizadas"
                sub = "${people.size} ${if (people.size == 1) "persona" else "personas"} · $gps con ubicación GPS"
            }
        }
        hero.addView(tv(title, 26f, cText, bold = true))
        val subTv = tv(sub, 14f, cSub).apply { setLineSpacing(0f, 1.2f) }
        hero.addView(subTv, lp(MATCH, WRAP).apply { topMargin = dp(6) })
        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = csl(cAccent)
            progressBackgroundTintList = csl(cLine)
            visibility = if (exploring) View.VISIBLE else View.GONE
        }
        hero.addView(pb, lp(MATCH, dp(10)).apply { topMargin = dp(16) })
        exProgress = pb
        exSub = subTv
        val label = when {
            exploring -> "Cancelar"
            analyzed -> "Analizar fotos nuevas"
            else -> "Analizar contenido"
        }
        hero.addView(pillButton(label, !exploring, if (exploring) cRed else cAccent) {
            if (exploring) analyzer.cancel = true else ensurePerms { startAnalysis() }
        }, lp(MATCH, WRAP).apply { topMargin = dp(18) })
        box.addView(hero, lp(MATCH, WRAP).apply { topMargin = dp(6) })

        if (!analyzed || exploring) return

        // Personas
        box.addView(sectionTitle("Personas"), lp(MATCH, WRAP).apply { topMargin = dp(22) })
        when {
            !analyzer.hasFaceModel -> box.addView(
                tv("Agrupar por persona no está disponible en esta versión (falta el modelo de caras). Lo demás funciona.", 13f, cSub),
                lp(MATCH, WRAP).apply { topMargin = dp(6) }
            )
            people.isEmpty() -> box.addView(
                tv("Todavía no hay suficientes caras repetidas para formar personas.", 13f, cSub),
                lp(MATCH, WRAP).apply { topMargin = dp(6) }
            )
            else -> {
                box.addView(
                    tv("Toca para ver sus fotos · mantén pulsado para ponerle nombre. Dos personas con el mismo nombre se unen.", 12f, cSub),
                    lp(MATCH, WRAP).apply { topMargin = dp(4) }
                )
                val strip = row().apply { gravity = Gravity.TOP }
                people.take(80).forEachIndexed { i, pr -> strip.addView(personView(pr, i)) }
                box.addView(HorizontalScrollView(this).apply {
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    addView(strip)
                }, lp(MATCH, WRAP).apply { topMargin = dp(12) })
            }
        }

        // Privacidad
        box.addView(sectionTitle("Privacidad"), lp(MATCH, WRAP).apply { topMargin = dp(22) })
        val gpsPhotos = explorePhotos.filter { it.hasGps }
        val priv = card()
        val pr = row()
        pr.addView(tv("📍", 24f, cText))
        val pt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pt.addView(tv("Fotos con ubicación GPS", 15f, cText, medium = true))
        pt.addView(tv(if (gpsPhotos.isEmpty()) "Ninguna detectada" else "${gpsPhotos.size} fotos revelan dónde se hicieron", 13f, cSub))
        pr.addView(pt, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(12) })
        if (gpsPhotos.isNotEmpty()) pr.addView(tv("›", 26f, cSub))
        priv.addView(pr)
        if (gpsPhotos.isNotEmpty()) {
            priv.background = ripple(round(cCard, 20), 20)
            priv.setOnClickListener { showGrid("📍  Con ubicación GPS", gpsPhotos) }
        }
        if (!hasLocPerm() && Build.VERSION.SDK_INT >= 29) {
            priv.addView(
                tv("Para ver la ubicación necesito el permiso de «ubicación de tus fotos».", 12f, cAmber),
                lp(MATCH, WRAP).apply { topMargin = dp(10) }
            )
            priv.addView(smallPill("Dar permiso y volver a comprobar") {
                afterPerm = { startAnalysis() }
                requestPermissions(allPerms(), REQ_PERM)
            }, lp(WRAP, WRAP).apply { topMargin = dp(8) })
        }
        box.addView(priv, lp(MATCH, WRAP).apply { topMargin = dp(10) })

        // Categorías
        box.addView(sectionTitle("Categorías"), lp(MATCH, WRAP).apply { topMargin = dp(22) })
        val cats = ContentAnalyzer.CATEGORIES.map { c -> c to explorePhotos.filter(c.test) }.filter { it.second.isNotEmpty() }
        if (cats.isEmpty()) {
            box.addView(tv("Sin categorías todavía.", 13f, cSub), lp(MATCH, WRAP).apply { topMargin = dp(6) })
        }
        cats.chunked(2).forEach { pair ->
            val r = row().apply { gravity = Gravity.TOP }
            pair.forEachIndexed { i, (c, ps) ->
                r.addView(categoryTile(c, ps), LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i == 1) leftMargin = dp(10) })
            }
            if (pair.size == 1) r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(10) })
            box.addView(r, lp(MATCH, WRAP).apply { topMargin = dp(10) })
        }
    }

    private fun categoryTile(c: Category, ps: List<Photo>): View {
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ripple(round(cCard, 18), 18)
            if (!dark) elevation = dp(1).toFloat()
            setPadding(dp(8), dp(8), dp(8), dp(12))
            setOnClickListener { showGrid("${c.emoji}  ${c.title}", ps) }
        }
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = round(cLine, 12)
            clipToOutline = true
        }
        t.addView(iv, lp(MATCH, dp(100)))
        loadThumb(ps[0], iv)
        t.addView(tv("${c.emoji}  ${c.title}", 14f, cText, medium = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, lp(MATCH, WRAP).apply { topMargin = dp(8); leftMargin = dp(4) })
        t.addView(tv("${ps.size} ${if (ps.size == 1) "foto" else "fotos"}", 12f, cSub), lp(MATCH, WRAP).apply {
            leftMargin = dp(4); topMargin = dp(2)
        })
        return t
    }

    private fun personView(pr: Person, i: Int): View {
        val size = dp(76)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, dp(14), 0)
        }
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cLine) }
            clipToOutline = true
        }
        val ring = FrameLayout(this).apply {
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), if (pr.name.isBlank()) cLine else cAccent)
            }
            foreground = ripple(null, 42)
            addView(iv, FrameLayout.LayoutParams(size, size))
        }
        col.addView(ring)
        val name = pr.name.ifBlank { "Persona ${i + 1}" }
        col.addView(tv(name, 13f, if (pr.name.isBlank()) cSub else cText, medium = true).apply {
            maxWidth = size + dp(12)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }, lp(WRAP, WRAP).apply { topMargin = dp(6) })
        col.addView(tv("${pr.photos.size} fotos", 11f, cSub))
        loadFace(pr, iv)
        ring.setOnClickListener { showGrid("👤  $name", pr.photos) }
        ring.setOnLongClickListener { renamePerson(pr); true }
        return col
    }

    private fun loadFace(pr: Person, iv: ImageView) {
        val (p, f) = pr.cover
        val key = "${p.id}:${f.l}:${f.t}"
        iv.tag = key
        val cached = faceCache.get(key)
        if (cached != null) { iv.setImageBitmap(cached); return }
        iv.setImageDrawable(null)
        try {
            thumbPool.execute {
                val b: Bitmap? = try {
                    val full = scanner.decodeUpright(p.uri, 1024)
                    if (full == null) null else {
                        val w = full.width
                        val h = full.height
                        val cx = (f.l + f.r) / 2f * w
                        val cy = (f.t + f.b) / 2f * h
                        val sz = max((f.r - f.l) * w, (f.b - f.t) * h) * 1.5f
                        val l = (cx - sz / 2f).toInt().coerceIn(0, w - 1)
                        val t = (cy - sz / 2f).toInt().coerceIn(0, h - 1)
                        val cw = min(sz.toInt(), w - l).coerceAtLeast(1)
                        val ch = min(sz.toInt(), h - t).coerceAtLeast(1)
                        val crop = Bitmap.createBitmap(full, l, t, cw, ch)
                        val sc = Bitmap.createScaledBitmap(crop, 200, 200, true)
                        if (crop !== sc && crop !== full) crop.recycle()
                        if (full !== sc) full.recycle()
                        sc
                    }
                } catch (_: Throwable) { null }
                if (b != null) {
                    faceCache.put(key, b)
                    ui { if (iv.tag == key) iv.setImageBitmap(b) }
                }
            }
        } catch (_: Exception) { }
    }

    private fun names(): Map<String, String> =
        getSharedPreferences("personas", MODE_PRIVATE).all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    private fun renamePerson(pr: Person) {
        val et = EditText(this).apply {
            setText(pr.name)
            hint = "Nombre"
            setSingleLine()
        }
        val wrap = FrameLayout(this).apply {
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(et)
        }
        AlertDialog.Builder(this)
            .setTitle("¿Quién es?")
            .setMessage("Si pones el mismo nombre a dos personas, se unen en una.")
            .setView(wrap)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar") { _, _ ->
                val name = et.text.toString().trim()
                val ed = getSharedPreferences("personas", MODE_PRIVATE).edit()
                for (k in pr.keys) if (name.isEmpty()) ed.remove(k) else ed.putString(k, name)
                ed.apply()
                pr.name = name
                regroupPeople()
            }
            .show()
    }

    private fun regroupPeople() {
        val ps = explorePhotos
        val nm = names()
        Thread {
            val pp = try { analyzer.people(ps, nm) } catch (_: Throwable) { people }
            ui { people = pp; renderExplore() }
        }.start()
    }

    private fun startAnalysis() {
        if (exploring) return
        exploring = true
        analyzer.cancel = false
        renderExplore()
        val loc = hasLocPerm()
        Thread {
            var err: String? = null
            try {
                ui { exSub?.text = "Preparando los modelos…" }
                analyzer.loadModel()
                ui { exSub?.text = "Leyendo la galería…" }
                val photos = scanner.query(settings)
                val cache = analyzer.loadCache()
                val total = photos.size
                for ((i, p) in photos.withIndex()) {
                    if (analyzer.cancel) break
                    if (!analyzer.applyCache(p, cache[p.id])) {
                        analyzer.analyze(p, loc)
                    } else if (!p.gpsChecked && loc) {
                        p.hasGps = analyzer.readGps(p.uri)
                        p.gpsChecked = true
                    }
                    if (i % 10 == 0) {
                        val pct = i * 950 / max(1, total)
                        ui {
                            exProgress?.progress = pct
                            exSub?.text = "Analizando ${i + 1} de $total fotos"
                        }
                    }
                    if (i % 300 == 299) analyzer.saveCache(photos, cache)
                }
                analyzer.saveCache(photos, cache)
                val done = photos.filter { it.contentDone }
                analyzer.cancel = false
                ui { exProgress?.progress = 960; exSub?.text = "Agrupando caras por persona…" }
                val pp = analyzer.people(done, names())
                ui {
                    explorePhotos = done
                    people = pp
                    analyzed = true
                }
            } catch (e: Throwable) {
                err = e.message ?: e.toString()
            }
            val msg = err
            ui {
                exploring = false
                if (msg != null) toast("Error: $msg")
                renderExplore()
            }
        }.start()
    }

    // ---------------------------------------------------------------- Rejilla de fotos

    private fun showGrid(title: String, photos: List<Photo>) {
        val g = Group(Kind.BROWSE, photos.toMutableList(), -1L)
        val d = Dialog(
            this,
            if (dark) android.R.style.Theme_Material_NoActionBar_Fullscreen
            else android.R.style.Theme_Material_Light_NoActionBar_Fullscreen
        )
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(0, b.top, 0, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            }
            insets
        }

        val head = row().apply { setPadding(dp(12), dp(10), dp(12), dp(10)) }
        head.addView(tv("✕", 18f, cText).apply {
            gravity = Gravity.CENTER
            background = ripple(round(cCard, 22), 22)
            setOnClickListener { d.dismiss() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val tcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tcol.addView(tv(title, 18f, cText, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val countTv = tv("", 12f, cSub)
        tcol.addView(countTv)
        head.addView(tcol, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(12) })
        val allBtn = smallPill("Todas") {}
        head.addView(allBtn)
        root.addView(head, MATCH, WRAP)

        val cols = 3
        val gap = dp(3)
        val cellSize = (resources.displayMetrics.widthPixels - gap * (cols + 1)) / cols
        val grid = GridView(this).apply {
            numColumns = cols
            horizontalSpacing = gap
            verticalSpacing = gap
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(gap, 0, gap, gap)
            clipToPadding = false
            selector = ColorDrawable(Color.TRANSPARENT)
        }
        root.addView(grid, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            setBackgroundColor(cBg)
            visibility = View.GONE
        }
        val delBtn = pillButton("", true, cRed) {}
        val gpsBtn = pillButton("📍 Quitar GPS", false, cAccent) {}
        val cleanBtn = pillButton("🧽 Quitar datos", false, cAccent) {}
        for (b in listOf(delBtn, gpsBtn, cleanBtn)) {
            b.textSize = 13f
            b.setPadding(dp(6), dp(13), dp(6), dp(13))
            b.maxLines = 1
        }
        bar.addView(delBtn, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(gpsBtn, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
        bar.addView(cleanBtn, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
        root.addView(bar, MATCH, WRAP)

        lateinit var adapter: BaseAdapter
        fun refresh() {
            val n = g.delete.size
            countTv.text = if (n > 0) "$n de ${g.photos.size} seleccionadas"
            else "${g.photos.size} fotos · mantén pulsado para seleccionar"
            allBtn.text = if (n > 0 && n == g.photos.size) "Ninguna" else "Todas"
            bar.visibility = if (n > 0) View.VISIBLE else View.GONE
            delBtn.text = "🗑 Borrar $n"
            adapter.notifyDataSetChanged()
        }
        adapter = object : BaseAdapter() {
            override fun getCount(): Int = g.photos.size
            override fun getItem(position: Int): Any = g.photos[position]
            override fun getItemId(position: Int): Long = g.photos[position].id
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val fr = (convertView as? FrameLayout) ?: FrameLayout(this@MainActivity).apply {
                    layoutParams = AbsListView.LayoutParams(cellSize, cellSize)
                    addView(ImageView(this@MainActivity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(cLine)
                    }, FrameLayout.LayoutParams(MATCH, MATCH))
                    addView(tv("✓", 12f, Color.WHITE, bold = true).apply {
                        gravity = Gravity.CENTER
                        background = round(cAccent, 11)
                    }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply {
                        setMargins(0, dp(6), dp(6), 0)
                    })
                }
                val p = g.photos[position]
                val iv = fr.getChildAt(0) as ImageView
                val sel = p.id in g.delete
                loadThumb(p, iv)
                iv.alpha = if (sel) 0.55f else 1f
                fr.getChildAt(1).visibility = if (sel) View.VISIBLE else View.GONE
                fr.foreground = if (sel) round(Color.TRANSPARENT, 0, 3, cAccent) else null
                return fr
            }
        }
        grid.adapter = adapter

        fun toggleAt(pos: Int) {
            val id = g.photos[pos].id
            if (!g.delete.remove(id)) g.delete.add(id)
            refresh()
        }
        grid.setOnItemClickListener { _, _, pos, _ ->
            if (g.delete.isNotEmpty()) toggleAt(pos) else showDetail(g, pos) { refresh() }
        }
        grid.setOnItemLongClickListener { _, _, pos, _ -> toggleAt(pos); true }
        allBtn.setOnClickListener {
            if (g.delete.size == g.photos.size) g.delete.clear() else g.photos.forEach { g.delete.add(it.id) }
            refresh()
        }
        fun selected() = g.photos.filter { it.id in g.delete }
        delBtn.setOnClickListener { deletePhotos(selected()) }
        gpsBtn.setOnClickListener {
            val ps = selected()
            performEdit(ps, "Ubicación quitada", { e -> Meta.clear(e, Meta.GPS_TAGS) }) {
                ps.forEach { it.hasGps = false }
                g.delete.clear()
                refresh()
                renderExplore()
            }
        }
        cleanBtn.setOnClickListener {
            val ps = selected()
            AlertDialog.Builder(this)
                .setTitle("Quitar datos personales")
                .setMessage(
                    "Se borrarán de ${ps.size} ${if (ps.size == 1) "foto" else "fotos"}: ubicación GPS, marca y modelo " +
                        "de la cámara, números de serie, propietario, autor, copyright, software, descripción y comentarios. " +
                        "La imagen no cambia."
                )
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Quitar") { _, _ ->
                    performEdit(ps, "Datos personales quitados", { e ->
                        Meta.clear(e, Meta.GPS_TAGS)
                        Meta.clear(e, Meta.PERSONAL_TAGS)
                    }) {
                        ps.forEach { it.hasGps = false }
                        g.delete.clear()
                        refresh()
                        renderExplore()
                    }
                }
                .show()
        }
        gridListener = { ids ->
            g.photos.removeAll { it.id in ids }
            g.delete.removeAll(ids)
            refresh()
        }
        d.setOnDismissListener { gridListener = null }
        d.setContentView(root)
        d.show()
        refresh()
    }

    // ---------------------------------------------------------------- Metadatos

    private fun showMeta(p: Photo) {
        Thread {
            val rows = try { Meta.read(contentResolver, p) } catch (_: Throwable) { listOf("Metadatos" to "No se han podido leer") }
            ui { showMetaDialog(p, rows) }
        }.start()
    }

    private fun showMetaDialog(p: Photo, rows: List<Pair<String, String>>) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(4), dp(22), dp(8))
        }
        for ((k, v) in rows) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(7), 0, dp(7))
            }
            r.addView(tv(k, 12f, cSub))
            r.addView(tv(v, 15f, cText).apply { setTextIsSelectable(true) })
            box.addView(r)
        }
        if (!hasLocPerm()) {
            box.addView(
                tv("La ubicación puede salir vacía si no das el permiso de «ubicación de tus fotos».", 12f, cAmber),
                lp(MATCH, WRAP).apply { topMargin = dp(6) }
            )
        }
        box.addView(tv("EDITAR", 12f, cAccent, bold = true).apply { letterSpacing = 0.06f },
            lp(MATCH, WRAP).apply { topMargin = dp(18) })
        box.addView(tv("Se puede editar en JPEG, PNG y WebP (no en HEIC). Android te pedirá confirmación.", 12f, cSub),
            lp(MATCH, WRAP).apply { topMargin = dp(4) })

        lateinit var dlg: AlertDialog
        fun act(label: String, f: () -> Unit) {
            box.addView(smallPill(label) { dlg.dismiss(); f() }, lp(MATCH, WRAP).apply { topMargin = dp(8) })
        }
        act("📍  Quitar ubicación") {
            performEdit(listOf(p), "Ubicación quitada", { e -> Meta.clear(e, Meta.GPS_TAGS) }) {
                p.hasGps = false
                renderExplore()
            }
        }
        act("🧽  Quitar todos los datos personales") {
            performEdit(listOf(p), "Datos personales quitados", { e ->
                Meta.clear(e, Meta.GPS_TAGS)
                Meta.clear(e, Meta.PERSONAL_TAGS)
            }) {
                p.hasGps = false
                renderExplore()
            }
        }
        act("📅  Cambiar fecha y hora") { editDate(p) }
        act("✏️  Descripción") { editTag(p, "Descripción", ExifInterface.TAG_IMAGE_DESCRIPTION) }
        act("👤  Autor") { editTag(p, "Autor", ExifInterface.TAG_ARTIST) }
        act("©  Copyright") { editTag(p, "Copyright", ExifInterface.TAG_COPYRIGHT) }

        dlg = AlertDialog.Builder(this)
            .setTitle("Datos de la foto")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Cerrar", null)
            .create()
        dlg.show()
    }

    private fun editDate(p: Photo) {
        val c = Calendar.getInstance().apply { timeInMillis = p.taken }
        DatePickerDialog(this, { _, y, m, day ->
            TimePickerDialog(this, { _, h, mi ->
                val v = String.format(Locale.US, "%04d:%02d:%02d %02d:%02d:00", y, m + 1, day, h, mi)
                performEdit(listOf(p), "Fecha cambiada", { e -> for (t in Meta.DATE_TAGS) e.setAttribute(t, v) })
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun editTag(p: Photo, label: String, tag: String) {
        Thread {
            val cur = try {
                contentResolver.openInputStream(p.uri)?.use { ExifInterface(it).getAttribute(tag) }
            } catch (_: Throwable) { null }
            ui {
                val et = EditText(this).apply {
                    setText(cur ?: "")
                    hint = label
                }
                val wrap = FrameLayout(this).apply {
                    setPadding(dp(22), dp(8), dp(22), 0)
                    addView(et)
                }
                AlertDialog.Builder(this)
                    .setTitle(label)
                    .setView(wrap)
                    .setNegativeButton("Cancelar", null)
                    .setNeutralButton("Borrar") { _, _ ->
                        performEdit(listOf(p), "$label borrado", { e -> e.setAttribute(tag, null) })
                    }
                    .setPositiveButton("Guardar") { _, _ ->
                        val v = et.text.toString().trim()
                        performEdit(listOf(p), "$label guardado", { e -> e.setAttribute(tag, v.ifEmpty { null }) })
                    }
                    .show()
            }
        }.start()
    }

    /** Escribe en los archivos originales. En Android 11+ el sistema pide permiso una vez para todo el lote. */
    private fun performEdit(ps: List<Photo>, msg: String, change: (ExifInterface) -> Unit, after: () -> Unit = {}) {
        if (ps.isEmpty()) return
        val job: () -> Unit = {
            if (ps.size > 5) toast("Guardando cambios en ${ps.size} fotos…")
            Thread {
                var ok = 0
                var fail = 0
                var rec: RecoverableSecurityException? = null
                for (p in ps) {
                    try {
                        Meta.edit(contentResolver, p.uri, change)
                        ok++
                    } catch (e: RecoverableSecurityException) {
                        rec = e
                        fail++
                    } catch (_: Throwable) {
                        fail++
                    }
                }
                val r = rec
                ui {
                    if (r != null && ok == 0) {
                        pendingWrite = { performEdit(ps, msg, change, after) }
                        try {
                            startIntentSenderForResult(r.userAction.actionIntent.intentSender, REQ_WRITE, null, 0, 0, 0)
                        } catch (_: Exception) {
                            toast("No tengo permiso para modificar esta foto")
                        }
                    } else {
                        if (ok > 0) after()
                        toast(if (fail == 0) "$msg ✓" else "$msg en $ok; $fail no admiten cambios (p. ej. HEIC)")
                    }
                }
            }.start()
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val pi = MediaStore.createWriteRequest(contentResolver, ps.map { it.uri })
                pendingWrite = job
                startIntentSenderForResult(pi.intentSender, REQ_WRITE, null, 0, 0, 0)
            } catch (e: Exception) {
                toast("No se pudo pedir permiso: ${e.message}")
            }
        } else {
            job()
        }
    }

    // ---------------------------------------------------------------- Borrado

    private fun deleteSelected() {
        val seen = HashSet<Long>()
        val targets = current.flatMap { g -> g.photos.filter { it.id in g.delete } }.filter { seen.add(it.id) }
        if (targets.isEmpty()) return
        deletePhotos(targets)
    }

    private fun deletePhotos(targets: List<Photo>) {
        if (targets.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val pi = MediaStore.createTrashRequest(contentResolver, targets.map { it.uri }, true)
                pendingTrash = targets.map { it.id }.toSet()
                startIntentSenderForResult(pi.intentSender, REQ_TRASH, null, 0, 0, 0)
            } catch (e: Exception) {
                toast("No se pudo mover a la papelera: ${e.message}")
            }
        } else {
            AlertDialog.Builder(this)
                .setTitle("Borrar ${targets.size} fotos")
                .setMessage("Tu versión de Android no tiene papelera: se borrarán definitivamente (${mb(targets.sumOf { it.size })}). ¿Continuar?")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Borrar") { _, _ ->
                    Thread {
                        val done = HashSet<Long>()
                        for (p in targets) {
                            try { if (contentResolver.delete(p.uri, null, null) > 0) done.add(p.id) } catch (_: Exception) { }
                        }
                        ui { removeDone(done, "Borradas") }
                    }.start()
                }
                .show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_TRASH && resultCode == RESULT_OK) removeDone(pendingTrash, "Movidas a la papelera")
        if (requestCode == REQ_WRITE) {
            val job = pendingWrite
            pendingWrite = null
            if (resultCode == RESULT_OK) job?.invoke() else toast("Cambio cancelado")
        }
    }

    private fun removeDone(ids: Set<Long>, verb: String) {
        var freed = 0L
        var count = 0
        val seen = HashSet<Long>()
        for (g in dupGroups + blurGroups) {
            val iter = g.photos.iterator()
            while (iter.hasNext()) {
                val p = iter.next()
                if (p.id in ids) {
                    if (seen.add(p.id)) { freed += p.size; count++ }
                    iter.remove()
                    g.delete.remove(p.id)
                }
            }
        }
        dupGroups.removeAll { it.photos.size < 2 }
        for (g in dupGroups) if (g.photos.all { it.id in g.delete }) scanner.remark(g, settings)
        blurGroups.removeAll { it.photos.isEmpty() }
        allPhotos = allPhotos.filter { it.id !in ids }
        if (explorePhotos.any { it.id in ids }) {
            explorePhotos = explorePhotos.filter { it.id !in ids }
            people = people.mapNotNull { pr ->
                val f = pr.faces.filter { it.first.id !in ids }
                if (f.map { it.first.id }.distinct().size >= ContentAnalyzer.MIN_FACES_PER_PERSON) Person(pr.keys, pr.name, f) else null
            }
            renderExplore()
        }
        gridListener?.invoke(ids)
        groupAdapter.notifyDataSetChanged()
        updateUi()
        toast("$verb $count fotos · ${mb(freed)} liberados")
    }
}
