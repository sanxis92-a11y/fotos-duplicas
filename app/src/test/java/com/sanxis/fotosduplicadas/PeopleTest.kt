package com.sanxis.fotosduplicadas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class PeopleTest {

    private val dim = 64
    private val rnd = Random(42)

    private fun unit(v: FloatArray) = PeopleEngine.normalize(v)
    private fun randomVec() = unit(FloatArray(dim) { rnd.nextGaussian().toFloat() })

    /** Cara de una identidad: base + ruido (noise alto = cara difícil). */
    private fun around(base: FloatArray, noise: Float) =
        unit(FloatArray(dim) { base[it] + noise * rnd.nextGaussian().toFloat() / kotlin.math.sqrt(dim.toFloat()) })

    private val ana = randomVec()
    private val luis = randomVec()
    private val eva = randomVec()

    /** 3 personas, 6 fotos cada una, una cara por foto. */
    private fun gallery(): MutableList<FaceRef> {
        val out = ArrayList<FaceRef>()
        var photo = 1L
        for ((base, n) in listOf(ana to 6, luis to 6, eva to 6)) {
            repeat(n) { out += FaceRef(photo++, 0, 0.5f, 0.4f, 0.05f, around(base, 0.6f)) }
        }
        return out
    }

    private fun groupOf(r: PeopleResult, f: FaceRef) = r.groups.firstOrNull { g -> g.faces.any { it === f } }

    @Test fun autoGroupsWithoutHelp() {
        val faces = gallery()
        val r = PeopleEngine.run(faces, PeopleState(), 0.38f)
        assertEquals(3, r.groups.size)
        assertTrue(r.groups.all { it.faces.size == 6 && !it.named })
        assertTrue(r.loose.isEmpty())
    }

    @Test fun confirmingOneFaceNamesTheWholeGroup() {
        val faces = gallery()
        val st = PeopleState()
        val p = st.createPerson("Ana")
        st.confirm(faces[0], p.id)
        val r = PeopleEngine.run(faces, st, 0.38f)
        val g = groupOf(r, faces[3])
        assertNotNull(g)
        assertEquals("Ana", g!!.name)
        assertTrue(g.named)
        assertEquals(6, g.faces.size)
        assertEquals(setOf(faces[0].key), g.confirmed)
    }

    @Test fun rejectedFaceLeavesThePerson() {
        val faces = gallery()
        val st = PeopleState()
        val p = st.createPerson("Ana")
        st.confirm(faces[0], p.id)
        st.reject(faces[2], p.id)
        val r = PeopleEngine.run(faces, st, 0.38f)
        assertFalse(groupOf(r, faces[2])?.id == p.id)
        assertEquals(5, r.groups.first { it.id == p.id }.faces.size)
    }

    @Test fun confirmedFaceAlwaysGoesToItsPerson() {
        val faces = gallery()
        val st = PeopleState()
        val l = st.createPerson("Luis")
        st.confirm(faces[0], l.id)          // el usuario dice que una cara de Ana es Luis
        val r = PeopleEngine.run(faces, st, 0.38f)
        assertEquals(l.id, groupOf(r, faces[0])?.id)
    }

    @Test fun hardFaceBecomesSuggestion() {
        val faces = gallery()
        val st = PeopleState()
        val p = st.createPerson("Ana")
        faces.take(6).forEach { st.confirm(it, p.id) }
        // cara difícil de Ana: se parece algo, pero menos que el umbral
        val hard = run {
            var f: FaceRef
            do {
                f = FaceRef(100, 0, 0.5f, 0.5f, 0.01f, around(ana, 2.2f))
                val s = PeopleEngine.dot(f.v, PeopleEngine.normalize(FloatArray(dim) { k -> faces.take(6).sumOf { it.v[k].toDouble() }.toFloat() }))
                val pairMax = faces.take(6).maxOf { PeopleEngine.dot(it.v, f.v) }
            } while (s >= 0.38f || s < 0.27f || pairMax >= 0.38f)
            f
        }
        faces += hard
        val r = PeopleEngine.run(faces, st, 0.38f)
        assertTrue(hard in r.loose)
        val s = r.suggestions.firstOrNull { it.face === hard }
        assertNotNull(s)
        assertEquals(p.id, s!!.groupId)
    }

    @Test fun secondOpinionPlacesHardFace() {
        val faces = gallery()
        val st = PeopleState()
        val p = st.createPerson("Ana")
        val big = randomVec()
        faces.take(6).forEach { st.confirm(it, p.id); it.v2 = around(big, 0.5f) }
        val hard = FaceRef(200, 0, 0.5f, 0.5f, 0.01f, randomVec(), v2 = around(big, 0.5f))
        faces += hard
        val r = PeopleEngine.run(faces, st, 0.38f)
        assertEquals(p.id, groupOf(r, hard)?.id)
    }

    @Test fun ignoredFaceDisappears() {
        val faces = gallery()
        val st = PeopleState()
        st.ignore(faces[1])
        val r = PeopleEngine.run(faces, st, 0.38f)
        assertTrue(groupOf(r, faces[1]) == null && faces[1] !in r.loose)
    }

    @Test fun anchorsSurviveReanalysis() {
        val faces = gallery()
        val st = PeopleState()
        val p = st.createPerson("Eva")
        st.confirm(faces[12], p.id)
        val text = st.serialize()
        val st2 = PeopleState.parse(text)
        assertEquals("Eva", st2.persons[p.id]?.name)
        // nuevo análisis: otro número de cara y el centro algo movido
        val re = faces.map { FaceRef(it.photoId, 3, it.cx + 0.02f, it.cy - 0.01f, it.area, it.v) }
        val r = PeopleEngine.run(re, st2, 0.38f)
        assertEquals("Eva", groupOf(r, re[14])?.name)
    }

    @Test fun renameToExistingNameMerges() {
        val faces = gallery()
        val st = PeopleState()
        val a = st.createPerson("Ana")
        val b = st.createPerson("Anita")
        st.confirm(faces[0], a.id)
        st.confirm(faces[1], b.id)
        val final = st.rename(b.id, "ana")
        assertEquals(a.id, final)
        assertFalse(b.id in st.persons)
        assertTrue(st.confirmed.all { it.personId == a.id })
    }

    @Test fun namesWithSemicolonsRoundTrip() {
        val st = PeopleState()
        st.createPerson("Ana; la de Madrid")
        val st2 = PeopleState.parse(st.serialize())
        assertEquals("Ana; la de Madrid", st2.persons.values.first().name)
    }

    @Test fun fileNameJoinsNamedPerson() {
        val faces = gallery().map { if (it.photoId <= 6) FaceRef(it.photoId, 0, it.cx, it.cy, it.area, it.v, fileName = "Ana") else it }
        val st = PeopleState()
        val p = st.createPerson("Ana")
        val r = PeopleEngine.run(faces, st, 0.38f)
        assertEquals(p.id, groupOf(r, faces[2])?.id)
    }
}
