package com.sanxis.fotosduplicadas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class XmpTest {

    // Lightroom / Picasa / digiKam (MWG), con atributos
    private val mwg = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
 <rdf:Description rdf:about="" xmlns:mwg-rs="http://www.metadataworkinggroup.com/schemas/regions/"
   xmlns:stArea="http://ns.adobe.com/xmp/sType/Area#" xmlns:exif="http://ns.adobe.com/exif/1.0/"
   xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
   exif:GPSLatitude="40,25.1N" exif:GPSLongitude="3,42.2W" GCamera:MotionPhoto="1">
  <mwg-rs:Regions rdf:parseType="Resource">
   <mwg-rs:AppliedToDimensions stDim:w="4000" stDim:h="3000" stDim:unit="pixel"/>
   <mwg-rs:RegionList><rdf:Bag>
    <rdf:li><rdf:Description mwg-rs:Name="Lucía García" mwg-rs:Type="Face">
     <mwg-rs:Area stArea:x="0.30" stArea:y="0.40" stArea:w="0.10" stArea:h="0.20" stArea:unit="normalized"/>
    </rdf:Description></rdf:li>
    <rdf:li><rdf:Description mwg-rs:Name="Pedro &amp; Co" mwg-rs:Type="Face">
     <mwg-rs:Area stArea:x="0.70" stArea:y="0.50" stArea:w="0.12" stArea:h="0.16" stArea:unit="normalized"/>
    </rdf:Description></rdf:li>
    <rdf:li><rdf:Description mwg-rs:Name="Perro" mwg-rs:Type="Pet">
     <mwg-rs:Area stArea:x="0.1" stArea:y="0.9" stArea:w="0.1" stArea:h="0.1" stArea:unit="normalized"/>
    </rdf:Description></rdf:li>
   </rdf:Bag></mwg-rs:RegionList>
  </mwg-rs:Regions>
  <dc:creator><rdf:Seq><rdf:li>Ana Fotógrafa</rdf:li></rdf:Seq></dc:creator>
 </rdf:Description></rdf:RDF></x:xmpmeta>"""

    // Windows Fotos (Microsoft), con elementos
    private val ms = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
 <rdf:Description rdf:about="" xmlns:MP="http://ns.microsoft.com/photo/1.2/"
   xmlns:MPRI="http://ns.microsoft.com/photo/1.2/t/RegionInfo#" xmlns:MPReg="http://ns.microsoft.com/photo/1.2/t/Region#">
  <MP:RegionInfo rdf:parseType="Resource"><MPRI:Regions><rdf:Bag>
   <rdf:li rdf:parseType="Resource">
    <MPReg:Rectangle>0.25, 0.30, 0.10, 0.15</MPReg:Rectangle>
    <MPReg:PersonDisplayName>María</MPReg:PersonDisplayName>
   </rdf:li>
  </rdf:Bag></MPRI:Regions></MP:RegionInfo>
 </rdf:Description></rdf:RDF></x:xmpmeta>"""

    // IPTC: nombres sin recuadro
    private val iptc = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
 <rdf:Description rdf:about="" xmlns:Iptc4xmpExt="http://iptc.org/std/Iptc4xmpExt/2008-02-29/">
  <Iptc4xmpExt:PersonInImage><rdf:Bag><rdf:li>Juan</rdf:li></rdf:Bag></Iptc4xmpExt:PersonInImage>
  <exif:GPSAltitude>650</exif:GPSAltitude>
 </rdf:Description></rdf:RDF></x:xmpmeta>"""

    @Test fun mwgRegionsUseCenterAndSkipPets() {
        val r = Xmp.regions(mwg)
        assertEquals(2, r.size)
        assertEquals("Lucía García", r[0].name)
        assertEquals(0.25f, r[0].x, 1e-4f)      // 0.30 - 0.10/2
        assertEquals(0.30f, r[0].y, 1e-4f)      // 0.40 - 0.20/2
        assertEquals(0.30f, r[0].cx, 1e-4f)
        assertEquals("Pedro & Co", r[1].name)
    }

    @Test fun microsoftRegionsUseCorner() {
        val r = Xmp.regions(ms)
        assertEquals(1, r.size)
        assertEquals("María", r[0].name)
        assertEquals(0.25f, r[0].x, 1e-4f)
        assertEquals(0.30f, r[0].cx, 1e-4f)
    }

    @Test fun namesAndCreator() {
        assertEquals(listOf("Lucía García", "Pedro & Co"), Xmp.names(mwg))
        assertEquals(listOf("Juan"), Xmp.names(iptc))
        assertEquals(listOf("Juan"), Xmp.personInImage(iptc))
        assertEquals("Ana Fotógrafa", Xmp.creator(mwg))
        assertTrue(Xmp.hasLocation(mwg))
        assertFalse(Xmp.hasLocation(ms))
    }

    @Test fun sanitizeRemovesNamesAndLocationButKeepsMotionPhoto() {
        val s = Xmp.sanitize(mwg, names = true, location = true)
        assertTrue(Xmp.names(s).isEmpty())
        assertTrue(Xmp.regions(s).isEmpty())
        assertNull(Xmp.creator(s))
        assertFalse(s.contains("GPSLatitude"))
        assertFalse(s.contains("Lucía"))
        assertTrue("debe conservar la foto en movimiento", s.contains("GCamera:MotionPhoto=\"1\""))
        assertTrue(s.contains("</x:xmpmeta>"))
        assertTrue(Xmp.sanitize(ms, true, false).let { Xmp.names(it).isEmpty() && !it.contains("María") })
        val onlyGps = Xmp.sanitize(iptc, names = false, location = true)
        assertFalse(onlyGps.contains("GPSAltitude"))
        assertEquals(listOf("Juan"), Xmp.names(onlyGps))
    }

    private fun jpeg(xmp: String): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        // APP1 EXIF falso
        val exif = "Exif\u0000\u0000ABCDEF".toByteArray(Charsets.ISO_8859_1)
        o.write(byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0, (exif.size + 2).toByte())); o.write(exif)
        val body = Xmp.JPEG_HEADER.toByteArray(Charsets.ISO_8859_1) + xmp.toByteArray(Charsets.UTF_8)
        val len = body.size + 2
        o.write(byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte())); o.write(body)
        // inicio de imagen + datos + fin
        o.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 4, 1, 2, 9, 8, 7, 0xFF.toByte(), 0x00, 6, 0xFF.toByte(), 0xD9.toByte()))
        return o.toByteArray()
    }

    @Test fun rewriteJpegCleansXmpAndKeepsImage() {
        val src = jpeg(mwg)
        assertNotNull(Xmp.extract(src))
        val out = Xmp.rewriteJpeg(src) { Xmp.sanitize(it, names = true, location = true) }
        assertNotNull(out)
        out!!
        val x = Xmp.extract(out)
        assertNotNull(x)
        assertTrue(Xmp.names(x!!).isEmpty())
        assertFalse(x.contains("GPSLatitude"))
        // la parte de la imagen (desde FFDA) es idéntica
        val tailSrc = src.copyOfRange(src.size - 14, src.size)
        val tailOut = out.copyOfRange(out.size - 14, out.size)
        assertTrue(tailSrc.contentEquals(tailOut))
        // el EXIF sigue ahí
        assertTrue(String(out, Charsets.ISO_8859_1).contains("Exif\u0000\u0000ABCDEF"))
    }

    @Test fun rewriteJpegNoChangeReturnsNull() {
        assertNull(Xmp.rewriteJpeg(jpeg(ms)) { it })
        assertNull(Xmp.rewriteJpeg("no soy un jpeg".toByteArray()) { "x" })
    }
}
