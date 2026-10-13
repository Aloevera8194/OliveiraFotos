package com.oliveira.fotos

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Roda o backup sozinho, em segundo plano, quando há Wi-Fi e a bateria não está fraca. */
class TrabalhoBackup(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext
        val cfg = lerConfig(app)
        val s = app.getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("tree", null)
        if (s == null || cfg.usuario.isEmpty() || cfg.senha.isEmpty()) return@withContext Result.success()
        val tree = Uri.parse(s)
        if (app.contentResolver.persistedUriPermissions.none { it.uri == tree }) {
            return@withContext Result.success()
        }
        val resultado = try {
            fazerBackup(app, tree, DocumentsContract.getTreeDocumentId(tree), cfg, Indice(app), { }, { isStopped })
        } catch (ex: Exception) {
            "Erro: " + (ex.message ?: ex.javaClass.simpleName)
        }
        val quando = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(Date())
        app.getSharedPreferences("servidor", Context.MODE_PRIVATE).edit()
            .putString("ultimo_auto", "$quando — $resultado").apply()
        if (isStopped) Result.retry() else Result.success()
    }
}

fun agendarBackupAutomatico(ctx: Context, ativo: Boolean) {
    val wm = WorkManager.getInstance(ctx)
    if (!ativo) {
        wm.cancelUniqueWork("backup-auto")
        return
    }
    val restricoes = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresBatteryNotLow(true)
        .build()
    val pedido = PeriodicWorkRequestBuilder<TrabalhoBackup>(12, TimeUnit.HOURS)
        .setConstraints(restricoes)
        .build()
    wm.enqueueUniquePeriodicWork("backup-auto", ExistingPeriodicWorkPolicy.UPDATE, pedido)
}
