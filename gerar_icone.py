#!/usr/bin/env python3
"""
Gera o ícone do Oliveira Fotos: três fotos empilhadas (só as bordas das de trás aparecem),
usando uma FOTO REAL de oliveira que você escolher.

Uso no Termux:
    pkg install python-pillow
    python ~/OliveiraFotos/gerar_icone.py ~/storage/downloads/oliveira.jpg
"""
import os
import sys

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageOps

TAM = 648     # 108dp x 6
LADO = 316    # tamanho de cada foto
BORDA = 13    # moldura branca


def recorte(img, zoom, cx, cy):
    w, h = img.size
    lado = min(w, h) / zoom
    esq = cx * (w - lado)
    topo = cy * (h - lado)
    return img.crop((int(esq), int(topo), int(esq + lado), int(topo + lado)))


def cartao(img, zoom, cx, cy, brilho=1.0):
    interno = LADO - 2 * BORDA
    foto = recorte(img, zoom, cx, cy).resize((interno, interno), Image.LANCZOS)
    if brilho != 1.0:
        foto = ImageEnhance.Brightness(foto).enhance(brilho)
    papel = Image.new("RGBA", (LADO, LADO), (248, 246, 238, 255))
    papel.paste(foto, (BORDA, BORDA))
    return papel


def camada(elem, x, y):
    c = Image.new("RGBA", (TAM, TAM), (0, 0, 0, 0))
    c.paste(elem, (x, y))
    return c


def colar(tela, cart, centro, angulo):
    r = cart.rotate(angulo, resample=Image.BICUBIC, expand=True)
    # sombra suave
    sombra = Image.new("RGBA", r.size, (0, 0, 0, 0))
    sombra.putalpha(r.split()[3].point(lambda v: int(v * 0.55)))
    m = 24
    grande = Image.new("RGBA", (r.width + 2 * m, r.height + 2 * m), (0, 0, 0, 0))
    grande.paste(sombra, (m, m))
    grande = grande.filter(ImageFilter.GaussianBlur(7))
    x = int(centro[0] - r.width / 2)
    y = int(centro[1] - r.height / 2)
    tela = Image.alpha_composite(tela, camada(grande, x - m + 5, y - m + 8))
    tela = Image.alpha_composite(tela, camada(r, x, y))
    return tela


def main():
    if len(sys.argv) < 2:
        print("Uso: python gerar_icone.py caminho/da/foto_de_oliveira.jpg")
        sys.exit(1)
    img = ImageOps.exif_transpose(Image.open(os.path.expanduser(sys.argv[1]))).convert("RGB")

    tela = Image.new("RGBA", (TAM, TAM), (0, 0, 0, 0))
    tela = colar(tela, cartao(img, 1.25, 0.0, 0.4, 0.90), (298, 318), 10)
    tela = colar(tela, cartao(img, 1.25, 1.0, 0.6, 0.86), (352, 314), -9)
    tela = colar(tela, cartao(img, 1.0, 0.5, 0.5, 1.0), (324, 336), 0)

    saida = os.path.expanduser("~/OliveiraFotos/app/src/main/res/drawable-nodpi/ic_launcher_foreground.png")
    os.makedirs(os.path.dirname(saida), exist_ok=True)
    tela.save(saida)
    print("Ícone salvo em", saida)

    # prévia (fundo verde oliva, recorte redondo)
    fundo = Image.new("RGBA", (TAM, TAM), (59, 66, 36, 255))
    prev = Image.alpha_composite(fundo, tela)
    mascara = Image.new("L", (TAM, TAM), 0)
    ImageDraw.Draw(mascara).ellipse((0, 0, TAM - 1, TAM - 1), fill=255)
    prev.putalpha(mascara)
    pasta = os.path.expanduser("~/storage/downloads")
    destino = os.path.join(pasta if os.path.isdir(pasta) else os.path.expanduser("~"), "icone_previa.png")
    prev.resize((324, 324), Image.LANCZOS).save(destino)
    print("Prévia em", destino)


if __name__ == "__main__":
    main()
