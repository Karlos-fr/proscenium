"""Construit les packs de fonds de Proscenium (dossier packs/ du dépôt).

Un pack ne contient AUCUNE image : seulement, pour chaque film, son numéro TMDB, les chemins TMDB
des fonds choisis, un logo par langue et le titre dans chaque langue. L'appli télécharge ensuite
les images directement chez TMDB (image.tmdb.org), sans clé d'API.

    python packs/build_packs.py [playlists.json]

  - playlists.json : export des playlists de la TV (adb pull …/files/playlists.json). Les packs
    « from_playlist » reprennent les films et les fonds choisis à la main dans cette playlist.
    Sans ce fichier, ces packs gardent la liste de films déjà publiée.
  - La clé TMDB est lue dans tmdb.key, à la racine du projet (jamais publiée).
"""
import json
import re
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "packs"
LANGS = ["fr", "en", "de", "it", "es", "sv"]
TAGS = {"fr": "fr-FR", "en": "en-US", "de": "de-DE", "it": "it-IT", "es": "es-ES", "sv": "sv-SE"}

PACKS = [
    {
        "id": "disney-pixar",
        "from_playlist": "Disney & Pixar",
        "name": {l: "Disney & Pixar" for l in LANGS},
        "description": {
            "fr": "Les longs métrages d'animation des studios Disney et Pixar",
            "en": "Animated feature films from Disney and Pixar",
            "de": "Animierte Spielfilme von Disney und Pixar",
            "it": "I lungometraggi d'animazione Disney e Pixar",
            "es": "Los largometrajes de animación de Disney y Pixar",
            "sv": "Animerade långfilmer från Disney och Pixar",
        },
    },
    {
        "id": "classiques-marvel",
        "from_playlist": "Classiques & Marvel",
        "name": {"fr": "Classiques & Marvel", "en": "Classics & Marvel", "de": "Klassiker & Marvel",
                 "it": "Classici & Marvel", "es": "Clásicos y Marvel", "sv": "Klassiker & Marvel"},
        "description": {
            "fr": "Grands classiques du cinéma et films Marvel",
            "en": "Great film classics and Marvel movies",
            "de": "Große Filmklassiker und Marvel-Filme",
            "it": "Grandi classici del cinema e film Marvel",
            "es": "Grandes clásicos del cine y películas de Marvel",
            "sv": "Stora filmklassiker och Marvel-filmer",
        },
    },
    {
        "id": "ghibli",
        "discover": {"with_companies": "10342", "with_genres": "16"},
        # Nausicaä (studio Topcraft, avant Ghibli) en plus ; un documentaire et une coproduction en moins
        "include": [81],
        "exclude": [141897, 12140],
        # Titres erronés sur TMDB (ceux du vieux montage américain)
        "titles": {81: {"fr": "Nausicaä de la vallée du vent", "en": "Nausicaä of the Valley of the Wind",
                        "de": "Nausicaä aus dem Tal der Winde", "es": "Nausicaä del Valle del Viento"}},
        "name": {l: "Studio Ghibli" for l in LANGS},
        "description": {
            "fr": "Les films d'animation du Studio Ghibli",
            "en": "The animated films of Studio Ghibli",
            "de": "Die Animationsfilme von Studio Ghibli",
            "it": "I film d'animazione dello Studio Ghibli",
            "es": "Las películas de animación del Studio Ghibli",
            "sv": "Studio Ghiblis animerade filmer",
        },
    },
]

KEY = (ROOT / "tmdb.key").read_text(encoding="utf-8").strip()


def api(path, **params):
    """Appel à l'API TMDB (clé v3 dans l'adresse, jeton v4 dans l'en-tête)."""
    headers = {"User-Agent": "Proscenium pack builder"}
    if len(KEY) > 40:
        headers["Authorization"] = "Bearer " + KEY
    else:
        params["api_key"] = KEY
    url = "https://api.themoviedb.org/3" + path + "?" + urllib.parse.urlencode(params)
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=20) as r:
                return json.load(r)
        except Exception as e:
            if attempt == 3:
                raise RuntimeError(f"TMDB {path} : {type(e).__name__}") from None
            time.sleep(1 + attempt)


def best(images, lang=None, textless=False):
    """Image la mieux notée (dans une langue, ou sans texte)."""
    pool = [i for i in images if (i.get("iso_639_1") is None if textless else i.get("iso_639_1") == lang)]
    pool.sort(key=lambda i: (i.get("vote_average", 0), i.get("vote_count", 0)), reverse=True)
    return pool[0] if pool else None


def film_entry(movie_id, backdrops=None, logo_fr=None, min_runtime=0):
    """Fiche d'un film : titres dans chaque langue, fonds, logo par langue."""
    titles, year, runtime = {}, "", 0
    for l in LANGS:
        d = api(f"/movie/{movie_id}", language=TAGS[l])
        titles[l] = d.get("title") or d.get("original_title") or ""
        year = (d.get("release_date") or "")[:4]
        runtime = d.get("runtime") or 0
    if min_runtime and runtime < min_runtime:
        return None
    # TMDB ne mélange pas plus de 5 langues par requête : deux appels
    imgs = api(f"/movie/{movie_id}/images", include_image_language="fr,en,de,null")
    more = api(f"/movie/{movie_id}/images", include_image_language="it,es,sv")
    for k in ("backdrops", "logos"):
        imgs[k] = imgs.get(k, []) + more.get(k, [])
    if not backdrops:
        # Même choix que l'appli : sans texte et en Full HD si possible, sinon le mieux noté
        bds = imgs.get("backdrops", [])
        full = [b for b in bds if b.get("width", 0) >= 1920]
        pick = best(full, textless=True) or best(bds, textless=True) or best(full, "en") or (bds[0] if bds else None)
        backdrops = [pick["file_path"]] if pick else []
    logos = {}
    for l in LANGS:
        lg = best([i for i in imgs.get("logos", []) if i["file_path"].endswith(".png")], l)
        if lg:
            logos[l] = lg["file_path"]
    if logo_fr:
        logos["fr"] = logo_fr  # logo choisi à la main sur la TV
    return {"id": movie_id, "year": year, "title": titles, "backdrops": backdrops, "logo": logos}


def from_playlist(playlists, name):
    """Films et fonds d'une playlist de la TV : les chemins TMDB se lisent dans les noms de fichiers."""
    for p in playlists:
        if p["name"] != name:
            continue
        out = []
        for f in p["films"]:
            mid = f["tmdbId"]
            bds = []
            for b in f["backdrops"]:
                m = re.match(r"(\d+)_([A-Za-z0-9]+\.(?:jpg|png))$", Path(b).name)
                if m and int(m.group(1)) == mid:
                    bds.append("/" + m.group(2))
            m = re.match(r"(\d+)_([A-Za-z0-9]+\.png)$", Path(f.get("logo") or "x").name)
            out.append((mid, bds, "/" + m.group(2) if m else None))
        return out
    raise SystemExit(f"Playlist « {name} » absente de l'export")


def from_discover(params):
    out, page = [], 1
    while True:
        d = api("/discover/movie", sort_by="primary_release_date.asc", page=page, **params)
        out += [(m["id"], None, None) for m in d["results"]]
        if page >= d.get("total_pages", 1):
            return out
        page += 1


def main():
    playlists = None
    if len(sys.argv) > 1:
        playlists = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    index = []
    for pack in PACKS:
        target = OUT / f"{pack['id']}.json"
        if "from_playlist" in pack:
            if playlists is None:
                if not target.exists():
                    raise SystemExit(f"{pack['id']} : export des playlists nécessaire")
                old = json.loads(target.read_text(encoding="utf-8"))
                sources = [(f["id"], f["backdrops"], f["logo"].get("fr")) for f in old["films"]]
            else:
                sources = from_playlist(playlists, pack["from_playlist"])
        else:
            sources = [(i, None, None) for i in pack.get("include", [])]
            sources += [s for s in from_discover(pack["discover"])
                        if s[0] not in pack.get("exclude", []) and s[0] not in pack.get("include", [])]
        films = []
        for n, (mid, bds, logo) in enumerate(sources, 1):
            print(f"  {pack['id']} {n}/{len(sources)}", end="\r")
            # Packs construits par recherche : longs métrages seulement (pas de courts ni de making-of)
            entry = film_entry(mid, bds, logo, 0 if "from_playlist" in pack else 60)
            if entry:
                entry["title"].update(pack.get("titles", {}).get(mid, {}))
            if entry and entry["backdrops"]:
                films.append(entry)
        print(f"{pack['id']} : {len(films)} films        ")
        data = {"id": pack["id"], "name": pack["name"], "description": pack["description"], "films": films}
        target.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
        index.append({"id": pack["id"], "name": pack["name"], "description": pack["description"],
                      "films": len(films), "cover": films[0]["backdrops"][0] if films else ""})
    (OUT / "index.json").write_text(json.dumps({"version": 1, "packs": index}, ensure_ascii=False, indent=1),
                                    encoding="utf-8")
    print("index.json écrit")


if __name__ == "__main__":
    main()
