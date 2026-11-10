package com.sanxis.fotosduplicadas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
        const val SAME_PERSON = 0.50f
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
            val labels = Tasks.await(labeler.process(img))
                .sortedByDescending { it.confidence }
                .take(8)
                .map { it.text }
            val found = Tasks.await(detector.process(img))
            val list = ArrayList<FaceInfo>()
            for (f in found) {
                val bb = Rect(f.boundingBox)
                if (!bb.intersect(0, 0, bmp.width, bmp.height)) continue
                if (bb.width() < 24 || bb.height() < 24) continue
                val emb = if (interp != null && bb.width() >= 40) embed(bmp, bb) else null
                list += FaceInfo(
                    bb.left.toFloat() / bmp.width, bb.top.toFloat() / bmp.height,
                    bb.right.toFloat() / bmp.width, bb.bottom.toFloat() / bmp.height, emb
                )
            }
            bmp.recycle()
            p.labels = labels
            p.faces = list
            p.hasGps = if (locAllowed) readGps(p.uri) else false
            p.gpsChecked = locAllowed
            p.contentDone = true
        } catch (_: Throwable) {
            p.contentDone = true
        }
    }

    @Synchronized
    private fun embed(bmp: Bitmap, bb: Rect): ByteArray? {
        val ip = interp ?: return null
        return try {
            val size = (max(bb.width(), bb.height()) * 1.15f).toInt()
            val cx = bb.exactCenterX()
            val cy = bb.exactCenterY()
            val l = (cx - size / 2f).toInt().coerceIn(0, bmp.width - 1)
            val t = (cy - size / 2f).toInt().coerceIn(0, bmp.height - 1)
            val w = min(size, bmp.width - l)
            val h = min(size, bmp.height - t)
            if (w < 16 || h < 16) return null
            val crop = Bitmap.createBitmap(bmp, l, t, w, h)
            val sc = Bitmap.createScaledBitmap(crop, inW, inH, true)
            if (crop != sc) crop.recycle()
            val px = IntArray(inW * inH)
            sc.getPixels(px, 0, inW, 0, 0, inW, inH)
            sc.recycle()
            val input = ByteBuffer.allocateDirect(batch * inW * inH * 3 * 4).order(ByteOrder.nativeOrder())
            for (c in px) {
                input.putFloat((((c shr 16) and 0xFF) - 127.5f) / 128f)
                input.putFloat((((c shr 8) and 0xFF) - 127.5f) / 128f)
                input.putFloat(((c and 0xFF) - 127.5f) / 128f)
            }
            while (input.hasRemaining()) input.putFloat(0f)
            input.rewind()
            val out = Array(batch) { FloatArray(dim) }
            ip.run(input, out)
            val v = out[0]
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

    fun people(photos: List<Photo>, names: Map<String, String>): List<Person> {
        class F(val p: Photo, val f: FaceInfo, val key: String, val v: FloatArray)
        val faces = ArrayList<F>()
        for (p in photos) p.faces.forEachIndexed { i, f ->
            val e = f.emb
            if (e != null && (dim == 0 || e.size == dim)) faces += F(p, f, "${p.id}:$i", deq(e))
        }
        if (faces.isEmpty()) return emptyList()
        val size = faces[0].v.size
        faces.removeAll { it.v.size != size }
        faces.sortByDescending { it.f.area }

        val cents = ArrayList<FloatArray>()
        val members = ArrayList<MutableList<F>>()
        for (fc in faces) {
            if (cancel) break
            var best = -1
            var bs = SAME_PERSON
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

        val raw = members.filter { m -> m.map { it.p.id }.distinct().size >= MIN_FACES_PER_PERSON }.map { m ->
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

    fun applyCache(p: Photo, s: List<String>?): Boolean {
        if (s == null) return false
        return try {
            if (s[1].toLong() != p.modified) return false
            if (interp != null && s[3] != "1") return false   // analizada sin modelo de caras: repetir
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
            true
        } catch (_: Exception) { false }
    }

    @Synchronized
    fun saveCache(photos: List<Photo>, old: Map<Long, List<String>>) {
        val model = if (interp != null) "1" else "0"
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
