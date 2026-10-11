package com.oliveira.fotos

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val context = LocalContext.current
    var fotos by remember { mutableStateOf<List<Uri>>(emptyList()) }

    val escolher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            fotos = listarImagens(context, uri)
        }
    }

    Scaffold(
        containerColor = Fundo,
        topBar = {
            TopAppBar(
                title = { Text("Oliveira Fotos") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = OliveEscuro,
                    titleContentColor = Color.White
                )
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(12.dp)) {
            Button(
                onClick = { escolher.launch(null) },
                colors = ButtonDefaults.buttonColors(containerColor = Oliva),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Escolher pasta de fotos") }

            if (fotos.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nenhuma foto ainda. Escolha uma pasta.", color = OliveEscuro)
                }
            } else {
                Text(
                    "${fotos.size} fotos",
                    color = OliveEscuro,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(fotos) { foto ->
                        AsyncImage(
                            model = foto,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.aspectRatio(1f)
                        )
                    }
                }
            }
        }
    }
}

fun listarImagens(context: Context, tree: Uri): List<Uri> {
    val pastaId = DocumentsContract.getTreeDocumentId(tree)
    val filhos = DocumentsContract.buildChildDocumentsUriUsingTree(tree, pastaId)
    val resultado = mutableListOf<Uri>()
    context.contentResolver.query(
        filhos,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        ),
        null, null, null
    )?.use { c ->
        while (c.moveToNext()) {
            val id = c.getString(0)
            val mime = c.getString(1) ?: ""
            if (mime.startsWith("image/")) {
                resultado.add(DocumentsContract.buildDocumentUriUsingTree(tree, id))
            }
        }
    }
    return resultado
}
