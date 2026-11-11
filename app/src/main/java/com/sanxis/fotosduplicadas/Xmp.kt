package com.sanxis.fotosduplicadas

/**
 * Metadatos XMP (Lightroom, Picasa, digiKam, Windows Fotos…): nombres de personas, caras etiquetadas,
 * autor y ubicación. Kotlin puro, sin Android, para poder probarlo con JUnit.
 */
object Xmp {

    const val JPEG_HEADER = "http://ns.adobe.com/xap/1.0/\u0000"

    /** Cara etiquetada en el archivo. Coordenadas 0..1, esquina superior izquierda, tal como vienen en el archivo. */
    class Region(val name: String, val x: Float, val y: Float, val w: Float, val h: Float) {
        val cx: Float get() = x + w / 2f
        val cy: Float get() = y + h / 2f
    }

    // ------------------------------------------------------------ Extraer

    /** Busca el paquete XMP dentro de los bytes del archivo (vale para JPEG, PNG, WebP, HEIC). */
    fun extract(bytes: ByteArray): String? {
        val latin = String(bytes, Charsets.ISO_8859_1)
        val start = latin.indexOf("<x:xmpmeta")
        if (start < 0) return null
        val endTag = "</x:xmpmeta>"
        val end = latin.indexOf(endTag, start)
        if (end < 0) return null
        return String(bytes, start, end + endTag.length - start, Charsets.UTF_8)
    }

    // ------------------------------------------------------------ Leer

    private fun unescape(s: String): String {
        var r = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        r = Regex("&#(x?)([0-9A-Fa-f]+);").replace(r) { m ->
            val code = m.groupValues[2].toIntOrNull(if (m.groupValues[1] == "x") 16 else 10)
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else ""
        }
        return r.replace("&amp;", "&").trim()
    }

    /** Valor de una propiedad como atributo (p:n="v") o como elemento simple (<p:n>v</p:n>). */
    private fun prop(chunk: String, name: String): String? {
        val q = Regex.escape(name)
        Regex("""(?:^|[\s<])$q\s*=\s*"([^"]*)"""").find(chunk)?.let { return unescape(it.groupValues[1]) }
        Regex("""(?:^|[\s<])$q\s*=\s*'([^']*)'""").find(chunk)?.let { return unescape(it.groupValues[1]) }
        Regex("""<$q(?:\s[^>]*)?>([^<]*)</$q\s*>""").find(chunk)?.let { return unescape(it.groupValues[1]) }
        return null
    }

    /** Trozos <rdf:li …> … dentro de un elemento (no anidados). */
    private fun items(section: String): List<String> {
        val parts = section.split(Regex("<rdf:li(?=[\\s>/])"))
        return if (parts.size <= 1) emptyList() else parts.drop(1)
    }

    private fun section(xmp: String, tag: String): String? {
        val q = Regex.escape(tag)
        return Regex("""<$q(?:\s[^>]*)?>(.*?)</$q\s*>""", RegexOption.DOT_MATCHES_ALL).find(xmp)?.groupValues?.get(1)
    }

    private fun f(s: String?): Float? = s?.trim()?.replace(',', '.')?.toFloatOrNull()

    fun regions(xmp: String): List<Region> {
        val out = ArrayList<Region>()
        // Metadata Working Group (Lightroom, Picasa, digiKam, Google): el área va por el CENTRO
        section(xmp, "mwg-rs:RegionList")?.let { list ->
            for (li in items(list)) {
                val name = prop(li, "mwg-rs:Name") ?: continue
                if (name.isEmpty()) continue
                val type = prop(li, "mwg-rs:Type")
                if (type != null && !type.equals("Face", true)) continue
                val w = f(prop(li, "stArea:w")) ?: continue
                val h = f(prop(li, "stArea:h")) ?: continue
                val cx = f(prop(li, "stArea:x")) ?: continue
                val cy = f(prop(li, "stArea:y")) ?: continue
                val unit = prop(li, "stArea:unit")
                if (unit != null && !unit.equals("normalized", true)) continue
                out += Region(name, cx - w / 2f, cy - h / 2f, w, h)
            }
        }
        // Microsoft (Windows Fotos / Live Photo Gallery): rectángulo «x, y, w, h» desde la esquina
        section(xmp, "MPRI:Regions")?.let { list ->
            for (li in items(list)) {
                val name = prop(li, "MPReg:PersonDisplayName") ?: continue
                if (name.isEmpty()) continue
                val r = prop(li, "MPReg:Rectangle")?.split(',')?.mapNotNull { f(it) } ?: continue
                if (r.size != 4) continue
                out += Region(name, r[0], r[1], r[2], r[3])
            }
        }
        return out
    }

    /** Todas las personas nombradas en el archivo, sin repetir. */
    fun names(xmp: String): List<String> {
        val out = LinkedHashSet<String>()
        regions(xmp).forEach { out += it.name }
        section(xmp, "Iptc4xmpExt:PersonInImage")?.let { s ->
            Regex("""<rdf:li(?:\s[^>]*)?>([^<]*)</rdf:li>""").findAll(s).forEach { m ->
                val n = unescape(m.groupValues[1])
                if (n.isNotEmpty()) out += n
            }
        }
        return out.toList()
    }

    /** Nombres de personas sin recuadro (Iptc4xmpExt:PersonInImage). */
    fun personInImage(xmp: String): List<String> {
        val s = section(xmp, "Iptc4xmpExt:PersonInImage") ?: return emptyList()
        return Regex("""<rdf:li(?:\s[^>]*)?>([^<]*)</rdf:li>""").findAll(s)
            .map { unescape(it.groupValues[1]) }.filter { it.isNotEmpty() }.toList()
    }

    fun creator(xmp: String): String? {
        val s = section(xmp, "dc:creator") ?: return prop(xmp, "dc:creator")
        return Regex("""<rdf:li(?:\s[^>]*)?>([^<]*)</rdf:li>""").findAll(s)
            .map { unescape(it.groupValues[1]) }.filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { null }
    }

    fun hasLocation(xmp: String): Boolean =
        Regex("""exif:GPSLatitude|drone-dji:GpsLatitude|photoshop:City|Iptc4xmpCore:Location|Iptc4xmpExt:LocationCreated""")
            .containsMatchIn(xmp)

    // ------------------------------------------------------------ Limpiar

    /** Propiedades que identifican a personas. */
    private val NAME_PROPS = listOf(
        "mwg-rs:Regions", "MP:RegionInfo", "Iptc4xmpExt:PersonInImage", "Iptc4xmpExt:PersonInImageWDetails",
        "dc:creator", "dc:rights", "dc:description", "xmpRights:Owner", "xmpRights:UsageTerms", "xmpRights:WebStatement",
        "photoshop:Author", "photoshop:AuthorsPosition", "photoshop:Credit", "photoshop:CaptionWriter",
        "Iptc4xmpCore:CreatorContactInfo", "exifEX:CameraOwnerName", "exifEX:BodySerialNumber",
        "exifEX:LensSerialNumber", "aux:SerialNumber", "aux:OwnerName", "aux:LensSerialNumber",
        "exif:UserComment", "xmp:Nickname"
    )

    /** Propiedades de ubicación (las que empiezan por estos prefijos). */
    private val LOCATION_PREFIXES = listOf(
        "exif:GPS", "drone-dji:Gps", "drone-dji:AbsoluteAltitude", "drone-dji:RelativeAltitude",
        "photoshop:City", "photoshop:State", "photoshop:Country", "Iptc4xmpCore:Location",
        "Iptc4xmpCore:CountryCode", "Iptc4xmpExt:LocationCreated", "Iptc4xmpExt:LocationShown"
    )

    private fun removeProp(xmp: String, pattern: String): String {
        var r = Regex("""<($pattern)(?=[\s/>])[^>]*?/>""").replace(xmp, "")
        r = Regex("""<($pattern)(?=[\s>])[^>]*?>.*?</\1\s*>""", RegexOption.DOT_MATCHES_ALL).replace(r, "")
        r = Regex("""\s($pattern)\s*=\s*("[^"]*"|'[^']*')""").replace(r, "")
        return r
    }

    /** Quita del XMP nombres y/o ubicación. Respeta el resto (fotos en movimiento, HDR…). */
    fun sanitize(xmp: String, names: Boolean, location: Boolean): String {
        var r = xmp
        if (names) for (p in NAME_PROPS) r = removeProp(r, Regex.escape(p))
        if (location) for (p in LOCATION_PREFIXES) r = removeProp(r, Regex.escape(p) + "[A-Za-z]*")
        return r
    }

    // ------------------------------------------------------------ Reescribir JPEG

    /**
     * Pasa cada segmento XMP de un JPEG por transform. Devuelve los bytes nuevos o null si no cambia nada
     * (o si no es un JPEG). La imagen en sí se copia tal cual.
     */
    fun rewriteJpeg(src: ByteArray, transform: (String) -> String): ByteArray? {
        if (src.size < 4 || (src[0].toInt() and 0xFF) != 0xFF || (src[1].toInt() and 0xFF) != 0xD8) return null
        val header = JPEG_HEADER.toByteArray(Charsets.ISO_8859_1)
        val out = java.io.ByteArrayOutputStream(src.size)
        out.write(src, 0, 2)
        var i = 2
        var changed = false
        while (i + 4 <= src.size) {
            if ((src[i].toInt() and 0xFF) != 0xFF) break
            val marker = src[i + 1].toInt() and 0xFF
            if (marker == 0xFF) { out.write(0xFF); i++; continue }            // relleno
            if (marker == 0xD9 || marker == 0xDA) break                        // fin o inicio de la imagen
            if (marker == 0x01 || marker in 0xD0..0xD7) { out.write(src, i, 2); i += 2; continue }
            val len = ((src[i + 2].toInt() and 0xFF) shl 8) or (src[i + 3].toInt() and 0xFF)
            if (len < 2 || i + 2 + len > src.size) return null                 // archivo raro: no se toca
            val segStart = i + 4
            val segLen = len - 2
            var written = false
            if (marker == 0xE1 && segLen > header.size && matches(src, segStart, header)) {
                val xmp = String(src, segStart + header.size, segLen - header.size, Charsets.UTF_8)
                val nx = transform(xmp)
                if (nx != xmp) {
                    val body = nx.toByteArray(Charsets.UTF_8)
                    val newLen = 2 + header.size + body.size
                    if (newLen <= 0xFFFF) {
                        out.write(0xFF); out.write(0xE1)
                        out.write((newLen shr 8) and 0xFF); out.write(newLen and 0xFF)
                        out.write(header)
                        out.write(body)
                        written = true
                        changed = true
                    }
                }
            }
            if (!written) out.write(src, i, 2 + len)
            i += 2 + len
        }
        if (!changed) return null
        out.write(src, i, src.size - i)
        return out.toByteArray()
    }

    private fun matches(b: ByteArray, off: Int, h: ByteArray): Boolean {
        for (k in h.indices) if (b[off + k] != h[k]) return false
        return true
    }
}
