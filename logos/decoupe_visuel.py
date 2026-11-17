"""Découpe le visuel du logo (logos/proscenium/visuel-source.webp) en icônes et bannière.

    python decoupe_visuel.py proscenium/visuel-source.webp proscenium
    python export_android.py proscenium
"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

src, out = Path(sys.argv[1]), Path(sys.argv[2])
out.mkdir(parents=True, exist_ok=True)
im = Image.open(src).convert("RGB")
BG = (0, 8, 32)

# Le « P » avec sa lueur, et le mot PROSCENIUM
P_BOX = (40, 110, 740, 870)        # P avec toute sa lueur, et de la marge
WORD_BOX = (670, 340, 1610, 475)   # PROSCENIUM
word = im.crop(WORD_BOX)
# Le symbole seul : le mot, juste à sa droite, est effacé avant la découpe
alone = im.copy()
ImageDraw.Draw(alone).rectangle((686, 0, im.width, im.height), fill=BG)
p = alone.crop(P_BOX)


def feather(img, edge):
    """Masque qui s'efface vers les bords : la lueur se fond dans le fond, sans rectangle visible."""
    m = Image.new("L", img.size, 0)
    ImageDraw.Draw(m).rectangle((edge, edge, img.width - edge, img.height - edge), fill=255)
    return m.filter(ImageFilter.GaussianBlur(edge / 2))


p_mask = feather(p, 60)
word_mask = feather(word, 12)
P_H = 770 - 213                    # hauteur du P lui-même (sans la lueur), dans le visuel
P_CENTER = ((145 + 625) / 2 - P_BOX[0], (213 + 770) / 2 - P_BOX[1])


def mark(size, p_ratio):
    """Carré bleu nuit avec le P centré, haut de p_ratio × size."""
    k = size * p_ratio / P_H
    pw, ph = round(p.width * k), round(p.height * k)
    canvas = Image.new("RGB", (size, size), BG)
    scaled = p.resize((pw, ph), Image.LANCZOS)
    x = round(size / 2 - P_CENTER[0] * k)
    y = round(size / 2 - P_CENTER[1] * k)
    canvas.paste(scaled, (x, y), p_mask.resize((pw, ph), Image.LANCZOS))
    return canvas


# Icône adaptative (Android 8+) : le P tient dans la zone sûre (66 % du centre)
mark(432, 0.50).save(out / "icon-foreground-432.png")
Image.new("RGB", (432, 432), BG).save(out / "icon-background-432.png")

# Icône classique (Android 7) : carré arrondi bleu nuit, liseré clair discret, comme la maquette
icon = mark(512, 0.62).convert("RGBA")
mask = Image.new("L", (512, 512), 0)
ImageDraw.Draw(mask).rounded_rectangle((8, 8, 503, 503), radius=110, fill=255)
icon.putalpha(mask)
ImageDraw.Draw(icon).rounded_rectangle((8, 8, 503, 503), radius=110, outline=(120, 140, 200, 70), width=3)
icon.save(out / "icon-512.png")

# Bannière Android TV : P à gauche, PROSCENIUM à droite, composée en 1280 × 720 puis réduite
W, H = 1280, 720
banner = Image.new("RGB", (W, H), BG)
k = 420 / P_H
ps = p.resize((round(p.width * k), round(p.height * k)), Image.LANCZOS)
pm = p_mask.resize(ps.size, Image.LANCZOS)
kw = 720 / word.width
ws = word.resize((720, round(word.height * kw)), Image.LANCZOS)
wm = word_mask.resize(ws.size, Image.LANCZOS)
# Groupe P + mot centré : largeur du P (sans lueur) + espace + mot
p_w = (625 - 145) * k
gap = 50
left = (W - (p_w + gap + 720 * (1 - 2 * 15 / word.width))) / 2
px = round(left - (145 - P_BOX[0]) * k)
banner.paste(ps, (px, round(H / 2 - P_CENTER[1] * k)), pm)
banner.paste(ws, (round(left + p_w + gap - 15 * kw), round(H / 2 - ws.height / 2)), wm)
banner.resize((320, 180), Image.LANCZOS).save(out / "banner-320x180.png")
banner.save(out / "banner-1280x720.png")
print("ok")
