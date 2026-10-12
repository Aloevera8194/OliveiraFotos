package com.oliveira.fotos

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage

/**
 * Foto em tela cheia com zoom. Com um dedo e sem zoom, o gesto passa para a ttela de
 * deslizar entre fotos; com dois dedos (ou já com zoom) a foto é ampliada e arrastada.
 */
@Composable
fun FotoZoom(model: Any, aoZoom: (Boolean) -> Unit, aoToque: () -> Unit) {
    var escala by remember { mutableStateOf(1f) }
    var deslocamento by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { aoToque() },
                    onDoubleTap = {
                        if (escala > 1f) {
                            escala = 1f
                            deslocamento = Offset.Zero
                            aoZoom(false)
                        } else {
                            escala = 2.5f
                            aoZoom(true)
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val evento = awaitPointerEvent()
                        val doisDedos = evento.changes.size >= 2
                        if (doisDedos || escala > 1f) {
                            val zoom = evento.calculateZoom()
                            val pan = evento.calculatePan()
                            if (zoom != 1f || pan != Offset.Zero) {
                                val nova = (escala * zoom).coerceIn(1f, 6f)
                                val maxX = size.width * (nova - 1f) / 2f
                                val maxY = size.height * (nova - 1f) / 2f
                                escala = nova
                                deslocamento = if (nova > 1f) {
                                    Offset(
                                        (deslocamento.x + pan.x).coerceIn(-maxX, maxX),
                                        (deslocamento.y + pan.y).coerceIn(-maxY, maxY)
                                    )
                                } else {
                                    Offset.Zero
                                }
                                aoZoom(nova > 1f)
                                evento.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (evento.changes.any { it.pressed })
                }
            }
    ) {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = escala,
                    scaleY = escala,
                    translationX = deslocamento.x,
                    translationY = deslocamento.y
                )
        )
    }
}

fun compartilhar(ctx: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val escolha = Intent.createChooser(intent, "Compartilhar foto")
    escolha.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(escolha)
}
