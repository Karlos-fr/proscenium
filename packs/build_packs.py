"""Construit les packs de fonds de Proscenium (dossier packs/ du dépôt).

Un pack ne contient AUCUNE image : seulement, pour chaque film, son numéro TMDB, les chemins TMDB
des fonds choisis, un logo par langue et le titre dans chaque langue. L'appli télécharge ensuite
les images directement chez TMDB (image.tmdb.org), sans clé d'API.

    python packs/build_packs.py [playlists.json] [--only id1,id2] [--refresh]

  - playlists.json : export des playlists de la TV (adb pull …/files/playlists.json). Les packs
    « from_playlist » reprennent les films et les fonds choisis à la main dans cette playlist.
    Sans ce fichier, ces packs gardent la liste de films déjà publiée.
  - --only : ne reconstruit que ces packs ; index.json liste quand même tous les packs (les autres
    sont relus depuis leur fichier).
  - --refresh : refait la fiche (titres, fond, logos) des films déjà publiés. Sans cette option,
    un film déjà présent dans le pack garde sa fiche publiée, pour que les packs restent stables.
  - La clé TMDB est lue dans tmdb.key, à la racine du projet (jamais publiée).

Sources possibles d'un pack (combinables, sauf from_playlist) :
  - "from_playlist": "Nom"           films et fonds d'une playlist de la TV (ordre conservé)
  - "collections": [ids]             collections TMDB (/collection/{id})
  - "directors": [ids]               films réalisés par ces personnes TMDB
  - "discover": {paramètres}         recherche /discover/movie (sort_by, vote_count.gte, dates, genres…)
  - "limit": N                       garde les N premiers films valides de la recherche discover
  - "include": [ids] / "exclude": [ids]  films ajoutés / retirés à la main
  - "titles": {id: {langue: titre}}  corrige un titre TMDB erroné
  - "cover": id                      film dont le fond sert de couverture (défaut : le plus populaire)

Version : un pack dont la liste de films change passe à la version suivante ; sinon il la garde. Il
passe aussi à la suivante quand ses films reçoivent leur fiche (résumé, accroche, genres, note, durée).

Fiche de chaque film, pour la carte titre du mode cinéma : résumé (« overview ») et accroche
(« tagline ») dans les 6 langues, genres dans les 6 langues, note TMDB (« rating », sur 10), nombre de
votes (« votes ») et durée en minutes (« runtime »). Une langue sans texte est omise : l'appli prend
l'anglais.
"""
import argparse
import copy
import datetime
import json
import re
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "packs"
LANGS = ["fr", "en", "de", "it", "es", "sv"]
TAGS = {"fr": "fr-FR", "en": "en-US", "de": "de-DE", "it": "it-IT", "es": "es-ES", "sv": "sv-SE"}
# Genres TMDB écartés des filmographies de réalisateurs : documentaire, téléfilm
SKIP_GENRES = {99, 10770}


def films_of(name):
    """Description « Les films de … » dans les 6 langues."""
    return {"fr": f"Les films de {name}", "en": f"The films of {name}", "de": f"Die Filme von {name}",
            "it": f"I film di {name}", "es": f"Las películas de {name}", "sv": f"{name}s filmer"}


PACKS = [
    {
        "id": "disney-pixar",
        "from_playlist": "Disney & Pixar",
        "cover": 408,
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
        "cover": 679,
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
        "cover": 81,
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
    {
        "id": "star-wars",
        "collections": [10],  # Star Wars Collection (les 9 épisodes)
        # Rogue One, Solo, The Clone Wars (sorti en salles), The Mandalorian & Grogu
        "include": [330459, 348350, 12180, 1228710],
        "name": {l: "Star Wars" for l in LANGS},
        "description": {
            "fr": "La saga Skywalker et ses films dérivés",
            "en": "The Skywalker saga and its spin-off films",
            "de": "Die Skywalker-Saga und ihre Ablegerfilme",
            "it": "La saga degli Skywalker e i suoi film spin-off",
            "es": "La saga Skywalker y sus películas derivadas",
            "sv": "Skywalkersagan och dess fristående filmer",
        },
    },
    {
        "id": "nolan",
        "cover": 157336,  # Interstellar
        "directors": [525],  # Christopher Nolan
        "exclude": [126444],  # Cinema16: British Short Films (compilation de courts métrages)
        "name": {l: "Christopher Nolan" for l in LANGS},
        "description": films_of("Christopher Nolan"),
    },
    {
        "id": "horreur",
        "cover": 694,  # Shining
        "discover": {"with_genres": "27", "without_genres": "99,16,35,10402", "sort_by": "vote_count.desc",
                     "vote_average.gte": "7", "vote_count.gte": "3000"},
        "limit": 36,
        # Classiques un peu moins votés
        "include": [805, 30497, 13310, 11906],
        # Thrillers et films hors sujet, suites, film trop récent pour être « reconnu »
        "exclude": [44214, 333371, 561, 1813, 1339713, 13885, 507089, 9533, 63311,
                    423108, 945961, 574475, 520763],
        "name": {"fr": "Horreur", "en": "Horror", "de": "Horror", "it": "Horror", "es": "Terror",
                 "sv": "Skräck"},
        "description": {
            "fr": "Les grands classiques du cinéma d'horreur",
            "en": "Great classics of horror cinema",
            "de": "Große Klassiker des Horrorfilms",
            "it": "I grandi classici del cinema horror",
            "es": "Los grandes clásicos del cine de terror",
            "sv": "Skräckfilmens stora klassiker",
        },
    },
    {
        "id": "annees-80",
        "discover": {"primary_release_date.gte": "1980-01-01", "primary_release_date.lte": "1989-12-31",
                     "without_genres": "99,16,10402,10770", "sort_by": "vote_count.desc",
                     "vote_average.gte": "7"},
        "limit": 40,
        "name": {"fr": "Années 80", "en": "The 80s", "de": "Die 80er", "it": "Anni '80", "es": "Años 80",
                 "sv": "80-talet"},
        "description": {
            "fr": "Les films marquants des années 1980",
            "en": "Landmark films of the 1980s",
            "de": "Prägende Filme der 1980er-Jahre",
            "it": "I film che hanno segnato gli anni Ottanta",
            "es": "Las películas más destacadas de los años 80",
            "sv": "1980-talets mest minnesvärda filmer",
        },
    },
    {
        "id": "annees-90",
        "discover": {"primary_release_date.gte": "1990-01-01", "primary_release_date.lte": "1999-12-31",
                     "without_genres": "99,16,10402,10770", "sort_by": "vote_count.desc",
                     "vote_average.gte": "7"},
        "limit": 40,
        "name": {"fr": "Années 90", "en": "The 90s", "de": "Die 90er", "it": "Anni '90", "es": "Años 90",
                 "sv": "90-talet"},
        "description": {
            "fr": "Les films marquants des années 1990",
            "en": "Landmark films of the 1990s",
            "de": "Prägende Filme der 1990er-Jahre",
            "it": "I film che hanno segnato gli anni Novanta",
            "es": "Las películas más destacadas de los años 90",
            "sv": "1990-talets mest minnesvärda filmer",
        },
    },
    {
        "id": "animation",
        # DreamWorks Animation, Illumination, Aardman, Laika, Sony Pictures Animation, Blue Sky,
        # Warner Animation Group, Cartoon Saloon ; sans Disney, Pixar, Walt Disney Animation ni Ghibli
        "discover": {"with_genres": "16", "with_companies": "521|6704|297|11537|2251|9383|25120|23948",
                     "without_companies": "2|3|6125|10342", "without_genres": "99",
                     "sort_by": "vote_count.desc", "vote_average.gte": "7.2", "vote_count.gte": "800"},
        "limit": 38,
        "include": [7443, 533],  # Chicken Run, Wallace & Gromit : Le Mystère du lapin-garou
        "exclude": [459151, 446893, 1315772],  # suites mineures, film trop récent
        "cover": 808,  # Shrek
        "name": {"fr": "Animation", "en": "Animation", "de": "Animation", "it": "Animazione",
                 "es": "Animación", "sv": "Animation"},
        "description": {
            "fr": "DreamWorks, Illumination, Aardman, Laika, Sony et d'autres studios d'animation",
            "en": "DreamWorks, Illumination, Aardman, Laika, Sony and other animation studios",
            "de": "DreamWorks, Illumination, Aardman, Laika, Sony und weitere Animationsstudios",
            "it": "DreamWorks, Illumination, Aardman, Laika, Sony e altri studi d'animazione",
            "es": "DreamWorks, Illumination, Aardman, Laika, Sony y otros estudios de animación",
            "sv": "DreamWorks, Illumination, Aardman, Laika, Sony och andra animationsstudior",
        },
    },
    {
        "id": "harry-potter",
        "collections": [1241, 435259],  # Harry Potter, Les Animaux fantastiques
        "name": {l: "Harry Potter" for l in LANGS},
        "description": {
            "fr": "Les films Harry Potter et Les Animaux fantastiques",
            "en": "The Harry Potter and Fantastic Beasts films",
            "de": "Die Filme von Harry Potter und Phantastische Tierwesen",
            "it": "I film di Harry Potter e Animali fantastici",
            "es": "Las películas de Harry Potter y Animales fantásticos",
            "sv": "Filmerna om Harry Potter och Fantastiska vidunder",
        },
    },
    {
        "id": "terre-du-milieu",
        "collections": [119, 121938],  # Le Seigneur des anneaux, Le Hobbit (Peter Jackson)
        "name": {"fr": "La Terre du Milieu", "en": "Middle-earth", "de": "Mittelerde",
                 "it": "La Terra di Mezzo", "es": "La Tierra Media", "sv": "Midgård"},
        "description": {
            "fr": "Le Seigneur des anneaux et Le Hobbit de Peter Jackson",
            "en": "Peter Jackson's The Lord of the Rings and The Hobbit",
            "de": "Peter Jacksons Der Herr der Ringe und Der Hobbit",
            "it": "Il Signore degli Anelli e Lo Hobbit di Peter Jackson",
            "es": "El Señor de los Anillos y El Hobbit de Peter Jackson",
            "sv": "Peter Jacksons Sagan om ringen och Hobbit",
        },
    },
    {
        "id": "james-bond",
        "collections": [645],
        "exclude": [36670],  # Jamais plus jamais (hors EON)
        "name": {l: "James Bond 007" for l in LANGS},
        "description": {
            "fr": "Les films officiels de l'agent 007",
            "en": "The official 007 films",
            "de": "Die offiziellen 007-Filme",
            "it": "I film ufficiali dell'agente 007",
            "es": "Las películas oficiales del agente 007",
            "sv": "De officiella 007-filmerna",
        },
    },
    {
        "id": "science-fiction",
        "cover": 78,  # Blade Runner
        # Sans super-héros ni adaptations de comics (mots-clés superhero, based on comic, superhero team)
        "discover": {"with_genres": "878", "without_genres": "99,16", "without_keywords": "9715|9717|155030",
                     "sort_by": "vote_average.desc", "vote_count.gte": "7000"},
        "limit": 38,
        "include": [840, 871],  # Rencontres du troisième type, La Planète des singes (1968)
        # Suites et franchises déjà présentes ailleurs, films hors sujet
        "exclude": [1892, 165, 196, 1124, 76600, 330459, 1895, 399566, 550988, 454626, 101299,
                    520763, 119450, 54138, 140607, 447332],
        "name": {"fr": "Science-fiction", "en": "Science Fiction", "de": "Science-Fiction",
                 "it": "Fantascienza", "es": "Ciencia ficción", "sv": "Science fiction"},
        "description": {
            "fr": "Les films cultes de la science-fiction",
            "en": "Cult science fiction films",
            "de": "Kultfilme der Science-Fiction",
            "it": "I film cult della fantascienza",
            "es": "Las películas de culto de la ciencia ficción",
            "sv": "Kultförklarade science fiction-filmer",
        },
    },
    {
        "id": "spielberg",
        "cover": 329,  # Jurassic Park
        "directors": [488],  # Steven Spielberg
        "exclude": [15301],  # La Quatrième Dimension (un segment seulement)
        "name": {l: "Steven Spielberg" for l in LANGS},
        "description": films_of("Steven Spielberg"),
    },
    {
        "id": "tarantino",
        "directors": [138],  # Quentin Tarantino
        # Doublon de Reservoir Dogs, Groom Service (un segment), Grindhouse (programme double),
        # Kill Bill : The Whole Bloody Affair (remontage)
        "exclude": [443129, 5, 285923, 414419],
        "name": {l: "Quentin Tarantino" for l in LANGS},
        "description": films_of("Quentin Tarantino"),
    },
    {
        "id": "dc",
        "discover": {"with_companies": "429|9993|128064|184898", "without_genres": "99,16,10770",
                     "sort_by": "vote_count.desc", "vote_average.gte": "6.5", "vote_count.gte": "1000"},
        # Batman (1989), Batman : Le Défi, Superman (1978), Superman II
        "include": [268, 364, 1924, 8536],
        "exclude": [39514, 146216],  # RED, RED 2 (pas des super-héros)
        "name": {"fr": "Super-héros DC", "en": "DC Superheroes", "de": "DC-Superhelden",
                 "it": "Supereroi DC", "es": "Superhéroes de DC", "sv": "DC:s superhjältar"},
        "description": {
            "fr": "Batman, Superman, Wonder Woman et les héros DC au cinéma",
            "en": "Batman, Superman, Wonder Woman and DC heroes on the big screen",
            "de": "Batman, Superman, Wonder Woman und die DC-Helden im Kino",
            "it": "Batman, Superman, Wonder Woman e gli eroi DC al cinema",
            "es": "Batman, Superman, Wonder Woman y los héroes de DC en el cine",
            "sv": "Batman, Stålmannen, Wonder Woman och DC:s hjältar på bio",
        },
    },
    {
        "id": "westerns",
        "discover": {"with_genres": "37", "without_genres": "99,16,10770", "sort_by": "vote_count.desc",
                     "vote_average.gte": "7", "vote_count.gte": "400"},
        "limit": 30,
        # Néo-westerns et films policiers plus que westerns
        "exclude": [338766, 82633, 4476, 6977, 446159, 52629, 324560],
        "name": {"fr": "Westerns", "en": "Westerns", "de": "Western", "it": "Western", "es": "Wésterns",
                 "sv": "Western"},
        "description": {
            "fr": "Les grands westerns, de John Ford à Sergio Leone",
            "en": "Great westerns, from John Ford to Sergio Leone",
            "de": "Große Western von John Ford bis Sergio Leone",
            "it": "I grandi western, da John Ford a Sergio Leone",
            "es": "Los grandes wésterns, de John Ford a Sergio Leone",
            "sv": "Stora westernfilmer, från John Ford till Sergio Leone",
        },
    },
    {
        "id": "villeneuve",
        "directors": [137427],  # Denis Villeneuve
        "exclude": [124818],  # Cosmos (film collectif, un segment)
        "name": {l: "Denis Villeneuve" for l in LANGS},
        "description": films_of("Denis Villeneuve"),
    },
    {
        "id": "wes-anderson",
        "directors": [5655],  # Wes Anderson
        "exclude": [1259365],  # compilation Netflix des quatre moyens métrages Roald Dahl
        "name": {l: "Wes Anderson" for l in LANGS},
        "description": films_of("Wes Anderson"),
    },
    {
        "id": "sagas-aventure",
        # Indiana Jones, Jurassic Park/World, Pirates des Caraïbes, Mission impossible,
        # Retour vers le futur, Matrix
        "collections": [84, 328, 295, 87359, 264, 2344],
        "name": {"fr": "Sagas d'aventure", "en": "Adventure Sagas", "de": "Abenteuer-Sagas",
                 "it": "Saghe d'avventura", "es": "Sagas de aventuras", "sv": "Äventyrssagor"},
        "description": {
            "fr": "Indiana Jones, Jurassic Park, Pirates des Caraïbes, Matrix et d'autres grandes sagas",
            "en": "Indiana Jones, Jurassic Park, Pirates of the Caribbean, The Matrix and more great sagas",
            "de": "Indiana Jones, Jurassic Park, Fluch der Karibik, Matrix und weitere große Sagas",
            "it": "Indiana Jones, Jurassic Park, Pirati dei Caraibi, Matrix e altre grandi saghe",
            "es": "Indiana Jones, Jurassic Park, Piratas del Caribe, Matrix y otras grandes sagas",
            "sv": "Indiana Jones, Jurassic Park, Pirates of the Caribbean, Matrix och fler stora sagor",
        },
    },
]

KEY = None


def api(path, **params):
    """Appel à l'API TMDB (clé v3 dans l'adresse, jeton v4 dans l'en-tête)."""
    global KEY
    if KEY is None:
        KEY = (ROOT / "tmdb.key").read_text(encoding="utf-8").strip()
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


def movie_meta(d):
    return {"date": d.get("release_date") or "", "runtime": d.get("runtime") or 0,
            "popularity": d.get("popularity") or 0}


def details(movie_id, entry=None):
    """Résumé, accroche et genres dans chaque langue, note, votes et durée ; complète {@code entry}.
    Renvoie aussi les titres et la dernière réponse (pour les métadonnées)."""
    entry = entry if entry is not None else {}
    titles, d = {}, {}
    overview, tagline, genres = {}, {}, {}
    for l in LANGS:
        d = api(f"/movie/{movie_id}", language=TAGS[l])
        titles[l] = d.get("title") or d.get("original_title") or ""
        if (d.get("overview") or "").strip():
            overview[l] = d["overview"].strip()
        if (d.get("tagline") or "").strip():
            tagline[l] = d["tagline"].strip()
        names = [g["name"] for g in d.get("genres", []) if g.get("name")]
        if names:
            genres[l] = names
    entry["overview"], entry["tagline"], entry["genres"] = overview, tagline, genres
    entry["rating"] = round(float(d.get("vote_average") or 0), 1)
    entry["votes"] = int(d.get("vote_count") or 0)
    entry["runtime"] = int(d.get("runtime") or 0)
    return entry, titles, d


def film_entry(movie_id, backdrops=None, logo_fr=None):
    """Fiche d'un film (titres dans chaque langue, fonds, logo par langue, résumé…) et ses métadonnées."""
    info, titles, d = details(movie_id)
    meta = movie_meta(d)
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
    entry = {"id": movie_id, "year": meta["date"][:4], "title": titles, "backdrops": backdrops, "logo": logos,
             **info}
    return entry, meta


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
    """Résultats de /discover/movie, page par page (générateur : on s'arrête quand on a assez de films)."""
    params = dict(params)
    params.setdefault("sort_by", "primary_release_date.asc")
    page = 1
    while True:
        d = api("/discover/movie", page=page, **params)
        for m in d["results"]:
            yield m["id"]
        if page >= min(d.get("total_pages", 1), 500):
            return
        page += 1


def from_collections(ids):
    out = []
    for c in ids:
        parts = api(f"/collection/{c}").get("parts", [])
        out += [m["id"] for m in sorted(parts, key=lambda m: m.get("release_date") or "9999")]
    return out


def from_directors(ids):
    out = []
    for p in ids:
        crew = api(f"/person/{p}/movie_credits").get("crew", [])
        crew = [m for m in crew if m.get("job") == "Director" and not SKIP_GENRES & set(m.get("genre_ids", []))]
        out += [m["id"] for m in sorted(crew, key=lambda m: m.get("release_date") or "9999")]
    return out


def read_pack(pack_id):
    target = OUT / f"{pack_id}.json"
    return json.loads(target.read_text(encoding="utf-8")) if target.exists() else None


def build(pack, old, playlists, refresh):
    """Liste des films d'un pack et identifiant TMDB du film le plus populaire."""
    playlist_pack = "from_playlist" in pack
    old_films = {f["id"]: f for f in (old or {}).get("films", [])}
    today = datetime.date.today().isoformat()
    need_meta = not playlist_pack or "cover" not in pack

    def make(mid, bds=None, logo=None):
        """Fiche du film, ou None s'il ne convient pas (pas de fond, court métrage, pas encore sorti)."""
        if mid in old_films and not refresh:
            entry = copy.deepcopy(old_films[mid])
            if bds:
                entry["backdrops"] = bds
            if logo:
                entry["logo"]["fr"] = logo
            if "overview" not in entry:
                # Film publié avant les fiches : on ajoute résumé, accroche, genres, note et durée
                details(mid, entry)
            meta = movie_meta(api(f"/movie/{mid}")) if need_meta else {}
        else:
            entry, meta = film_entry(mid, bds, logo)
        if not playlist_pack:
            # Packs construits par recherche : longs métrages déjà sortis seulement
            if meta["runtime"] < 60 or not meta["date"] or meta["date"] > today:
                return None
        entry["title"].update(pack.get("titles", {}).get(mid, {}))
        return (entry, meta) if entry["backdrops"] else None

    made = []  # (fiche, métadonnées)
    if playlist_pack:
        if playlists is None:
            if old is None:
                raise SystemExit(f"{pack['id']} : export des playlists nécessaire")
            sources = [(f["id"], f["backdrops"], f["logo"].get("fr")) for f in old["films"]]
        else:
            sources = from_playlist(playlists, pack["from_playlist"])
        for n, (mid, bds, logo) in enumerate(sources, 1):
            print(f"  {pack['id']} {n}/{len(sources)}", end="\r")
            r = make(mid, bds, logo)
            if r:
                made.append(r)
    else:
        exclude = set(pack.get("exclude", []))
        seen = set(exclude)
        fixed = []
        for mid in pack.get("include", []) + from_collections(pack.get("collections", [])) + \
                from_directors(pack.get("directors", [])):
            if mid not in seen:
                seen.add(mid)
                fixed.append(mid)
        for n, mid in enumerate(fixed, 1):
            print(f"  {pack['id']} {n}/{len(fixed)}   ", end="\r")
            r = make(mid)
            if r:
                made.append(r)
        if "discover" in pack:
            params = dict(pack["discover"])
            limit = pack.get("limit", params.pop("limit", None))
            count = 0
            for mid in from_discover(params):
                if limit is not None and count >= limit:
                    break
                if mid in seen:
                    continue
                seen.add(mid)
                print(f"  {pack['id']} recherche {count + 1}   ", end="\r")
                r = make(mid)
                if r:
                    made.append(r)
                    count += 1
        # Tri final : par date de sortie croissante (ordre de la playlist gardé pour from_playlist)
        made.sort(key=lambda r: r[1]["date"])
    films = [e for e, _ in made]
    popular = max(made, key=lambda r: r[1].get("popularity", 0))[0]["id"] if made and need_meta else None
    return films, popular


def cover_of(pack, films, popular=None, old_cover=""):
    """Fond de couverture : film « cover » du pack, sinon le plus populaire, sinon l'ancienne couverture."""
    by_id = {f["id"]: f for f in films}
    want = pack.get("cover", popular)
    if want in by_id:
        return by_id[want]["backdrops"][0]
    if "cover" in pack:
        print(f"  ! {pack['id']} : film de couverture {pack['cover']} absent du pack")
    if old_cover and any(old_cover in f["backdrops"] for f in films):
        return old_cover
    return films[0]["backdrops"][0] if films else ""


def main():
    ap = argparse.ArgumentParser(description="Construit les packs de fonds de Proscenium")
    ap.add_argument("playlists", nargs="?", help="export playlists.json de la TV (packs from_playlist)")
    ap.add_argument("--only", help="identifiants des packs à reconstruire, séparés par des virgules")
    ap.add_argument("--refresh", action="store_true", help="refait aussi la fiche des films déjà publiés")
    args = ap.parse_args()
    playlists = json.loads(Path(args.playlists).read_text(encoding="utf-8")) if args.playlists else None
    ids = [p["id"] for p in PACKS]
    if len(set(ids)) != len(ids):
        raise SystemExit("Identifiants de packs en double dans PACKS")
    only = set(ids)
    if args.only:
        only = {s.strip() for s in args.only.split(",") if s.strip()}
        unknown = only - set(ids)
        if unknown:
            raise SystemExit(f"Packs inconnus : {', '.join(sorted(unknown))}")

    old_index = {}
    if (OUT / "index.json").exists():
        old_index = {e["id"]: e for e in json.loads((OUT / "index.json").read_text(encoding="utf-8"))["packs"]}

    index = []
    for pack in PACKS:
        old = read_pack(pack["id"])
        old_version = (old or {}).get("version", 1)
        old_cover = old_index.get(pack["id"], {}).get("cover", "")
        if pack["id"] in only:
            films, popular = build(pack, old, playlists, args.refresh)
            if old is None:
                version = 1
            elif [f["id"] for f in old["films"]] == [f["id"] for f in films] and all("overview" in f for f in old["films"]):
                version = old_version
            else:
                version = old_version + 1
            print(f"{pack['id']} : {len(films)} films, version {version}        ")
            data = {"id": pack["id"], "version": version, "name": pack["name"],
                    "description": pack["description"], "films": films}
            (OUT / f"{pack['id']}.json").write_text(json.dumps(data, ensure_ascii=False, indent=1),
                                                    encoding="utf-8")
            cover = cover_of(pack, films, popular, old_cover)
        else:
            if old is None:
                raise SystemExit(f"{pack['id']} : pas encore construit (relancer sans --only ou avec ce pack)")
            data = old
            if "version" not in data:  # fichier d'avant les versions : on ajoute la version 1
                data = {"id": old["id"], "version": 1, **{k: v for k, v in old.items() if k != "id"}}
                (OUT / f"{pack['id']}.json").write_text(json.dumps(data, ensure_ascii=False, indent=1),
                                                        encoding="utf-8")
            cover = cover_of(pack, data["films"], None, old_cover)
        index.append({"id": data["id"], "version": data["version"], "name": data["name"],
                      "description": data["description"], "films": len(data["films"]), "cover": cover})
    (OUT / "index.json").write_text(json.dumps({"version": 1, "packs": index}, ensure_ascii=False, indent=1),
                                    encoding="utf-8")
    print(f"index.json écrit ({len(index)} packs)")


if __name__ == "__main__":
    main()
