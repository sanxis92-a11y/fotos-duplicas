package com.sanxis.fotosduplicadas

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class Decoded(val bmp: Bitmap, val w: Int, val h: Int)

/** Todo el análisis ocurre aquí, en el propio móvil. */
class Scanner(private val cr: ContentResolver, private val dir: File) {

    companion object {
        const val BLUR_MAX = 40.0      // por debajo: posiblemente borrosa
        const val MIN_CONTRAST = 18.0  // por debajo: foto lisa (cielo, pared), no se marca como borrosa
        const val MAX_BLURRY = 300
    }

    @Volatile var cancel = false

    // ------------------------------------------------------------ Galería

    fun query(s: Settings): List<Photo> {
        val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        @Suppress("DEPRECATION")
        val pathCol = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA
        val proj = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE, MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.BUCKET_DISPLAY_NAME, pathCol
        )
        val excluded = s.excluded
        val chosen = s.folders
        val scope = s.scope
        val out = ArrayList<Photo>()
        val minBytes = s.minKb * 1024L
        cr.query(
            base, proj, "${MediaStore.Images.Media.SIZE} >= ?", arrayOf(minBytes.toString()),
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val folder = c.getString(7) ?: ""
                val path = c.getString(8) ?: ""
                val camera = folder.equals("Camera", true) || path.contains("DCIM/Camera", true)
                if (folder in excluded) continue
                if (scope == 1 && !camera) continue
                if (scope == 2 && folder !in chosen) continue
                val id = c.getLong(0)
                val modified = c.getLong(6)
                var taken = c.getLong(5)
                if (taken <= 0) taken = modified * 1000
                out += Photo(
                    id, ContentUris.withAppendedId(base, id), c.getString(1) ?: "", folder, camera,
                    c.getInt(2), c.getInt(3), c.getLong(4), taken, modified
                )
            }
        }
        return out
    }

    fun folders(): List<Pair<String, Int>> {
        val m = HashMap<String, Int>()
        cr.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME), null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val f = c.getString(0) ?: continue
                m[f] = (m[f] ?: 0) + 1
            }
        }
        return m.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    // ------------------------------------------------------------ Decodificar

    /** atMost=false: lado largo entre side y 2·side. atMost=true: lado largo ≤ side. */
    fun decode(uri: Uri, side: Int, atMost: Boolean = false): Decoded? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val ins = cr.openInputStream(uri) ?: return null
        ins.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        val long = max(o.outWidth, o.outHeight)
        var s = 1
        if (atMost) { while (long / s > side) s *= 2 }
        else { while (long / (s * 2) >= side) s *= 2 }
        val o2 = BitmapFactory.Options().apply { inSampleSize = s }
        val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: return null
        return Decoded(bmp, o.outWidth, o.outHeight)
    }

    /** Imagen para ver en grande, con la orientación EXIF aplicada. */
    fun decodeForView(uri: Uri): Bitmap? {
        val b = decode(uri, 2048, atMost = true)?.bmp ?: return null
        val orient = try {
            cr.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) } ?: 1
        } catch (_: Exception) { 1 }
        val deg = when (orient) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
        if (deg == 0f) return b
        val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)
        if (r != b) b.recycle()
        return r
    }

    // ------------------------------------------------------------ Análisis

    fun analyze(p: Photo, withBlur: Boolean) {
        try {
            val d = decode(p.uri, if (withBlur) 512 else 160) ?: return
            p.w = d.w
            p.h = d.h
            val w = d.bmp.width
            val h = d.bmp.height
            val g = gray(d.bmp)
            d.bmp.recycle()

            if (withBlur) {
                val (lv, sd) = lapVar(g, w, h)
                p.blur = lv
                p.contrast = sd
            }
            var cur = g
            var cw = w
            var ch = h
            for (k in 0 until 4) {
                val hp = dhash(cur, cw, ch)
                p.hashes[2 * k] = hp.first
                p.hashes[2 * k + 1] = hp.second
                if (k < 3) {
                    cur = rot90(cur, cw, ch)
                    val t = cw; cw = ch; ch = t
                }
            }
            val fp = dhash(flipH(g, w, h), w, h)
            p.hashes[8] = fp.first
            p.hashes[9] = fp.second
            var sum = 0L
            for (v in g) sum += v
            p.lum = (sum / max(1, g.size)).toInt()
            p.valid = true
        } catch (_: Throwable) {
            // archivo dañado o formato no soportado: se ignora
        }
    }

    fun sharpness(uri: Uri): Double = try {
        val d = decode(uri, 1024)
        if (d == null) 0.0 else {
            val w = d.bmp.width
            val h = d.bmp.height
            val g = gray(d.bmp)
            d.bmp.recycle()
            lapVar(g, w, h).first
        }
    } catch (_: Throwable) { 0.0 }

    // ------------------------------------------------------------ Agrupar

    fun dist(a: Photo, b: Photo, rotated: Boolean): Int {
        var best = java.lang.Long.bitCount(a.hashes[0] xor b.hashes[0]) +
            java.lang.Long.bitCount(a.hashes[1] xor b.hashes[1])
        if (rotated) {
            for (k in 1..4) {
                val d = java.lang.Long.bitCount(a.hashes[0] xor b.hashes[2 * k]) +
                    java.lang.Long.bitCount(a.hashes[1] xor b.hashes[2 * k + 1])
                if (d < best) best = d
            }
        }
        return best
    }

    fun cluster(list: List<Photo>, thr: Int, rotated: Boolean, windowMs: Long): List<List<Photo>>? {
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
            if (cancel) return null
            val a = ps[i]
            val ar = a.ratio
            var j = i + 1
            while (j < n && ps[j].lum - a.lum <= 20) {
                val b = ps[j]
                if (abs(ar - b.ratio) < 0.08 &&
                    (windowMs <= 0L || abs(a.taken - b.taken) <= windowMs) &&
                    dist(a, b, rotated) <= thr
                ) {
                    val ra = find(i)
                    val rb = find(j)
                    if (ra != rb) parent[ra] = rb
                }
                j++
            }
        }
        return (0 until n).groupBy { find(it) }.values.filter { it.size > 1 }.map { idx -> idx.map { ps[it] } }
    }

    fun buildDuplicates(
        list: List<Photo>, s: Settings, progress: (Int, String) -> Unit, base: Int, span: Int
    ): MutableList<Group>? {
        val raw = cluster(list, s.threshold, s.rotated, Settings.WINDOWS[s.timeWindow]) ?: return null
        val ignored = loadIgnored()
        val groups = raw.filter { g ->
            val ids = g.map { it.id }
            ignored.none { it.containsAll(ids) }
        }
        val members = groups.flatten()
        for ((i, p) in members.withIndex()) {
            if (cancel) return null
            if (p.sharp < 0) p.sharp = sharpness(p.uri)
            if (i % 5 == 0) {
                progress(base + i * span / max(1, members.size), "Evaluando calidad ${i + 1} de ${members.size}…")
            }
        }
        return groups.map { makeGroup(it, s) }.sortedByDescending { it.reclaim() }.toMutableList()
    }

    fun makeGroup(g: List<Photo>, s: Settings): Group {
        val best = g.reduce { a, b -> if (better(b, a, s.keepRule, s.preferCamera)) b else a }
        val exact = g.all { it === best || (dist(it, best, false) == 0 && it.pixels == best.pixels) }
        val ordered = (listOf(best) + g.filter { it !== best }.sortedByDescending { it.pixels }).toMutableList()
        val gr = Group(if (exact) Kind.COPIES else Kind.SIMILAR, ordered, best.id)
        for (p in g) if (p !== best) gr.delete.add(p.id)
        return gr
    }

    /** Vuelve a elegir la mejor foto y a marcar el resto, con las reglas actuales. */
    fun remark(gr: Group, s: Settings) {
        if (gr.photos.isEmpty()) return
        val best = gr.photos.reduce { a, b -> if (better(b, a, s.keepRule, s.preferCamera)) b else a }
        val rest = gr.photos.filter { it !== best }.sortedByDescending { it.pixels }
        gr.photos.clear()
        gr.photos.add(best)
        gr.photos.addAll(rest)
        gr.bestId = best.id
        gr.delete.clear()
        for (p in rest) gr.delete.add(p.id)
    }

    fun buildBlurry(list: List<Photo>): MutableList<Group> =
        list.filter { it.blur >= 0.0 && it.blur < BLUR_MAX && it.contrast >= MIN_CONTRAST }
            .sortedBy { it.blur }
            .take(MAX_BLURRY)
            .chunked(12)
            .map { Group(Kind.BLURRY, it.toMutableList(), -1L) }
            .toMutableList()

    /** ¿a es mejor que b según la regla elegida? */
    fun better(a: Photo, b: Photo, rule: Int, preferCamera: Boolean): Boolean {
        when (rule) {
            1 -> if (a.pixels != b.pixels) return a.pixels > b.pixels
            2 -> if (a.size != b.size) return a.size > b.size
            3 -> if (a.taken != b.taken) return a.taken < b.taken
            4 -> if (a.taken != b.taken) return a.taken > b.taken
        }
        val pa = a.pixels
        val pb = b.pixels
        if (pa > pb * 105 / 100) return true
        if (pb > pa * 105 / 100) return false
        if (a.sharp > b.sharp * 1.10) return true
        if (b.sharp > a.sharp * 1.10) return false
        if (preferCamera && a.isCamera != b.isCamera) return a.isCamera
        if (a.size != b.size) return a.size > b.size
        return a.taken <= b.taken
    }

    // ------------------------------------------------------------ Caché de huellas


    private fun cacheFile() = File(dir, "hashes_v2.txt")

    fun loadCache(): Map<Long, List<String>> {
        val m = HashMap<Long, List<String>>()
        val f = cacheFile()
        if (!f.exists()) return m
        try {
            f.forEachLine { line ->
                val s = line.split(';')
                if (s.size == 18) {
                    val id = s[0].toLongOrNull()
                    if (id != null) m[id] = s
                }
            }
        } catch (_: Exception) { }
        return m
    }

    /** Aplica la entrada de caché si sigue siendo válida. Devuelve false si hay que analizar. */
    fun applyCache(p: Photo, s: List<String>?, needBlur: Boolean): Boolean {
        if (s == null) return false
        return try {
            if (s[1].toLong() != p.size || s[2].toLong() != p.modified) return false
            val blur = s[6].toDouble()
            if (needBlur && blur < 0) return false
            p.w = s[3].toInt()
            p.h = s[4].toInt()
            p.lum = s[5].toInt()
            p.blur = blur
            p.contrast = s[7].toDouble()
            for (k in 0 until 10) p.hashes[k] = s[8 + k].toLong()
            p.valid = true
            true
        } catch (_: Exception) { false }
    }

    fun saveCache(photos: List<Photo>) {
        try {
            cacheFile().bufferedWriter().use { w ->
                for (p in photos) if (p.valid) {
                    w.write("${p.id};${p.size};${p.modified};${p.w};${p.h};${p.lum};${p.blur};${p.contrast}")
                    for (v in p.hashes) w.write(";$v")
                    w.write("\n")
                }
            }
        } catch (_: Exception) { }
    }

    // ------------------------------------------------------------ "No son iguales"

    private fun ignoredFile() = File(dir, "ignored_v1.txt")

    fun loadIgnored(): List<Set<Long>> {
        val f = ignoredFile()
        if (!f.exists()) return emptyList()
        return try {
            f.readLines().map { l -> l.split(',').mapNotNull { it.toLongOrNull() }.toSet() }.filter { it.size > 1 }
        } catch (_: Exception) { emptyList() }
    }

    fun addIgnored(ids: Collection<Long>) {
        try { ignoredFile().appendText(ids.joinToString(",") + "\n") } catch (_: Exception) { }
    }

    fun clearIgnored() { ignoredFile().delete() }
}

// ---------------------------------------------------------------- Utilidades de imagen

fun gray(b: Bitmap): IntArray {
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

/** (varianza del Laplaciano, desviación típica del gris) */
fun lapVar(g: IntArray, w: Int, h: Int): Pair<Double, Double> {
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
    val lv = if (n == 0) 0.0 else sq / n - (s / n) * (s / n)
    var gs = 0.0
    var gsq = 0.0
    for (v in g) { gs += v; gsq += v.toDouble() * v }
    val m = gs / max(1, g.size)
    val sd = sqrt(max(0.0, gsq / max(1, g.size) - m * m))
    return Pair(lv, sd)
}

fun pool(g: IntArray, w: Int, h: Int, cols: Int, rows: Int): FloatArray {
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

fun dhash(g: IntArray, w: Int, h: Int): Pair<Long, Long> {
    val a = pool(g, w, h, 9, 8)
    var h1 = 0L
    var bit = 0
    for (r in 0 until 8) for (c in 0 until 8) {
        if (a[r * 9 + c] > a[r * 9 + c + 1]) h1 = h1 or (1L shl bit)
        bit++
    }
    val b = pool(g, w, h, 8, 9)
    var h2 = 0L
    bit = 0
    for (r in 0 until 8) for (c in 0 until 8) {
        if (b[r * 8 + c] > b[(r + 1) * 8 + c]) h2 = h2 or (1L shl bit)
        bit++
    }
    return Pair(h1, h2)
}

/** Giro 90° horario. La imagen resultante mide h × w. */
fun rot90(g: IntArray, w: Int, h: Int): IntArray {
    val out = IntArray(w * h)
    for (y2 in 0 until w) for (x2 in 0 until h) out[y2 * h + x2] = g[(h - 1 - x2) * w + y2]
    return out
}

fun flipH(g: IntArray, w: Int, h: Int): IntArray {
    val out = IntArray(w * h)
    for (y in 0 until h) for (x in 0 until w) out[y * w + x] = g[y * w + (w - 1 - x)]
    return out
}
