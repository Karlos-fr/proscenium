<p align="center">
  <img src="images/banner.png" alt="Proscenium" width="760" />
</p>

<p align="center">
  <a href="README.md"><img src="images/flags/flag-gb.svg" alt="" width="18" height="12" /> English</a>
  /
  <a href="doc/README.de.md"><img src="images/flags/flag-de.svg" alt="" width="18" height="12" /> Deutsch</a>
  /
  <a href="doc/README.it.md"><img src="images/flags/flag-it.svg" alt="" width="18" height="12" /> Italiano</a>
  /
  <a href="doc/README.es.md"><img src="images/flags/flag-es.svg" alt="" width="18" height="12" /> Español</a>
  /
  <a href="doc/README.sv.md"><img src="images/flags/flag-se.svg" alt="" width="18" height="12" /> Svenska</a>
  /
  <a href="doc/README.fr.md"><img src="images/flags/flag-fr.svg" alt="" width="18" height="12" /> Français</a>
</p>

# Proscenium

Proscenium turns the home screen of your Android TV into a movie theatre: large film images
filling the screen and moving gently, a glass app bar, the time, the weather, and a cinema mode
that takes over when the remote is put down.

It runs on Android TV / Google TV televisions and boxes (Android 7.1 or later) and is driven
entirely with the remote.

## Installation

1. Download `Proscenium.apk` from the [Releases](https://github.com/Karlos-fr/proscenium/releases)
   page and install it on the TV (with a USB stick and a file manager, or an app such as
   "Send files to TV").
2. Open Proscenium. The first time, Android may ask you to choose the home screen: pick Proscenium.
3. On Google TV sets (Sony, TCL, Chromecast…), the Home button of the remote always brings back the
   Google home screen. To make it open Proscenium, turn on the service made for this:
   **Settings → System → Home button service**, which opens Android's accessibility page.

To open the settings, select the time and weather header (arrow up, then OK), or use the menu of
an app in the bar.

The first time, a **Getting started** screen helps you choose the language, the town for the
weather, a backdrop pack and the Home button service. It is still there later, in
**Settings → System → Getting started**.

### Updates

Proscenium checks GitHub for a new version once a day and lets you know. To install it, open
**Settings → System → Update** and press OK: the new version is downloaded and Android asks you to
confirm. Your settings are kept. The first time, Android asks you to allow Proscenium to install apps.

You can also follow updates with [Obtainium](https://github.com/ImranR98/Obtainium): add `https://github.com/Karlos-fr/proscenium` as the app's source.

> **Coming from version 1.0?** Version 1.1 is signed with a new key, so it cannot be installed over
> 1.0: uninstall Proscenium, then install 1.1. Settings and playlists start from scratch (packs are
> reinstalled in one click). This happens only once: later versions install over 1.1 directly.

## The home screen

- **Full-screen film backdrops**, changing every few seconds, with the transition of your choice
  (blur and zoom, fade, slide).
- **Ken Burns effect**: the picture slowly zooms and drifts, like in a documentary. Strength and
  duration can be adjusted.
- **Colours that follow the picture**: the dominant colour of each backdrop tints the glass, the
  light and the icons. Or a fixed colour, if you prefer.
- **Readability**: a slight shading appears only where text would be hard to read.
- **Depth**: the backdrop shifts a little when the selection moves, and comes in blurred then sharp
  when you return to the home screen.
- **The time and date**, large, with rolling digits. 24-hour or 12-hour format, adjustable size.
- **An information header**: memory used, processor, and the weather in your town with an animated
  icon (spinning sun, rain, snow, storm, fog), the temperature, the wind, its direction and gusts.
  Each item can be hidden.

## The app bar

- **A carousel of your apps**, with their TV banners. Choose which ones appear and in what order.
- **The selected app** grows, tilts slightly, gets a shine and a coloured light around it. That
  light takes the colour of its logo and ripples gently all around.
- **Realistic glass**: the bar refracts the picture behind it, with presets (Clear, Subtle, Frosted,
  Strong) and calm water, liquid or rain effects.
- **Shortcuts to HDMI inputs**: a console, a Blu-ray player or a box plugged into the TV gets its
  own tile in the bar, with a name and an icon chosen among 24 consoles (PlayStation, Xbox,
  Nintendo, Sega…). OK switches straight to the right input.
- **Everything can be tuned**: number of visible apps, tile size and spacing, full-width band or
  floating bar, scrolling speed, wrapping back to the first app after the last one.

## Cinema mode and screensaver

When the remote stays down for a while, the home screen switches to **cinema mode**: the interface
fades away, black bars frame the picture in a cinema format (2:1 or Scope 2.39:1), and the film's
title appears, with its official logo when there is one and its year. The delay, the bars, the
title and the header can all be adjusted.

Proscenium can also be used as the **Android screensaver**: pick it in Android's settings, under
Screen saver.

On Google TV, Android only lists Google's own screensavers. Choose Proscenium once from a
computer, with [adb](https://developer.android.com/tools/adb) connected to the TV:
`adb shell settings put secure screensaver_components fr.proscenium.launcher/.CinemaDream`. **Settings → Cinema mode → Screensaver** turns it on or off.

## Your backdrops: playlists and packs

The home screen pictures come either from **Bing's photos of the day** or from **your playlists**
of films.

- **Background packs**: ready-made themed playlists (Disney & Pixar, Studio Ghibli,
  Classics & Marvel…), installed in one click from **Settings → Playlists → Browse packs**. They
  need no TMDB key. Titles and logos are in your language when they exist.
- **Your own playlists**: search for a film by its title, with the keyboard or by voice, or import
  the popular or trending films at once. For each film, choose the backdrops you like and the logo
  to show.
- **Trending now**: a playlist that updates itself every day with the films of the moment.
- Several playlists can be active at the same time: their backdrops are mixed.
- **Pack updates**: when films are added to a pack, the pack shows "update available" and
  **Update installed packs** downloads the new films. Anyone can suggest a pack: see
  [packs/README.md](packs/README.md).

The pictures come from [The Movie Database (TMDB)](https://www.themoviedb.org). You can show only
backdrops without text and in Full HD.

## The TMDB key

Packs work without a key. To search for films yourself, you need a TMDB API key, which is free:
create an account on [themoviedb.org](https://www.themoviedb.org/signup), then request a key in your
account settings, under API.

No need to type it with the remote: **Settings → Films (TMDB) → Enter the key from your phone**
shows a QR code. Scan it with your phone (on the same network as the TV) and paste the key in the
page that opens: the TV checks it and saves it.

## And also

- **Six languages**: English, French, German, Italian, Spanish and Swedish. By default, the TV's
  language.
- **Discreet interface sounds**, with adjustable volume.
- **Economy mode**: turns off the most demanding effects at once, for small boxes.
- **Backup and restore** of all settings.

## Credits

- Films, backdrops and logos: [The Movie Database (TMDB)](https://www.themoviedb.org). This product
  uses the TMDB API but is not endorsed or certified by TMDB. The pictures remain the property of
  their rights holders.
- Photos of the day: Bing. Weather: [Open-Meteo](https://open-meteo.com).
- Code released under the [MIT licence](LICENSE).

What's new: [CHANGELOG.md](CHANGELOG.md). To build the app or the packs yourself: [doc/BUILDING.md](doc/BUILDING.md) (in French).
