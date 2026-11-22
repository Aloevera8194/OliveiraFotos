package com.oliveira.fotos

import android.content.Context
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class PastaRemota(val nome: String, val caminho: String, val publico: Boolean)
class FotoRemota(val nome: String, val caminho: String)
class ListaRemota(val pastas: List<PastaRemota>, val fotos: List<FotoRemota>)
class ColecaoRemota(val nome: String, val total: Int, val publico: Boolean)
class SugestaoRemota(val caminho: String, val n: Int, val sim: Double)

/** Conversa com o servidor de fotos da família (HTTP na rede de casa). */
class ClienteFamilia(val base: String, usuario: String, senha: String) {
    val auth: String = "Basic " + Base64.encodeToString("$usuario:$senha".toByteArray(), Base64.NO_WRAP)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun abrir(api: String): HttpURLConnection {
        val c = URL(base + api).openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", auth)
        c.connectTimeout = 8000
        c.readTimeout = 20000
        return c
    }

    private fun ler(c: HttpURLConnection): String {
        try {
            if (c.responseCode == 401) throw IllegalStateException("usuário ou senha incorretos")
            if (c.responseCode == 403) throw IllegalStateException("sem acesso a essa pasta")
            if (c.responseCode != 200) {
                val msg = try {
                    JSONObject(c.errorStream.bufferedReader().readText()).optString("erro")
                } catch (e: Exception) {
                    ""
                }
                throw IllegalStateException(if (msg.isNotEmpty()) msg else "erro ${c.responseCode}")
            }
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    fun get(api: String): String = ler(abrir(api))

    fun post(api: String, corpo: String, leitura: Int = 20000): String {
        val c = abrir(api)
        c.readTimeout = leitura
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(corpo.toByteArray()) }
        return ler(c)
    }

    fun lista(caminho: String): ListaRemota = lerLista(get("/api/lista?c=" + enc(caminho)))

    fun colecao(tipo: String, nome: String): ListaRemota =
        lerLista(get("/api/colecao?t=" + tipo + "&n=" + enc(nome)))

    fun colecoes(tipo: String): List<ColecaoRemota> {
        val a = JSONObject(get("/api/colecoes?t=" + tipo)).getJSONArray("itens")
        return List(a.length()) {
            val o = a.getJSONObject(it)
            ColecaoRemota(o.getString("nome"), o.getInt("total"), o.optBoolean("publico"))
        }
    }

    fun marcar(tipo: String, nome: String, caminhos: List<String>, remover: Boolean = false) {
        post("/api/marcar", JSONObject().put("t", tipo).put("n", nome)
            .put("c", JSONArray(caminhos)).put("remover", remover).toString())
    }

    fun liberarColecao(tipo: String, nome: String, publico: Boolean) {
        post("/api/liberar", JSONObject().put("t", tipo).put("n", nome).put("publico", publico).toString())
    }

    fun mover(caminhos: List<String>, destino: String) {
        post("/api/mover", JSONObject().put("c", JSONArray(caminhos)).put("d", destino).toString())
    }

    fun pastas(): List<String> {
        val a = JSONObject(get("/api/pastas")).getJSONArray("pastas")
        return List(a.length()) { a.getString(it) }
    }

    fun exemplo(nome: String, caminhos: List<String>): String =
        JSONObject(post("/api/exemplo", JSONObject().put("n", nome).put("c", JSONArray(caminhos)).toString(), 300000))
            .optString("resultado")

    fun sugestoes(nome: String): List<SugestaoRemota> {
        val a = JSONObject(get("/api/sugestoes?n=" + enc(nome))).getJSONArray("itens")
        return List(a.length()) {
            val o = a.getJSONObject(it)
            SugestaoRemota(o.getString("caminho"), o.getInt("n"), o.optDouble("sim"))
        }
    }

    fun responder(nome: String, caminho: String, n: Int, sim: Boolean) {
        post("/api/responder", JSONObject().put("n", nome).put("c", caminho).put("i", n).put("sim", sim).toString())
    }

    fun urlRecorte(c: String, n: Int) = base + "/api/recorte?i=" + n + "&c=" + enc(c)

    fun renomear(tipo: String, nome: String, novo: String) {
        post("/api/renomear", JSONObject().put("t", tipo).put("n", nome).put("novo", novo).toString())
    }

    fun apagarColecao(tipo: String, nome: String) {
        post("/api/apagarcolecao", JSONObject().put("t", tipo).put("n", nome).toString())
    }

    fun apagarPasta(caminho: String) {
        post("/api/apagarpasta", JSONObject().put("c", caminho).toString())
    }

    private fun lerLista(texto: String): ListaRemota {
        val j = JSONObject(texto)
        val pastas = j.getJSONArray("pastas")
        val fotos = j.getJSONArray("fotos")
        return ListaRemota(
            List(pastas.length()) {
                val o = pastas.getJSONObject(it)
                PastaRemota(o.getString("nome"), o.getString("caminho"), o.optBoolean("publico"))
            },
            List(fotos.length()) {
                val o = fotos.getJSONObject(it)
                FotoRemota(o.getString("nome"), o.getString("caminho"))
            }
        )
    }

    fun definirPublico(caminho: String, publico: Boolean) {
        post("/api/publico", JSONObject().put("c", caminho).put("publico", publico).toString())
    }

    fun urlMini(c: String) = base + "/api/mini?c=" + enc(c)
    fun urlFoto(c: String) = base + "/api/foto?l=1800&c=" + enc(c)

    fun pedido(ctx: Context, url: String): ImageRequest =
        ImageRequest.Builder(ctx).data(url).addHeader("Authorization", auth).build()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TelaFamilia() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("familia", Context.MODE_PRIVATE) }
    var host by remember { mutableStateOf(prefs.getString("host", null) ?: lerConfig(ctx).host) }
    var porta by remember { mutableStateOf(prefs.getString("porta", "8085") ?: "8085") }
    var usuario by remember { mutableStateOf(prefs.getString("usuario", "") ?: "") }
    var senha by remember { mutableStateOf(prefs.getString("senha", "") ?: "") }
    var cliente by remember { mutableStateOf<ClienteFamilia?>(null) }
    var admin by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var pilha by remember { mutableStateOf(listOf("")) }
    var lista by remember { mutableStateOf<ListaRemota?>(null) }
    var visualizando by remember { mutableStateOf<Int?>(null) }
    var recarregar by remember { mutableStateOf(0) }
    var secao by remember { mutableStateOf(0) } // 0 pastas, 1 etiquetas, 2 álbuns, 3 pessoas
    var aviso by remember { mutableStateOf("") }
    var nomesTodos by remember { mutableStateOf<List<String>>(emptyList()) }
    var alvoCol by remember { mutableStateOf<ColecaoRemota?>(null) }
    var apagarCol by remember { mutableStateOf<ColecaoRemota?>(null) }
    var sugs by remember { mutableStateOf<List<SugestaoRemota>?>(null) }
    var aberta by remember { mutableStateOf<String?>(null) }
    var colecoes by remember { mutableStateOf<List<ColecaoRemota>>(emptyList()) }
    var marcadas by remember { mutableStateOf(setOf<String>()) }
    var dialogo by remember { mutableStateOf("") } // "", "etiqueta", "album", "mover"
    var texto by remember { mutableStateOf("") }
    var pastasTodas by remember { mutableStateOf<List<String>>(emptyList()) }
    var apagar by remember { mutableStateOf<PastaRemota?>(null) }
    val tipoAtual = when (secao) { 1 -> "etiqueta"; 2 -> "album"; else -> "pessoa" }
    val botao = ButtonDefaults.buttonColors(containerColor = Oliva)

    fun entrar() {
        val novo = ClienteFamilia("http://${host.trim()}:${porta.trim()}", usuario.trim(), senha)
        status = "Entrando…"
        scope.launch {
            try {
                val eu = withContext(Dispatchers.IO) { JSONObject(novo.get("/api/eu")) }
                admin = eu.optBoolean("admin")
                prefs.edit()
                    .putString("host", host.trim()).putString("porta", porta.trim())
                    .putString("usuario", usuario.trim()).putString("senha", senha)
                    .apply()
                pilha = listOf("")
                secao = 0
                aberta = null
                marcadas = emptySet()
                lista = null
                cliente = novo
                status = ""
            } catch (ex: Exception) {
                status = "Não consegui entrar: " + (ex.message ?: ex.javaClass.simpleName)
            }
        }
    }

    LaunchedEffect(Unit) {
        if (usuario.isNotEmpty() && senha.isNotEmpty()) entrar()
    }

    LaunchedEffect(cliente, pilha, recarregar, secao, aberta) {
        val c = cliente
        val ab = aberta
        if (c != null) {
            try {
                if (secao == 0) {
                    lista = withContext(Dispatchers.IO) { c.lista(pilha.last()) }
                } else if (ab == null) {
                    colecoes = withContext(Dispatchers.IO) { c.colecoes(tipoAtual) }
                } else {
                    lista = withContext(Dispatchers.IO) { c.colecao(tipoAtual, ab) }
                }
                status = ""
            } catch (ex: Exception) {
                status = "Erro: " + (ex.message ?: ex.javaClass.simpleName)
            }
        }
    }

    fun voltar() {
        if (sugs != null) {
            sugs = null
        } else if (marcadas.isNotEmpty()) {
            marcadas = emptySet()
        } else if (secao == 0) {
            pilha = pilha.dropLast(1)
        } else {
            aberta = null
            lista = null
        }
    }

    BackHandler(
        enabled = cliente != null && (sugs != null || marcadas.isNotEmpty() || (secao == 0 && pilha.size > 1) || (secao != 0 && aberta != null))
    ) { voltar() }

    val c = cliente
    if (c == null) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp)
        ) {
            Text("Fotos da família (servidor da casa)", color = OliveEscuro)
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
            Spacer(Modifier.height(8.dp))
            Button(onClick = { entrar() }, colors = botao) { Text("Entrar") }
            if (status.isNotEmpty()) {
                Text(status, color = OliveEscuro, modifier = Modifier.padding(top = 12.dp))
            }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Olá, $usuario" + if (admin) " (administrador)" else "",
                    color = OliveEscuro, modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    prefs.edit().remove("senha").apply()
                    senha = ""
                    cliente = null
                    lista = null
                }) { Text("Sair") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                BotaoSecao("📁", secao == 0, Modifier.weight(1f)) { secao = 0; aberta = null; marcadas = emptySet(); sugs = null; aviso = ""; lista = null }
                BotaoSecao("🏷", secao == 1, Modifier.weight(1f)) { secao = 1; aberta = null; marcadas = emptySet(); sugs = null; aviso = ""; lista = null }
                BotaoSecao("🖼", secao == 2, Modifier.weight(1f)) { secao = 2; aberta = null; marcadas = emptySet(); sugs = null; aviso = ""; lista = null }
                BotaoSecao("👤", secao == 3, Modifier.weight(1f)) { secao = 3; aberta = null; marcadas = emptySet(); sugs = null; aviso = ""; lista = null }
            }
            if ((secao == 0 && pilha.size > 1) || (secao != 0 && aberta != null)) {
                TextButton(onClick = { voltar() }) { Text("⬆ Voltar" + if (secao != 0 && aberta != null) " (${aberta})" else "") }
            }
            fun abrirNome(tp: String) {
                texto = ""
                dialogo = tp
                nomesTodos = emptyList()
                scope.launch {
                    try {
                        nomesTodos = withContext(Dispatchers.IO) { c.colecoes(tp).map { it.nome } }
                    } catch (ex: Exception) {
                        status = "Erro: " + (ex.message ?: ex.javaClass.simpleName)
                    }
                }
            }
            if (admin && marcadas.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().background(OlivaClaro).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${marcadas.size} ✓", color = OliveEscuro, modifier = Modifier.padding(horizontal = 12.dp))
                    TextButton(onClick = { abrirNome("etiqueta") }) { Text("🏷 Etiqueta") }
                    TextButton(onClick = { abrirNome("album") }) { Text("🖼 Álbum") }
                    TextButton(onClick = { abrirNome("pessoa") }) { Text("👤 Pessoa") }
                    TextButton(onClick = {
                        texto = ""
                        dialogo = "mover"
                        scope.launch {
                            try {
                                pastasTodas = withContext(Dispatchers.IO) { c.pastas() }
                            } catch (ex: Exception) {
                                status = "Erro: " + (ex.message ?: ex.javaClass.simpleName)
                            }
                        }
                    }) { Text("📁 Mover") }
                    if (secao != 0 && aberta != null) {
                        TextButton(onClick = {
                            val ab = aberta
                            val fs = marcadas.toList()
                            if (ab != null) {
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { c.marcar(tipoAtual, ab, fs, true) }
                                        marcadas = emptySet()
                                        recarregar++
                                    } catch (ex: Exception) {
                                        status = "Erro: " + (ex.message ?: ex.javaClass.simpleName)
                                    }
                                }
                            }
                        }) { Text("➖ Tirar") }
                    }
                    TextButton(onClick = { marcadas = emptySet() }) { Text("✕") }
                }
            }
            if (status.isNotEmpty()) Text(status, color = OliveEscuro)
            if (aviso.isNotEmpty()) Text(aviso, color = OliveEscuro, modifier = Modifier.padding(horizontal = 8.dp))
            val abPessoa = aberta
            if (admin && secao == 3 && abPessoa != null) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = {
                        scope.launch {
                            try {
                                sugs = withContext(Dispatchers.IO) { c.sugestoes(abPessoa) }
                                if (sugs.isNullOrEmpty()) aviso = "Sem sugestões no momento."
                            } catch (ex: Exception) {
                                status = "Erro: " + (ex.message ?: ex.javaClass.simpleName)
                            }
                        }
                    }) { Text("🔍 Sugestões") }
                    TextButton(onClick = {
                        aviso = "Procurando nas fotos…"
                        scope.launch {
                            try {
                                aviso = withContext(Dispatchers.IO) { c.exemplo(abPessoa, emptyList()) }
                                recarregar++
                            } catch (ex: Exception) {
                                aviso = ""
                                status = "Erro: " + (ex.message ?: ex.javaClass.simpleName)
                            }
                        }
                    }) { Text("🔄 Procurar de novo") }
                }
            }
            val sg = sugs
            val l = lista
            if (sg != null) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(1),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(sg, key = { it.caminho + "#" + it.n }) { s ->
                        Row(
                            Modifier.fillMaxWidth().background(OlivaClaro).padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = c.pedido(ctx, c.urlRecorte(s.caminho, s.n)),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(96.dp)
                            )
                            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                Text("É ${abPessoa ?: ""}?", color = OliveEscuro)
                                Text("parecido: ${(s.sim * 100).toInt()}%", color = OliveEscuro, fontSize = 12.sp)
                            }
                            TextButton(onClick = {
                                val nomeP = abPessoa
                                sugs = sugs?.filter { it !== s }
                                if (nomeP != null) scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { c.responder(nomeP, s.caminho, s.n, true) }
                                    } catch (ex: Exception) {
                                        status = "Erro: " + (ex.message ?: ex.javaClass.simpleName)
                                    }
                                }
                            }) { Text("✅ Sim") }
                            TextButton(onClick = {
                                val nomeP = abPessoa
                                sugs = sugs?.filter { it !== s }
                                if (nomeP != null) scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { c.responder(nomeP, s.caminho, s.n, false) }
                                    } catch (ex: Exception) {
                                        status = "Erro: " + (ex.message ?: ex.javaClass.simpleName)
                                    }
                                }
                            }) { Text("❌ Não") }
                        }
                    }
                }
            } else if (secao != 0 && aberta == null) {
                if (colecoes.isEmpty()) {
                    Text(
                        if (admin && secao == 3) "Nenhuma pessoa ainda. Em Pastas, segure fotos em que ela aparece e toque em 👤 Pessoa."
                        else if (admin) "Nada aqui ainda. Segure uma foto em Pastas para selecionar e etiquetar."
                        else "Nada liberado ainda.",
                        color = OliveEscuro, modifier = Modifier.padding(8.dp)
                    )
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(1),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(colecoes, key = { it.nome }) { col ->
                        Row(
                            Modifier.fillMaxWidth().background(OlivaClaro),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                (when (secao) { 1 -> "🏷 "; 2 -> "🖼 "; else -> "👤 " }) + "${col.nome} (${col.total})",
                                color = OliveEscuro,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { lista = null; aberta = col.nome }
                                    .padding(12.dp)
                            )
                            if (admin) {
                                TextButton(onClick = {
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) { c.liberarColecao(tipoAtual, col.nome, !col.publico) }
                                            recarregar++
                                        } catch (ex: Exception) {
                                            status = "Erro: " + (ex.message ?: ex.javaClass.simpleName)
                                        }
                                    }
                                }) { Text(if (col.publico) "🌐" else "🔒") }
                                TextButton(onClick = {
                                    texto = col.nome
                                    nomesTodos = colecoes.map { it.nome }
                                    alvoCol = col
                                    dialogo = "renomear"
                                }) { Text("✏") }
                                TextButton(onClick = { apagarCol = col }) { Text("🗑") }
                            }
                        }
                    }
                }
            } else if (l != null) {
                if (l.pastas.isEmpty() && l.fotos.isEmpty()) {
                    Text(
                        if (secao == 0 && pilha.size == 1 && !admin) "Nenhuma pasta pública ainda." else "Vazio.",
                        color = OliveEscuro, modifier = Modifier.padding(8.dp)
                    )
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(l.pastas, key = { it.caminho }, span = { GridItemSpan(maxLineSpan) }) { pasta ->
                        Row(
                            Modifier.fillMaxWidth().background(OlivaClaro),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "📁 ${pasta.nome}",
                                color = OliveEscuro,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { pilha = pilha + pasta.caminho }
                                    .padding(12.dp)
                            )
                            if (admin) {
                                TextButton(onClick = {
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) { c.definirPublico(pasta.caminho, !pasta.publico) }
                                            recarregar++
                                        } catch (ex: Exception) {
                                            status = "Erro: " + (ex.message ?: ex.javaClass.simpleName)
                                        }
                                    }
                                }) { Text(if (pasta.publico) "🌐 Pública" else "🔒 Privada") }
                                TextButton(onClick = { apagar = pasta }) { Text("🗑") }
                            }
                        }
                    }
                    items(l.fotos, key = { it.caminho }) { foto ->
                        Box(
                            Modifier
                                .aspectRatio(1f)
                                .combinedClickable(
                                    onClick = {
                                        if (marcadas.isNotEmpty()) {
                                            marcadas = if (foto.caminho in marcadas) marcadas - foto.caminho else marcadas + foto.caminho
                                        } else {
                                            visualizando = l.fotos.indexOf(foto)
                                        }
                                    },
                                    onLongClick = {
                                        if (admin) {
                                            marcadas = if (foto.caminho in marcadas) marcadas - foto.caminho else marcadas + foto.caminho
                                        }
                                    }
                                )
                        ) {
                            AsyncImage(
                                model = c.pedido(ctx, c.urlMini(foto.caminho)),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            if (foto.caminho in marcadas) {
                                Box(
                                    Modifier.fillMaxSize().background(Color(0x99556B2F)),
                                    contentAlignment = Alignment.Center
                                ) { Text("✓", color = Color.White) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (dialogo.isNotEmpty() && c != null) {
        val titulo = when (dialogo) {
            "etiqueta" -> "Etiqueta para ${marcadas.size} foto(s)"
            "album" -> "Álbum para ${marcadas.size} foto(s)"
            "pessoa" -> "Nome da pessoa nestas ${marcadas.size} foto(s)"
            "renomear" -> "Novo nome"
            else -> "Mover ${marcadas.size} foto(s) para a pasta"
        }
        AlertDialog(
            onDismissRequest = { dialogo = "" },
            title = { Text(titulo) },
            text = {
                Column {
                    OutlinedTextField(
                        value = texto, onValueChange = { texto = it },
                        label = { Text(if (dialogo == "mover") "Pasta (toque numa da lista ou digite)" else "Nome") },
                        singleLine = true
                    )
                    run {
                        val opcoes = if (dialogo == "mover") pastasTodas else nomesTodos
                        val digitado = texto.trim()
                        if (digitado.isNotEmpty() && digitado !in opcoes) {
                            if (dialogo == "mover") {
                                Text("⚠ Vai criar a pasta nova \"$digitado\"", color = Color(0xFF8A5A00))
                            } else if (dialogo != "renomear") {
                                Text("⚠ Vai criar \"$digitado\" como novo", color = Color(0xFF8A5A00))
                            }
                        } else if (dialogo == "renomear" && digitado.isNotEmpty() && digitado != alvoCol?.nome) {
                            Text("⚠ Já existe: vai juntar as duas", color = Color(0xFF8A5A00))
                        }
                        Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                            opcoes
                                .filter { digitado.isEmpty() || it.contains(digitado, ignoreCase = true) }
                                .take(60)
                                .forEach { p ->
                                    TextButton(onClick = { texto = p }) { Text((if (dialogo == "mover") "📁 " else "• ") + p) }
                                }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val nome = texto.trim()
                    val tp = dialogo
                    val fs = marcadas.toList()
                    val antigo = alvoCol?.nome ?: ""
                    if (nome.isNotEmpty()) {
                        dialogo = ""
                        scope.launch {
                            try {
                                if (tp == "pessoa") aviso = "Procurando nas fotos…"
                                val res = withContext(Dispatchers.IO) {
                                    if (tp == "mover") {
                                        c.mover(fs, nome); ""
                                    } else if (tp == "renomear") {
                                        c.renomear(tipoAtual, antigo, nome); ""
                                    } else if (tp == "pessoa") {
                                        c.exemplo(nome, fs)
                                    } else {
                                        c.marcar(tp, nome, fs); ""
                                    }
                                }
                                if (tp == "pessoa") aviso = res
                                marcadas = emptySet()
                                recarregar++
                            } catch (ex: Exception) {
                                status = "Erro: " + (ex.message ?: ex.javaClass.simpleName)
                            }
                        }
                    }
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { dialogo = "" }) { Text("Cancelar") } }
        )
    }

    val ap = apagar
    if (ap != null && c != null) {
        AlertDialog(
            onDismissRequest = { apagar = null },
            title = { Text("Apagar a pasta?") },
            text = { Text("\"${ap.nome}\" só será apagada se estiver vazia. Pastas com fotos são mantidas.") },
            confirmButton = {
                TextButton(onClick = {
                    apagar = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { c.apagarPasta(ap.caminho) }
                            recarregar++
                        } catch (ex: Exception) {
                            status = "Erro: " + (ex.message ?: ex.javaClass.simpleName)
                        }
                    }
                }) { Text("Apagar") }
            },
            dismissButton = { TextButton(onClick = { apagar = null }) { Text("Cancelar") } }
        )
    }

    val ac = apagarCol
    if (ac != null && c != null) {
        AlertDialog(
            onDismissRequest = { apagarCol = null },
            title = { Text("Apagar \"${ac.nome}\"?") },
            text = { Text("Só o agrupamento é apagado. As fotos continuam nas pastas.") },
            confirmButton = {
                TextButton(onClick = {
                    apagarCol = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { c.apagarColecao(tipoAtual, ac.nome) }
                            recarregar++
                        } catch (ex: Exception) {
                            status = "Erro: " + (ex.message ?: ex.javaClass.simpleName)
                        }
                    }
                }) { Text("Apagar") }
            },
            dismissButton = { TextButton(onClick = { apagarCol = null }) { Text("Cancelar") } }
        )
    }

    val pos = visualizando
    val atualLista = lista
    if (pos != null && c != null && atualLista != null && atualLista.fotos.isNotEmpty()) {
        Dialog(
            onDismissRequest = { visualizando = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                val fotos = atualLista.fotos
                val pager = rememberPagerState(
                    initialPage = pos.coerceIn(0, fotos.size - 1),
                    pageCount = { fotos.size }
                )
                var zoomando by remember { mutableStateOf(false) }
                var info by remember { mutableStateOf(true) }
                Box(Modifier.fillMaxSize()) {
                    HorizontalPager(
                        state = pager,
                        userScrollEnabled = !zoomando,
                        modifier = Modifier.fillMaxSize()
                    ) { p ->
                        FotoZoom(
                            c.pedido(ctx, c.urlFoto(fotos[p].caminho)),
                            { zoomando = it },
                            { info = !info }
                        )
                    }
                    if (info) {
                        Row(
                            Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .background(Color(0xAA000000))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${pager.currentPage + 1}/${fotos.size}  ${fotos[pager.currentPage.coerceIn(0, fotos.size - 1)].nome}",
                                color = Color.White, maxLines = 1, modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { visualizando = null }) { Text("Fechar", color = Color.White) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BotaoSecao(texto: String, ativo: Boolean, modifier: Modifier = Modifier, aoTocar: () -> Unit) {
    if (ativo) {
        Button(
            onClick = aoTocar, modifier = modifier, contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Oliva)
        ) { Text(texto, fontSize = 20.sp) }
    } else {
        TextButton(onClick = aoTocar, modifier = modifier, contentPadding = PaddingValues(0.dp)) {
            Text(texto, fontSize = 20.sp)
        }
    }
}
