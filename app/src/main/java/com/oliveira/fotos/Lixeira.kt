package com.oliveira.fotos

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

const val NOME_LIXEIRA = ".lixeira"

fun idLixeira(raizId: String): String =
    if (raizId.endsWith(":")) raizId + NOME_LIXEIRA else "$raizId/$NOME_LIXEIRA"

private fun paiDe(id: String, padrao: String): String {
    val i = id.lastIndexOf('/')
    return if (i > 0) id.substring(0, i) else padrao
}

fun garantirLixeira(ctx: Context, tree: Uri, raizId: String): String? {
    val id = idLixeira(raizId)
    val existe = try {
        ctx.contentResolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(tree, id),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null
        )?.use { true } ?: false
    } catch (_: Exception) {
        false
    }
    return if (existe) id else criarPasta(ctx, tree, raizId, NOME_LIXEIRA)
}

fun moverParaLixeira(ctx: Context, tree: Uri, itens: List<Entrada>, raizId: String, indice: Indice): Int {
    val lixeira = garantirLixeira(ctx, tree, raizId) ?: return 0
    val destino = docUri(tree, lixeira)
    var ok = 0
    for (e in itens) {
        try {
            val origemId = paiDe(e.id, raizId)
            val novo = DocumentsContract.moveDocument(
                ctx.contentResolver, docUri(tree, e.id), docUri(tree, origemId), destino
            )
            if (novo != null) {
                val novoId = DocumentsContract.getDocumentId(novo)
                indice.trocarId(e.id, novoId)
                indice.registrarLixeira(novoId, origemId)
                ok++
            }
        } catch (_: Exception) {
        }
    }
    return ok
}

fun restaurarDaLixeira(ctx: Context, tree: Uri, itens: List<Entrada>, raizId: String, indice: Indice): Int {
    var ok = 0
    for (e in itens) {
        try {
            val lixeiraId = paiDe(e.id, raizId)
            val destinoId = indice.origemLixeira(e.id) ?: raizId
            val novo = DocumentsContract.moveDocument(
                ctx.contentResolver, docUri(tree, e.id), docUri(tree, lixeiraId), docUri(tree, destinoId)
            )
            if (novo != null) {
                indice.trocarId(e.id, DocumentsContract.getDocumentId(novo))
                indice.esquecerLixeira(e.id)
                ok++
            }
        } catch (_: Exception) {
        }
    }
    return ok
}

fun apagarDeVez(ctx: Context, tree: Uri, itens: List<Entrada>, indice: Indice): Int {
    var ok = 0
    for (e in itens) {
        try {
            if (DocumentsContract.deleteDocument(ctx.contentResolver, docUri(tree, e.id))) {
                indice.apagarDoIndice(e.id)
                ok++
            }
        } catch (_: Exception) {
        }
    }
    return ok
}
