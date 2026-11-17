# Proscenium

Écran d'accueil (launcher) pour Android TV, façon salle de cinéma : fonds de films en plein écran
avec effet Ken Burns, barre d'applications en verre, horloge, météo, raccourcis vers les entrées HDMI,
économiseur d'écran « mode cinéma ». Interface en français, anglais, allemand, italien, espagnol et suédois.

## Compiler

Sans Gradle : `launcher/build.ps1` enchaîne aapt2, javac, d8, zipalign et apksigner
(SDK Android dans `android-sdk/`, non publié).

```powershell
cd launcher
.\build.ps1
```

`python launcher/check-strings.py` vérifie que chaque texte existe dans toutes les langues.

## Packs de fonds

Le dossier [`packs/`](packs) contient des playlists thématiques prêtes à installer depuis l'appli
(Réglages → Playlists → Packs de fonds). Un pack ne contient **aucune image** : seulement les numéros
TMDB des films, les chemins des fonds et logos choisis et les titres dans chaque langue. L'appli
télécharge les images directement depuis TMDB.

`python packs/build_packs.py [playlists.json]` reconstruit les packs (clé TMDB dans `tmdb.key`, non publiée).

## Clé TMDB

Les fonctions de recherche de films demandent une clé d'API TMDB personnelle (gratuite). Elle se saisit
sur la TV, ou depuis le téléphone : Réglages → Films → « Saisir la clé depuis le téléphone » affiche
un QR code vers une petite page servie par la TV sur le réseau local.

## Crédits

- Films, fonds et logos : [The Movie Database (TMDB)](https://www.themoviedb.org).
  Ce produit utilise l'API TMDB mais n'est ni approuvé ni certifié par TMDB.
  Les images restent la propriété de leurs ayants droit.
- Photos du jour : Bing. Météo : [Open-Meteo](https://open-meteo.com).
