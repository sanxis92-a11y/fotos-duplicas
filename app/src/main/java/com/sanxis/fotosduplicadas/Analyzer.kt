package com.sanxis.fotosduplicadas

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Persona para mostrar: un grupo de PeopleEngine con sus fotos. */
class Person(val id: String, var name: String, val named: Boolean, val faces: List<Pair<Photo, FaceInfo>>, val refs: List<FaceRef>) {
    val photos: List<Photo> get() = faces.map { it.first }.distinctBy { it.id }
    val cover: Pair<Photo, FaceInfo> get() = faces.maxBy { it.second.area }
}

/**
 * Reconocimiento de caras 100 % en el móvil (InsightFace con ONNX Runtime):
 *  - rápido: SCRFD + w600k_mbf para todas las caras,
 *  - segunda opinión: w600k_r50 solo para las caras difíciles.
 */
class ContentAnalyzer(private val ctx: Context, private val scanner: Scanner) {

    companion object {
        const val FACE_VERSION = "6"           // sube cuando cambia el cálculo de huellas
        /** Similitud base para considerar dos caras la misma persona (medida con fotos etiquetadas). */
        const val BASE_THRESHOLD = 0.38f
        private const val MAX_PROFILE = 0.65f
        private const val MIN_EYES_PX = 12f      // distancia entre ojos mínima para fiarse de la huella
        const val MIN_FACES_PER_PERSON = 2
        /** Motivos por los que una cara no se usa para comparar. */
        const val WHY_OK = 0
        const val WHY_PROFILE = 1
        const val WHY_SMALL = 2
        const val WHY_FAIL = 3
    }

    @Volatile var cancel = false

    private val engine = FaceEngine(ctx)
    val hasFaceModel: Boolean get() = engine.ready
    private var dim = 512

    /** Carga los modelos de caras si vienen en la APK. Si no, se sigue sin agrupar por persona. */
    fun loadModel() = engine.load()

    // ------------------------------------------------------------ Análisis de una foto

    fun analyze(p: Photo, locAllowed: Boolean) {
        try {
            val bmp = scanner.decodeUpright(p.uri, 1024)
            if (bmp == null) { p.contentDone = true; return }
            p.faces = detectFaces(p, bmp)
            applyXmpNames(p)
            bmp.recycle()
            p.hasGps = if (locAllowed) readGps(p.uri) else false
            p.gpsChecked = locAllowed
            p.contentDone = true
        } catch (_: Throwable) {
            p.contentDone = true
        }
    }

    /** Recalcula solo las caras (cuando mejora el método de reconocimiento). */
    fun analyzeFaces(p: Photo) {
        try {
            val bmp = scanner.decodeUpright(p.uri, 1024) ?: return
            p.faces = detectFaces(p, bmp)
            applyXmpNames(p)
            bmp.recycle()
        } catch (_: Throwable) { }
    }

    private fun detectFaces(p: Photo, bmp: Bitmap): List<FaceInfo> {
        if (!engine.canDetect) return emptyList()
        val first = engine.detect(bmp)
        var big: Bitmap? = null   // la misma foto a más resolución (~2000 px)
        var bs = 1f
        fun ensureBig(): Bitmap? {
            if (big == null) {
                val b = scanner.decodeUpright(p.uri, 2304)
                if (b != null && b.width > bmp.width) {
                    big = b
                    bs = b.width.toFloat() / bmp.width
                } else b?.recycle()
            }
            return big
        }
        val list = ArrayList<FaceInfo>()
        try {
            // Caras pequeñas (fotos de grupo, gente al fondo): segunda pasada por trozos
            val all = ArrayList<Det>(first)
            val side = max(p.w, p.h)
            val screenshot = p.folder.contains("Screenshot", true) || p.folder.contains("Captura", true) ||
                p.name.endsWith(".png", true)
            if (!screenshot && (side == 0 || side >= 1200)) {
                val b = ensureBig()
                if (b != null) {
                    for (d in engine.detectTiles(b)) {
                        all += Det(d.x1 / bs, d.y1 / bs, d.x2 / bs, d.y2 / bs, d.score, FloatArray(10) { d.kps[it] / bs })
                    }
                }
            }
            val found = if (all.size == first.size) first else engine.nms(all)
            for (d in found) {
                val l = (d.x1 / bmp.width).coerceIn(0f, 1f)
                val t = (d.y1 / bmp.height).coerceIn(0f, 1f)
                val r = (d.x2 / bmp.width).coerceIn(0f, 1f)
                val b = (d.y2 / bmp.height).coerceIn(0f, 1f)
                if (r - l <= 0f || b - t <= 0f) continue
                var emb: ByteArray? = null
                var why = WHY_FAIL
                val eyes = engine.eyeDistance(d)
                if (engine.profile(d) > MAX_PROFILE) why = WHY_PROFILE
                if (engine.ready && engine.profile(d) <= MAX_PROFILE) {
                    var src = bmp
                    var k = d.kps
                    if (eyes < 40f) {
                        val bg = ensureBig()
                        if (bg != null) {
                            src = bg
                            k = FloatArray(10) { d.kps[it] * bs }
                        }
                    }
                    val effEyes = eyes * src.width / bmp.width
                    if (effEyes < MIN_EYES_PX) why = WHY_SMALL
                    else engine.embed(src, k)?.let { v ->
                        dim = v.size
                        emb = quantize(v)
                        why = WHY_OK
                    }
                }
                list += FaceInfo(l, t, r, b, emb).apply {
                    kps = FloatArray(10) { (if (it % 2 == 0) d.kps[it] / bmp.width else d.kps[it] / bmp.height) }
                    this.why = why
                }
            }
        } finally {
            big?.recycle()
        }
        return list
    }

    /** Guarda la huella en 1 byte por valor, escalada a su máximo (error despreciable). */
    private fun quantize(v: FloatArray): ByteArray? {
        var mx = 0f
        for (x in v) mx = max(mx, abs(x))
        if (mx <= 0f) return null
        return ByteArray(v.size) { (v[it] / mx * 127f).roundToInt().coerceIn(-127, 127).toByte() }
    }

    // ------------------------------------------------------------ Nombres guardados en el archivo (XMP)

    /** Para fotos ya analizadas: lee solo los nombres XMP y los asigna a sus caras. */
    fun readXmpNames(p: Photo) = applyXmpNames(p)

    private fun applyXmpNames(p: Photo) {
        p.xmpChecked = true
        val faces = p.faces
        if (faces.isEmpty()) return
        val xmp = try { Meta.xmpOf(ctx.contentResolver, p.uri) } catch (_: Throwable) { null } ?: return
        val regions = Xmp.regions(xmp)
        if (regions.isNotEmpty()) {
            // Según el programa, el recuadro viene sobre la foto girada o sin girar: se prueban las dos
            val orient = Meta.orientation(ctx.contentResolver, p.uri)
            val asIs = matchRegions(regions, faces, 1)
            val turned = if (orient == 3 || orient == 6 || orient == 8) matchRegions(regions, faces, orient) else emptyMap()
            val best = if (turned.size > asIs.size) turned else asIs
            for ((i, n) in best) faces[i].name = n
        } else {
            val people = Xmp.personInImage(xmp)
            val withEmb = faces.indices.filter { faces[it].emb != null }
            if (people.size == 1 && withEmb.size == 1) faces[withEmb[0]].name = people[0]
        }
    }

    private fun matchRegions(regions: List<Xmp.Region>, faces: List<FaceInfo>, orient: Int): Map<Int, String> {
        val out = HashMap<Int, String>()
        for (r in regions) {
            val (cx, cy) = when (orient) {
                6 -> (1f - r.cy) to r.cx
                3 -> (1f - r.cx) to (1f - r.cy)
                8 -> r.cy to (1f - r.cx)
                else -> r.cx to r.cy
            }
            var best = -1
            var bd = Float.MAX_VALUE
            for (i in faces.indices) {
                if (i in out) continue
                val f = faces[i]
                val fx = (f.l + f.r) / 2f
                val fy = (f.t + f.b) / 2f
                val size = max(f.r - f.l, f.b - f.t)
                val d = sqrt((cx - fx) * (cx - fx) + (cy - fy) * (cy - fy))
                if (d < size * 0.75f && d < bd) { bd = d; best = i }
            }
            if (best >= 0) out[best] = r.name
        }
        return out
    }

    /** Lee si la foto tiene coordenadas GPS (requiere permiso de ubicación de fotos en Android 10+). */
    fun readGps(uri: Uri): Boolean {
        val cr = ctx.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val orig = MediaStore.setRequireOriginal(uri)
                return cr.openInputStream(orig)?.use { ExifInterface(it).latLong != null } ?: false
            } catch (_: Throwable) { }
        }
        return try {
            cr.openInputStream(uri)?.use { ExifInterface(it).latLong != null } ?: false
        } catch (_: Throwable) { false }
    }

    // ------------------------------------------------------------ Agrupar caras por persona

    private fun deq(e: ByteArray): FloatArray {
        val v = FloatArray(e.size) { e[it] / 127f }
        normalize(v)
        return v
    }

    private fun normalize(v: FloatArray) {
        var n = 0.0
        for (x in v) n += x * x
        val s = sqrt(n).toFloat()
        if (s > 0f) for (i in v.indices) v[i] /= s
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private class F(val p: Photo, val f: FaceInfo, val key: String, val v: FloatArray)

    /** Datos del último agrupado, para mostrarlos en la app. */
    @Volatile var lastStats: String = ""

    private fun percentile(xs: List<Float>, q: Double): Float {
        if (xs.isEmpty()) return 0f
        val sorted = xs.sorted()
        return sorted[((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)]
    }

    /** Caras con huella → referencias para PeopleEngine (con la segunda opinión si ya se calculó). */
    fun faceRefs(photos: List<Photo>): List<FaceRef> {
        val v2 = loadV2()
        val out = ArrayList<FaceRef>()
        for (p in photos) p.faces.forEachIndexed { i, f ->
            val e = f.emb ?: return@forEachIndexed
            if (dim != 0 && e.size != dim) return@forEachIndexed
            val cx = (f.l + f.r) / 2f
            val cy = (f.t + f.b) / 2f
            val r = FaceRef(p.id, i, cx, cy, f.area, deq(e), null, f.name)
            r.v2 = v2[p.id]?.firstOrNull { abs(it.cx - cx) < 0.03f && abs(it.cy - cy) < 0.03f }?.v
            out += r
        }
        return out
    }

    fun people(refs: List<FaceRef>, state: PeopleState, sens: Int): PeopleResult {
        val t = PeopleEngine.threshold(refs, BASE_THRESHOLD, sens)
        val r = PeopleEngine.run(refs, state, t) { cancel }
        lastStats = r.stats
        return r
    }

    class FaceStats(
        val photos: Int, val withFaces: Int, val faces: Int, val usable: Int,
        val profile: Int, val small: Int, val failed: Int, val second: Int
    )

    fun stats(photos: List<Photo>): FaceStats {
        var withFaces = 0; var total = 0; var ok = 0; var prof = 0; var small = 0; var fail = 0
        for (p in photos) {
            if (p.faces.isNotEmpty()) withFaces++
            for (f in p.faces) {
                total++
                when {
                    f.emb != null -> ok++
                    f.why == WHY_PROFILE -> prof++
                    f.why == WHY_SMALL -> small++
                    else -> fail++
                }
            }
        }
        val second = loadV2().values.sumOf { it.size }
        return FaceStats(photos.size, withFaces, total, ok, prof, small, fail, second)
    }

    // ------------------------------------------------------------ Segunda opinión (modelo grande)

    class V2(val cx: Float, val cy: Float, val v: FloatArray)

    private var v2Cache: HashMap<Long, MutableList<V2>>? = null
    private fun v2File() = File(ctx.filesDir, "faces_r50.txt")

    val hasLargeModel: Boolean get() = engine.hasLarge()

    @Synchronized
    fun loadV2(): Map<Long, List<V2>> {
        v2Cache?.let { return it }
        val m = HashMap<Long, MutableList<V2>>()
        try {
            if (v2File().exists()) v2File().forEachLine { line ->
                val q = line.split(';')
                if (q.size == 4) {
                    val id = q[0].toLongOrNull() ?: return@forEachLine
                    val e = try { Base64.decode(q[3], Base64.NO_WRAP) } catch (_: Exception) { return@forEachLine }
                    m.getOrPut(id) { ArrayList() } += V2(q[1].toFloat(), q[2].toFloat(), deq(e))
                }
            }
        } catch (_: Exception) { }
        v2Cache = m
        return m
    }

    @Synchronized
    private fun addV2(photoId: Long, cx: Float, cy: Float, v: FloatArray) {
        val q = quantize(v) ?: return
        loadV2()
        v2Cache!!.getOrPut(photoId) { ArrayList() } += V2(cx, cy, deq(q))
        try { v2File().appendText("$photoId;$cx;$cy;${Base64.encodeToString(q, Base64.NO_WRAP)}\n") } catch (_: Exception) { }
    }

    /** Calcula la huella del modelo grande para las caras indicadas. Devuelve cuántas ha hecho. */
    fun secondOpinion(photos: Map<Long, Photo>, targets: List<FaceRef>, progress: (Int, Int) -> Unit): Int {
        engine.loadLarge()
        if (!engine.largeReady) return -1
        val todo = targets.filter { it.v2 == null }
        val byPhoto = todo.groupBy { it.photoId }
        var done = 0
        for ((pid, fs) in byPhoto) {
            if (cancel) break
            val p = photos[pid] ?: continue
            val bmp = scanner.decodeUpright(p.uri, 2304) ?: continue
            try {
                for (r in fs) {
                    val k = p.faces.getOrNull(r.index)?.kps ?: continue
                    val kk = FloatArray(10) { if (it % 2 == 0) k[it] * bmp.width else k[it] * bmp.height }
                    engine.embedLarge(bmp, kk)?.let { v ->
                        r.v2 = v
                        addV2(pid, r.cx, r.cy, v)
                    }
                    done++
                    progress(done, todo.size)
                }
            } finally {
                bmp.recycle()
            }
        }
        return done
    }

    // ------------------------------------------------------------ Caché

    private fun cacheFile() = File(ctx.filesDir, "content_v1.txt")

    fun loadCache(): Map<Long, List<String>> {
        val m = HashMap<Long, List<String>>()
        val f = cacheFile()
        if (!f.exists()) return m
        try {
            f.forEachLine { line ->
                val s = line.split(';')
                if (s.size == 6 || s.size == 7) s[0].toLongOrNull()?.let { m[it] = s }
            }
        } catch (_: Exception) { }
        return m
    }

    /** 0 = no sirve · 1 = completa · 2 = sirve salvo las caras (calculadas con un método anterior). */
    fun applyCache(p: Photo, s: List<String>?): Int {
        if (s == null) return 0
        return try {
            if (s[1].toLong() != p.modified) return 0
            p.hasGps = s[2] == "1"
            p.gpsChecked = s[2] != "?"
            p.labels = if (s[4].isEmpty()) emptyList() else s[4].split(',')
            p.faces = if (s[5].isEmpty()) emptyList() else s[5].split('|').map { enc ->
                val q = enc.split(',')
                FaceInfo(
                    q[0].toFloat(), q[1].toFloat(), q[2].toFloat(), q[3].toFloat(),
                    if (q.size > 4 && q[4].isNotEmpty()) Base64.decode(q[4], Base64.NO_WRAP) else null
                ).apply {
                    if (q.size > 5 && q[5].isNotEmpty()) name = String(Base64.decode(q[5], Base64.NO_WRAP), Charsets.UTF_8)
                    if (q.size > 6 && q[6].isNotEmpty()) kps = q[6].split(':').map { it.toFloat() }.toFloatArray().takeIf { it.size == 10 }
                    if (q.size > 7) why = q[7].toIntOrNull() ?: 0
                }
            }
            p.xmpChecked = s.size >= 7 && s[6] == "1"
            p.contentDone = true
            if (engine.ready && s[3] != FACE_VERSION) 2 else 1
        } catch (_: Exception) { 0 }
    }

    @Synchronized
    fun saveCache(photos: List<Photo>, old: Map<Long, List<String>>) {
        val model = if (engine.ready) FACE_VERSION else "0"
        try {
            val done = HashSet<Long>()
            for (p in photos) if (p.contentDone) done.add(p.id)
            cacheFile().bufferedWriter().use { w ->
                for ((id, line) in old) if (id !in done) w.write(line.joinToString(";") + "\n")
                for (p in photos) if (p.contentDone) {
                    val faces = p.faces.joinToString("|") { f ->
                        val e = f.emb?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: ""
                        val nm = f.name?.let { Base64.encodeToString(it.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) } ?: ""
                        val k = f.kps?.joinToString(":") ?: ""
                        "${f.l},${f.t},${f.r},${f.b},$e,$nm,$k,${f.why}"
                    }
                    val gps = if (!p.gpsChecked) "?" else if (p.hasGps) "1" else "0"
                    w.write("${p.id};${p.modified};$gps;$model;${p.labels.joinToString(",")};$faces;${if (p.xmpChecked) 1 else 0}\n")
                }
            }
        } catch (_: Exception) { }
    }
}
