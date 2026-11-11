package com.sanxis.fotosduplicadas

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Cara detectada por SCRFD, en píxeles de la imagen analizada. */
class Det(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val score: Float, val kps: FloatArray)

/**
 * Motor de caras de InsightFace (buffalo_s), todo en el móvil con ONNX Runtime:
 *  - det_500m.onnx (SCRFD): detecta caras y 5 puntos (ojos, nariz, comisuras).
 *  - w600k_mbf.onnx (MobileFaceNet entrenada con WebFace600K): huella de 512 valores.
 * El mismo proceso se ha verificado en Python con fotos etiquetadas (100 % de acierto, margen 0,43 / 0,24).
 */
class FaceEngine(private val ctx: Context) {

    companion object {
        const val DET_FILE = "det_500m.onnx"
        const val REC_FILE = "w600k_mbf.onnx"
        private const val DET = 640
        private const val REC = 112
        private const val DET_THRESHOLD = 0.5f
        private const val NMS = 0.4f
        /** Plantilla ArcFace 112×112 (ojo izq., ojo dcho., nariz, boca izq., boca dcha. de la imagen). */
        private val TEMPLATE = floatArrayOf(
            38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f, 71.7366f, 41.5493f, 92.3655f, 70.7299f, 92.2041f
        )
    }

    private var env: OrtEnvironment? = null
    private var det: OrtSession? = null
    private var rec: OrtSession? = null
    private var detIn = ""
    private var recIn = ""
    val ready: Boolean get() = det != null && rec != null
    val canDetect: Boolean get() = det != null

    @Synchronized
    fun load() {
        if (ready) return
        try {
            val e = OrtEnvironment.getEnvironment()
            env = e
            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
            if (det == null) {
                val b = ctx.assets.open(DET_FILE).use { it.readBytes() }
                det = e.createSession(b, opts).also { detIn = it.inputNames.first() }
            }
            if (rec == null) {
                val b = ctx.assets.open(REC_FILE).use { it.readBytes() }
                rec = e.createSession(b, opts).also { recIn = it.inputNames.first() }
            }
        } catch (_: Throwable) {
            // Sin modelos: la app sigue funcionando sin caras
        }
    }

    // ------------------------------------------------------------ Detección (SCRFD)

    @Synchronized
    fun detect(bmp: Bitmap): List<Det> {
        val s = det ?: return emptyList()
        val e = env ?: return emptyList()
        val w = bmp.width
        val h = bmp.height
        val scale = DET.toFloat() / max(w, h)
        val nw = max(1, (w * scale).roundToInt())
        val nh = max(1, (h * scale).roundToInt())
        val small = Bitmap.createScaledBitmap(bmp, nw, nh, true)
        val px = IntArray(nw * nh)
        small.getPixels(px, 0, nw, 0, 0, nw, nh)
        if (small !== bmp) small.recycle()

        // NCHW, RGB, (x - 127.5) / 128, relleno negro a la derecha y abajo
        val plane = DET * DET
        val data = FloatArray(3 * plane) { -127.5f / 128f }
        for (y in 0 until nh) for (x in 0 until nw) {
            val c = px[y * nw + x]
            val o = y * DET + x
            data[o] = (((c shr 16) and 0xFF) - 127.5f) / 128f
            data[plane + o] = (((c shr 8) and 0xFF) - 127.5f) / 128f
            data[2 * plane + o] = ((c and 0xFF) - 127.5f) / 128f
        }
        val found = ArrayList<Det>()
        OnnxTensor.createTensor(e, FloatBuffer.wrap(data), longArrayOf(1, 3, DET.toLong(), DET.toLong())).use { input ->
            s.run(mapOf(detIn to input)).use { out ->
                val strides = intArrayOf(8, 16, 32)
                for ((i, st) in strides.withIndex()) {
                    val sc = (out.get(i) as OnnxTensor).floatBuffer
                    val bb = (out.get(i + 3) as OnnxTensor).floatBuffer
                    val kp = (out.get(i + 6) as OnnxTensor).floatBuffer
                    val g = DET / st
                    val n = g * g * 2
                    for (a in 0 until n) {
                        val score = sc.get(a)
                        if (score < DET_THRESHOLD) continue
                        val cell = a / 2
                        val cx = (cell % g) * st.toFloat()
                        val cy = (cell / g) * st.toFloat()
                        val k = FloatArray(10)
                        for (q in 0 until 5) {
                            k[2 * q] = (cx + kp.get(a * 10 + 2 * q) * st) / scale
                            k[2 * q + 1] = (cy + kp.get(a * 10 + 2 * q + 1) * st) / scale
                        }
                        found += Det(
                            (cx - bb.get(a * 4) * st) / scale, (cy - bb.get(a * 4 + 1) * st) / scale,
                            (cx + bb.get(a * 4 + 2) * st) / scale, (cy + bb.get(a * 4 + 3) * st) / scale,
                            score, k
                        )
                    }
                }
            }
        }
        return nms(found)
    }

    private fun nms(list: List<Det>): List<Det> {
        val sorted = list.sortedByDescending { it.score }
        val keep = ArrayList<Det>()
        val removed = BooleanArray(sorted.size)
        for (i in sorted.indices) {
            if (removed[i]) continue
            val a = sorted[i]
            keep += a
            val aa = (a.x2 - a.x1) * (a.y2 - a.y1)
            for (j in i + 1 until sorted.size) {
                if (removed[j]) continue
                val b = sorted[j]
                val iw = max(0f, min(a.x2, b.x2) - max(a.x1, b.x1))
                val ih = max(0f, min(a.y2, b.y2) - max(a.y1, b.y1))
                val inter = iw * ih
                val ab = (b.x2 - b.x1) * (b.y2 - b.y1)
                if (inter / (aa + ab - inter + 1e-9f) > NMS) removed[j] = true
            }
        }
        return keep
    }

    /** 0 = de frente; más de ~0,5 = de perfil (la nariz se sale de entre los ojos). */
    fun profile(d: Det): Float {
        val k = d.kps
        val ed = sqrt((k[2] - k[0]) * (k[2] - k[0]) + (k[3] - k[1]) * (k[3] - k[1]))
        val mid = (k[0] + k[2]) / 2f
        return abs(k[4] - mid) / (ed + 1e-6f)
    }

    fun eyeDistance(d: Det): Float {
        val k = d.kps
        return sqrt((k[2] - k[0]) * (k[2] - k[0]) + (k[3] - k[1]) * (k[3] - k[1]))
    }

    // ------------------------------------------------------------ Huella (ArcFace)

    /** Semejanza por mínimos cuadrados (igual que en la prueba en Python). */
    private fun similarity(s: FloatArray, d: FloatArray): Matrix? {
        var sxm = 0f; var sym = 0f; var dxm = 0f; var dym = 0f
        for (i in 0 until 5) { sxm += s[2 * i]; sym += s[2 * i + 1]; dxm += d[2 * i]; dym += d[2 * i + 1] }
        sxm /= 5; sym /= 5; dxm /= 5; dym /= 5
        var a = 0f; var b = 0f; var ss = 0f
        for (i in 0 until 5) {
            val x = s[2 * i] - sxm
            val y = s[2 * i + 1] - sym
            val u = d[2 * i] - dxm
            val v = d[2 * i + 1] - dym
            a += x * u + y * v
            b += x * v - y * u
            ss += x * x + y * y
        }
        if (ss < 1e-3f) return null
        val ca = a / ss
        val cb = b / ss
        val tx = dxm - (ca * sxm - cb * sym)
        val ty = dym - (cb * sxm + ca * sym)
        return Matrix().apply { setValues(floatArrayOf(ca, -cb, tx, cb, ca, ty, 0f, 0f, 1f)) }
    }

    private fun run(px: IntArray): FloatArray? {
        val s = rec ?: return null
        val e = env ?: return null
        val plane = REC * REC
        val data = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val c = px[i]
            data[i] = (((c shr 16) and 0xFF) - 127.5f) / 127.5f
            data[plane + i] = (((c shr 8) and 0xFF) - 127.5f) / 127.5f
            data[2 * plane + i] = ((c and 0xFF) - 127.5f) / 127.5f
        }
        OnnxTensor.createTensor(e, FloatBuffer.wrap(data), longArrayOf(1, 3, REC.toLong(), REC.toLong())).use { input ->
            s.run(mapOf(recIn to input)).use { out ->
                val fb = (out.get(0) as OnnxTensor).floatBuffer
                val v = FloatArray(fb.remaining())
                fb.get(v)
                return v
            }
        }
    }

    /**
     * Huella normalizada de la cara. kps en coordenadas de src.
     * Se suma la huella de la cara en espejo (más estable).
     */
    @Synchronized
    fun embed(src: Bitmap, kps: FloatArray): FloatArray? {
        if (rec == null) return null
        return try {
            val m = similarity(kps, TEMPLATE) ?: return null
            val face = Bitmap.createBitmap(REC, REC, Bitmap.Config.ARGB_8888)
            Canvas(face).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
            val px = IntArray(REC * REC)
            face.getPixels(px, 0, REC, 0, 0, REC, REC)
            face.recycle()
            val mirror = IntArray(px.size)
            for (y in 0 until REC) for (x in 0 until REC) mirror[y * REC + x] = px[y * REC + (REC - 1 - x)]
            val a = run(px) ?: return null
            val b = run(mirror) ?: return null
            val v = FloatArray(a.size) { a[it] + b[it] }
            var n = 0.0
            for (x in v) n += x * x
            val norm = sqrt(n).toFloat()
            if (norm <= 0f) return null
            for (i in v.indices) v[i] /= norm
            v
        } catch (_: Throwable) { null }
    }
}
