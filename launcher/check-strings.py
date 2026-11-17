"""Contrôle des traductions de Proscenium : anglais (values/), référence, et chaque values-xx/.

Vérifie que chaque texte existe dans les deux langues, que les pluriels ont les mêmes formes,
que les emplacements de valeurs (%d, %1$s, %.1f…) correspondent, et que tout texte utilisé dans
le code (R.string.x, R.plurals.x) existe. Code de sortie 1 s'il y a un problème.

    python check-strings.py
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent
LANGS = {"anglais": ROOT / "res/values/strings.xml"}
LANGS.update({d.name[7:]: d / "strings.xml" for d in sorted((ROOT / "res").glob("values-*"))
              if (d / "strings.xml").exists()})
# Sans le drapeau « espace » : « 100 % sur » est du texte, pas un emplacement
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[-#+0,(]*\d*(?:\.\d+)?[sdfx%]")


def load(path):
    root = ET.parse(path).getroot()
    strings, plurals = {}, {}
    for el in root:
        name = el.get("name")
        if el.tag == "string":
            strings[name] = "".join(el.itertext())
        elif el.tag == "plurals":
            plurals[name] = {item.get("quantity"): "".join(item.itertext()) for item in el}
    return strings, plurals


def placeholders(text):
    return sorted(p for p in PLACEHOLDER.findall(text) if p != "%%")


problems = []
data = {lang: load(path) for lang, path in LANGS.items()}
en_s, en_p = data["anglais"]

for lang, (fr_s, fr_p) in data.items():
    if lang == "anglais":
        continue
    for name in sorted(set(en_s) ^ set(fr_s)):
        missing = lang if name in en_s else "anglais"
        problems.append(f"texte « {name} » absent en {missing}")
    for name in sorted(set(en_p) ^ set(fr_p)):
        missing = lang if name in en_p else "anglais"
        problems.append(f"pluriel « {name} » absent en {missing}")
    for name in sorted(set(en_s) & set(fr_s)):
        if placeholders(en_s[name]) != placeholders(fr_s[name]):
            problems.append(f"[{lang}] « {name} » : valeurs différentes ({placeholders(en_s[name])} / {placeholders(fr_s[name])})")
    for name in sorted(set(en_p) & set(fr_p)):
        if "other" not in en_p[name] or "other" not in fr_p[name]:
            problems.append(f"[{lang}] pluriel « {name} » : forme « other » manquante")
        for q in set(en_p[name]) & set(fr_p[name]):
            if placeholders(en_p[name][q]) != placeholders(fr_p[name][q]):
                problems.append(f"[{lang}] pluriel « {name} » ({q}) : valeurs différentes")

used = set()
for java in (ROOT / "src").rglob("*.java"):
    code = java.read_text(encoding="utf-8")
    # android.R.string.ok, cancel… sont fournis par Android
    used |= {("string", n) for n in re.findall(r"(?<!android\.)\bR\.string\.(\w+)", code)}
    used |= {("plurals", n) for n in re.findall(r"(?<!android\.)\bR\.plurals\.(\w+)", code)}
for xml in [*(ROOT / "res").rglob("*.xml"), ROOT / "AndroidManifest.xml"]:
    used |= {("string", n) for n in re.findall(r"@string/(\w+)", xml.read_text(encoding="utf-8"))}
for kind, name in sorted(used):
    table = en_s if kind == "string" else en_p
    if name not in table:
        problems.append(f"{'texte' if kind == 'string' else 'pluriel'} « {name} » utilisé dans le code mais absent")

unused = sorted((set(en_s) - {n for k, n in used if k == "string"}) | (set(en_p) - {n for k, n in used if k == "plurals"}))

print(f"Traductions ({', '.join(data)}) : {len(en_s)} textes et {len(en_p)} pluriels contrôlés.")
if unused:
    print(f"Inutilisés ({len(unused)}) : {', '.join(unused)}")
if problems:
    print(f"{len(problems)} problème(s) :")
    for p in problems:
        print("  - " + p)
    sys.exit(1)
print("Aucun texte manquant.")
