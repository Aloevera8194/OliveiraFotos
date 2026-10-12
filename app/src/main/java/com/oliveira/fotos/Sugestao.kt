package com.oliveira.fotos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions

/** Rótulos do ML Kit (em inglês) -> etiquetas em português. O que não está aqui é ignorado. */
private val traducao: Map<String, String> = buildMap {
    fun grupo(nome: String, vararg rotulos: String) {
        rotulos.forEach { put(it, nome) }
    }
    grupo("comida", "Food", "Dish", "Cuisine", "Meal", "Recipe", "Baked goods", "Dessert", "Cake", "Fast food", "Ingredient")
    grupo("fruta", "Fruit")
    grupo("verdura", "Vegetable")
    grupo("bebida", "Drink", "Beverage")
    grupo("planta", "Plant", "Flower", "Leaf", "Grass", "Garden", "Houseplant", "Flowerpot")
    grupo("árvore", "Tree")
    grupo("céu", "Sky", "Cloud")
    grupo("pôr do sol", "Sunset", "Sunrise")
    grupo("praia", "Beach", "Sea", "Ocean", "Coast")
    grupo("água", "Water", "Lake", "River", "Pool")
    grupo("montanha", "Mountain", "Hill")
    grupo("construção", "Building", "Architecture", "Skyscraper", "Tower")
    grupo("casa", "House", "Home")
    grupo("interior", "Room", "Ceiling", "Wall", "Floor", "Window", "Door", "Interior design")
    grupo("cozinha", "Kitchen")
    grupo("móveis", "Furniture", "Table", "Chair", "Couch", "Sofa", "Bed", "Shelf")
    grupo("carro", "Car", "Vehicle", "Truck")
    grupo("moto", "Motorcycle")
    grupo("bicicleta", "Bicycle")
    grupo("rua", "Road", "Street", "Asphalt")
    grupo("cidade", "City", "Urban area")
    grupo("cachorro", "Dog")
    grupo("gato", "Cat")
    grupo("pássaro", "Bird")
    grupo("animal", "Animal", "Pet")
    grupo("peixe", "Fish")
    grupo("documento", "Text", "Paper", "Document", "Font", "Handwriting", "Paper product")
    grupo("planta baixa", "Floor plan", "Blueprint", "Diagram", "Plan", "Technical drawing")
    grupo("desenho", "Drawing", "Sketch", "Illustration", "Cartoon", "Art")
    grupo("livro", "Book", "Publication")
    grupo("cartaz", "Poster", "Banner", "Signage")
    grupo("celular", "Phone", "Mobile phone", "Smartphone")
    grupo("computador", "Computer", "Laptop", "Monitor")
    grupo("eletrônico", "Electronics", "Gadget", "Screen", "Electronic device", "Cable")
    grupo("tv", "Television")
    grupo("roupa", "Clothing", "Shirt", "Dress", "Fashion")
    grupo("calçado", "Shoe", "Footwear")
    grupo("brinquedo", "Toy")
    grupo("ferramenta", "Tool", "Machine", "Engineering")
}

fun rotacao(ctx: Context, uri: Uri): Int {
    return try {
        ctx.contentResolver.openInputStream(uri)?.use { entrada ->
            when (ExifInterface(entrada).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
    } catch (_: Exception) {
        0
    }
}

/** Abre a foto já reduzida, para gastar pouca memória. */
fun carregar(ctx: Context, uri: Uri, max: Int = 640): Bitmap? {
    val cr = ctx.contentResolver
    val medida = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, medida) }
    var amostra = 1
    while (medida.outWidth / amostra > max * 2 || medida.outHeight / amostra > max * 2) amostra *= 2
    val opcoes = BitmapFactory.Options().apply { inSampleSize = amostra }
    return cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opcoes) }
}

/**
 * Analisa as fotos no próprio celular (nada sai do aparelho).
 * Devolve, para cada foto (id), as etiquetas sugeridas.
 */
fun analisar(
    ctx: Context,
    tree: Uri,
    fotos: List<Entrada>,
    cancelado: () -> Boolean,
    aoProgredir: (Int, Int) -> Unit
): Map<String, Set<String>> {
    val rotulador = ImageLabeling.getClient(
        ImageLabelerOptions.Builder().setConfidenceThreshold(0.7f).build()
    )
    val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.08f)
            .build()
    )
    val saida = mutableMapOf<String, Set<String>>()
    try {
        for ((i, foto) in fotos.withIndex()) {
            if (cancelado()) break
            val uri = docUri(tree, foto.id)
            val bmp = carregar(ctx, uri)
            if (bmp != null) {
                val tags = mutableSetOf<String>()
                try {
                    val imagem = InputImage.fromBitmap(bmp, rotacao(ctx, uri))
                    for (r in Tasks.await(rotulador.process(imagem))) {
                        traducao[r.text]?.let { tags.add(it) }
                    }
                    val rostos = Tasks.await(detector.process(imagem)).size
                    if (rostos >= 1) tags.add("pessoas")
                    if (rostos >= 3) tags.add("grupo")
                } catch (_: Exception) {
                }
                bmp.recycle()
                if (tags.isNotEmpty()) saida[foto.id] = tags
            }
            aoProgredir(i + 1, fotos.size)
        }
    } finally {
        rotulador.close()
        detector.close()
    }
    return saida
}
