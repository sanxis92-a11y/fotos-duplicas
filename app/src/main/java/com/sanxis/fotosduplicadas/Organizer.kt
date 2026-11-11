package com.sanxis.fotosduplicadas

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Locale

/** Carpeta de la memoria con sus fotos. */
class Folder(val relPath: String, val name: String, val photos: List<Photo>)

/**
 * Mueve fotos entre carpetas en la memoria del móvil (no solo dentro de la app):
 * cualquier galería o explorador de archivos verá el nuevo orden.
 */
class Organizer(private val ctx: Context) {

    companion object {
        const val ROOT = "Pictures/"

        /** "/storage/emulated/0/Pictures/Viajes/a.jpg" → "Pictures/Viajes/" */
        @Suppress("DEPRECATION")
        fun relFromData(data: String): String {
            if (data.isEmpty()) return ""
            val root = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/') + "/"
            val parent = File(data).parent ?: return ""
            val rel = if (parent.startsWith(root)) parent.removePrefix(root) else parent.trimStart('/')
            return rel.trimEnd('/') + "/"
        }

        /** Quita caracteres no válidos en nombres de carpeta. */
        fun cleanName(s: String): String =
            s.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").replace(Regex("\\s+"), " ").trim().take(60)

        fun lastSegment(rel: String): String = rel.trimEnd('/').substringAfterLast('/')

        private val MONTHS: Array<String> = DateFormatSymbols(Locale("es", "ES")).months
    }

    private val cr: ContentResolver = ctx.contentResolver
    @Volatile var cancel = false

    // ------------------------------------------------------------ Carpetas existentes

    fun folders(photos: List<Photo>): List<Folder> =
        photos.filter { it.relPath.isNotEmpty() }
            .groupBy { it.relPath }
            .map { (rel, ps) -> Folder(rel, ps.first().folder.ifEmpty { lastSegment(rel) }, ps) }
            .sortedWith(compareBy<Folder> { !it.relPath.startsWith("DCIM/Camera", true) }.thenByDescending { it.photos.size })

    // ------------------------------------------------------------ Planes automáticos

    fun planByDate(photos: List<Photo>, monthly: Boolean): List<Pair<Photo, String>> = photos.mapNotNull { p ->
        if (p.taken <= 0) return@mapNotNull null
        val c = Calendar.getInstance().apply { timeInMillis = p.taken }
        val y = c.get(Calendar.YEAR)
        val target = if (monthly) {
            val m = c.get(Calendar.MONTH)
            String.format(Locale.US, "%sPor fecha/%d/%d-%02d %s/", ROOT, y, y, m + 1, MONTHS[m])
        } else {
            "${ROOT}Por fecha/$y/"
        }
        p to target
    }

    /** Solo personas con nombre; si en una foto hay varias, gana la de la cara más grande. */
    fun planByPerson(people: List<Person>): List<Pair<Photo, String>> {
        val best = HashMap<Long, Pair<Photo, Pair<Float, String>>>()
        for (pr in people) {
            val name = cleanName(pr.name)
            if (name.isEmpty()) continue
            for ((p, f) in pr.faces) {
                val cur = best[p.id]
                if (cur == null || f.area > cur.second.first) best[p.id] = p to (f.area to name)
            }
        }
        return best.values.map { (p, v) -> p to "${ROOT}Personas/${v.second}/" }
    }

    fun planByCategory(photos: List<Photo>): List<Pair<Photo, String>> {
        // Orden de prioridad: lo más concreto primero; las categorías genéricas de personas no cuentan
        val skip = setOf("people", "groups", "nopeople", "whatsapp")
        val cats = ContentAnalyzer.CATEGORIES.filter { it.key !in skip }
        return photos.mapNotNull { p ->
            val c = cats.firstOrNull { it.test(p) } ?: return@mapNotNull null
            p to "${ROOT}Categorías/${cleanName(c.title)}/"
        }
    }

    // ------------------------------------------------------------ Mover

    /** Mueve una foto. Devuelve true si se movió (o ya estaba allí). */
    fun move(p: Photo, target: String): Boolean {
        if (p.relPath.equals(target, true)) return true
        val ok = if (Build.VERSION.SDK_INT >= 30) moveMediaStore(p.uri, p.name, target) else moveLegacy(p.uri, target)
        if (ok) {
            p.relPath = target
            p.folder = lastSegment(target)
        }
        return ok
    }

    private fun moveMediaStore(uri: Uri, name: String, target: String): Boolean {
        val cv = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, target) }
        try {
            if (cr.update(uri, cv, null, null) > 0) return true
        } catch (_: Exception) { }
        // Puede fallar si ya existe un archivo con el mismo nombre: se renombra
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (n in 1..20) {
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, "$base ($n)$ext")
            try {
                if (cr.update(uri, cv, null, null) > 0) return true
            } catch (_: Exception) { }
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun moveLegacy(uri: Uri, target: String): Boolean {
        val data = cr.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: return false
        val src = File(data)
        if (!src.exists()) return false
        val dir = File(Environment.getExternalStorageDirectory(), target)
        if (!dir.exists() && !dir.mkdirs()) return false
        var dst = File(dir, src.name)
        var n = 1
        while (dst.exists() && n < 50) {
            dst = File(dir, "${src.nameWithoutExtension} ($n).${src.extension}")
            n++
        }
        if (!src.renameTo(dst)) {
            try {
                src.copyTo(dst)
                src.delete()
            } catch (_: Exception) { return false }
        }
        try {
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DATA, dst.absolutePath) }, null, null)
        } catch (_: Exception) { }
        MediaScannerConnection.scanFile(ctx, arrayOf(src.absolutePath, dst.absolutePath), null, null)
        return true
    }

    // ------------------------------------------------------------ Deshacer

    private fun logFile() = File(ctx.filesDir, "ultimo_orden.txt")

    fun hasUndo(): Boolean = logFile().exists() && logFile().length() > 0

    fun undoCount(): Int = try { logFile().readLines().count { it.isNotBlank() } } catch (_: Exception) { 0 }

    /** Guarda dónde estaba cada foto antes de moverla. */
    fun saveLog(moves: List<Pair<Photo, String>>) {
        try {
            logFile().writeText(moves.joinToString("") { (p, _) -> "${p.id};${p.relPath}\n" })
        } catch (_: Exception) { }
    }

    /** Lista (uri, carpeta original) del último orden. */
    fun undoList(): List<Pair<Uri, String>> = try {
        logFile().readLines().mapNotNull { l ->
            val s = l.split(';')
            val id = s.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val rel = s.getOrNull(1) ?: return@mapNotNull null
            if (rel.isEmpty()) null
            else android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id) to rel
        }
    } catch (_: Exception) { emptyList() }

    fun moveBack(uri: Uri, target: String): Boolean {
        val name = cr.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: return false
        return if (Build.VERSION.SDK_INT >= 30) moveMediaStore(uri, name, target) else moveLegacy(uri, target)
    }

    fun clearLog() { logFile().delete() }
}
