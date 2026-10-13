package com.oliveira.fotos

import android.content.Context
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class PastaRemota(val nome: String, val caminho: String, val publico: Boolean)
class FotoRemota(val nome: String, val caminho: String)
class ListaRemota(val pastas: List<PastaRemota>, val fotos: List<FotoRemota>)

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
            if (c.responseCode != 200) throw IllegalStateException("erro ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    fun get(api: String): String = ler(abrir(api))

    fun post(api: String, corpo: String): String {
        val c = abrir(api)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(corpo.toByteArray()) }
        return ler(c)
    }

    fun lista(caminho: String): ListaRemota {
        val j = JSONObject(get("/api/lista?c=" + enc(caminho)))
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

    LaunchedEffect(cliente, pilha, recarregar) {
        val c = cliente
        if (c != null) {
            try {
                lista = withContext(Dispatchers.IO) { c.lista(pilha.last()) }
                status = ""
            } catch (ex: Exception) {
                status = "Erro: " + (ex.message ?: ex.javaClass.simpleName)
            }
        }
    }

    BackHandler(enabled = cliente != null && pilha.size > 1) { pilha = pilha.dropLast(1) }

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
            if (pilha.size > 1) {
                TextButton(onClick = { pilha = pilha.dropLast(1) }) { Text("⬆ Voltar") }
            }
            if (status.isNotEmpty()) Text(status, color = OliveEscuro)
            val l = lista
            if (l != null) {
                if (l.pastas.isEmpty() && l.fotos.isEmpty()) {
                    Text(
                        if (pilha.size == 1 && !admin) "Nenhuma pasta pública ainda." else "Pasta vazia.",
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
                            }
                        }
                    }
                    items(l.fotos, key = { it.caminho }) { foto ->
                        AsyncImage(
                            model = c.pedido(ctx, c.urlMini(foto.caminho)),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clickable { visualizando = l.fotos.indexOf(foto) }
                        )
                    }
                }
            }
        }
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
