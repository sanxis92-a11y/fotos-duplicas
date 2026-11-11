package com.sanxis.fotosduplicadas

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.io.File
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

        const val FACE_VERSION = "5"           // sube cuando cambia el cálculo de huellas
        /** Similitud base para considerar dos caras la misma persona (medida con fotos etiquetadas). */
        const val BASE_THRESHOLD = 0.38f
        private const val MAX_PROFILE = 0.65f
        private const val MIN_EYES_PX = 12f      // distancia entre ojos mínima para fiarse de la huella
        const val MIN_FACES_PER_PERSON = 2
        /** Etiquetas que indican que puede haber gente (para buscar también caras pequeñas). */
        private val PEOPLE_HINT = setOf(
            "Person", "Crowd", "Team", "Selfie", "Smile", "Laugh", "Party", "Event", "Concert", "Dance",
            "Marriage", "Bride", "Groom", "Graduation", "Prom", "Sports", "Fun", "Leisure", "Vacation",
            "Community", "Crew", "Class", "Competition", "Baby", "Beard", "Hair", "Dude", "Interaction",
            "Sitting", "Standing", "Eating", "Musician", "Singer", "Swimming", "Running", "Picnic", "Bathing",
            "Grandparent", "Love", "Eyelash", "Mouth", "Ear", "Hand", "Tattoo", "Muscle", "Skin"
        )
    }

    @Volatile var cancel = false

    private val labeler by lazy {
        ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.6f).build())
    }
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
            val img = InputImage.fromBitmap(bmp, 0)
            p.labels = Tasks.await(labeler.process(img))
                .sortedByDescending { it.confidence }
                .take(8)
                .map { it.text }
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
            val maybePeople = first.isNotEmpty() || p.labels.any { it in PEOPLE_HINT }
            val side = max(p.w, p.h)
            if (maybePeople && (side == 0 || side >= 1200)) {
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
                val eyes = engine.eyeDistance(d)
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
                    if (effEyes >= MIN_EYES_PX) engine.embed(src, k)?.let { v ->
                        dim = v.size
                        emb = quantize(v)
                    }
                }
                list += FaceInfo(l, t, r, b, emb)
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

    /**
     * Agrupa caras por persona.
     *  1) Calibra el umbral con caras de la MISMA foto (son por fuerza personas distintas):
     *     el umbral queda justo por encima de lo que se parecen dos desconocidos en estas fotos.
     *  2) Une dos caras solo si cada una está entre las K más parecidas de la otra (vecinos mutuos)
     *     y nunca si salen en la misma foto. Así no se forman cadenas que mezclen gente.
     *  3) Chinese Whispers sobre ese grafo para formar los grupos.
     * sens: 0..30, 15 = automático; más alto junta más.
     */
    fun people(photos: List<Photo>, names: Map<String, String>, sens: Int, excluded: Set<String>): List<Person> {
        val faces = ArrayList<F>()
        for (p in photos) p.faces.forEachIndexed { i, f ->
            val e = f.emb
            val key = "${p.id}:$i"
            if (e != null && (dim == 0 || e.size == dim) && key !in excluded) faces += F(p, f, key, deq(e))
        }
        if (faces.isEmpty()) { lastStats = "Sin caras válidas"; return emptyList() }
        val size = faces[0].v.size
        faces.removeAll { it.v.size != size }
        val n = faces.size

        // 1) Calibración
        val same = ArrayList<Float>()
        faces.indices.groupBy { faces[it].p.id }.values.forEach { idx ->
            if (idx.size >= 2) for (a in idx.indices) for (b in a + 1 until idx.size) {
                same += dot(faces[idx[a]].v, faces[idx[b]].v)
            }
        }
        // Base medida con fotos etiquetadas; si en tus fotos los desconocidos se parecen más, se sube
        val floor = if (same.size >= 15) percentile(same, 0.99) + 0.06f else 0f
        val auto = max(BASE_THRESHOLD, floor).coerceIn(0.25f, 0.80f)
        val t = (auto + (15 - sens.coerceIn(0, 30)) * 0.01f).coerceIn(0.15f, 0.90f)

        // 2) K vecinos más parecidos por encima del umbral
        val k = 12
        val nb = Array(n) { IntArray(k) { -1 } }
        val ns = Array(n) { FloatArray(k) { -2f } }
        fun offer(i: Int, j: Int, sim: Float) {
            val a = ns[i]
            var mi = 0
            for (q in 1 until k) if (a[q] < a[mi]) mi = q
            if (sim > a[mi]) { a[mi] = sim; nb[i][mi] = j }
        }
        for (i in 0 until n) {
            if (cancel) return emptyList()
            val vi = faces[i].v
            val pi = faces[i].p.id
            for (j in i + 1 until n) {
                if (faces[j].p.id == pi) continue
                val sim = dot(vi, faces[j].v)
                if (sim > t) { offer(i, j, sim); offer(j, i, sim) }
            }
        }
        val adj = Array(n) { ArrayList<Int>() }
        val adjW = Array(n) { ArrayList<Float>() }
        var edges = 0
        for (i in 0 until n) for (q in 0 until k) {
            val j = nb[i][q]
            if (j > i && nb[j].contains(i)) {
                adj[i] += j; adjW[i] += ns[i][q]
                adj[j] += i; adjW[j] += ns[i][q]
                edges++
            }
        }

        // 3) Chinese Whispers
        val label = IntArray(n) { it }
        val order = (0 until n).toMutableList()
        val rnd = java.util.Random(7)
        repeat(20) {
            order.shuffle(rnd)
            var changed = 0
            for (i in order) {
                if (adj[i].isEmpty()) continue
                val w = HashMap<Int, Float>()
                for (q in adj[i].indices) {
                    val l = label[adj[i][q]]
                    w[l] = (w[l] ?: 0f) + adjW[i][q]
                }
                val best = w.maxBy { it.value }.key
                if (best != label[i]) { label[i] = best; changed++ }
            }
            if (changed == 0) return@repeat
        }

        // Limpieza: fuera las caras que se parecen poco al conjunto de su grupo
        val groups = (0 until n).groupBy { label[it] }.values.map { idx ->
            if (idx.size < 3) idx else {
                val c = FloatArray(size)
                for (i in idx) for (q in 0 until size) c[q] += faces[i].v[q]
                normalize(c)
                idx.filter { dot(c, faces[it].v) >= t }
            }
        }.filter { idx -> idx.map { faces[it].p.id }.distinct().size >= MIN_FACES_PER_PERSON }
        lastStats = String.format(
            java.util.Locale.getDefault(),
            "%d caras · umbral %.2f (automático %.2f con %d parejas) · %d enlaces",
            n, t, auto, same.size, edges
        )

        val raw = groups.map { idx ->
            val m = idx.map { faces[it] }.sortedByDescending { it.f.area }
            val keys = m.map { it.key }
            // Nombre: el que pusiste en la app; si no, el más repetido en las caras etiquetadas del archivo
            val name = keys.firstNotNullOfOrNull { names[it] }
                ?: m.mapNotNull { it.f.name }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                ?: ""
            Person(keys, name, m.map { it.p to it.f })
        }
        // Personas con el mismo nombre se unen (así se pueden fusionar renombrando)
        val named = raw.filter { it.name.isNotBlank() }.groupBy { it.name.trim().lowercase() }.values.map { sameName ->
            if (sameName.size == 1) sameName[0]
            else Person(sameName.flatMap { it.keys }, sameName[0].name, sameName.flatMap { it.faces })
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
                        "${f.l},${f.t},${f.r},${f.b},$e,$nm"
                    }
                    val gps = if (!p.gpsChecked) "?" else if (p.hasGps) "1" else "0"
                    w.write("${p.id};${p.modified};$gps;$model;${p.labels.joinToString(",")};$faces;${if (p.xmpChecked) 1 else 0}\n")
                }
            }
        } catch (_: Exception) { }
    }
}
