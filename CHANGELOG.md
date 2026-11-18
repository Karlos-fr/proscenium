# Changelog

All notable changes to Proscenium are listed here, newest first.

## [1.1] — 2026-11-18

Download: [Proscenium 1.1](https://github.com/Karlos-fr/proscenium/releases/tag/v1.1).

> **Coming from 1.0?** This version is signed with a new key and cannot be installed over 1.0:
> uninstall Proscenium, then install 1.1. Settings and playlists start from scratch (packs are
> reinstalled in one click). This happens only once.

### Added

- Updates: Proscenium checks GitHub for a new version once a day and shows a short message when one
  comes out. Settings → System → Update downloads it and hands it to Android's installer, which asks
  for confirmation. Settings are kept. The daily check can be turned off.
- 17 new backdrop packs, 20 in all: Star Wars, Christopher Nolan, horror, the 80s, the 90s,
  animation (beyond Disney and Ghibli), Harry Potter, Middle-earth, James Bond, science fiction,
  Steven Spielberg, Quentin Tarantino, DC, westerns, Denis Villeneuve, Wes Anderson and adventure
  sagas.
- Pack updates: each pack has a version. When films are added to a pack, it shows "update
  available" and "Update installed packs" downloads only the new films.
- Anyone can suggest a pack by pull request or issue: see packs/README.md.
- Getting started: on first launch, a screen helps choose the language, the town for the weather, a
  backdrop pack and the Home button service. It can be reopened from Settings → System.
- Screensaver option (Settings → Cinema mode): offer Proscenium as Android's screensaver or not, and
  a shortcut to Android's screensaver settings (ambient mode on Google TV, where a third-party
  screensaver is chosen once with adb, see the README).
- TMDB logo next to the TMDB notice (Films (TMDB) tab and System → About), as TMDB's terms ask.
- README: demo video, screenshots and a short FAQ (Fire TV, Home button, TMDB key, data use).
- Obtainium support (the GitHub releases page is the source) and store metadata for IzzyOnDroid.
- MIT licence.

### Changed

- New release signing key (CN=Proscenium). Later versions install over 1.1 directly.

## [1.0] — 2026-11-17

First public release: a cinema-style home screen for Android TV / Google TV (Android 7.1 or later),
driven entirely with the remote. Download: [Proscenium 1.0](https://github.com/Karlos-fr/proscenium/releases/tag/v1.0).

### Added

- Home screen: full-screen film backdrops changing every few seconds, with a transition to choose
  (blur and zoom, fade, slide, none) and a slow Ken Burns zoom and drift, both adjustable.
- Colours that follow the picture: the dominant colour of each backdrop tints the glass, the light
  and the icons, or a fixed colour. A light shading appears only where text would be hard to read.
- Depth effects: the backdrop shifts slightly when the selection moves, and comes in blurred then
  sharp when you return to the home screen.
- Clock and date with rolling digits, 24-hour or 12-hour, with adjustable sizes.
- Information header: memory, processor, and the weather of your town with an animated icon (sun,
  clouds, rain, snow, storm, fog, moon), the temperature, the wind, its direction and gusts. Each
  item can be hidden; °C or °F.
- App bar: a carousel of your apps with their TV banners. Choose and order the apps; the selected
  one grows, tilts, gets a shine and a coloured glow in the colour of its logo that ripples gently
  around it. Full-width band or floating bar, slim band with the selected app standing out of it,
  number of visible apps, tile size and spacing, scrolling speed, wrap-around.
- Refractive glass on the app bar, the settings and the menus, with presets (Clear, Subtle,
  Frosted, Strong), calm water, liquid and rain effects, and every setting tunable with a live
  preview.
- Shortcuts to HDMI inputs: a console, a Blu-ray player or a box gets its own tile in the bar, with
  a name and one of 24 console icons. OK switches the TV straight to that input.
- App menu (long press on OK, or the remote's Menu key): move, edit a shortcut, remove, add an app,
  bar settings. The "add an app" list shows each app's logo.
- Cinema mode: after a set delay without the remote, the interface fades away, letterbox bars frame
  the picture (2:1 or Scope 2.39:1) and the film's title appears, with its official logo and year.
- Android screensaver ("Proscenium" in Android's Screen saver settings), with the same backdrops,
  title card and clock.
- Backdrops from Bing's photo of the day or from your playlists of films; several playlists can be
  active at once and their backdrops are mixed.
- Backdrop packs: ready-made themed playlists (Disney & Pixar, Studio Ghibli, Classics & Marvel),
  installed in one click from Settings → Playlists → Browse packs. No TMDB key needed: a pack only
  lists films and the chosen images, which are downloaded straight from TMDB. Titles and logos in
  your language when they exist.
- Your own playlists: search a film by title (keyboard or voice), import the popular or trending
  films, pick the exact backdrops and the logo of each film. A "Trending now" playlist renews itself
  every day.
- TMDB key entry from the phone: a QR code opens a small page served by the TV on the local network;
  paste the key, the TV checks it with TMDB and saves it.
- Six languages: English, French, German, Italian, Spanish and Swedish, following the TV's language
  by default. The language of film titles and logos can be set separately.
- Interface sounds, economy mode (turns off the most demanding effects), backup and restore of all
  settings, "Home button service" so the remote's Home button opens Proscenium on Google TV sets.

### Known limitations

- Tested on a Sony Bravia (Android 14) only. Other TVs and boxes should work but have not been tried.
- On Google TV sets, the remote's Home button opens Proscenium only once the Home button service is
  turned on in Android's accessibility settings.
- Some films of the packs have no logo in German, Italian, Spanish or Swedish on TMDB: the English
  logo is used instead.
- The TMDB logo is not yet shown in the credits (only the TMDB notice).
