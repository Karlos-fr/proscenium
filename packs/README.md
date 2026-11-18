# Proscenium backdrop packs

A **pack** is a themed playlist of films (Studio Ghibli, Star Wars, westerns…) that Proscenium can
download from this repository and show as backdrops on the TV.

**No image is stored here.** A pack only holds TMDB references: movie ids and TMDB image paths
(backdrops and logos). The app downloads the images itself from TMDB (`https://image.tmdb.org/t/p/...`).

## Files

### `index.json`

The catalogue the app reads first:

```json
{
 "version": 1,
 "packs": [
  {
   "id": "ghibli",
   "version": 1,
   "name": {"fr": "Studio Ghibli", "en": "Studio Ghibli", "...": "..."},
   "description": {"fr": "...", "en": "...", "de": "...", "it": "...", "es": "...", "sv": "..."},
   "films": 25,
   "cover": "/z2jsAbXWGdJvadhovznjGg33VND.jpg"
  }
 ]
}
```

- top-level `version`: version of the index format.
- `id`: pack id (lowercase, dashes), also the name of the pack file `<id>.json`.
- `version`: version of the pack (see below).
- `name`, `description`: one string per language (`fr`, `en`, `de`, `it`, `es`, `sv`).
- `films`: number of films; `cover`: TMDB path of the backdrop used as the pack's tile.

### `<id>.json`

```json
{
 "id": "ghibli",
 "version": 1,
 "name": {"...": "..."},
 "description": {"...": "..."},
 "films": [
  {
   "id": 81,
   "year": "1984",
   "title": {"fr": "...", "en": "...", "de": "...", "it": "...", "es": "...", "sv": "..."},
   "backdrops": ["/z2jsAbXWGdJvadhovznjGg33VND.jpg"],
   "logo": {"fr": "/....png", "en": "/....png"}
  }
 ]
}
```

- `id`: TMDB movie id; `year`: release year; `title`: title in each language.
- `backdrops`: one or more TMDB backdrop paths (every film has at least one).
- `logo`: TMDB logo path per language, when TMDB has one.

### Versions

Each pack has an integer `version`, set automatically by `build_packs.py`: it stays the same as long
as the pack's list of films is unchanged, and goes up by one when films are added, removed or
reordered. A new pack starts at 1. The app can use it to notice that a pack it installed was updated.

## Proposing a pack (pull request)

1. Add an entry to the `PACKS` list in `packs/build_packs.py`. Give it an `id`, a `name` and a short
   `description` in the six languages, and one or more sources:

   ```python
   # Films from TMDB collections
   {"id": "harry-potter", "collections": [1241, 435259], "name": {...}, "description": {...}},

   # Films directed by TMDB people
   {"id": "nolan", "directors": [525], "name": {...}, "description": {...}},

   # A TMDB /discover/movie search (any discover parameter), keeping the first N films
   {"id": "westerns",
    "discover": {"with_genres": "37", "sort_by": "vote_count.desc", "vote_average.gte": "7"},
    "limit": 30, "exclude": [338766], "name": {...}, "description": {...}},

   # A hand-picked list of TMDB movie ids
   {"id": "my-pack", "include": [11, 1891, 1892], "name": {...}, "description": {...}},
   ```

   Optional keys: `exclude` (movie ids to drop), `titles` (fix a wrong TMDB title:
   `{movie_id: {"fr": "..."}}`), `cover` (movie id whose backdrop is the pack tile; default: the most
   popular film). Only feature films (60 min or more) that are released and have a backdrop are kept,
   sorted by release date. Documentaries and TV movies are skipped in director filmographies.

2. Put your own TMDB API key (v3 key or v4 read token) in a `tmdb.key` file at the root of the
   repository. It is ignored by git: **never commit it**.

3. Build your pack only (it makes many API calls, this can take a few minutes):

   ```
   python packs/build_packs.py --only my-pack
   ```

   This writes `packs/my-pack.json` and updates `packs/index.json` (which always lists every pack).

4. Check the result: the titles, that there is no off-topic film (documentaries, making-ofs, concerts…;
   add them to `exclude`), and that the cover looks good.

5. Open a pull request with your change to `build_packs.py`, the new `packs/<id>.json` and the updated
   `packs/index.json`.

Don't want to run the script? Simply open an issue with the pack name and the list of films (titles
and years, or TMDB links), and we'll build it.
