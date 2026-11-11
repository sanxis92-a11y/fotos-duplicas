package com.sanxis.fotosduplicadas

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Personas: agrupado automático + lo que el usuario confirma o corrige.
 * Kotlin puro (sin Android) para poder probarlo con JUnit.
 *
 * Las confirmaciones se guardan por foto y posición de la cara (no por número de cara),
 * así sobreviven aunque se vuelvan a analizar las fotos.
 */

/** Una cara con huella. v = modelo rápido; v2 = segunda opinión (modelo grande), si se ha calculado. */
class FaceRef(
    val photoId: Long, val index: Int, val cx: Float, val cy: Float, val area: Float,
    val v: FloatArray, var v2: FloatArray? = null, val fileName: String? = null
) {
    val key: String get() = "$photoId:$index"
}

class PersonRec(val id: String, var name: String)

/** Cara señalada por el usuario: foto + centro de la cara (0..1). */
data class Anchor(val personId: String, val photoId: Long, val cx: Float, val cy: Float)

class PeopleState {
    val persons = LinkedHashMap<String, PersonRec>()
    val confirmed = ArrayList<Anchor>()
    val rejected = ArrayList<Anchor>()
    val ignored = ArrayList<Anchor>()            // «no es nadie» (personId vacío)
    val notSame = HashSet<String>()              // "idA|idB" ordenado

    private var counter = 0

    fun newPersonId(): String {
        counter++
        var id: String
        do { id = "p" + System.currentTimeMillis().toString(36) + counter } while (id in persons)
        return id
    }

    fun pairKey(a: String, b: String) = if (a < b) "$a|$b" else "$b|$a"

    // ------------------------------------------------------------ Acciones

    fun confirm(f: FaceRef, personId: String) {
        confirmed.removeAll { it.photoId == f.photoId && near(it, f) }
        rejected.removeAll { it.personId == personId && it.photoId == f.photoId && near(it, f) }
        ignored.removeAll { it.photoId == f.photoId && near(it, f) }
        confirmed += Anchor(personId, f.photoId, f.cx, f.cy)
    }

    fun reject(f: FaceRef, personId: String) {
        confirmed.removeAll { it.personId == personId && it.photoId == f.photoId && near(it, f) }
        if (rejected.none { it.personId == personId && it.photoId == f.photoId && near(it, f) }) {
            rejected += Anchor(personId, f.photoId, f.cx, f.cy)
        }
    }

    fun ignore(f: FaceRef) {
        confirmed.removeAll { it.photoId == f.photoId && near(it, f) }
        ignored += Anchor("", f.photoId, f.cx, f.cy)
    }

    fun createPerson(name: String): PersonRec {
        val clean = name.trim()
        persons.values.firstOrNull { it.name.equals(clean, true) && clean.isNotEmpty() }?.let { return it }
        val r = PersonRec(newPersonId(), clean)
        persons[r.id] = r
        return r
    }

    /** Renombra; si ya existe otra persona con ese nombre, se unen. Devuelve el id final. */
    fun rename(id: String, name: String): String {
        val clean = name.trim()
        val other = persons.values.firstOrNull { it.id != id && clean.isNotEmpty() && it.name.equals(clean, true) }
        if (other != null) { merge(other.id, id); return other.id }
        persons[id]?.name = clean
        return id
    }

    /** Une b dentro de a. */
    fun merge(a: String, b: String) {
        if (a == b) return
        val moved = confirmed.filter { it.personId == b }.map { it.copy(personId = a) }
        confirmed.removeAll { it.personId == b }
        confirmed += moved
        val movedR = rejected.filter { it.personId == b }.map { it.copy(personId = a) }
        rejected.removeAll { it.personId == b }
        rejected += movedR
        persons.remove(b)
        notSame.removeAll { it.split('|').contains(b) }
    }

    fun markNotSame(a: String, b: String) { notSame += pairKey(a, b) }

    fun clearCorrections() { rejected.clear(); ignored.clear(); notSame.clear() }

    // ------------------------------------------------------------ Guardar / leer

    fun serialize(): String = buildString {
        fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\s").replace("\n", " ")
        for (p in persons.values) append("P;${p.id};${esc(p.name)}\n")
        for (a in confirmed) append("C;${a.personId};${a.photoId};${a.cx};${a.cy}\n")
        for (a in rejected) append("R;${a.personId};${a.photoId};${a.cx};${a.cy}\n")
        for (a in ignored) append("I;;${a.photoId};${a.cx};${a.cy}\n")
        for (k in notSame) append("N;$k\n")
    }

    companion object {
        const val NEAR = 0.06f

        fun near(a: Anchor, f: FaceRef): Boolean {
            val dx = a.cx - f.cx
            val dy = a.cy - f.cy
            return dx * dx + dy * dy <= NEAR * NEAR
        }

        fun parse(text: String): PeopleState {
            val s = PeopleState()
            fun unesc(x: String) = x.replace("\\s", ";").replace("\\\\", "\\")
            for (line in text.lines()) {
                val q = line.split(';')
                try {
                    when (q[0]) {
                        "P" -> if (q.size >= 3) s.persons[q[1]] = PersonRec(q[1], unesc(q.subList(2, q.size).joinToString(";")))
                        "C" -> s.confirmed += Anchor(q[1], q[2].toLong(), q[3].toFloat(), q[4].toFloat())
                        "R" -> s.rejected += Anchor(q[1], q[2].toLong(), q[3].toFloat(), q[4].toFloat())
                        "I" -> s.ignored += Anchor("", q[2].toLong(), q[3].toFloat(), q[4].toFloat())
                        "N" -> if (q.size >= 2) s.notSame += q.subList(1, q.size).joinToString(";")
                    }
                } catch (_: Exception) { }
            }
            // Confirmaciones de personas que ya no existen: fuera
            s.confirmed.removeAll { it.personId !in s.persons }
            return s
        }
    }
}

/** Grupo mostrado: una persona con nombre (id de PersonRec) o un grupo automático (id «auto:…»). */
class PeopleGroup(val id: String, val name: String, val named: Boolean, val faces: List<FaceRef>, val confirmed: Set<String>) {
    val photoIds: Set<Long> get() = faces.map { it.photoId }.toSet()
}

class Suggestion(val face: FaceRef, val groupId: String, val sim: Float, val strong: Boolean)
class MergeSuggestion(val a: String, val b: String, val sim: Float)

class PeopleResult(
    val groups: List<PeopleGroup>,
    val loose: List<FaceRef>,
    val suggestions: List<Suggestion>,
    val merges: List<MergeSuggestion>,
    val threshold: Float,
    val stats: String
)

object PeopleEngine {

    const val MIN_PHOTOS = 2
    /** Modelo grande: medido con fotos degradadas (distintas máx. 0,22 · misma persona ≥ 0,4 en fotos normales). */
    const val V2_SAME = 0.32f
    const val V2_SUGGEST = 0.20f
    const val SUGGEST_GAP = 0.13f

    fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    fun normalize(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += x * x
        val s = sqrt(n).toFloat()
        if (s > 0f) for (i in v.indices) v[i] /= s
        return v
    }

    private fun centroid(fs: List<FaceRef>, second: Boolean = false): FloatArray? {
        val vs = fs.mapNotNull { if (second) it.v2 else it.v }
        if (vs.isEmpty()) return null
        val c = FloatArray(vs[0].size)
        for (v in vs) if (v.size == c.size) for (k in c.indices) c[k] += v[k]
        return normalize(c)
    }

    /** Une dos caras si cada una está entre las K más parecidas de la otra (y nunca de la misma foto). */
    fun cluster(faces: List<FaceRef>, t: Float, k: Int = 12, cancel: () -> Boolean = { false }): IntArray {
        val n = faces.size
        val nb = Array(n) { IntArray(k) { -1 } }
        val ns = Array(n) { FloatArray(k) { -2f } }
        fun offer(i: Int, j: Int, sim: Float) {
            val a = ns[i]
            var mi = 0
            for (q in 1 until k) if (a[q] < a[mi]) mi = q
            if (sim > a[mi]) { a[mi] = sim; nb[i][mi] = j }
        }
        for (i in 0 until n) {
            if (cancel()) break
            for (j in i + 1 until n) {
                if (faces[j].photoId == faces[i].photoId) continue
                val sim = dot(faces[i].v, faces[j].v)
                if (sim > t) { offer(i, j, sim); offer(j, i, sim) }
            }
        }
        val adj = Array(n) { ArrayList<Int>() }
        val adjW = Array(n) { ArrayList<Float>() }
        for (i in 0 until n) for (q in 0 until k) {
            val j = nb[i][q]
            if (j > i && nb[j].contains(i)) {
                adj[i] += j; adjW[i] += ns[i][q]
                adj[j] += i; adjW[j] += ns[i][q]
            }
        }
        val label = IntArray(n) { it }
        val order = (0 until n).toMutableList()
        val rnd = java.util.Random(7)
        for (round in 0 until 20) {
            order.shuffle(rnd)
            var changed = 0
            for (i in order) {
                if (adj[i].isEmpty()) continue
                val w = HashMap<Int, Float>()
                for (q in adj[i].indices) {
                    val l = label[adj[i][q]]
                    w[l] = (w[l] ?: 0f) + adjW[i][q]
                }
                val best = w.maxByOrNull { it.value }!!.key
                if (best != label[i]) { label[i] = best; changed++ }
            }
            if (changed == 0) break
        }
        // Limpieza: fuera las caras que se parecen poco al conjunto de su grupo
        val groups = (0 until n).groupBy { label[it] }
        for ((_, idx) in groups) {
            if (idx.size < 3) continue
            val c = centroid(idx.map { faces[it] }) ?: continue
            for (i in idx) if (dot(c, faces[i].v) < t) label[i] = -1 - i
        }
        return label
    }

    fun run(faces: List<FaceRef>, state: PeopleState, t: Float, cancel: () -> Boolean = { false }): PeopleResult {
        val byPhoto = faces.groupBy { it.photoId }
        fun resolve(a: Anchor): FaceRef? = byPhoto[a.photoId]
            ?.filter { PeopleState.near(a, it) }
            ?.minByOrNull { (it.cx - a.cx) * (it.cx - a.cx) + (it.cy - a.cy) * (it.cy - a.cy) }

        val confirmedTo = HashMap<FaceRef, String>()
        for (a in state.confirmed) {
            if (a.personId !in state.persons) continue
            resolve(a)?.let { confirmedTo[it] = a.personId }
        }
        val rejectedFor = HashMap<FaceRef, MutableSet<String>>()
        for (a in state.rejected) resolve(a)?.let { rejectedFor.getOrPut(it) { HashSet() } += a.personId }
        val ignored = state.ignored.mapNotNull { resolve(it) }.toSet()
        fun rejected(f: FaceRef, pid: String) = rejectedFor[f]?.contains(pid) == true

        val cand = faces.filter { it !in ignored }
        val label = cluster(cand, t, cancel = cancel)

        // Personas con nombre: sus caras confirmadas, siempre
        val members = LinkedHashMap<String, LinkedHashSet<FaceRef>>()
        for (p in state.persons.values) members[p.id] = LinkedHashSet()
        for ((f, pid) in confirmedTo) if (f !in ignored) members[pid]?.add(f)

        // Grupos automáticos → a la persona con más caras confirmadas dentro, si la hay
        val autos = LinkedHashMap<String, MutableList<FaceRef>>()
        for ((_, idx) in cand.indices.groupBy { label[it] }) {
            val fs = idx.map { cand[it] }
            val votes = fs.mapNotNull { confirmedTo[it] }.groupingBy { it }.eachCount()
            val owner = votes.maxByOrNull { it.value }?.key
            if (owner != null) {
                for (f in fs) if (f !in confirmedTo && !rejected(f, owner)) members[owner]!!.add(f)
            } else {
                val free = fs.filter { it !in confirmedTo }
                if (free.map { it.photoId }.distinct().size >= MIN_PHOTOS) {
                    autos["auto:" + free.minOf { it.key }] = free.toMutableList()
                }
            }
        }

        // Grupos automáticos cuyo nombre de archivo (XMP) coincide con una persona → se unen
        val byName = state.persons.values.filter { it.name.isNotEmpty() }.associateBy { it.name.lowercase() }
        val autoNames = HashMap<String, String>()
        for ((id, fs) in autos.entries.toList()) {
            val fileName = fs.mapNotNull { it.fileName }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            val rec = fileName?.let { byName[it.lowercase()] }
            if (rec != null) {
                for (f in fs) if (!rejected(f, rec.id)) members[rec.id]!!.add(f)
                autos.remove(id)
            } else if (fileName != null) autoNames[id] = fileName
        }

        // Caras sueltas: se intentan colocar con las personas con nombre
        val placed = HashSet<FaceRef>()
        members.values.forEach { placed += it }
        autos.values.forEach { placed += it }
        val cents = members.mapValues { (_, fs) -> centroid(fs.filter { it in confirmedTo }.ifEmpty { fs.toList() }) }
        val cents2 = members.mapValues { (_, fs) -> centroid(fs.filter { it.v2 != null && it in confirmedTo }.ifEmpty { fs.filter { it.v2 != null } }, true) }
        val suggestions = ArrayList<Suggestion>()
        val looseList = ArrayList<FaceRef>()
        for (f in cand) {
            if (f in placed) continue
            var best: String? = null
            var bs = -1f
            var best2: String? = null
            var bs2 = -1f
            for ((pid, c) in cents) {
                if (c == null || rejected(f, pid)) continue
                if (members[pid]!!.any { it.photoId == f.photoId }) continue   // ya sale en esa foto
                val s = dot(c, f.v)
                if (s > bs) { bs = s; best = pid }
                val v2 = f.v2
                val c2 = cents2[pid]
                if (v2 != null && c2 != null && c2.size == v2.size) {
                    val s2 = dot(c2, v2)
                    if (s2 > bs2) { bs2 = s2; best2 = pid }
                }
            }
            when {
                best != null && bs >= t -> { members[best]!!.add(f); placed += f }
                best2 != null && bs2 >= V2_SAME -> { members[best2]!!.add(f); placed += f }
                else -> {
                    looseList += f
                    val strong2 = best2 != null && bs2 >= V2_SUGGEST
                    if (strong2) suggestions += Suggestion(f, best2!!, bs2, true)
                    else if (best != null && bs >= t - SUGGEST_GAP) suggestions += Suggestion(f, best, bs, false)
                }
            }
        }

        // Segunda opinión entre caras sueltas: forman grupos nuevos si el modelo grande las ve iguales
        val loose2 = looseList.filter { it.v2 != null }
        if (loose2.size >= 2) {
            val parent = IntArray(loose2.size) { it }
            fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; return r }
            for (i in loose2.indices) for (j in i + 1 until loose2.size) {
                if (loose2[i].photoId == loose2[j].photoId) continue
                val a = loose2[i].v2!!
                val b = loose2[j].v2!!
                if (a.size == b.size && dot(a, b) >= V2_SAME + 0.02f) parent[find(i)] = find(j)
            }
            for ((_, idx) in loose2.indices.groupBy { find(it) }) {
                val fs = idx.map { loose2[it] }
                if (fs.map { it.photoId }.distinct().size >= MIN_PHOTOS && suggestions.none { s -> fs.any { it === s.face } }) {
                    autos["auto:" + fs.minOf { it.key }] = fs.toMutableList()
                    looseList.removeAll(fs.toSet())
                }
            }
        }

        // Propuestas de unir grupos
        val all = LinkedHashMap<String, List<FaceRef>>()
        for ((id, fs) in members) if (fs.isNotEmpty()) all[id] = fs.toList()
        for ((id, fs) in autos) all[id] = fs
        val gc = all.mapValues { centroid(it.value) }
        val merges = ArrayList<MergeSuggestion>()
        val ids = all.keys.toList()
        for (i in ids.indices) for (j in i + 1 until ids.size) {
            val a = ids[i]
            val b = ids[j]
            if (state.pairKey(a, b) in state.notSame) continue
            val ca = gc[a] ?: continue
            val cb = gc[b] ?: continue
            val s = dot(ca, cb)
            if (s >= t - 0.03f) {
                // la persona con nombre siempre primero
                if (!a.startsWith("auto:") || b.startsWith("auto:")) merges += MergeSuggestion(a, b, s)
                else merges += MergeSuggestion(b, a, s)
            }
        }
        merges.sortByDescending { it.sim }
        suggestions.sortWith(compareByDescending<Suggestion> { it.strong }.thenByDescending { it.sim })

        val groups = ArrayList<PeopleGroup>()
        for ((id, fs) in members) {
            val rec = state.persons[id] ?: continue
            if (fs.isEmpty()) continue
            groups += PeopleGroup(id, rec.name, true, fs.sortedByDescending { it.area }, fs.filter { confirmedTo[it] == id }.map { it.key }.toSet())
        }
        for ((id, fs) in autos) {
            groups += PeopleGroup(id, autoNames[id] ?: "", false, fs.sortedByDescending { it.area }, emptySet())
        }
        groups.sortWith(compareByDescending<PeopleGroup> { it.named }.thenByDescending { it.photoIds.size })

        val inGroups = groups.sumOf { it.faces.size }
        val stats = "${faces.size} caras con huella · ${inGroups} en personas · ${looseList.size} sueltas · " +
            "${ignored.size} ignoradas · umbral ${"%.2f".format(t)}"
        return PeopleResult(groups, looseList.sortedByDescending { it.area }, suggestions.take(300), merges.take(50), t, stats)
    }

    /** Umbral: base medida con fotos etiquetadas; se sube si en tus fotos los desconocidos se parecen más. */
    fun threshold(faces: List<FaceRef>, base: Float, sens: Int): Float {
        val same = ArrayList<Float>()
        for ((_, fs) in faces.groupBy { it.photoId }) {
            if (fs.size < 2) continue
            for (a in fs.indices) for (b in a + 1 until fs.size) same += dot(fs[a].v, fs[b].v)
        }
        val floor = if (same.size >= 15) same.sorted()[((same.size - 1) * 0.99).toInt()] + 0.06f else 0f
        val auto = max(base, floor).coerceIn(0.25f, 0.80f)
        return (auto + (15 - sens.coerceIn(0, 30)) * 0.01f).coerceIn(0.15f, 0.90f)
    }
}
