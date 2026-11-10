package com.sanxis.fotosduplicadas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class Category(val key: String, val title: String, val emoji: String, val test: (Photo) -> Boolean)

class Person(val keys: List<String>, var name: String, val faces: List<Pair<Photo, FaceInfo>>) {
    val photos: List<Photo> get() = faces.map { it.first }.distinctBy { it.id }
    val cover: Pair<Photo, FaceInfo> get() = faces.maxBy { it.second.area }
}

/**
 * Reconocimiento de contenido 100 % en el móvil:
 *  - ML Kit (modelos incluidos en la APK) para etiquetas de objetos y detección de caras.
 *  - MobileFaceNet (TensorFlow Lite) para la huella de cada cara y agrupar por persona.
 */
class ContentAnalyzer(private val ctx: Context, private val scanner: Scanner) {

    companion object {
        private val DOCS = setOf(
            "Receipt", "Paper", "Menu", "Newspaper", "News", "Passport", "Money", "Whiteboard",
            "Blackboard", "Poster", "Presentation", "Comics"
        )
        private val FOOD = setOf(
            "Food", "Fast food", "Cuisine", "Pizza", "Cake", "Bread", "Cookie", "Fruit", "Vegetable", "Sushi",
            "Cheeseburger", "Hot dog", "Bento", "Couscous", "Pho", "Pie", "Gelato", "Icing", "Juice", "Coffee",
            "Cappuccino", "Wine", "Alcohol", "Meal", "Lunch", "Supper", "Cola", "Pasteles", "Eating", "Tableware"
        )
        private val ANIMALS = setOf(
            "Pet", "Dog", "Cat", "Bird", "Horse", "Insect", "Butterfly", "Duck", "Turtle", "Bear", "Cattle", "Herd",
            "Penguin", "Seal", "Bull", "Crocodile", "Dalmatian", "Shetland sheepdog", "Cairn terrier", "Basset hound",
            "Ragdoll", "Himalayan", "Sphynx", "Pixie-bob", "Cavalier", "Shikoku", "Gerbil", "Waterfowl", "Larva",
            "Pomacentridae", "Dinosaur"
        )
        private val NATURE = setOf(
            "Sky", "Sunset", "Beach", "Mountain", "Lake", "River", "Forest", "Waterfall", "Desert", "Dune", "Glacier",
            "Iceberg", "Canyon", "Cliff", "Field", "Prairie", "Jungle", "Swamp", "Volcano", "Aurora", "Rainbow",
            "Moon", "Nebula", "Flower", "Flora", "Plant", "Garden", "Sand", "Fog", "Storm", "Lightning", "Reef",
            "Underwater", "Cave", "Petal", "Twig", "Branch"
        )
        private val CITY = setOf(
            "Building", "Skyscraper", "Skyline", "Tower", "Bridge", "Church", "Cathedral", "Castle", "Palace",
            "Monument", "Museum", "Temple", "Mosque", "Ruins", "Lighthouse", "Stadium", "Statue", "Ferris wheel"
        )
        private val VEHICLES = setOf(
            "Car", "Vehicle", "Motorcycle", "Bicycle", "Bus", "Train", "Airplane", "Aircraft", "Airliner", "Boat",
            "Sailboat", "Speedboat", "Van", "Tractor", "Helicopter", "Tire", "Wheel", "Odometer", "Windshield",
            "Bumper", "Aviation"
        )
        private val EVENTS = setOf(
            "Party", "Concert", "Event", "Christmas", "Carnival", "Fireworks", "Marriage", "Bride", "Groom",
            "Graduation", "Prom", "Balloon", "Thanksgiving", "Hanukkah", "Dance", "Nightclub", "Sparkler",
            "Santa claus", "Wreath", "Mortarboard"
        )
        private val SPORTS = setOf(
            "Sports", "Soccer", "Running", "Marathon", "Cycling", "Swimming", "Skiing", "Surfing", "Rugby",
            "Competition", "Gymnastics", "Racing", "Race", "Badminton", "Softball", "Snowboarding", "Skateboarder",
            "Pitch", "Rowing", "Kayak", "Scuba diving"
        )
        private val KIDS = setOf("Baby", "Toy", "Stuffed toy", "Plush", "Playground", "Lego")

        private fun has(p: Photo, set: Set<String>) = p.labels.any { it in set }

        val CATEGORIES = listOf(
            Category("people", "Con personas", "👥") { it.faces.isNotEmpty() },
            Category("groups", "Grupos de gente", "👨‍👩‍👧") { it.faces.size >= 3 },
            Category("selfies", "Selfies", "🤳") { "Selfie" in it.labels },
            Category("nopeople", "Sin personas", "🖼️") { it.faces.isEmpty() },
            Category("screens", "Capturas de pantalla", "📱") {
                "Screenshot" in it.labels || "Web page" in it.labels ||
                    it.folder.contains("Screenshot", true) || it.folder.contains("Captura", true)
            },
            Category("docs", "Documentos y papeles", "📄") { has(it, DOCS) },
            Category("whatsapp", "WhatsApp", "💬") { it.folder.contains("WhatsApp", true) },
            Category("food", "Comida y bebida", "🍽️") { has(it, FOOD) },
            Category("animals", "Animales", "🐾") { has(it, ANIMALS) },
            Category("nature", "Paisajes y naturaleza", "🏞️") { has(it, NATURE) },
            Category("city", "Ciudad y monumentos", "🏛️") { has(it, CITY) },
            Category("vehicles", "Vehículos", "🚗") { has(it, VEHICLES) },
            Category("events", "Fiestas y celebraciones", "🎉") { has(it, EVENTS) },
            Category("sports", "Deporte", "⚽") { has(it, SPORTS) },
            Category("kids", "Bebés y juguetes", "🧸") { has(it, KIDS) }
        )

        const val MODEL_FILE = "mobile_face_net.tflite"
        const val FACE_VERSION = "2"           // sube cuando cambia el cálculo de huellas
        const val MIN_FACE_SHARPNESS = 8.0
        /** Plantilla ArcFace 112×112: ojo izq., ojo dcho., nariz, boca izq., boca dcha. (de la imagen). */
        private val TEMPLATE = floatArrayOf(
            38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f, 71.7366f, 41.5493f, 92.3655f, 70.7299f, 92.2041f
        )
        const val MIN_FACES_PER_PERSON = 3
    }

    @Volatile var cancel = false

    private val labeler by lazy {
        ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.6f).build())
    }
    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.06f)
                .build()
        )
    }

    private var interp: Interpreter? = null
    private var batch = 1
    private var inW = 112
    private var inH = 112
    private var dim = 0
    val hasFaceModel: Boolean get() = interp != null

    /** Carga MobileFaceNet si viene en la APK. Si no está, se sigue sin agrupar por persona. */
    @Synchronized
    fun loadModel() {
        if (interp != null) return
        try {
            val bytes = ctx.assets.open(MODEL_FILE).use { it.readBytes() }
            if (bytes.size < 100_000) return
            val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            buf.put(bytes)
            buf.rewind()
            val ip = Interpreter(buf)
            val inT = ip.getInputTensor(0)
            val outT = ip.getOutputTensor(0)
            val s = inT.shape()
            val o = outT.shape()
            if (inT.dataType() != DataType.FLOAT32 || s.size != 4 || s[3] != 3 || o.size != 2 || o[1] < 64) {
                ip.close()
                return
            }
            batch = max(1, s[0])
            inH = s[1]
            inW = s[2]
            dim = o[1]
            interp = ip
        } catch (_: Throwable) {
            interp = null
        }
    }

    // ------------------------------------------------------------ Análisis de una foto

    fun analyze(p: Photo, locAllowed: Boolean) {
        try {
            val bmp = scanner.decodeUpright(p.uri, 1024)
            if (bmp == null) { p.contentDone = true; return }
            val img = InputImage.fromBitmap(bmp, 0)
            p.labels = Tasks.await(labeler.process(img))
                .sortedByDescending { it.confidence }
                .take(8)
                .map { it.text }
            p.faces = detectFaces(p, bmp, img)
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
            p.faces = detectFaces(p, bmp, InputImage.fromBitmap(bmp, 0))
            bmp.recycle()
        } catch (_: Throwable) { }
    }

    private fun detectFaces(p: Photo, bmp: Bitmap, img: InputImage): List<FaceInfo> {
        val found = Tasks.await(detector.process(img))
        if (found.isEmpty()) return emptyList()
        var big: Bitmap? = null   // la misma foto a más resolución, para caras pequeñas
        val list = ArrayList<FaceInfo>()
        try {
            for (f in found) {
                val bb = Rect(f.boundingBox)
                if (!bb.intersect(0, 0, bmp.width, bmp.height)) continue
                if (bb.width() < 24 || bb.height() < 24) continue
                var emb: ByteArray? = null
                // Solo caras casi de frente: de perfil la huella no es fiable
                val frontal = abs(f.headEulerAngleY) <= 35f && abs(f.headEulerAngleX) <= 30f
                if (interp != null && frontal && bb.width() >= 36) {
                    var src = bmp
                    var scale = 1f
                    if (bb.width() < 112) {
                        if (big == null) big = scanner.decodeUpright(p.uri, 2304)
                        val b = big
                        if (b != null && b.width > bmp.width) {
                            src = b
                            scale = b.width.toFloat() / bmp.width
                        }
                    }
                    if (bb.width() * scale >= 48f) emb = embed(src, bb, landmarks(f), scale)
                }
                list += FaceInfo(
                    bb.left.toFloat() / bmp.width, bb.top.toFloat() / bmp.height,
                    bb.right.toFloat() / bmp.width, bb.bottom.toFloat() / bmp.height, emb
                )
            }
        } finally {
            big?.recycle()
        }
        return list
    }

    /** Ojos, nariz y comisuras, ordenados de izquierda a derecha de la imagen como en la plantilla. */
    private fun landmarks(f: Face): FloatArray? {
        val ids = intArrayOf(
            FaceLandmark.RIGHT_EYE, FaceLandmark.LEFT_EYE, FaceLandmark.NOSE_BASE,
            FaceLandmark.MOUTH_RIGHT, FaceLandmark.MOUTH_LEFT
        )
        val out = FloatArray(10)
        for ((i, id) in ids.withIndex()) {
            val pt = f.getLandmark(id)?.position ?: return null
            out[2 * i] = pt.x
            out[2 * i + 1] = pt.y
        }
        if (out[0] > out[2]) {   // si vienen al revés, se intercambian
            var t = out[0]; out[0] = out[2]; out[2] = t
            t = out[1]; out[1] = out[3]; out[3] = t
            t = out[6]; out[6] = out[8]; out[8] = t
            t = out[7]; out[7] = out[9]; out[9] = t
        }
        return out
    }

    /** Transformación de semejanza (giro + escala + desplazamiento) por mínimos cuadrados. */
    private fun similarity(s: FloatArray, d: FloatArray, m: Matrix): Boolean {
        val n = s.size / 2
        var sxm = 0f; var sym = 0f; var dxm = 0f; var dym = 0f
        for (i in 0 until n) { sxm += s[2 * i]; sym += s[2 * i + 1]; dxm += d[2 * i]; dym += d[2 * i + 1] }
        sxm /= n; sym /= n; dxm /= n; dym /= n
        var a = 0f; var b = 0f; var ss = 0f
        for (i in 0 until n) {
            val x = s[2 * i] - sxm
            val y = s[2 * i + 1] - sym
            val u = d[2 * i] - dxm
            val v = d[2 * i + 1] - dym
            a += x * u + y * v
            b += x * v - y * u
            ss += x * x + y * y
        }
        if (ss < 1e-3f) return false
        val ca = a / ss
        val cb = b / ss
        val tx = dxm - (ca * sxm - cb * sym)
        val ty = dym - (cb * sxm + ca * sym)
        m.setValues(floatArrayOf(ca, -cb, tx, cb, ca, ty, 0f, 0f, 1f))
        return true
    }

    private fun fillInput(buf: ByteBuffer, px: IntArray) {
        for (c in px) {
            buf.putFloat((((c shr 16) and 0xFF) - 127.5f) / 128f)
            buf.putFloat((((c shr 8) and 0xFF) - 127.5f) / 128f)
            buf.putFloat(((c and 0xFF) - 127.5f) / 128f)
        }
    }

    /** Huella de la cara: alineada con la plantilla, sumando la versión en espejo. */
    @Synchronized
    private fun embed(src: Bitmap, bb: Rect, pts: FloatArray?, scale: Float): ByteArray? {
        val ip = interp ?: return null
        return try {
            val m = Matrix()
            if (pts != null) {
                val s = FloatArray(10) { pts[it] * scale }
                val d = FloatArray(10) { if (it % 2 == 0) TEMPLATE[it] * inW / 112f else TEMPLATE[it] * inH / 112f }
                if (!similarity(s, d, m)) return null
            } else {
                val size = max(bb.width(), bb.height()) * 1.15f * scale
                val l = bb.exactCenterX() * scale - size / 2f
                val t = bb.exactCenterY() * scale - size / 2f
                m.setTranslate(-l, -t)
                m.postScale(inW / size, inH / size)
            }
            val face = Bitmap.createBitmap(inW, inH, Bitmap.Config.ARGB_8888)
            Canvas(face).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
            val px = IntArray(inW * inH)
            face.getPixels(px, 0, inW, 0, 0, inW, inH)
            face.recycle()

            // Cara demasiado borrosa: mejor no usarla para comparar
            val g = IntArray(px.size) { (((px[it] shr 16) and 0xFF) * 299 + ((px[it] shr 8) and 0xFF) * 587 + (px[it] and 0xFF) * 114) / 1000 }
            if (lapVar(g, inW, inH).first < MIN_FACE_SHARPNESS) return null

            val mirror = IntArray(px.size)
            for (y in 0 until inH) for (x in 0 until inW) mirror[y * inW + x] = px[y * inW + (inW - 1 - x)]

            val v = FloatArray(dim)
            if (batch >= 2) {
                val input = ByteBuffer.allocateDirect(batch * inW * inH * 12).order(ByteOrder.nativeOrder())
                fillInput(input, px)
                fillInput(input, mirror)
                while (input.hasRemaining()) input.putFloat(0f)
                input.rewind()
                val out = Array(batch) { FloatArray(dim) }
                ip.run(input, out)
                for (k in 0 until dim) v[k] = out[0][k] + out[1][k]
            } else {
                for (img in listOf(px, mirror)) {
                    val input = ByteBuffer.allocateDirect(inW * inH * 12).order(ByteOrder.nativeOrder())
                    fillInput(input, img)
                    input.rewind()
                    val out = Array(1) { FloatArray(dim) }
                    ip.run(input, out)
                    for (k in 0 until dim) v[k] += out[0][k]
                }
            }
            var n = 0.0
            for (x in v) n += x * x
            val norm = sqrt(n).toFloat().takeIf { it > 0f } ?: return null
            ByteArray(dim) { (v[it] / norm * 127f).roundToInt().coerceIn(-127, 127).toByte() }
        } catch (_: Throwable) { null }
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

    private fun centroid(m: List<F>): FloatArray {
        val c = FloatArray(m[0].v.size)
        for (x in m) for (k in c.indices) c[k] += x.v[k]
        normalize(c)
        return c
    }

    /**
     * Agrupa caras en tres pasadas:
     *  1) asignación al grupo más parecido (empezando por las caras más grandes y nítidas),
     *  2) fusión de grupos cuyos centros se parecen mucho,
     *  3) recolocación de caras sueltas en el grupo grande más parecido.
     * threshold: similitud coseno mínima (más alto = más estricto).
     */
    fun people(photos: List<Photo>, names: Map<String, String>, threshold: Float, excluded: Set<String>): List<Person> {
        val faces = ArrayList<F>()
        for (p in photos) p.faces.forEachIndexed { i, f ->
            val e = f.emb
            val key = "${p.id}:$i"
            if (e != null && (dim == 0 || e.size == dim) && key !in excluded) faces += F(p, f, key, deq(e))
        }
        if (faces.isEmpty()) return emptyList()
        val size = faces[0].v.size
        faces.removeAll { it.v.size != size }
        faces.sortByDescending { it.f.area }

        // 1) Asignación voraz
        var cents = ArrayList<FloatArray>()
        var members = ArrayList<MutableList<F>>()
        for (fc in faces) {
            if (cancel) break
            var best = -1
            var bs = threshold
            for (c in cents.indices) {
                val s = dot(cents[c], fc.v)
                if (s > bs) { bs = s; best = c }
            }
            if (best < 0) {
                cents += fc.v.copyOf()
                members += mutableListOf(fc)
            } else {
                val c = cents[best]
                val n = members[best].size
                for (k in c.indices) c[k] = (c[k] * n + fc.v[k]) / (n + 1)
                normalize(c)
                members[best] += fc
            }
        }

        // 2) Fusionar grupos (de 2 o más caras) con centros muy parecidos
        val mergeT = threshold + 0.05f
        for (round in 0 until 3) {
            val idx = members.indices.filter { members[it].size >= 2 }
            if (idx.size < 2) break
            val parent = IntArray(members.size) { it }
            fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; return r }
            var merged = false
            for (a in idx.indices) for (b in a + 1 until idx.size) {
                val i = idx[a]
                val j = idx[b]
                if (dot(cents[i], cents[j]) > mergeT) {
                    val ri = find(i)
                    val rj = find(j)
                    if (ri != rj) { parent[ri] = rj; merged = true }
                }
            }
            if (!merged) break
            val byRoot = LinkedHashMap<Int, MutableList<F>>()
            for (i in members.indices) byRoot.getOrPut(find(i)) { mutableListOf() }.addAll(members[i])
            members = ArrayList(byRoot.values)
            cents = ArrayList(members.map { centroid(it) })
        }

        // 3) Caras de grupos pequeños: al grupo grande más parecido, si se parece lo suficiente
        val isBig = BooleanArray(members.size) { i -> members[i].map { it.p.id }.distinct().size >= MIN_FACES_PER_PERSON }
        val bigIdx = members.indices.filter { isBig[it] }
        if (bigIdx.isNotEmpty()) {
            for (i in members.indices) if (!isBig[i]) {
                for (fc in members[i]) {
                    var best = -1
                    var bs = threshold
                    for (j in bigIdx) {
                        val s = dot(cents[j], fc.v)
                        if (s > bs) { bs = s; best = j }
                    }
                    if (best >= 0) members[best] += fc
                }
            }
        }

        val raw = bigIdx.map { i ->
            val m = members[i]
            val keys = m.map { it.key }
            val name = keys.firstNotNullOfOrNull { names[it] } ?: ""
            Person(keys, name, m.map { it.p to it.f })
        }
        // Personas con el mismo nombre se unen (así se pueden fusionar renombrando)
        val named = raw.filter { it.name.isNotBlank() }.groupBy { it.name.trim().lowercase() }.values.map { same ->
            if (same.size == 1) same[0]
            else Person(same.flatMap { it.keys }, same[0].name, same.flatMap { it.faces })
        }
        return (named + raw.filter { it.name.isBlank() }).sortedByDescending { it.photos.size }
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
                if (s.size == 6) s[0].toLongOrNull()?.let { m[it] = s }
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
                )
            }
            p.contentDone = true
            if (interp != null && s[3] != FACE_VERSION) 2 else 1
        } catch (_: Exception) { 0 }
    }

    @Synchronized
    fun saveCache(photos: List<Photo>, old: Map<Long, List<String>>) {
        val model = if (interp != null) FACE_VERSION else "0"
        try {
            val done = HashSet<Long>()
            for (p in photos) if (p.contentDone) done.add(p.id)
            cacheFile().bufferedWriter().use { w ->
                for ((id, line) in old) if (id !in done) w.write(line.joinToString(";") + "\n")
                for (p in photos) if (p.contentDone) {
                    val faces = p.faces.joinToString("|") { f ->
                        val e = f.emb?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: ""
                        "${f.l},${f.t},${f.r},${f.b},$e"
                    }
                    val gps = if (!p.gpsChecked) "?" else if (p.hasGps) "1" else "0"
                    w.write("${p.id};${p.modified};$gps;$model;${p.labels.joinToString(",")};$faces\n")
                }
            }
        } catch (_: Exception) { }
    }
}
