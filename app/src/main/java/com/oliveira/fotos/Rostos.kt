package com.oliveira.fotos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Rosto novo, ainda não gravado no índice. */
class RostoNovo(val n: Int, val emb: FloatArray, val mini: ByteArray?, val pessoa: String?)

/** Rosto já gravado no índice. */
class RostoRow(val foto: String, val n: Int, val emb: FloatArray, val mini: ByteArray?)

// Quanto menor, mais parecidos. 0 = idênticos. Valores abaixo disso são "a mesma pessoa".
const val LIMITE_PESSOA = 0.35f
const val LIMITE_GRUPO = 0.33f

fun paraBytes(v: FloatArray): ByteArray {
    val b = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    for (x in v) b.putFloat(x)
    return b.array()
}

fun deBytes(b: ByteArray): FloatArray {
    val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(b.size / 4) { buf.getFloat() }
}

fun normalizar(v: FloatArray): FloatArray {
    var soma = 0f
    for (x in v) soma += x * x
    val n = sqrt(soma)
    return if (n == 0f) v else FloatArray(v.size) { v[it] / n }
}

fun produto(a: FloatArray, b: FloatArray): Float {
    var s = 0f
    for (i in a.indices) s += a[i] * b[i]
    return s
}

/** Modelo FaceNet (TFLite): transforma um rosto numa lista de 128 números. */
class Reconhecedor(ctx: Context) : AutoCloseable {
    private val interprete: Interpreter

    init {
        val bytes = ctx.assets.open("facenet.tflite").use { it.readBytes() }
        val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buf.put(bytes)
        buf.rewind()
        interprete = Interpreter(buf, Interpreter.Options().apply { setNumThreads(4) })
    }

    fun embedding(rosto: Bitmap): FloatArray {
        val lado = 160
        val b = Bitmap.createScaledBitmap(rosto, lado, lado, true)
        val px = IntArray(lado * lado)
        b.getPixels(px, 0, lado, 0, 0, lado, lado)
        if (b !== rosto) b.recycle()
        val vals = FloatArray(lado * lado * 3)
        var i = 0
        for (p in px) {
            vals[i++] = ((p shr 16) and 0xFF).toFloat()
            vals[i++] = ((p shr 8) and 0xFF).toFloat()
            vals[i++] = (p and 0xFF).toFloat()
        }
        // padronização por imagem (média 0, desvio 1), como o modelo espera
        var soma = 0.0
        for (v in vals) soma += v
        val media = (soma / vals.size).toFloat()
        var q = 0.0
        for (v in vals) q += (v - media) * (v - media)
        val desvio = max(sqrt((q / vals.size).toFloat()), 1f / sqrt(vals.size.toFloat()))
        val entrada = ByteBuffer.allocateDirect(vals.size * 4).order(ByteOrder.nativeOrder())
        for (v in vals) entrada.putFloat((v - media) / desvio)
        entrada.rewind()
        val saida = Array(1) { FloatArray(128) }
        interprete.run(entrada, saida)
        return normalizar(saida[0])
    }

    override fun close() {
        interprete.close()
    }
}

private fun alinhar(bmp: Bitmap, graus: Int): Bitmap {
    if (graus == 0) return bmp
    val m = Matrix().apply { postRotate(graus.toFloat()) }
    val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    if (r !== bmp) bmp.recycle()
    return r
}

private fun recortar(img: Bitmap, caixa: Rect): Bitmap? {
    val mx = (caixa.width() * 0.1f).toInt()
    val my = (caixa.height() * 0.1f).toInt()
    val x0 = max(0, caixa.left - mx)
    val y0 = max(0, caixa.top - my)
    val x1 = min(img.width, caixa.right + mx)
    val y1 = min(img.height, caixa.bottom + my)
    if (x1 - x0 < 8 || y1 - y0 < 8) return null
    return Bitmap.createBitmap(img, x0, y0, x1 - x0, y1 - y0)
}

private fun miniatura(rec: Bitmap): ByteArray {
    val m = Bitmap.createScaledBitmap(rec, 72, 72, true)
    val s = ByteArrayOutputStream()
    m.compress(Bitmap.CompressFormat.JPEG, 80, s)
    if (m !== rec) m.recycle()
    return s.toByteArray()
}

fun melhorPessoa(emb: FloatArray, centros: Map<String, FloatArray>, limite: Float): String? {
    var melhor: String? = null
    var dist = Float.MAX_VALUE
    for ((nome, c) in centros) {
        val d = 1f - produto(emb, c)
        if (d < dist) {
            dist = d
            melhor = nome
        }
    }
    return if (melhor != null && dist < limite) melhor else null
}

/**
 * Detecta e reconhece os rostos das fotos (tudo no celular). Rostos parecidos com alguém já
 * nomeado recebem o nome sozinhos. Devolve quantos rostos foram encontrados.
 */
fun analisarRostos(
    ctx: Context,
    tree: Uri,
    fotos: List<Entrada>,
    indice: Indice,
    cancelado: () -> Boolean,
    aoProgredir: (Int, Int, Int) -> Unit
): Int {
    val jaFeitas = indice.fotosAnalisadas()
    val pendentes = fotos.filter { it.id !in jaFeitas }
    if (pendentes.isEmpty()) return 0
    val reconhecedor = Reconhecedor(ctx)
    val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setMinFaceSize(0.05f)
            .build()
    )
    val centros = indice.centroides()
    var total = 0
    try {
        for ((i, foto) in pendentes.withIndex()) {
            if (cancelado()) break
            val uri = docUri(tree, foto.id)
            val original = carregar(ctx, uri, 1000)
            if (original != null) {
                val img = alinhar(original, rotacao(ctx, uri))
                try {
                    val rostos = Tasks.await(detector.process(InputImage.fromBitmap(img, 0)))
                    val lista = mutableListOf<RostoNovo>()
                    for ((n, r) in rostos.withIndex()) {
                        val caixa = r.boundingBox
                        if (caixa.width() < 48) continue
                        val rec = recortar(img, caixa) ?: continue
                        val emb = reconhecedor.embedding(rec)
                        val pessoa = melhorPessoa(emb, centros, LIMITE_PESSOA)
                        lista.add(RostoNovo(n, emb, miniatura(rec), pessoa))
                        rec.recycle()
                    }
                    indice.salvarRostos(foto.id, lista)
                    total += lista.size
                } catch (_: Exception) {
                }
                img.recycle()
            }
            aoProgredir(i + 1, pendentes.size, total)
        }
    } finally {
        detector.close()
        reconhecedor.close()
    }
    return total
}

/** Junta rostos parecidos em grupos (só devolve grupos com 2 rostos ou mais, do maior para o menor). */
fun agrupar(rostos: List<RostoRow>, limite: Float = LIMITE_GRUPO): List<List<RostoRow>> {
    val grupos = mutableListOf<MutableList<RostoRow>>()
    val somas = mutableListOf<FloatArray>()
    val centros = mutableListOf<FloatArray>()
    for (r in rostos) {
        var melhor = -1
        var dist = Float.MAX_VALUE
        for (i in centros.indices) {
            val d = 1f - produto(r.emb, centros[i])
            if (d < dist) {
                dist = d
                melhor = i
            }
        }
        if (melhor >= 0 && dist < limite) {
            grupos[melhor].add(r)
            val s = somas[melhor]
            for (k in s.indices) s[k] += r.emb[k]
            centros[melhor] = normalizar(s)
        } else {
            grupos.add(mutableListOf(r))
            somas.add(r.emb.copyOf())
            centros.add(r.emb)
        }
    }
    return grupos.filter { it.size >= 2 }.sortedByDescending { it.size }
}
