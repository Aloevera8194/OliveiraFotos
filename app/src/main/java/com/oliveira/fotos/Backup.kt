package com.oliveira.fotos

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.SftpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

data class ConfigServidor(
    val host: String,
    val porta: Int,
    val usuario: String,
    val senha: String,
    val pasta: String,
    val subpasta: String = ""
)

fun lerConfig(ctx: Context): ConfigServidor {
    val p = ctx.getSharedPreferences("servidor", Context.MODE_PRIVATE)
    return ConfigServidor(
        p.getString("host", "192.168.3.51") ?: "192.168.3.51",
        p.getInt("porta", 22),
        p.getString("usuario", "") ?: "",
        p.getString("senha", "") ?: "",
        p.getString("pasta", "imagens") ?: "imagens",
        p.getString("subpasta", "") ?: ""
    )
}

fun salvarConfig(ctx: Context, c: ConfigServidor) {
    ctx.getSharedPreferences("servidor", Context.MODE_PRIVATE).edit()
        .putString("host", c.host)
        .putInt("porta", c.porta)
        .putString("usuario", c.usuario)
        .putString("senha", c.senha)
        .putString("pasta", c.pasta)
        .putString("subpasta", c.subpasta)
        .apply()
}

class Contagem {
    var enviadas = 0
    var puladas = 0
    var falhas = 0
    var ultimoErro = ""
    fun resumo() = "Enviadas $enviadas • já estavam $puladas • falhas $falhas"
}

private class ItemBackup(val id: String, val nome: String, val mime: String, val tamanho: Long)

private fun garantirPasta(canal: ChannelSftp, caminho: String) {
    var atual = if (caminho.startsWith("/")) "/" else ""
    for (parte in caminho.split("/").filter { it.isNotEmpty() }) {
        atual = if (atual.isEmpty()) parte else if (atual.endsWith("/")) atual + parte else "$atual/$parte"
        try {
            canal.stat(atual)
        } catch (_: SftpException) {
            canal.mkdir(atual)
        }
    }
}

private fun percorrer(
    ctx: Context,
    tree: Uri,
    pastaId: String,
    remoto: String,
    canal: ChannelSftp,
    cont: Contagem,
    aoProgredir: (String) -> Unit,
    cancelado: () -> Boolean
) {
    val filhos = DocumentsContract.buildChildDocumentsUriUsingTree(tree, pastaId)
    val lista = mutableListOf<ItemBackup>()
    ctx.contentResolver.query(
        filhos,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        ),
        null, null, null
    )?.use { c ->
        while (c.moveToNext()) {
            lista.add(ItemBackup(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3)))
        }
    }
    for (e in lista) {
        if (cancelado()) return
        if (e.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
            if (e.nome.startsWith(".")) continue
            val sub = "$remoto/${e.nome}"
            try {
                garantirPasta(canal, sub)
                percorrer(ctx, tree, e.id, sub, canal, cont, aoProgredir, cancelado)
            } catch (ex: Exception) {
                cont.falhas++
                cont.ultimoErro = ex.message ?: ex.javaClass.simpleName
            }
        } else if (e.mime.startsWith("image/") && !e.nome.startsWith(".")) {
            val destino = "$remoto/${e.nome}"
            try {
                val igual = try {
                    canal.stat(destino).size == e.tamanho
                } catch (_: SftpException) {
                    false
                }
                if (igual) {
                    cont.puladas++
                } else {
                    val tmp = "$destino.parte"
                    val entrada = ctx.contentResolver.openInputStream(docUri(tree, e.id))
                        ?: throw IllegalStateException("não consegui abrir ${e.nome}")
                    entrada.use { canal.put(it, tmp) }
                    try {
                        canal.rm(destino)
                    } catch (_: SftpException) {
                    }
                    canal.rename(tmp, destino)
                    cont.enviadas++
                }
            } catch (ex: Exception) {
                cont.falhas++
                cont.ultimoErro = ex.message ?: ex.javaClass.simpleName
            }
            aoProgredir(cont.resumo() + "\n" + e.nome)
        }
    }
}

fun fazerBackup(
    ctx: Context,
    tree: Uri,
    raizId: String,
    cfg: ConfigServidor,
    indice: Indice,
    aoProgredir: (String) -> Unit,
    cancelado: () -> Boolean
): String {
    val jsch = JSch()
    val sessao = jsch.getSession(cfg.usuario, cfg.host, cfg.porta)
    sessao.setPassword(cfg.senha)
    val props = Properties()
    props["StrictHostKeyChecking"] = "no"
    sessao.setConfig(props)
    sessao.setServerAliveInterval(15000)
    sessao.connect(15000)
    val canal = sessao.openChannel("sftp") as ChannelSftp
    canal.connect(15000)
    val cont = Contagem()
    try {
        garantirPasta(canal, cfg.pasta)
        val destinoFotos = if (cfg.subpasta.isNotEmpty()) cfg.pasta + "/" + cfg.subpasta else cfg.pasta
        garantirPasta(canal, destinoFotos)
        percorrer(ctx, tree, raizId, destinoFotos, canal, cont, aoProgredir, cancelado)

        // copia também o índice (etiquetas e álbuns)
        try {
            indice.checkpoint()
            val arq = ctx.getDatabasePath("indice.db")
            if (arq.exists()) {
                garantirPasta(canal, cfg.pasta + "/_indice")
                arq.inputStream().use { canal.put(it, cfg.pasta + "/_indice/indice.db") }
            }
        } catch (ex: Exception) {
            cont.ultimoErro = "índice: " + (ex.message ?: ex.javaClass.simpleName)
        }
    } finally {
        canal.disconnect()
        sessao.disconnect()
    }
    val titulo = if (cancelado()) "Interrompido." else "Pronto."
    val erro = if (cont.ultimoErro.isNotEmpty()) "\nÚltimo erro: ${cont.ultimoErro}" else ""
    return titulo + " " + cont.resumo() + erro
}

fun restaurarIndice(ctx: Context, cfg: ConfigServidor, indice: Indice): String {
    val sessao = JSch().getSession(cfg.usuario, cfg.host, cfg.porta)
    sessao.setPassword(cfg.senha)
    val props = Properties()
    props["StrictHostKeyChecking"] = "no"
    sessao.setConfig(props)
    sessao.connect(15000)
    val canal = sessao.openChannel("sftp") as ChannelSftp
    canal.connect(15000)
    val temp = java.io.File(ctx.cacheDir, "indice_servidor.db")
    val remoto = cfg.pasta + "/_indice/indice.db"
    try {
        try {
            canal.stat(remoto)
        } catch (_: SftpException) {
            return "Ainda não há índice salvo no servidor. Faça um backup primeiro."
        }
        canal.get(remoto, temp.path)
    } finally {
        canal.disconnect()
        sessao.disconnect()
    }
    val n = indice.mesclarDe(temp)
    temp.delete()
    java.io.File(temp.path + "-wal").delete()
    java.io.File(temp.path + "-shm").delete()
    return "Índice restaurado: $n registros lidos do servidor (o que você já tinha foi mantido)."
}

@Composable
fun TelaBackup(tree: Uri?, raizId: String?, indice: Indice) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val salvo = remember { lerConfig(ctx) }
    var host by remember { mutableStateOf(salvo.host) }
    var porta by remember { mutableStateOf(salvo.porta.toString()) }
    var usuario by remember { mutableStateOf(salvo.usuario) }
    var senha by remember { mutableStateOf(salvo.senha) }
    var pasta by remember { mutableStateOf(salvo.pasta) }
    var subpasta by remember { mutableStateOf(salvo.subpasta) }
    var status by remember { mutableStateOf("") }
    var rodando by remember { mutableStateOf(false) }
    val parar = remember { AtomicBoolean(false) }

    DisposableEffect(rodando) {
        view.keepScreenOn = rodando
        onDispose { view.keepScreenOn = false }
    }

    fun configAtual() = ConfigServidor(
        host.trim(),
        porta.trim().toIntOrNull() ?: 22,
        usuario.trim(),
        senha,
        pasta.trim().ifEmpty { "imagens" },
        subpasta.trim().trim('/')
    )

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 8.dp)
    ) {
        Text("Servidor (SFTP)", color = OliveEscuro)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = host, onValueChange = { host = it },
            label = { Text("Endereço do servidor") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = porta, onValueChange = { porta = it.filter(Char::isDigit) },
            label = { Text("Porta") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = usuario, onValueChange = { usuario = it },
            label = { Text("Usuário") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = senha, onValueChange = { senha = it },
            label = { Text("Senha") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = pasta, onValueChange = { pasta = it },
            label = { Text("Pasta no servidor") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = subpasta, onValueChange = { subpasta = it },
            label = { Text("Subpasta (opcional, ex.: DCIM)") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !rodando,
                colors = ButtonDefaults.buttonColors(containerColor = Oliva),
                onClick = {
                    salvarConfig(ctx, configAtual())
                    status = "Configuração salva."
                }
            ) { Text("Salvar") }
            Button(
                enabled = !rodando && tree != null && raizId != null,
                colors = ButtonDefaults.buttonColors(containerColor = Oliva),
                onClick = {
                    val cfg = configAtual()
                    val t = tree
                    val r = raizId
                    if (cfg.usuario.isEmpty() || cfg.senha.isEmpty()) {
                        status = "Preencha usuário e senha."
                    } else if (t != null && r != null) {
                        salvarConfig(ctx, cfg)
                        rodando = true
                        parar.set(false)
                        status = "Conectando…"
                        scope.launch {
                            val resultado = withContext(Dispatchers.IO) {
                                try {
                                    fazerBackup(ctx, t, r, cfg, indice, { s -> status = s }, { parar.get() })
                                } catch (ex: Exception) {
                                    "Erro: " + (ex.message ?: ex.javaClass.simpleName)
                                }
                            }
                            status = resultado
                            rodando = false
                        }
                    }
                }
            ) { Text("Fazer backup agora") }
            if (rodando) {
                TextButton(onClick = { parar.set(true) }) { Text("Parar") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            enabled = !rodando,
            colors = ButtonDefaults.buttonColors(containerColor = Oliva),
            onClick = {
                val cfg = configAtual()
                if (cfg.usuario.isEmpty() || cfg.senha.isEmpty()) {
                    status = "Preencha usuário e senha."
                } else {
                    salvarConfig(ctx, cfg)
                    rodando = true
                    status = "Buscando o índice no servidor…"
                    scope.launch {
                        val resultado = withContext(Dispatchers.IO) {
                            try {
                                restaurarIndice(ctx, cfg, indice)
                            } catch (ex: Exception) {
                                "Erro: " + (ex.message ?: ex.javaClass.simpleName)
                            }
                        }
                        status = resultado
                        rodando = false
                    }
                }
            }
        ) { Text("Restaurar índice do servidor") }
        if (tree == null) {
            Text(
                "Escolha primeiro a pasta de fotos (botão lá em cima).",
                color = OliveEscuro, modifier = Modifier.padding(top = 8.dp)
            )
        }
        if (status.isNotEmpty()) {
            Text(status, color = OliveEscuro, modifier = Modifier.padding(top = 12.dp))
        }
        if (rodando) {
            Text(
                "Deixe o app aberto até terminar.",
                color = OliveEscuro, modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
