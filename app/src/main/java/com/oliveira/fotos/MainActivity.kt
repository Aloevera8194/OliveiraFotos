package com.oliveira.fotos

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val OliveEscuro = Color(0xFF3B4224)
val Oliva = Color(0xFF708238)
val OlivaClaro = Color(0xFFD9E0B5)
val Fundo = Color(0xFFF4F5E8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Oliva,
                    onPrimary = Color.White,
                    primaryContainer = OlivaClaro,
                    background = Fundo,
                    surface = Fundo
                )
            ) { App() }
        }
    }
}

data class Entrada(val id: String, val nome: String, val mime: String) {
    val ehPasta: Boolean get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
}

fun docUri(tree: Uri, id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

fun listar(ctx: Context, tree: Uri, pastaId: String): List<Entrada> {
    val filhos = DocumentsContract.buildChildDocumentsUriUsingTree(tree, pastaId)
    val saida = mutableListOf<Entrada>()
    ctx.contentResolver.query(
        filhos,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        ),
        null, null, null
    )?.use { c ->
        while (c.moveToNext()) {
            val e = Entrada(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "")
            if (e.ehPasta || e.mime.startsWith("image/")) saida.add(e)
        }
    }
    return saida.sortedWith(compareBy({ !it.ehPasta }, { it.nome.lowercase() }))
}

fun nomeNovo(original: String, base: String, numero: Int): String {
    val ext = original.substringAfterLast('.', "")
    val n = numero.toString().padStart(3, '0')
    return if (ext.isEmpty()) "${base}_$n" else "${base}_$n.$ext"
}

fun renomearLote(ctx: Context, tree: Uri, itens: List<Entrada>, base: String, inicio: Int): Int {
    var ok = 0
    itens.forEachIndexed { i, e ->
        try {
            val novo = nomeNovo(e.nome, base, inicio + i)
            if (DocumentsContract.renameDocument(ctx.contentResolver, docUri(tree, e.id), novo) != null) ok++
        } catch (_: Exception) {
        }
    }
    return ok
}

fun moverLote(ctx: Context, tree: Uri, itens: List<Entrada>, origemId: String, destinoId: String): Int {
    var ok = 0
    val origem = docUri(tree, origemId)
    val destino = docUri(tree, destinoId)
    itens.forEach { e ->
        try {
            if (DocumentsContract.moveDocument(ctx.contentResolver, docUri(tree, e.id), origem, destino) != null) ok++
        } catch (_: Exception) {
        }
    }
    return ok
}

fun criarPasta(ctx: Context, tree: Uri, paiId: String, nome: String): String? {
    return try {
        val uri = DocumentsContract.createDocument(
            ctx.contentResolver, docUri(tree, paiId),
            DocumentsContract.Document.MIME_TYPE_DIR, nome
        )
        if (uri != null) DocumentsContract.getDocumentId(uri) else null
    } catch (_: Exception) {
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tree by remember { mutableStateOf<Uri?>(null) }
    var pilha by remember { mutableStateOf<List<String>>(emptyList()) }
    var itens by remember { mutableStateOf<List<Entrada>>(emptyList()) }
    var sel by remember { mutableStateOf<Set<String>>(emptySet()) }
    var dialogo by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var versao by remember { mutableStateOf(0) }

    fun abrirRaiz(uri: Uri) {
        tree = uri
        pilha = listOf(DocumentsContract.getTreeDocumentId(uri))
    }

    LaunchedEffect(Unit) {
        val s = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("tree", null)
        if (s != null) {
            val u = Uri.parse(s)
            if (ctx.contentResolver.persistedUriPermissions.any { it.uri == u }) abrirRaiz(u)
        }
    }

    LaunchedEffect(tree, pilha, versao) {
        val t = tree
        val p = pilha.lastOrNull()
        if (t != null && p != null) {
            itens = withContext(Dispatchers.IO) { listar(ctx, t, p) }
        }
        sel = emptySet()
    }

    val escolher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
                .edit().putString("tree", uri.toString()).apply()
            msg = null
            abrirRaiz(uri)
        }
    }

    BackHandler(enabled = sel.isNotEmpty() || pilha.size > 1) {
        if (sel.isNotEmpty()) sel = emptySet() else pilha = pilha.dropLast(1)
    }

    fun alternar(id: String) {
        sel = if (id in sel) sel - id else sel + id
    }

    val t = tree
    val pastas = itens.filter { it.ehPasta }
    val fotos = itens.filter { !it.ehPasta }
    val botao = ButtonDefaults.buttonColors(containerColor = Oliva)

    Scaffold(
        containerColor = Fundo,
        topBar = {
            TopAppBar(
                title = {
                    Text(if (sel.isEmpty()) "Oliveira Fotos" else "${sel.size} selecionadas")
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = OliveEscuro,
                    titleContentColor = Color.White
                )
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(12.dp)) {
            if (sel.isEmpty()) {
                Button(
                    onClick = { escolher.launch(null) },
                    colors = botao,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (t == null) "Escolher pasta de fotos" else "Trocar pasta") }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = { dialogo = "renomear" }, colors = botao) { Text("Renomear") }
                    Button(onClick = { dialogo = "mover" }, colors = botao) { Text("Mover") }
                    TextButton(onClick = { sel = emptySet() }) { Text("Cancelar") }
                }
            }

            msg?.let { Text(it, color = OliveEscuro, modifier = Modifier.padding(top = 8.dp)) }

            if (t == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nenhuma pasta escolhida.", color = OliveEscuro)
                }
            } else {
                Text(
                    "${fotos.size} fotos  •  segure uma foto para selecionar",
                    color = OliveEscuro,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(pastas, key = { it.id }, span = { GridItemSpan(maxLineSpan) }) { pasta ->
                        Text(
                            "📁 ${pasta.nome}",
                            color = OliveEscuro,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(OlivaClaro)
                                .clickable { pilha = pilha + pasta.id }
                                .padding(12.dp)
                        )
                    }
                    items(fotos, key = { it.id }) { foto ->
                        Box(
                            Modifier
                                .aspectRatio(1f)
                                .combinedClickable(
                                    onClick = { if (sel.isNotEmpty()) alternar(foto.id) },
                                    onLongClick = { alternar(foto.id) }
                                )
                        ) {
                            AsyncImage(
                                model = docUri(t, foto.id),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            if (foto.id in sel) {
                                Box(
                                    Modifier.fillMaxSize().background(Color(0x99708238)),
                                    contentAlignment = Alignment.Center
                                ) { Text("✓", color = Color.White) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (dialogo == "renomear") {
        var base by remember { mutableStateOf("foto") }
        var inicio by remember { mutableStateOf("1") }
        val escolhidas = itens.filter { it.id in sel }
        val ini = inicio.toIntOrNull() ?: 1
        val exemplo = escolhidas.firstOrNull()?.let { nomeNovo(it.nome, base, ini) } ?: ""
        AlertDialog(
            onDismissRequest = { dialogo = "" },
            title = { Text("Renomear ${escolhidas.size} fotos") },
            text = {
                Column {
                    OutlinedTextField(
                        value = base,
                        onValueChange = { base = it },
                        label = { Text("Nome base") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inicio,
                        onValueChange = { inicio = it.filter(Char::isDigit) },
                        label = { Text("Começar em") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Ex.: $exemplo")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val arvore = tree
                    if (arvore != null && base.isNotBlank()) {
                        scope.launch {
                            val n = withContext(Dispatchers.IO) {
                                renomearLote(ctx, arvore, escolhidas, base.trim(), ini)
                            }
                            msg = "$n de ${escolhidas.size} renomeadas"
                            dialogo = ""
                            versao++
                        }
                    }
                }) { Text("Renomear") }
            },
            dismissButton = { TextButton(onClick = { dialogo = "" }) { Text("Cancelar") } }
        )
    }

    if (dialogo == "mover") {
        var nova by remember { mutableStateOf("") }
        val escolhidas = itens.filter { it.id in sel }
        val executar: (String) -> Unit = { destinoId ->
            val arvore = tree
            val origem = pilha.lastOrNull()
            if (arvore != null && origem != null) {
                scope.launch {
                    val n = withContext(Dispatchers.IO) {
                        moverLote(ctx, arvore, escolhidas, origem, destinoId)
                    }
                    msg = "$n de ${escolhidas.size} movidas"
                    dialogo = ""
                    versao++
                }
            }
        }
        AlertDialog(
            onDismissRequest = { dialogo = "" },
            title = { Text("Mover ${escolhidas.size} fotos para…") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (pilha.size > 1) {
                        TextButton(onClick = { executar(pilha[pilha.size - 2]) }) {
                            Text("⬆ Pasta acima")
                        }
                    }
                    pastas.forEach { p ->
                        TextButton(onClick = { executar(p.id) }) { Text("📁 ${p.nome}") }
                    }
                    OutlinedTextField(
                        value = nova,
                        onValueChange = { nova = it },
                        label = { Text("Nova pasta") },
                        singleLine = true
                    )
                    TextButton(onClick = {
                        val arvore = tree
                        val pai = pilha.lastOrNull()
                        if (arvore != null && pai != null && nova.isNotBlank()) {
                            scope.launch {
                                val id = withContext(Dispatchers.IO) {
                                    criarPasta(ctx, arvore, pai, nova.trim())
                                }
                                if (id != null) {
                                    executar(id)
                                } else {
                                    msg = "Não consegui criar a pasta"
                                    dialogo = ""
                                }
                            }
                        }
                    }) { Text("Criar e mover") }
                }
            },
            confirmButton = { TextButton(onClick = { dialogo = "" }) { Text("Cancelar") } }
        )
    }
}
