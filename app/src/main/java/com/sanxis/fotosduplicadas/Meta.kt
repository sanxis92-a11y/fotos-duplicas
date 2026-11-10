package com.sanxis.fotosduplicadas

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/** Lectura y edición de metadatos EXIF, en el propio archivo y sin conexión. */
object Meta {

    val GPS_TAGS = arrayOf(
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_MAP_DATUM
    )

    /** Datos que identifican a la persona o al aparato (además de la ubicación). */
    val PERSONAL_TAGS = arrayOf(
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
        ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_LENS_SERIAL_NUMBER,
        ExifInterface.TAG_BODY_SERIAL_NUMBER, ExifInterface.TAG_CAMERA_OWNER_NAME,
        ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_USER_COMMENT, ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_IMAGE_UNIQUE_ID, ExifInterface.TAG_MAKER_NOTE
    )

    val DATE_TAGS = arrayOf(
        ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_DIGITIZED
    )

    private fun open(cr: ContentResolver, uri: Uri): InputStream? {
        if (Build.VERSION.SDK_INT >= 29) {
            try { return cr.openInputStream(MediaStore.setRequireOriginal(uri)) } catch (_: Throwable) { }
        }
        return cr.openInputStream(uri)
    }

    /** Lista (etiqueta, valor) legible en español. Lo vacío no aparece. */
    fun read(cr: ContentResolver, p: Photo): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        out += "Archivo" to p.name
        out += "Carpeta" to p.folder.ifEmpty { "—" }
        out += "Tamaño" to String.format(Locale.getDefault(), "%.2f MB", p.size / 1048576.0)
        out += "Resolución" to "${p.w} × ${p.h} px"
        val e = try { open(cr, p.uri)?.use { ExifInterface(it) } } catch (_: Throwable) { null }
        if (e == null) {
            out += "Metadatos" to "No se han podido leer"
            return out
        }
        fun a(tag: String): String? = e.getAttribute(tag)?.trim()?.takeIf { it.isNotEmpty() }
        fun add(label: String, v: String?) { if (!v.isNullOrBlank()) out += label to v }

        add("Fecha de captura", a(ExifInterface.TAG_DATETIME_ORIGINAL) ?: a(ExifInterface.TAG_DATETIME))
        val cam = listOfNotNull(a(ExifInterface.TAG_MAKE), a(ExifInterface.TAG_MODEL)).joinToString(" ")
        add("Cámara", cam)
        add("Objetivo", a(ExifInterface.TAG_LENS_MODEL))
        val exp = e.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
        if (exp > 0) add("Exposición", if (exp < 1) "1/${(1 / exp).toInt()} s" else String.format(Locale.getDefault(), "%.1f s", exp))
        val fn = e.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
        if (fn > 0) add("Apertura", String.format(Locale.getDefault(), "f/%.1f", fn))
        add("ISO", a(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY))
        val fl = e.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
        if (fl > 0) add("Distancia focal", String.format(Locale.getDefault(), "%.1f mm", fl))
        val flash = e.getAttributeInt(ExifInterface.TAG_FLASH, -1)
        if (flash >= 0) add("Flash", if ((flash and 1) == 1) "Disparado" else "No disparado")
        val ll = e.latLong
        if (ll != null) {
            val alt = e.getAltitude(Double.NaN)
            add(
                "📍 Ubicación",
                String.format(Locale.US, "%.5f, %.5f", ll[0], ll[1]) +
                    if (!alt.isNaN()) String.format(Locale.getDefault(), " · %.0f m", alt) else ""
            )
        } else {
            add("📍 Ubicación", "Sin ubicación")
        }
        add("Autor", a(ExifInterface.TAG_ARTIST))
        add("Descripción", a(ExifInterface.TAG_IMAGE_DESCRIPTION))
        add("Comentario", a(ExifInterface.TAG_USER_COMMENT))
        add("Copyright", a(ExifInterface.TAG_COPYRIGHT))
        add("Software", a(ExifInterface.TAG_SOFTWARE))
        add("Propietario", a(ExifInterface.TAG_CAMERA_OWNER_NAME))
        add("Nº de serie", a(ExifInterface.TAG_BODY_SERIAL_NUMBER))
        return out
    }

    /** Edita el EXIF del archivo original. Lanza excepción si el formato no se puede escribir (p. ej. HEIC). */
    fun edit(cr: ContentResolver, uri: Uri, change: (ExifInterface) -> Unit) {
        val pfd = cr.openFileDescriptor(uri, "rw") ?: throw IOException("No se pudo abrir el archivo")
        pfd.use {
            val e = ExifInterface(it.fileDescriptor)
            change(e)
            e.saveAttributes()
        }
    }

    fun clear(e: ExifInterface, tags: Array<String>) {
        for (t in tags) e.setAttribute(t, null)
    }
}
