package com.sanxis.fotosduplicadas

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Una foto de la galería con su huella visual y su puntuación de nitidez. */
class Photo(
    val id: Long, val uri: Uri, val name: String,
    var w: Int, var h: Int, val size: Long, val date: Long, val modified: Long
) {
    var h1 = 0L      // dHash horizontal (64 bits)
    var h2 = 0L      // dHash vertical (64 bits)
    var lum = 0      // brillo medio 0..255
    var sharp = -1.0 // varianza del Laplaciano (más alto = más nítida)
    var valid = false
    val pixels: Long get() = w.toLong() * h
    val ratio: Double get() = if (w > 0 && h > 0) max(w, h).toDouble() / min(w, h) else 1.0
}

class Group(val photos: MutableList<Photo>, val bestId: Long) {
    val delete = HashSet<Long>()
    fun reclaim(): Long = photos.filter { it.id in delete }.sumOf { it.size }
}

class MainActivity : Activity() {

    companion object {
        const val REQ_PERM = 1
        const val REQ_TRASH = 2
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        val GREEN = Color.parseColor("#2E7D32")
        val RED = Color.parseColor("#C62828")
        val DARK = Color.parseColor("#1A1A1A")
        val GREY = Color.parseColor("#5F6368")
        // Umbral de diferencia (sobre 128 bits) para cada nivel de sensibilidad
        val THRESHOLDS = intArrayOf(6, 12, 20)
    }

    private val main = Handler(Looper.getMainLooper())
    private val thumbPool = Executors.newFixedThreadPool(3)
    private val thumbs = object : LruCache<Long, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }

    private var groups = mutableListOf<Group>()
    @Volatile private var scanning = false
    @Volatile private var cancel = false
    private var pendingTrash: Set<Long> = emptySet()

    private lateinit var scanBtn: Button
    private lateinit var deleteBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var sens: RadioGroup
    private val groupAdapter = GroupAdapter()

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun ui(f: () -> Unit) { main.post(f) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun mb(b: Long): String =
        if (b >= (1L shl 30)) String.format(Locale.getDefault(), "%.2f GB", b / 1073741824.0)
        else String.format(Locale.getDefault(), "%.1f MB", b / 1048576.0)
    private fun mp(p: Photo): String = String.format(Locale.getDefault(), "%.1f MP", p.pixels / 1_000_000.0)
    private val trashVerb: String get() = if (Build.VERSION.SDK_INT >= 30) "Mover a la papelera" else "Borrar"

    // ---------------------------------------------------------------- UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F3F4F7"))
            setPadding(pad, pad, pad, pad / 2)
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(b.left + pad, b.top + pad, b.right + pad, b.bottom + pad / 2)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    insets.systemWindowInsetLeft + pad, insets.systemWindowInsetTop + pad,
                    insets.systemWindowInsetRight + pad, insets.systemWindowInsetBottom + pad / 2
                )
            }
            insets
        }

        root.addView(TextView(this).apply {
            text = "Fotos duplicadas"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(DARK)
        })
        root.addView(TextView(this).apply {
            text = "100 % offline: la app no tiene permiso de Internet. Nada sale del móvil."
            textSize = 13f
            setTextColor(GREY)
            setPadding(0, dp(2), 0, dp(12))
        })
        root.addView(TextView(this).apply {
            text = "Sensibilidad"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(DARK)
        })
        sens = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        listOf("Casi idénticas", "Normal", "Ráfagas").forEachIndexed { i, s ->
            sens.addView(RadioButton(this).apply { id = 100 + i; text = s; textSize = 14f })
        }
        sens.check(101)
        root.addView(sens)

        scanBtn = Button(this).apply {
            text = "Buscar duplicadas"
            setOnClickListener { onScanClick() }
        }
        root.addView(scanBtn, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            max = 100
        }
        root.addView(progress, MATCH, WRAP)

        status = TextView(this).apply {
            textSize = 13f
            setTextColor(DARK)
            setPadding(0, dp(6), 0, dp(6))
            text = "Pulsa «Buscar duplicadas». La mejor de cada grupo se conserva y el resto se marca para borrar."
        }
        root.addView(status)

        val list = ListView(this).apply {
            adapter = groupAdapter
            divider = null
            dividerHeight = 0
        }
        root.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))

        deleteBtn = Button(this).apply {
            text = trashVerb
            isEnabled = false
            setOnClickListener { deleteSelected() }
        }
        root.addView(deleteBtn, MATCH, WRAP)

        setContentView(root)
    }

    override fun onDestroy() {
        cancel = true
        thumbPool.shutdownNow()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Permisos

    private fun perms(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        Build.VERSION.SDK_INT >= 30 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private fun hasPerms() = perms().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun onScanClick() {
        if (scanning) { cancel = true; return }
        if (hasPerms()) startScan() else requestPermissions(perms(), REQ_PERM)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) {
            if (hasPerms()) startScan()
            else status.text = "Sin permiso para leer tus fotos no puedo buscar duplicadas."
        }
    }

    // ---------------------------------------------------------------- Escaneo

    private fun startScan() {
        scanning = true
        cancel = false
        val thr = THRESHOLDS[(sens.checkedRadioButtonId - 100).coerceIn(0, 2)]
        scanBtn.text = "Cancelar"
        deleteBtn.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.progress = 0
        groups = mutableListOf()
        groupAdapter.notifyDataSetChanged()

        Thread {
            var error: String? = null
            val result = try { scan(thr) } catch (e: Throwable) { error = e.message ?: e.toString(); null }
            ui {
                scanning = false
                scanBtn.text = "Buscar duplicadas"
                progress.visibility = View.GONE
                when {
                    result != null -> { groups = result; groupAdapter.notifyDataSetChanged(); updateSummary() }
                    error != null -> status.text = "Error: $error"
                    else -> status.text = "Búsqueda cancelada."
                }
            }
        }.start()
    }

    private fun scan(thr: Int): MutableList<Group>? {
        ui { status.text = "Leyendo la galería…" }
        val photos = queryPhotos()
        val cache = loadCache()
        val total = photos.size

        // 1) Huella visual de cada foto (con caché para que la segunda vez vuele)
        for ((i, p) in photos.withIndex()) {
            if (cancel) return null
            val c = cache[p.id]
            if (c != null && c.size == p.size && c.modified == p.modified) {
                p.w = c.w; p.h = c.h; p.h1 = c.h1; p.h2 = c.h2; p.lum = c.lum; p.valid = true
            } else {
                hashPhoto(p)
            }
            if (i % 25 == 0) {
                val pct = i * 80 / max(1, total)
                ui { progress.progress = pct; status.text = "Analizando ${i + 1} de $total fotos…" }
            }
        }
        saveCache(photos)

        // 2) Agrupar las parecidas
        ui { status.text = "Comparando…" }
        val raw = cluster(photos.filter { it.valid }, thr)
        if (cancel) return null

        // 3) Nitidez solo de las que están en algún grupo
        val members = raw.flatten()
        for ((i, p) in members.withIndex()) {
            if (cancel) return null
            if (p.sharp < 0) p.sharp = sharpness(p.uri)
            if (i % 5 == 0) {
                val pct = 80 + i * 20 / max(1, members.size)
                ui { progress.progress = pct; status.text = "Evaluando calidad ${i + 1} de ${members.size}…" }
            }
        }

        // 4) Elegir la mejor de cada grupo y marcar el resto
        return raw.map { g ->
            val best = g.reduce { a, b -> if (better(b, a)) b else a }
            val ordered = (listOf(best) + g.filter { it !== best }.sortedByDescending { it.pixels }).toMutableList()
            Group(ordered, best.id).also { gr -> g.forEach { if (it !== best) gr.delete.add(it.id) } }
        }.sortedByDescending { it.reclaim() }.toMutableList()
    }

    private fun queryPhotos(): List<Photo> {
        val out = ArrayList<Photo>()
        val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED
        )
        contentResolver.query(base, proj, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out += Photo(
                    id, ContentUris.withAppendedId(base, id), c.getString(1) ?: "",
                    c.getInt(2), c.getInt(3), c.getLong(4), c.getLong(5), c.getLong(6)
                )
            }
        }
        return out
    }

    // ---------------------------------------------------------------- Imagen

    private class Decoded(val bmp: Bitmap, val w: Int, val h: Int)

    /** Decodifica reducida (lado largo entre side y 2*side) y devuelve también el tamaño real. */
    private fun decode(uri: Uri, side: Int): Decoded? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val ins = contentResolver.openInputStream(uri) ?: return null
        ins.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var s = 1
        while (max(o.outWidth, o.outHeight) / (s * 2) >= side) s *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = s }
        val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: return null
        return Decoded(bmp, o.outWidth, o.outHeight)
    }

    private fun gray(b: Bitmap): IntArray {
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            px[i] = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }
        return px
    }

    /** Media por celdas (reducción con antialiasing). */
    private fun pool(g: IntArray, w: Int, h: Int, cols: Int, rows: Int): FloatArray {
        val out = FloatArray(cols * rows)
        for (r in 0 until rows) for (c in 0 until cols) {
            val x0 = c * w / cols
            val x1 = max(x0 + 1, (c + 1) * w / cols)
            val y0 = r * h / rows
            val y1 = max(y0 + 1, (r + 1) * h / rows)
            var sum = 0L
            for (y in y0 until y1) for (x in x0 until x1) sum += g[y * w + x]
            out[r * cols + c] = sum.toFloat() / ((x1 - x0) * (y1 - y0))
        }
        return out
    }

    private fun hashPhoto(p: Photo) {
        try {
            val d = decode(p.uri, 160) ?: return
            p.w = d.w
            p.h = d.h
            val bw = d.bmp.width
            val bh = d.bmp.height
            val g = gray(d.bmp)
            d.bmp.recycle()

            val a = pool(g, bw, bh, 9, 8)
            var h1 = 0L
            var bit = 0
            for (r in 0 until 8) for (c in 0 until 8) {
                if (a[r * 9 + c] > a[r * 9 + c + 1]) h1 = h1 or (1L shl bit)
                bit++
            }
            val b = pool(g, bw, bh, 8, 9)
            var h2 = 0L
            bit = 0
            for (r in 0 until 8) for (c in 0 until 8) {
                if (b[r * 8 + c] > b[(r + 1) * 8 + c]) h2 = h2 or (1L shl bit)
                bit++
            }
            p.h1 = h1
            p.h2 = h2
            p.lum = (g.fold(0L) { acc, v -> acc + v } / max(1, g.size)).toInt()
            p.valid = true
        } catch (_: Throwable) {
            // Archivo corrupto o formato no soportado: se ignora
        }
    }

    /** Nitidez = varianza del Laplaciano sobre la imagen a ~1–2 MP. */
    private fun sharpness(uri: Uri): Double = try {
        val d = decode(uri, 1024)
        if (d == null) 0.0 else {
            val w = d.bmp.width
            val h = d.bmp.height
            val g = gray(d.bmp)
            d.bmp.recycle()
            var s = 0.0
            var sq = 0.0
            var n = 0
            for (y in 1 until h - 1) {
                var i = y * w + 1
                for (x in 1 until w - 1) {
                    val l = (4 * g[i] - g[i - 1] - g[i + 1] - g[i - w] - g[i + w]).toDouble()
                    s += l; sq += l * l; n++; i++
                }
            }
            if (n == 0) 0.0 else sq / n - (s / n) * (s / n)
        }
    } catch (_: Throwable) { 0.0 }

    private fun cluster(list: List<Photo>, thr: Int): List<List<Photo>> {
        val ps = list.sortedBy { it.lum }
        val n = ps.size
        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var y = x
            while (parent[y] != r) { val nx = parent[y]; parent[y] = r; y = nx }
            return r
        }
        for (i in 0 until n) {
            if (cancel) break
            val a = ps[i]
            val ar = a.ratio
            var j = i + 1
            while (j < n && ps[j].lum - a.lum <= 20) {
                val b = ps[j]
                if (abs(ar - b.ratio) < 0.08) {
                    val d = java.lang.Long.bitCount(a.h1 xor b.h1) + java.lang.Long.bitCount(a.h2 xor b.h2)
                    if (d <= thr) {
                        val ra = find(i)
                        val rb = find(j)
                        if (ra != rb) parent[ra] = rb
                    }
                }
                j++
            }
        }
        return (0 until n).groupBy { find(it) }.values.filter { it.size > 1 }.map { idx -> idx.map { ps[it] } }
    }

    /** ¿a es mejor que b? Resolución > nitidez > peso del archivo (menos compresión) > la más antigua (original). */
    private fun better(a: Photo, b: Photo): Boolean {
        val pa = a.pixels
        val pb = b.pixels
        if (pa > pb * 105 / 100) return true
        if (pb > pa * 105 / 100) return false
        if (a.sharp > b.sharp * 1.10) return true
        if (b.sharp > a.sharp * 1.10) return false
        if (a.size != b.size) return a.size > b.size
        return a.date <= b.date
    }

    // ---------------------------------------------------------------- Caché

    private class Cached(val size: Long, val modified: Long, val w: Int, val h: Int, val h1: Long, val h2: Long, val lum: Int)

    private fun cacheFile() = File(filesDir, "hashes_v1.txt")

    private fun loadCache(): Map<Long, Cached> {
        val m = HashMap<Long, Cached>()
        val f = cacheFile()
        if (!f.exists()) return m
        try {
            f.forEachLine { line ->
                val s = line.split(';')
                if (s.size == 8) {
                    try {
                        m[s[0].toLong()] = Cached(
                            s[1].toLong(), s[2].toLong(), s[3].toInt(), s[4].toInt(),
                            s[5].toLong(), s[6].toLong(), s[7].toInt()
                        )
                    } catch (_: Exception) { }
                }
            }
        } catch (_: Exception) { }
        return m
    }

    private fun saveCache(photos: List<Photo>) {
        try {
            cacheFile().bufferedWriter().use { w ->
                for (p in photos) if (p.valid) {
                    w.write("${p.id};${p.size};${p.modified};${p.w};${p.h};${p.h1};${p.h2};${p.lum}\n")
                }
            }
        } catch (_: Exception) { }
    }

    // ---------------------------------------------------------------- Lista

    private fun loadThumb(p: Photo, iv: ImageView) {
        iv.tag = p.id
        val cached = thumbs.get(p.id)
        if (cached != null) { iv.setImageBitmap(cached); return }
        iv.setImageDrawable(null)
        try {
            thumbPool.execute {
                val bmp: Bitmap? = try {
                    if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(p.uri, Size(256, 256), null)
                    else decode(p.uri, 256)?.bmp
                } catch (_: Throwable) { null }
                if (bmp != null) {
                    thumbs.put(p.id, bmp)
                    ui { if (iv.tag == p.id) iv.setImageBitmap(bmp) }
                }
            }
        } catch (_: Exception) { }
    }

    inner class GroupAdapter : BaseAdapter() {
        override fun getCount(): Int = groups.size
        override fun getItem(position: Int): Any = groups[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val ctx = this@MainActivity
            val outer = (convertView as? FrameLayout) ?: FrameLayout(ctx).apply {
                setPadding(0, dp(6), 0, dp(6))
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(12).toFloat() }
                    setPadding(dp(12), dp(10), dp(12), dp(12))
                }, FrameLayout.LayoutParams(MATCH, WRAP))
            }
            val card = outer.getChildAt(0) as LinearLayout
            card.removeAllViews()
            val g = groups[position]
            card.addView(TextView(ctx).apply {
                text = "Grupo ${position + 1} · ${g.photos.size} fotos · liberas ${mb(g.reclaim())}"
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(DARK)
            })
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (p in g.photos) row.addView(cell(g, p))
            card.addView(HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(0, dp(8), 0, 0)
                addView(row)
            })
            return outer
        }
    }

    private fun cell(g: Group, p: Photo): View {
        val del = p.id in g.delete
        val side = dp(112)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(8), 0)
        }
        val frame = FrameLayout(this).apply {
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = GradientDrawable().apply {
                setColor(if (del) RED else GREEN)
                cornerRadius = dp(8).toFloat()
            }
        }
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#E0E0E0"))
            alpha = if (del) 0.45f else 1f
        }
        frame.addView(iv, FrameLayout.LayoutParams(side, side))
        frame.addView(TextView(this).apply {
            text = if (del) "BORRAR" else "CONSERVAR"
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setBackgroundColor(if (del) RED else GREEN)
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START))
        col.addView(frame)
        col.addView(TextView(this).apply {
            text = (if (p.id == g.bestId) "★ Mejor\n" else "") + "${mp(p)} · ${mb(p.size)}"
            textSize = 11f
            setTextColor(if (p.id == g.bestId) GREEN else GREY)
            maxWidth = side + dp(6)
            setPadding(dp(2), dp(4), 0, 0)
        })
        loadThumb(p, iv)
        frame.setOnClickListener { toggle(g, p) }
        frame.setOnLongClickListener { openPhoto(p); true }
        return col
    }

    private fun toggle(g: Group, p: Photo) {
        if (p.id in g.delete) {
            g.delete.remove(p.id)
        } else {
            if (g.photos.count { it.id !in g.delete } <= 1) {
                toast("Tiene que quedar al menos una foto de cada grupo")
                return
            }
            g.delete.add(p.id)
        }
        groupAdapter.notifyDataSetChanged()
        updateSummary()
    }

    private fun openPhoto(p: Photo) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(p.uri, "image/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (_: Exception) {
            toast("No hay ningún visor de imágenes instalado")
        }
    }

    private fun updateSummary() {
        val n = groups.sumOf { it.delete.size }
        val bytes = groups.sumOf { it.reclaim() }
        status.text = if (groups.isEmpty()) "No he encontrado fotos repetidas."
        else "${groups.size} grupos · $n fotos marcadas · ${mb(bytes)}\n" +
            "Toca una foto para cambiar su marca; mantén pulsado para verla en grande."
        deleteBtn.isEnabled = n > 0
        deleteBtn.text = if (n > 0) "$trashVerb $n fotos (${mb(bytes)})" else trashVerb
    }

    // ---------------------------------------------------------------- Borrado

    private fun deleteSelected() {
        val targets = groups.flatMap { g -> g.photos.filter { it.id in g.delete } }
        if (targets.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 30) {
            // Papelera del sistema: recuperable durante 30 días. Android pide confirmación propia.
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
    }

    private fun removeDone(ids: Set<Long>, verb: String) {
        var freed = 0L
        var count = 0
        for (g in groups) {
            val iter = g.photos.iterator()
            while (iter.hasNext()) {
                val p = iter.next()
                if (p.id in ids) {
                    freed += p.size
                    count++
                    iter.remove()
                    g.delete.remove(p.id)
                }
            }
        }
        groups.removeAll { it.photos.size < 2 }
        groupAdapter.notifyDataSetChanged()
        updateSummary()
        toast("$verb $count fotos · ${mb(freed)} liberados")
    }
}
