<p align="center">
  <img src="../images/banner.png" alt="Proscenium" width="760" />
</p>

<p align="center">
  <a href="../README.md"><img src="../images/flags/flag-gb.svg" alt="" width="18" height="12" /> English</a>
  /
  <a href="README.de.md"><img src="../images/flags/flag-de.svg" alt="" width="18" height="12" /> Deutsch</a>
  /
  <a href="README.it.md"><img src="../images/flags/flag-it.svg" alt="" width="18" height="12" /> Italiano</a>
  /
  <a href="README.es.md"><img src="../images/flags/flag-es.svg" alt="" width="18" height="12" /> Español</a>
  /
  <a href="README.sv.md"><img src="../images/flags/flag-se.svg" alt="" width="18" height="12" /> Svenska</a>
  /
  <a href="README.fr.md"><img src="../images/flags/flag-fr.svg" alt="" width="18" height="12" /> Français</a>
</p>

# Proscenium

Proscenium ersetzt den Startbildschirm Ihres Android-TVs durch einen Kinosaal: große, vollflächige
Filmbilder, die sich sanft bewegen, eine Leiste aus Glas für Ihre Apps, Uhrzeit, Wetter und ein
Kinomodus, der übernimmt, wenn die Fernbedienung eine Weile liegen bleibt.

Es läuft auf Android-TV- und Google-TV-Fernsehern und -Boxen (Android 7.1 oder neuer) und lässt sich
komplett mit der Fernbedienung bedienen.

<p align="center">
  <a href="https://github.com/Karlos-fr/proscenium/blob/main/video/proscenium-demo.mp4"><img src="../images/video-thumbnail.jpg" alt="Demo-Video (1:29)" width="760" /></a>
  <br /><sub>▶ Demo-Video (1:29)</sub>
</p>

<p align="center">
  <img src="../images/screenshots/home.jpg" alt="" width="380" />
  <img src="../images/screenshots/cinema.jpg" alt="" width="380" />
  <br />
  <img src="../images/screenshots/settings.jpg" alt="" width="380" />
  <img src="../images/screenshots/packs.jpg" alt="" width="380" />
  <br /><sub>Startbildschirm · Kinomodus · Einstellungen und Glas · Hintergrundpakete</sub>
</p>

## Installation

1. Laden Sie `Proscenium.apk` auf der Seite [Releases](https://github.com/Karlos-fr/proscenium/releases)
   herunter und installieren Sie die Datei auf dem Fernseher (per USB-Stick und Dateimanager oder
   mit einer App wie „Send files to TV“).
2. Öffnen Sie Proscenium. Beim ersten Mal fragt Android Sie eventuell, welchen Startbildschirm Sie
   verwenden möchten: Wählen Sie Proscenium.
3. Bei Google-TV-Fernsehern (Sony, TCL, Chromecast …) führt die Home-Taste der Fernbedienung immer
   zum Google-Startbildschirm. Damit sie Proscenium öffnet, aktivieren Sie den dafür vorgesehenen
   Dienst: **Einstellungen → System → Home-Tasten-Dienst**, was die Bedienungshilfen-Seite von
   Android öffnet.

Die Einstellungen öffnen Sie, indem Sie das Banner mit Uhrzeit und Wetter auswählen (Pfeil nach oben,
dann OK) oder über das Menü einer App in der Leiste.

Beim ersten Start hilft ein Bildschirm **Erste Schritte** bei der Wahl der Sprache, des Orts für
das Wetter, eines Hintergrundpakets und des Home-Tasten-Dienstes. Er bleibt danach unter
**Einstellungen → System → Erste Schritte** erreichbar.

### Updates

Proscenium sucht einmal am Tag auf GitHub nach einer neuen Version und gibt dir Bescheid. Zum
Installieren öffne **Einstellungen → System → Update** und drücke OK: Die neue Version wird
heruntergeladen und Android bittet um Bestätigung. Deine Einstellungen bleiben erhalten. Beim ersten
Mal fragt Android, ob Proscenium Apps installieren darf.

Updates lassen sich auch mit [Obtainium](https://github.com/ImranR98/Obtainium) verfolgen: `https://github.com/Karlos-fr/proscenium` als Quelle hinzufügen.

> **Du hast Version 1.0?** Version 1.1 ist mit einem neuen Schlüssel signiert und lässt sich nicht
> über 1.0 installieren: Deinstalliere Proscenium und installiere dann 1.1. Einstellungen und
> Playlists beginnen von vorn (Pakete sind mit einem Klick wieder installiert). Das ist nur einmal
> nötig: Spätere Versionen werden direkt über 1.1 installiert.

## Der Startbildschirm

- **Vollflächige Filmhintergründe**, die alle paar Sekunden wechseln, mit einem Übergang Ihrer Wahl
  (Überblenden, Gleiten …).
- **Ken-Burns-Effekt**: Das Bild zoomt und driftet langsam, wie in einer Dokumentation. Stärke und
  Dauer sind einstellbar.
- **Farben, die dem Bild folgen**: Die dominante Farbe jedes Hintergrunds färbt das Glas, das Licht
  und die Symbole. Oder eine feste Farbe, wenn Sie das bevorzugen.
- **Lesbarkeit**: Eine leichte Abdunklung erscheint nur dort, wo Text schwer zu lesen wäre.
- **Tiefe**: Der Hintergrund verschiebt sich leicht, wenn sich die Auswahl bewegt, und erscheint
  erst unscharf, dann scharf, wenn Sie zum Startbildschirm zurückkehren.
- **Uhrzeit und Datum**, groß dargestellt, mit rollenden Ziffern. 24- oder 12-Stunden-Format,
  Größe einstellbar.
- **Ein Info-Banner**: belegter Speicher, Prozessor und das Wetter Ihrer Stadt mit animiertem Symbol
  (drehende Sonne, Regen, Schnee, Gewitter, Nebel), Temperatur, Wind, Windrichtung und Böen. Jedes
  Element lässt sich ausblenden.

## Lebendiges Wetter

Das Wetter kann auf dem Bild lebendig werden, wie in einer Filmszene:

- **Sonne**: Lichtstrahlen dringen durch die hellen Stellen des Bildes, mit einem warmen Lichthof und
  Staub, der im Licht glitzert. Auf einem dunklen Bild übernehmen sanfte Strahlen von der Sonne. Die
  Sonne wandert langsam, und ab und zu dämpfen Wolken das Licht.
- **Regen**: Tropfen landen auf dem Bildschirm wie auf einer Fensterscheibe, wachsen und rinnen dann
  mit einer nassen Spur hinab. Der Wind Ihrer Stadt treibt sie zur Seite, mit plötzlichen Böen.
- **Blitze**: echte verzweigte Blitze, die das Bild erhellen.
- **Schnee**: Flocken fallen, und die, die den Bildschirm berühren, schmelzen zu Tropfen.
- **Nebel**: Nebelschwaden, die treiben, wirbeln, sich verdichten und auflösen.

**Echtes Wetter** zeigt das Wetter Ihrer Stadt (ein Gewitter bringt Regen und Blitze, Nieselregen
bringt Regen und Nebel…). **Benutzerdefiniert** kombiniert die gewünschten Ebenen, mit dem Wind
Ihrer Wahl.
**Zufällig** lost ab und zu eine neue Mischung aus, und **Passend zum Hintergrund** wählt eine für
jeden Hintergrund nach den Genres des Films, seinem Inhalt und dem Licht des Bildes (Schnee für
einen Winterfilm, Nebel für eine Geistergeschichte, Sonne für einen Western…). In allen Modi
addieren sich die Ebenen, und das Wetter wechselt sanft von einem Hintergrund zum nächsten. Jedes Wetter hat seine eigene Seite unter **Einstellungen → Startseite →
Wettereinstellungen**: Voreinstellungen, jede Einstellung live auf dem Bild, Zufallswerte und
Rückkehr zu den ursprünglichen Einstellungen.

## Die App-Leiste

- **Ein Karussell Ihrer Apps** mit ihren TV-Bannern. Sie bestimmen, welche Apps erscheinen und in
  welcher Reihenfolge.
- **Die ausgewählte App** wird größer, neigt sich leicht, bekommt eine Spiegelung und ein farbiges
  Licht um sich herum. Dieses Licht übernimmt die Farbe ihres Logos und wogt sanft rundherum.
- **Realistisches Glas**: Die Leiste bricht das Bild, das dahinter vorbeizieht, mit Voreinstellungen
  (Klar, Dezent, Milchig, Stark) und Effekten wie ruhigem Wasser, Flüssigkeit oder Regen.
- **Verknüpfungen zu HDMI-Eingängen**: Eine Konsole, ein Blu-ray-Player oder eine Box am Fernseher
  bekommt eine eigene Kachel in der Leiste, mit Namen und einem Symbol aus 24 Konsolen
  (PlayStation, Xbox, Nintendo, Sega …). Mit OK wechseln Sie direkt zum richtigen Eingang.
- **Alles ist einstellbar**: Anzahl sichtbarer Apps, Größe und Abstand der Kacheln, durchgehendes
  Band oder schwebende Leiste, Scrollgeschwindigkeit, Rücksprung zum Anfang nach der letzten App.

## Der Kinomodus und der Bildschirmschoner

Wenn die Fernbedienung eine Weile liegen bleibt, wechselt der Startbildschirm in den **Kinomodus**:
Die Oberfläche blendet sich aus, schwarze Balken beschneiden das Bild im Kinoformat (2:1 oder
Scope 2,39:1), und der Filmtitel erscheint, mit seinem offiziellen Logo, sofern vorhanden, und dem
Erscheinungsjahr. Wartezeit, Balken, Titel und Banner sind einstellbar.

Die Titelkarte kann auch die **Angaben zum Film** zeigen: Jahr, Laufzeit und Genres, die
TMDB-Bewertung (Sterne, ein Prozentring oder eine Note von 10) und eine kurze oder vollständige
Inhaltsangabe, die langsam scrollt. Die Pakete liefern diese Angaben in sechs Sprachen.

Proscenium kann auch als **Android-Bildschirmschoner** dienen: Wählen Sie ihn in den
Android-Einstellungen unter „Bildschirmschoner“ aus.

Auf Google TV listet Android nur die Bildschirmschoner von Google auf. Wählen Sie Proscenium
einmalig von einem Computer aus, mit [adb](https://developer.android.com/tools/adb) mit dem
Fernseher verbunden: `adb shell settings put secure screensaver_components fr.proscenium.launcher/.CinemaDream`. **Einstellungen → Kinomodus → Bildschirmschoner** schaltet ihn ein
oder aus.

**Einstellungen → Kinomodus → Bildschirm eingeschaltet lassen** verhindert, dass Android seinen
Bildschirmschoner startet oder in den Ruhezustand geht, solange Proscenium angezeigt wird: Der
Kinomodus läuft dann ohne Ende weiter (das verbraucht mehr Strom, und manche Fernseher gehen nach
einigen Stunden trotzdem in den Standby).

## Ihre Hintergründe: Playlists und Pakete

Die Bilder des Startbildschirms stammen entweder von den **Bing-Fotos des Tages** oder aus **Ihren
Playlists** mit Filmen.

- **Hintergrundpakete**: fertige Playlists zu bestimmten Themen (20 Pakete: Star Wars,
  Studio Ghibli, Christopher Nolan, Horror, die 80er, James Bond, Western …), die Sie mit einem Klick installieren unter
  **Einstellungen → Playlists → Pakete durchsuchen**. Dafür ist kein TMDB-Schlüssel nötig. Titel und
  Logos erscheinen in Ihrer Sprache, sofern verfügbar.
- **Eigene Playlists**: Suchen Sie einen Film per Titel, mit der Tastatur oder per Sprache, oder
  importieren Sie auf einen Schlag die beliebten oder angesagten Filme. Wählen Sie für jeden Film
  den oder die Hintergründe, die Ihnen gefallen, und das anzuzeigende Logo.
- **Aktuelle Trends**: eine Playlist, die sich jeden Tag von selbst mit den angesagten Filmen
  aktualisiert.
- Mehrere Playlists können gleichzeitig aktiv sein: Ihre Hintergründe werden gemischt.
- **Paket-Updates**: Werden einem Paket Filme hinzugefügt, zeigt es „Update verfügbar“ und
  **Installierte Pakete aktualisieren** lädt die neuen Filme herunter. Jeder kann ein Paket
  vorschlagen: siehe [packs/README.md](../packs/README.md).

Die Bilder stammen von [The Movie Database (TMDB)](https://www.themoviedb.org). Sie können
ausschließlich Hintergründe ohne Text und in Full HD anzeigen lassen.

## Der TMDB-Schlüssel

Die Pakete funktionieren ohne Schlüssel. Um selbst nach Filmen zu suchen, brauchen Sie einen
kostenlosen TMDB-API-Schlüssel: Erstellen Sie ein Konto auf
[themoviedb.org](https://www.themoviedb.org/signup) und fordern Sie dann in den Einstellungen Ihres
Kontos unter „API“ einen Schlüssel an.

Sie müssen ihn nicht mit der Fernbedienung eintippen: **Einstellungen → Filme (TMDB) → Schlüssel per
Telefon eingeben** zeigt einen QR-Code an. Scannen Sie ihn mit Ihrem Telefon (im selben Netzwerk wie
der Fernseher) und fügen Sie den Schlüssel auf der Seite ein, die sich öffnet: Der Fernseher prüft
ihn und speichert ihn.

## Web-Fernbedienung

**Einstellungen → System → Web-Fernbedienung** bringt alle Einstellungen auf Ihr Handy oder Ihren
Computer: einschalten, dann den QR-Code scannen, den der Fernseher zeigt (das Handy muss im selben
WLAN sein). Die Seite hat dieselben Registerkarten wie das Einstellungsfenster (außer dem Glas),
funktioniert auf dem Handy und im Browser eines Computers und zeigt den laufenden Film mit einer
Taste für den nächsten Hintergrund. Dort wählen Sie die angezeigten Playlists, installieren oder
entfernen Pakete, ordnen die Apps der Leiste, geben die Wetterstadt oder den TMDB-Schlüssel ein und
stellen jedes Wetter ein, während es live auf dem Fernseher erscheint. Die Seite aktualisiert sich
von selbst, wenn sich eine Einstellung am Fernseher ändert. Die Adresse bleibt gleich:
**http://proscenium.local:8770** auf iPhone, Mac oder Computer, oder die Adresse des Fernsehers
unter dem QR-Code; speichern Sie sie als Lesezeichen oder auf dem Startbildschirm. Der Name lässt
sich ändern, und der Browser kann ein Passwort abfragen (empfohlen, wenn andere Ihr WLAN nutzen).
Nur Geräte in Ihrem Heimnetz erreichen die Seite. Ohne **Feste Adresse** ist die Adresse geheim und
bei jedem Start von Proscenium neu.

## Und außerdem

- **Sechs Sprachen**: Französisch, Englisch, Deutsch, Italienisch, Spanisch und Schwedisch.
  Standardmäßig die Sprache des Fernsehers.
- **Dezente Oberflächentöne**, Lautstärke einstellbar.
- **Sparmodus**: schaltet mit einem Schlag die rechenintensivsten Effekte ab, für kleine Boxen.
- **Sicherung und Wiederherstellung** aller Einstellungen.

## FAQ

**Funktioniert es auf Fire TV?** Wahrscheinlich als App, aber nicht als Startbildschirm: Amazon
verhindert, dass sein Startbildschirm ersetzt wird, die Home-Taste führt also immer zu Amazon zurück.
Nicht getestet. Die neuesten Fire-TV-Modelle mit Vega OS können überhaupt keine Android-Apps
installieren.

**Warum öffnet die Home-Taste noch den Google-Startbildschirm?** Auf Google TV steuert Google diese
Taste. Schalten Sie den dafür vorgesehenen Dienst ein (**Einstellungen → System → Home-Tasten-Dienst**,
dann Proscenium auf der Bedienungshilfen-Seite von Android): Die Home-Taste öffnet dann Proscenium.

**Der Dienst der Home-Taste lässt sich nicht einschalten.** Ab Android 13 wird die Bedienungshilfe
für Apps außerhalb des Play Store gesperrt („eingeschränkte Einstellung“). Öffnen Sie
**Einstellungen → System → Dienst lässt sich nicht einschalten?**, das die App-Infos von Proscenium
zeigt, wählen Sie **⋮ → Eingeschränkte Einstellungen zulassen** und schalten Sie den Dienst erneut
ein. Hat der Fernseher dieses Menü nicht, führen Sie einmal von einem Computer mit adb aus: `adb shell appops set fr.proscenium.launcher ACCESS_RESTRICTED_SETTINGS allow`.

**Warum ein TMDB-Schlüssel?** Er wird nur gebraucht, um selbst nach Filmen zu suchen. Pakete,
Bing-Fotos und Wetter funktionieren ohne. TMDB vergibt Schlüssel für private Projekte kostenlos, und
jeder Nutzer hat seinen eigenen: Proscenium teilt keinen gemeinsamen Schlüssel.

**Wie viele Daten verbraucht es?** Etwa 0,5 MB pro Film (ein Hintergrund von etwa 400 KB und sein
Logo), einmal heruntergeladen und dann auf dem Fernseher gespeichert: etwa 20 MB für ein Paket mit
40 Filmen. Danach nutzen nur neue Filme, das Bing-Foto des Tages (falls gewählt), das Wetter (wenige
KB) und eine tägliche Update-Prüfung das Netz.

## Danksagungen

<img src="../images/tmdb-logo.svg" alt="TMDB" width="160" />

- Filme, Hintergründe und Logos: [The Movie Database (TMDB)](https://www.themoviedb.org). Dieses
  Produkt verwendet die TMDB-API, wird aber von TMDB weder unterstützt noch zertifiziert. Die Bilder
  bleiben Eigentum ihrer jeweiligen Rechteinhaber.
- Fotos des Tages: Bing. Wetter: [Open-Meteo](https://open-meteo.com).
- Code unter der [MIT-Lizenz](../LICENSE).
