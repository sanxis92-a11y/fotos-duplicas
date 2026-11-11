package com.sanxis.fotosduplicadas

import android.content.Context
import android.net.Uri
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

/** Una foto de la galería con su huella visual y sus métricas de calidad. */
class Photo(
    val id: Long, val uri: Uri, var name: String, var folder: String, val isCamera: Boolean,
    var w: Int, var h: Int, val size: Long, val taken: Long, val modified: Long
) {
    /** dHash horizontal+vertical: [0,1] original, [2,3] 90°, [4,5] 180°, [6,7] 270°, [8,9] espejo. */
    val hashes = LongArray(10)
    var lum = 0
    var blur = -1.0      // varianza del Laplaciano a escala reducida (-1 = sin calcular)
    var contrast = 0.0   // desviación típica del gris (para no tomar fotos lisas por borrosas)
    var sharp = -1.0     // nitidez a ~1-2 MP, solo para fotos que están en un grupo
    var valid = false

    // Contenido (pestaña Explorar)
    var labels: List<String> = emptyList()
    var faces: List<FaceInfo> = emptyList()
    var hasGps = false
    /** Carpeta relativa a la memoria, p. ej. "DCIM/Camera/" o "Pictures/Viajes/". */
    var relPath = ""
    var gpsChecked = false   // false si no se pudo comprobar (sin permiso de ubicación de fotos)
    var contentDone = false

    val pixels: Long get() = w.toLong() * h
    val ratio: Double get() = if (w > 0 && h > 0) max(w, h).toDouble() / min(w, h) else 1.0
}

/** Cara detectada: caja normalizada 0..1 sobre la imagen ya enderezada y su huella (int8, normalizada). */
class FaceInfo(val l: Float, val t: Float, val r: Float, val b: Float, val emb: ByteArray?) {
    val area: Float get() = (r - l) * (b - t)
}

enum class Kind { COPIES, SIMILAR, BLURRY, BROWSE }

class Group(val kind: Kind, val photos: MutableList<Photo>, var bestId: Long) {
    val delete = HashSet<Long>()
    fun reclaim(): Long = photos.filter { it.id in delete }.sumOf { it.size }
}

class Settings(ctx: Context) {
    companion object {
        val SCOPES = arrayOf("Toda la galería", "Solo fotos de la cámara", "Solo las carpetas que elija")
        val MIN_KB = intArrayOf(0, 20, 50, 100)
        val MIN_LABELS = arrayOf("Todas", "Más de 20 KB", "Más de 50 KB", "Más de 100 KB")
        val WINDOWS = longArrayOf(0L, 60_000L, 600_000L, 3_600_000L, 86_400_000L)
        val WINDOW_LABELS = arrayOf(
            "Sin límite de tiempo", "Hechas con menos de 1 minuto", "Hechas con menos de 10 minutos",
            "Hechas con menos de 1 hora", "Hechas el mismo día (24 h)"
        )
        const val DEFAULT_CODE = "5454+5353"

        /** Pasa a la forma interna: × → *, ÷ → /, − → -, coma → punto, sin espacios. */
        fun normalizeCode(s: String): String = s.trim()
            .replace('×', '*').replace('x', '*').replace('X', '*')
            .replace('÷', '/').replace(':', '/').replace('−', '-').replace(',', '.')
            .replace(" ", "")

        fun isValidCode(s: String): Boolean =
            s.length >= 5 && Regex("^[0-9.]+([+\\-*/][0-9.]+)+$").matches(s)

        fun hashCode(code: String): String =
            MessageDigest.getInstance("SHA-256").digest(("fd-" + normalizeCode(code)).toByteArray())
                .joinToString("") { "%02x".format(it) }

        val KEEP_LABELS = arrayOf(
            "La de mejor calidad", "La de mayor resolución", "La que más ocupa",
            "La más antigua", "La más reciente"
        )
    }

    private val sp = ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    var scope: Int
        get() = sp.getInt("scope", 0)
        set(v) { sp.edit().putInt("scope", v).apply() }
    var folders: Set<String>
        get() = sp.getStringSet("folders", null)?.toSet() ?: emptySet()
        set(v) { sp.edit().putStringSet("folders", v).apply() }
    var excluded: Set<String>
        get() = sp.getStringSet("excluded", null)?.toSet() ?: emptySet()
        set(v) { sp.edit().putStringSet("excluded", v).apply() }
    var minKb: Int
        get() = sp.getInt("minKb", 20)
        set(v) { sp.edit().putInt("minKb", v).apply() }
    var rotated: Boolean
        get() = sp.getBoolean("rotated", false)
        set(v) { sp.edit().putBoolean("rotated", v).apply() }
    var timeWindow: Int
        get() = sp.getInt("timeWindow", 0).coerceIn(0, WINDOWS.size - 1)
        set(v) { sp.edit().putInt("timeWindow", v).apply() }
    var keepRule: Int
        get() = sp.getInt("keepRule", 0).coerceIn(0, KEEP_LABELS.size - 1)
        set(v) { sp.edit().putInt("keepRule", v).apply() }
    var preferCamera: Boolean
        get() = sp.getBoolean("preferCamera", true)
        set(v) { sp.edit().putBoolean("preferCamera", v).apply() }
    var blurry: Boolean
        get() = sp.getBoolean("blurry", true)
        set(v) { sp.edit().putBoolean("blurry", v).apply() }
    var threshold: Int
        get() = sp.getInt("threshold", 12)
        set(v) { sp.edit().putInt("threshold", v).apply() }
    /** Calculadora al abrir la app. */
    var lockEnabled: Boolean
        get() = sp.getBoolean("lockEnabled", true)
        set(v) { sp.edit().putBoolean("lockEnabled", v).apply() }
    var codeHash: String
        get() = sp.getString("codeHash", null) ?: hashCode(DEFAULT_CODE)
        set(v) { sp.edit().putString("codeHash", v).apply() }
    var calcIcon: Boolean
        get() = sp.getBoolean("calcIcon", false)
        set(v) { sp.edit().putBoolean("calcIcon", v).apply() }

    /** 0..30: más alto = junta más caras como la misma persona. */
    var peopleSens: Int
        get() = sp.getInt("peopleSens", 15)
        set(v) { sp.edit().putInt("peopleSens", v).apply() }
}
