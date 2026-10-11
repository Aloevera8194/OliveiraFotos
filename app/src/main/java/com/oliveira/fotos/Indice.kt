package com.oliveira.fotos

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Índice local: etiquetas (assuntos) e álbuns. Não mexe nos arquivos das fotos. */
class Indice(ctx: Context) : SQLiteOpenHelper(ctx, "indice.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE etiqueta (foto TEXT NOT NULL, nome TEXT NOT NULL, PRIMARY KEY (foto, nome))")
        db.execSQL("CREATE TABLE album (nome TEXT NOT NULL, foto TEXT NOT NULL, PRIMARY KEY (nome, foto))")
    }

    override fun onUpgrade(db: SQLiteDatabase, antiga: Int, nova: Int) {}

    fun etiquetar(fotos: List<String>, nomes: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (f in fotos) for (n in nomes) {
                db.execSQL("INSERT OR IGNORE INTO etiqueta (foto, nome) VALUES (?, ?)", arrayOf(f, n))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun adicionarAlbum(fotos: List<String>, album: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (f in fotos) {
                db.execSQL("INSERT OR IGNORE INTO album (nome, foto) VALUES (?, ?)", arrayOf(album, f))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun contar(sql: String): List<Pair<String, Int>> {
        val saida = mutableListOf<Pair<String, Int>>()
        readableDatabase.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) saida.add(c.getString(0) to c.getInt(1))
        }
        return saida
    }

    private fun ids(sql: String, arg: String): List<String> {
        val saida = mutableListOf<String>()
        readableDatabase.rawQuery(sql, arrayOf(arg)).use { c ->
            while (c.moveToNext()) saida.add(c.getString(0))
        }
        return saida
    }

    fun etiquetas() = contar("SELECT nome, COUNT(*) FROM etiqueta GROUP BY nome ORDER BY nome")
    fun albuns() = contar("SELECT nome, COUNT(*) FROM album GROUP BY nome ORDER BY nome")
    fun fotosDaEtiqueta(nome: String) = ids("SELECT foto FROM etiqueta WHERE nome = ? ORDER BY foto", nome)
    fun fotosDoAlbum(nome: String) = ids("SELECT foto FROM album WHERE nome = ? ORDER BY foto", nome)

    /** Quando uma foto é renomeada ou movida pelo app, o índice acompanha o novo id. */
    fun trocarId(antigo: String, novo: String) {
        val db = writableDatabase
        db.execSQL("UPDATE OR REPLACE etiqueta SET foto = ? WHERE foto = ?", arrayOf(novo, antigo))
        db.execSQL("UPDATE OR REPLACE album SET foto = ? WHERE foto = ?", arrayOf(novo, antigo))
    }

    /** Junta o conteúdo de outro índice (o do servidor) com o atual, sem apagar nada. */
    fun mesclarDe(arquivo: java.io.File): Int {
        var n = 0
        val outro = SQLiteDatabase.openDatabase(arquivo.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                outro.rawQuery("SELECT foto, nome FROM etiqueta", null).use { c ->
                    while (c.moveToNext()) {
                        db.execSQL(
                            "INSERT OR IGNORE INTO etiqueta (foto, nome) VALUES (?, ?)",
                            arrayOf(c.getString(0), c.getString(1))
                        )
                        n++
                    }
                }
                outro.rawQuery("SELECT nome, foto FROM album", null).use { c ->
                    while (c.moveToNext()) {
                        db.execSQL(
                            "INSERT OR IGNORE INTO album (nome, foto) VALUES (?, ?)",
                            arrayOf(c.getString(0), c.getString(1))
                        )
                        n++
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            outro.close()
        }
        return n
    }

    /** Grava tudo no arquivo principal do banco, para poder copiá-lo no backup. */
    fun checkpoint() {
        writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
    }

    /** aba 1 = etiqueta, aba 2 = álbum */
    fun remover(aba: Int, nome: String, fotos: List<String>) {
        val tabela = if (aba == 1) "etiqueta" else "album"
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (f in fotos) {
                db.execSQL("DELETE FROM $tabela WHERE nome = ? AND foto = ?", arrayOf(nome, f))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
