package com.oliveira.fotos

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Índice local: etiquetas, álbuns e rostos. Não mexe nos arquivos das fotos. */
class Indice(ctx: Context) : SQLiteOpenHelper(ctx, "indice.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE etiqueta (foto TEXT NOT NULL, nome TEXT NOT NULL, PRIMARY KEY (foto, nome))")
        db.execSQL("CREATE TABLE album (nome TEXT NOT NULL, foto TEXT NOT NULL, PRIMARY KEY (nome, foto))")
        criarRostos(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, antiga: Int, nova: Int) {
        if (antiga < 2) criarRostos(db)
    }

    private fun criarRostos(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS rosto (foto TEXT NOT NULL, n INTEGER NOT NULL, pessoa TEXT, " +
                "emb BLOB NOT NULL, mini BLOB, PRIMARY KEY (foto, n))"
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS analisada (foto TEXT PRIMARY KEY NOT NULL)")
    }

    fun etiquetar(fotos: List<String>, nomes: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (f in fotos) for (n in nomes) {
                db.execSQL("INSERT OR IGNORE INTO etiqueta (foto, nome) VALUES (?, ?)", arrayOf<Any>(f, n))
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
                db.execSQL("INSERT OR IGNORE INTO album (nome, foto) VALUES (?, ?)", arrayOf<Any>(album, f))
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
    fun pessoas() = contar("SELECT pessoa, COUNT(*) FROM rosto WHERE pessoa IS NOT NULL GROUP BY pessoa ORDER BY pessoa")

    /** Quando uma foto é renomeada ou movida pelo app, o índice acompanha o novo id. */
    fun trocarId(antigo: String, novo: String) {
        val db = writableDatabase
        val args = arrayOf<Any>(novo, antigo)
        db.execSQL("UPDATE OR REPLACE etiqueta SET foto = ? WHERE foto = ?", args)
        db.execSQL("UPDATE OR REPLACE album SET foto = ? WHERE foto = ?", args)
        db.execSQL("UPDATE OR REPLACE rosto SET foto = ? WHERE foto = ?", args)
        db.execSQL("UPDATE OR REPLACE analisada SET foto = ? WHERE foto = ?", args)
    }

    /** aba 1 = etiqueta, aba 2 = álbum */
    fun remover(aba: Int, nome: String, fotos: List<String>) {
        val tabela = if (aba == 1) "etiqueta" else "album"
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (f in fotos) {
                db.execSQL("DELETE FROM $tabela WHERE nome = ? AND foto = ?", arrayOf<Any>(nome, f))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Grava tudo no arquivo principal do banco, para poder copiá-lo no backup. */
    fun checkpoint() {
        writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
    }

    // ---------------- rostos ----------------

    fun fotosAnalisadas(): Set<String> {
        val s = mutableSetOf<String>()
        readableDatabase.rawQuery("SELECT foto FROM analisada", null).use { c ->
            while (c.moveToNext()) s.add(c.getString(0))
        }
        return s
    }

    fun salvarRostos(foto: String, lista: List<RostoNovo>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM rosto WHERE foto = ?", arrayOf<Any>(foto))
            for (r in lista) {
                val pessoa = r.pessoa
                db.execSQL(
                    "INSERT INTO rosto (foto, n, pessoa, emb, mini) VALUES (?, ?, ?, ?, ?)",
                    arrayOf<Any?>(foto, r.n, pessoa, paraBytes(r.emb), r.mini)
                )
                if (pessoa != null) {
                    db.execSQL("INSERT OR IGNORE INTO etiqueta (foto, nome) VALUES (?, ?)", arrayOf<Any>(foto, pessoa))
                }
            }
            db.execSQL("INSERT OR IGNORE INTO analisada (foto) VALUES (?)", arrayOf<Any>(foto))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun rostosSemPessoa(): List<RostoRow> {
        val saida = mutableListOf<RostoRow>()
        readableDatabase.rawQuery(
            "SELECT foto, n, emb, mini FROM rosto WHERE pessoa IS NULL ORDER BY foto, n", null
        ).use { c ->
            while (c.moveToNext()) {
                saida.add(
                    RostoRow(
                        c.getString(0), c.getInt(1), deBytes(c.getBlob(2)),
                        if (c.isNull(3)) null else c.getBlob(3)
                    )
                )
            }
        }
        return saida
    }

    /** Média (normalizada) dos rostos já nomeados de cada pessoa. */
    fun centroides(): Map<String, FloatArray> {
        val somas = mutableMapOf<String, FloatArray>()
        readableDatabase.rawQuery("SELECT pessoa, emb FROM rosto WHERE pessoa IS NOT NULL", null).use { c ->
            while (c.moveToNext()) {
                val v = deBytes(c.getBlob(1))
                val s = somas.getOrPut(c.getString(0)) { FloatArray(v.size) }
                for (i in v.indices) s[i] += v[i]
            }
        }
        return somas.mapValues { normalizar(it.value) }
    }

    /** Dá o nome da pessoa aos rostos e etiqueta as fotos com esse nome. */
    fun nomearRostos(chaves: List<Pair<String, Int>>, nome: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((foto, n) in chaves) {
                db.execSQL("UPDATE rosto SET pessoa = ? WHERE foto = ? AND n = ?", arrayOf<Any>(nome, foto, n))
                db.execSQL("INSERT OR IGNORE INTO etiqueta (foto, nome) VALUES (?, ?)", arrayOf<Any>(foto, nome))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------------- restaurar do servidor ----------------

    private fun temTabela(db: SQLiteDatabase, nome: String): Boolean =
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(nome))
            .use { it.moveToFirst() }

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
                            arrayOf<Any>(c.getString(0), c.getString(1))
                        )
                        n++
                    }
                }
                outro.rawQuery("SELECT nome, foto FROM album", null).use { c ->
                    while (c.moveToNext()) {
                        db.execSQL(
                            "INSERT OR IGNORE INTO album (nome, foto) VALUES (?, ?)",
                            arrayOf<Any>(c.getString(0), c.getString(1))
                        )
                        n++
                    }
                }
                if (temTabela(outro, "rosto")) {
                    outro.rawQuery("SELECT foto, n, pessoa, emb, mini FROM rosto", null).use { c ->
                        while (c.moveToNext()) {
                            val pessoa: String? = if (c.isNull(2)) null else c.getString(2)
                            val ins = if (pessoa != null) "INSERT OR REPLACE" else "INSERT OR IGNORE"
                            db.execSQL(
                                "$ins INTO rosto (foto, n, pessoa, emb, mini) VALUES (?, ?, ?, ?, ?)",
                                arrayOf<Any?>(
                                    c.getString(0), c.getInt(1), pessoa, c.getBlob(3),
                                    if (c.isNull(4)) null else c.getBlob(4)
                                )
                            )
                            n++
                        }
                    }
                }
                if (temTabela(outro, "analisada")) {
                    outro.rawQuery("SELECT foto FROM analisada", null).use { c ->
                        while (c.moveToNext()) {
                            db.execSQL("INSERT OR IGNORE INTO analisada (foto) VALUES (?)", arrayOf<Any>(c.getString(0)))
                        }
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
}
