"""Exporte un logo (dossier d'une piste) vers les ressources Android de Proscenium.

    python export_android.py proscenium

- icône classique (Android 7, Freebox) : mipmap-*/ic_launcher.png, de 48 à 192 px ;
- icône adaptative (Android 8 et plus, TV) : premier plan et fond, de 108 à 432 px ;
- bannière Android TV : drawable-xhdpi/banner.png (320 × 180).
"""
import shutil
import sys
from pathlib import Path

from PIL import Image

piste = Path(__file__).resolve().parent / sys.argv[1]
res = Path(__file__).resolve().parent.parent / "launcher" / "res"
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

icon = Image.open(piste / "icon-512.png").convert("RGBA")
fg = Image.open(piste / "icon-foreground-432.png").convert("RGBA")
bg = Image.open(piste / "icon-background-432.png").convert("RGB")

for name, k in DENSITIES.items():
    d = res / f"mipmap-{name}"
    d.mkdir(parents=True, exist_ok=True)
    icon.resize((round(48 * k),) * 2, Image.LANCZOS).save(d / "ic_launcher.png", optimize=True)
    size = (round(108 * k),) * 2
    fg.resize(size, Image.LANCZOS).save(d / "ic_launcher_foreground.png", optimize=True)
    bg.resize(size, Image.LANCZOS).save(d / "ic_launcher_background.png", optimize=True)

banner = res / "drawable-xhdpi"
banner.mkdir(parents=True, exist_ok=True)
Image.open(piste / "banner-320x180.png").convert("RGB").save(banner / "banner.png", optimize=True)

adaptive = res / "mipmap-anydpi-v26"
adaptive.mkdir(parents=True, exist_ok=True)
(adaptive / "ic_launcher.xml").write_text(
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
    '    <background android:drawable="@mipmap/ic_launcher_background" />\n'
    '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
    '</adaptive-icon>\n', encoding="utf-8")
print("Icônes et bannière exportées depuis", piste.name)
