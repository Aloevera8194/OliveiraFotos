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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import java.util.concurrent.atomic.AtomicBoolean
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

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
            if (e.nome.startsWith(".")) continue
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

fun renomearLote(
    ctx: Context, tree: Uri, itens: List<Entrada>, base: String, inicio: Int, indice: Indice
): Int {
    var ok = 0
    itens.forEachIndexed { i, e ->
        try {
            val novo = nomeNovo(e.nome, base, inicio + i)
            val uri = DocumentsContract.renameDocument(ctx.contentResolver, docUri(tree, e.id), novo)
            if (uri != null) {
                ok++
                indice.trocarId(e.id, DocumentsContract.getDocumentId(uri))
            }
        } catch (_: Exception) {
        }
    }
    return ok
}

fun moverLote(
    ctx: Context, tree: Uri, itens: List<Entrada>, origemId: String, destinoId: String, indice: Indice
): Int {
    var ok = 0
    val origem = docUri(tree, origemId)
    val destino = docUri(tree, destinoId)
    itens.forEach { e ->
        try {
            val uri = DocumentsContract.moveDocument(ctx.contentResolver, docUri(tree, e.id), origem, destino)
            if (uri != null) {
                ok++
                indice.trocarId(e.id, DocumentsContract.getDocumentId(uri))
            }
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
    val indice = remember { Indice(ctx) }

    var tree by remember { mutableStateOf<Uri?>(null) }
    var pilha by remember { mutableStateOf<List<String>>(emptyList()) }
    var itens by remember { mutableStateOf<List<Entrada>>(emptyList()) }
    var sel by remember { mutableStateOf<Set<String>>(emptySet()) }
    var dialogo by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var versao by remember { mutableStateOf(0) }
    var aba by remember { mutableStateOf(0) }
    var colecao by remember { mutableStateOf<String?>(null) }
    var nomesColecao by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var idsColecao by remember { mutableStateOf<List<String>>(emptyList()) }
    var sugestoes by remember { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    var aceitas by remember { mutableStateOf<Set<String>>(emptySet()) }
    var analisando by remember { mutableStateOf(false) }
    var progresso by remember { mutableStateOf("") }
    val pararAnalise = remember { AtomicBoolean(false) }
    var rostoFase by remember { mutableStateOf("") }
    var rostoProgresso by remember { mutableStateOf("") }
    var grupos by remember { mutableStateOf<List<List<RostoRow>>>(emptyList()) }
    var grupoAtual by remember { mutableStateOf(0) }
    var nomePessoa by remember { mutableStateOf("") }
    val pararRostos = remember { AtomicBoolean(false) }
    var visualizar by remember { mutableStateOf<Int?>(null) }
    val telaView = LocalView.current
    DisposableEffect(analisando, rostoFase) {
        telaView.keepScreenOn = analisando || rostoFase == "analisando"
        onDispose { telaView.keepScreenOn = false }
    }

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

    LaunchedEffect(tree, pilha, versao, aba, colecao) {
        val t = tree
        val p = pilha.lastOrNull()
        val col = colecao
        val abaAtual = aba
        if (t != null) {
            val r = withContext(Dispatchers.IO) {
                val l1: List<Entrada> =
                    if (abaAtual == 0 && p != null) listar(ctx, t, p) else emptyList()
                val l2: List<Pair<String, Int>> =
                    if (abaAtual in 1..2 && col == null) {
                        if (abaAtual == 1) indice.etiquetas() else indice.albuns()
                    } else emptyList()
                val l3: List<String> =
                    if (abaAtual in 1..2 && col != null) {
                        if (abaAtual == 1) indice.fotosDaEtiqueta(col) else indice.fotosDoAlbum(col)
                    } else emptyList()
                Triple(l1, l2, l3)
            }
            itens = r.first
            nomesColecao = r.second
            idsColecao = r.third
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

    BackHandler(enabled = sel.isNotEmpty() || colecao != null || pilha.size > 1) {
        when {
            sel.isNotEmpty() -> sel = emptySet()
            colecao != null -> colecao = null
            else -> pilha = pilha.dropLast(1)
        }
    }

    fun alternar(id: String) {
        sel = if (id in sel) sel - id else sel + id
    }

    val t = tree
    val pastas: List<Entrada> = if (aba == 0) itens.filter { it.ehPasta } else emptyList()
    val fotos: List<Entrada> =
        if (aba == 0) itens.filter { !it.ehPasta }
        else idsColecao.map { Entrada(it, it.substringAfterLast('/').substringAfterLast(':'), "image/jpeg") }
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { escolher.launch(null) },
                        colors = botao,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (t == null) "Escolher pasta" else "Trocar pasta") }
                    if (t != null && aba != 3 && fotos.isNotEmpty()) {
                        Button(
                            onClick = { sel = fotos.map { it.id }.toSet() },
                            colors = botao,
                            modifier = Modifier.weight(1f)
                        ) { Text("Selecionar tudo") }
                    }
                }
            } else {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = { dialogo = "renomear" }, colors = botao) { Text("Renomear") }
                    if (aba == 0) {
                        Button(onClick = { dialogo = "mover" }, colors = botao) { Text("Mover") }
                    }
                    Button(onClick = { dialogo = "etiquetar" }, colors = botao) { Text("Etiquetar") }
                    Button(onClick = { dialogo = "album" }, colors = botao) { Text("Álbum") }
                    Button(
                        onClick = {
                            val arvore = t
                            val escolhidas = fotos.filter { it.id in sel }
                            if (arvore != null && escolhidas.isNotEmpty()) {
                                dialogo = "sugerir"
                                analisando = true
                                sugestoes = emptyMap()
                                aceitas = emptySet()
                                pararAnalise.set(false)
                                progresso = "0 de ${escolhidas.size}"
                                scope.launch {
                                    val r = withContext(Dispatchers.Default) {
                                        analisar(
                                            ctx, arvore, escolhidas,
                                            { pararAnalise.get() },
                                            { n, total -> progresso = "$n de $total" }
                                        )
                                    }
                                    sugestoes = r
                                    aceitas = r.values.flatten().toSet()
                                    analisando = false
                                }
                            }
                        },
                        colors = botao
                    ) { Text("Sugerir assunto") }
                    Button(
                        onClick = {
                            val arvore = t
                            val escolhidas = fotos.filter { it.id in sel }
                            if (arvore != null && escolhidas.isNotEmpty()) {
                                dialogo = "pessoas"
                                rostoFase = "analisando"
                                pararRostos.set(false)
                                rostoProgresso = "Preparando…"
                                scope.launch {
                                    val erro = withContext(Dispatchers.Default) {
                                        try {
                                            analisarRostos(
                                                ctx, arvore, escolhidas, indice,
                                                { pararRostos.get() },
                                                { n, total, rostos ->
                                                    rostoProgresso = "$n de $total fotos • $rostos rostos"
                                                }
                                            )
                                            ""
                                        } catch (ex: Exception) {
                                            "Erro: " + (ex.message ?: ex.javaClass.simpleName)
                                        }
                                    }
                                    if (erro.isNotEmpty()) {
                                        msg = erro
                                        dialogo = ""
                                        rostoFase = ""
                                    } else {
                                        val sem = withContext(Dispatchers.IO) { indice.rostosSemPessoa() }
                                        val encontrados = withContext(Dispatchers.Default) { agrupar(sem) }
                                        grupos = encontrados
                                        grupoAtual = 0
                                        nomePessoa = ""
                                        if (encontrados.isEmpty()) {
                                            msg = "Rostos analisados. Nenhum grupo novo para nomear."
                                            dialogo = ""
                                            rostoFase = ""
                                            versao++
                                        } else {
                                            rostoFase = "nomear"
                                        }
                                    }
                                }
                            }
                        },
                        colors = botao
                    ) { Text("Reconhecer rostos") }
                    val col = colecao
                    if (aba != 0 && col != null) {
                        Button(
                            onClick = {
                                val ids = fotos.filter { it.id in sel }.map { it.id }
                                val abaAtual = aba
                                scope.launch {
                                    withContext(Dispatchers.IO) { indice.remover(abaAtual, col, ids) }
                                    msg = "${ids.size} fotos tiradas de $col"
                                    versao++
                                }
                            },
                            colors = botao
                        ) { Text("Tirar daqui") }
                    }
                    TextButton(onClick = { sel = emptySet() }) { Text("Cancelar") }
                }
            }

            TabRow(
                selectedTabIndex = aba,
                containerColor = Fundo,
                contentColor = OliveEscuro,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                listOf("Pastas", "Etiquetas", "Álbuns", "Backup").forEachIndexed { i, nome ->
                    Tab(
                        selected = aba == i,
                        onClick = {
                            aba = i
                            colecao = null
                            sel = emptySet()
                            msg = null
                        },
                        text = { Text(nome) }
                    )
                }
            }

            msg?.let { Text(it, color = OliveEscuro, modifier = Modifier.padding(top = 8.dp)) }

            if (aba == 3) {
                TelaBackup(t, pilha.firstOrNull(), indice)
            } else if (t == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nenhuma pasta escolhida.", color = OliveEscuro)
                }
            } else if (aba != 0 && colecao == null) {
                if (nomesColecao.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "Nada aqui ainda. Na aba Pastas, segure uma foto, selecione e use Etiquetar ou Álbum.",
                            color = OliveEscuro
                        )
                    }
                } else {
                    LazyColumn(Modifier.padding(top = 8.dp)) {
                        items(nomesColecao, key = { it.first }) { (nome, qtd) ->
                            Text(
                                (if (aba == 1) "🏷 " else "🖼 ") + "$nome  ($qtd)",
                                color = OliveEscuro,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .background(OlivaClaro)
                                    .clickable { colecao = nome }
                                    .padding(14.dp)
                            )
                        }
                    }
                }
            } else {
                Text(
                    if (aba == 0) "${fotos.size} fotos  •  segure uma foto para selecionar"
                    else "${colecao}  •  ${fotos.size} fotos",
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
                                    onClick = { if (sel.isNotEmpty()) alternar(foto.id) else visualizar = fotos.indexOf(foto) },
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
        val escolhidas = fotos.filter { it.id in sel }
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
                                renomearLote(ctx, arvore, escolhidas, base.trim(), ini, indice)
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
        val escolhidas = fotos.filter { it.id in sel }
        val executar: (String) -> Unit = { destinoId ->
            val arvore = tree
            val origem = pilha.lastOrNull()
            if (arvore != null && origem != null) {
                scope.launch {
                    val n = withContext(Dispatchers.IO) {
                        moverLote(ctx, arvore, escolhidas, origem, destinoId, indice)
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

    if (dialogo == "etiquetar") {
        var texto by remember { mutableStateOf("") }
        val existentes = remember { indice.etiquetas().map { it.first } }
        val escolhidas = fotos.filter { it.id in sel }
        AlertDialog(
            onDismissRequest = { dialogo = "" },
            title = { Text("Etiquetar ${escolhidas.size} fotos") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = texto,
                        onValueChange = { texto = it },
                        label = { Text("Etiquetas (separe por vírgula)") }
                    )
                    existentes.forEach { nome ->
                        TextButton(onClick = {
                            texto = if (texto.isBlank()) nome else "$texto, $nome"
                        }) { Text("🏷 $nome") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val nomes = texto.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                    val ids = escolhidas.map { it.id }
                    if (nomes.isNotEmpty()) {
                        scope.launch {
                            withContext(Dispatchers.IO) { indice.etiquetar(ids, nomes) }
                            msg = "${ids.size} fotos etiquetadas: ${nomes.joinToString(", ")}"
                            dialogo = ""
                            versao++
                        }
                    }
                }) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { dialogo = "" }) { Text("Cancelar") } }
        )
    }

    val posicao = visualizar
    if (posicao != null && t != null && fotos.isNotEmpty()) {
        Dialog(
            onDismissRequest = { visualizar = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                val pager = rememberPagerState(
                    initialPage = posicao.coerceIn(0, fotos.size - 1),
                    pageCount = { fotos.size }
                )
                var zoomando by remember { mutableStateOf(false) }
                var mostrarInfo by remember { mutableStateOf(true) }
                val atual = fotos[pager.currentPage.coerceIn(0, fotos.size - 1)]
                val etiquetasDela = remember(atual.id, versao) { indice.etiquetasDaFoto(atual.id) }
                Box(Modifier.fillMaxSize()) {
                    HorizontalPager(
                        state = pager,
                        userScrollEnabled = !zoomando,
                        modifier = Modifier.fillMaxSize()
                    ) { pagina ->
                        FotoZoom(
                            docUri(t, fotos[pagina].id),
                            { zoomando = it },
                            { mostrarInfo = !mostrarInfo }
                        )
                    }
                    if (mostrarInfo) {
                        Row(
                            Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .background(Color(0xAA000000))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${pager.currentPage + 1}/${fotos.size}  ${atual.nome}",
                                color = Color.White,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { visualizar = null }) { Text("Fechar", color = Color.White) }
                        }
                        Column(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .background(Color(0xAA000000))
                                .padding(12.dp)
                        ) {
                            Text(
                                if (etiquetasDela.isEmpty()) "Sem etiquetas" else etiquetasDela.joinToString("  •  "),
                                color = Color.White
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                Button(
                                    colors = botao,
                                    onClick = {
                                        visualizar = null
                                        sel = setOf(atual.id)
                                        dialogo = "etiquetar"
                                    }
                                ) { Text("Etiquetar") }
                                Button(
                                    colors = botao,
                                    onClick = { compartilhar(ctx, docUri(t, atual.id)) }
                                ) { Text("Compartilhar") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (dialogo == "pessoas") {
        val grupo = grupos.getOrNull(grupoAtual)
        val nomesConhecidos = remember(versao, grupoAtual, rostoFase) { indice.pessoas().map { it.first } }
        AlertDialog(
            onDismissRequest = {
                if (rostoFase != "analisando") {
                    dialogo = ""
                    rostoFase = ""
                    versao++
                }
            },
            title = {
                Text(
                    if (rostoFase == "analisando") "Reconhecendo rostos…"
                    else "Quem é? (${grupoAtual + 1} de ${grupos.size})"
                )
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (rostoFase == "analisando") {
                        Text(rostoProgresso)
                    } else if (grupo != null) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            grupo.take(8).forEach { r ->
                                val bmp = remember(r.foto, r.n) {
                                    r.mini?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                                }
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier.size(72.dp)
                                    )
                                }
                            }
                        }
                        Text("${grupo.size} rostos parecidos", modifier = Modifier.padding(top = 8.dp))
                        OutlinedTextField(
                            value = nomePessoa,
                            onValueChange = { nomePessoa = it },
                            label = { Text("Nome da pessoa") },
                            singleLine = true
                        )
                        nomesConhecidos.forEach { nome ->
                            TextButton(onClick = { nomePessoa = nome }) { Text("👤 $nome") }
                        }
                    }
                }
            },
            confirmButton = {
                if (rostoFase == "analisando") {
                    TextButton(onClick = { pararRostos.set(true) }) { Text("Parar") }
                } else if (grupo != null) {
                    TextButton(onClick = {
                        val nome = nomePessoa.trim()
                        if (nome.isNotEmpty()) {
                            val chaves = grupo.map { it.foto to it.n }
                            scope.launch {
                                withContext(Dispatchers.IO) { indice.nomearRostos(chaves, nome) }
                                nomePessoa = ""
                                if (grupoAtual + 1 < grupos.size) {
                                    grupoAtual += 1
                                } else {
                                    dialogo = ""
                                    rostoFase = ""
                                    msg = "Pessoas salvas. Veja na aba Etiquetas."
                                    versao++
                                }
                            }
                        }
                    }) { Text("Salvar") }
                }
            },
            dismissButton = {
                if (rostoFase == "nomear") {
                    Row {
                        TextButton(onClick = {
                            nomePessoa = ""
                            if (grupoAtual + 1 < grupos.size) {
                                grupoAtual += 1
                            } else {
                                dialogo = ""
                                rostoFase = ""
                                versao++
                            }
                        }) { Text("Pular") }
                        TextButton(onClick = {
                            dialogo = ""
                            rostoFase = ""
                            versao++
                        }) { Text("Encerrar") }
                    }
                }
            }
        )
    }

    if (dialogo == "sugerir") {
        val contagem = sugestoes.values.flatten()
            .groupingBy { it }.eachCount().toList().sortedByDescending { it.second }
        AlertDialog(
            onDismissRequest = { if (!analisando) dialogo = "" },
            title = { Text(if (analisando) "Analisando…" else "Sugestões de assunto") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (analisando) {
                        Text(progresso)
                    } else if (contagem.isEmpty()) {
                        Text("Nenhuma sugestão para essas fotos.")
                    } else {
                        Text("Desmarque o que não quiser aplicar:")
                        contagem.forEach { (nome, qtd) ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        aceitas = if (nome in aceitas) aceitas - nome else aceitas + nome
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = nome in aceitas, onCheckedChange = null)
                                Text("  $nome ($qtd)")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (analisando) {
                    TextButton(onClick = { pararAnalise.set(true) }) { Text("Parar") }
                } else {
                    TextButton(onClick = {
                        val porNome = mutableMapOf<String, MutableList<String>>()
                        for ((id, tags) in sugestoes) {
                            for (tag in tags) {
                                if (tag in aceitas) porNome.getOrPut(tag) { mutableListOf() }.add(id)
                            }
                        }
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                for ((nome, ids) in porNome) indice.etiquetar(ids, listOf(nome))
                            }
                            msg = "Etiquetas aplicadas: ${porNome.keys.joinToString(", ")}"
                            dialogo = ""
                            versao++
                        }
                    }) { Text("Aplicar") }
                }
            },
            dismissButton = {
                if (!analisando) {
                    TextButton(onClick = { dialogo = "" }) { Text("Cancelar") }
                }
            }
        )
    }

    if (dialogo == "album") {
        var novo by remember { mutableStateOf("") }
        val existentes = remember { indice.albuns().map { it.first } }
        val escolhidas = fotos.filter { it.id in sel }
        val adicionar: (String) -> Unit = { nome ->
            val ids = escolhidas.map { it.id }
            scope.launch {
                withContext(Dispatchers.IO) { indice.adicionarAlbum(ids, nome) }
                msg = "${ids.size} fotos no álbum $nome"
                dialogo = ""
                versao++
            }
        }
        AlertDialog(
            onDismissRequest = { dialogo = "" },
            title = { Text("Álbum para ${escolhidas.size} fotos") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    existentes.forEach { nome ->
                        TextButton(onClick = { adicionar(nome) }) { Text("🖼 $nome") }
                    }
                    OutlinedTextField(
                        value = novo,
                        onValueChange = { novo = it },
                        label = { Text("Novo álbum") },
                        singleLine = true
                    )
                    TextButton(onClick = {
                        if (novo.isNotBlank()) adicionar(novo.trim())
                    }) { Text("Criar e adicionar") }
                }
            },
            confirmButton = { TextButton(onClick = { dialogo = "" }) { Text("Cancelar") } }
        )
    }
}
