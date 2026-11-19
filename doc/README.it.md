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

Proscenium sostituisce la schermata Home della tua TV Android con una sala cinematografica: grandi
immagini di film a tutto schermo che si muovono dolcemente, una barra di vetro per le app, l'ora, il
meteo e una modalità cinema che subentra quando il telecomando resta fermo.

Funziona su TV e box Android TV / Google TV (Android 7.1 o successivo) e si controlla interamente con
il telecomando.

<p align="center">
  <a href="https://github.com/Karlos-fr/proscenium/blob/main/video/proscenium-demo.mp4"><img src="../images/video-thumbnail.jpg" alt="Video dimostrativo (55 s)" width="760" /></a>
  <br /><sub>▶ Video dimostrativo (55 s)</sub>
</p>

<p align="center">
  <img src="../images/screenshots/home.jpg" alt="" width="380" />
  <img src="../images/screenshots/cinema.jpg" alt="" width="380" />
  <br />
  <img src="../images/screenshots/settings.jpg" alt="" width="380" />
  <img src="../images/screenshots/packs.jpg" alt="" width="380" />
  <br /><sub>Home · Modalità cinema · Impostazioni e vetro · Pacchetti di sfondi</sub>
</p>

## Installazione

1. Scarica `Proscenium.apk` dalla pagina [Releases](https://github.com/Karlos-fr/proscenium/releases)
   e installalo sulla TV (tramite una chiavetta USB e un gestore di file, oppure con un'app come
   «Send files to TV»).
2. Apri Proscenium. La prima volta Android potrebbe chiederti di scegliere la schermata Home:
   scegli Proscenium.
3. Sulle TV Google TV (Sony, TCL, Chromecast…), il tasto Home del telecomando riporta sempre alla
   Home di Google. Perché apra Proscenium, attiva il servizio previsto allo scopo:
   **Impostazioni → Sistema → Servizio tasto Home**, che apre la pagina di accessibilità di Android.

Le impostazioni si aprono selezionando il banner con l'ora e il meteo (freccia su, poi OK), oppure
dal menu di un'app della barra.

Al primo avvio, una schermata **Primi passi** aiuta a scegliere la lingua, la città per il meteo,
un pacchetto di sfondi e il servizio del tasto Home. Resta disponibile in seguito in
**Impostazioni → Sistema → Primi passi**.

### Aggiornamenti

Proscenium controlla una volta al giorno se su GitHub c'è una nuova versione e ti avvisa. Per
installarla apri **Impostazioni → Sistema → Aggiornamento** e premi OK: la nuova versione viene
scaricata e Android ti chiede di confermare. Le impostazioni vengono mantenute. La prima volta Android
ti chiede di consentire a Proscenium di installare app.

Puoi anche seguire gli aggiornamenti con [Obtainium](https://github.com/ImranR98/Obtainium): aggiungi `https://github.com/Karlos-fr/proscenium` come sorgente.

> **Hai la versione 1.0?** La versione 1.1 è firmata con una nuova chiave e non si installa sopra la
> 1.0: disinstalla Proscenium, poi installa la 1.1. Impostazioni e playlist ripartono da zero (i pacchetti
> si reinstallano con un clic). Succede una sola volta: le versioni successive si installeranno
> direttamente sopra la 1.1.

## La Home

- **Sfondi di film a tutto schermo**, che cambiano ogni pochi secondi, con una transizione a scelta
  (dissolvenza, scorrimento…).
- **Effetto Ken Burns**: l'immagine zooma e si sposta lentamente, come in un documentario. Ampiezza
  e durata regolabili.
- **Colori che seguono l'immagine**: il colore dominante di ogni sfondo tinge il vetro, la luce e le
  icone. Oppure un colore fisso, se preferisci.
- **Leggibilità**: un leggero oscuramento compare solo dove il testo sarebbe difficile da leggere.
- **Profondità**: lo sfondo si sposta un po' quando la selezione si muove, e arriva sfocato e poi
  nitido quando torni alla Home.
- **Ora e data**, in grande, con cifre che scorrono. Formato 24 h o 12 h, dimensione regolabile.
- **Un banner di informazioni**: memoria usata, processore e il meteo della tua città con un'icona
  animata (sole che ruota, pioggia, neve, temporale, nebbia), temperatura, vento, direzione e
  raffiche. Ogni elemento può essere nascosto.

## Il meteo vivo

Il meteo può animarsi sull'immagine, come in una scena di film:

- **Sole**: raggi di luce attraversano le zone chiare dell'immagine, con un alone caldo e pulviscolo
  che brilla nella luce. Su un'immagine scura, morbidi fasci partono dal sole. Il sole si sposta
  lentamente e ogni tanto delle nuvole attenuano la luce.
- **Pioggia**: le gocce si posano sullo schermo come su un vetro, crescono, poi scivolano lasciando
  una scia bagnata. Il vento della tua città le spinge di lato, con raffiche improvvise.
- **Fulmini**: veri fulmini ramificati che illuminano l'immagine.
- **Neve**: i fiocchi cadono, e quelli che toccano lo schermo si sciolgono in gocce.
- **Nebbia**: banchi di foschia che scorrono, turbinano, si addensano e si diradano.

**Meteo reale** mostra il tempo della tua città (un temporale porta pioggia e fulmini, la pioviggine
porta pioggia e nebbia…). **Personalizzato** combina gli strati che vuoi, con il vento che scegli.
**Casuale** estrae un nuovo mix ogni tanto, e **In base allo sfondo** ne sceglie uno per ogni
sfondo in base ai generi del film, alla trama e alla luce dell'immagine (neve per un film
invernale, nebbia per una storia di fantasmi, sole per un western…). In tutte le modalità gli
strati si sommano e il meteo passa dolcemente da uno sfondo all'altro.
Ogni meteo ha la sua pagina in **Impostazioni → Home → Impostazioni del meteo**: preimpostazioni,
ogni impostazione visibile dal vivo sull'immagine, valori casuali e ritorno alle impostazioni
originali.

## La barra delle app

- **Un carosello delle tue app**, con i loro banner TV. Scegli quali mostrare e in quale ordine.
- **L'app selezionata** si ingrandisce, si inclina leggermente, riceve un riflesso e una luce
  colorata intorno a sé. Questa luce prende il colore del suo logo e ondeggia dolcemente tutt'intorno.
- **Vetro realistico**: la barra rifrange l'immagine che passa dietro di essa, con preset (Chiaro,
  Sottile, Satinato, Forte) ed effetti di acqua calma, liquido o pioggia.
- **Scorciatoie per gli ingressi HDMI**: una console, un lettore Blu-ray o un box collegati alla TV
  hanno una propria tessera nella barra, con un nome e un'icona tra 24 console (PlayStation, Xbox,
  Nintendo, Sega…). OK passa direttamente all'ingresso giusto.
- **Tutto è regolabile**: numero di app visibili, dimensione e spaziatura delle tessere, banda a
  tutta larghezza o barra fluttuante, velocità di scorrimento, ritorno all'inizio dopo l'ultima app.

## La modalità cinema e lo screensaver

Quando il telecomando resta fermo per un po', la Home passa alla **modalità cinema**: l'interfaccia
sparisce, bande nere ritagliano l'immagine in formato cinematografico (2:1 o Scope 2,39:1) e compare
il titolo del film, con il suo logo ufficiale quando esiste e l'anno. Attesa, bande, titolo e banner
sono regolabili.

La scheda del titolo può mostrare anche i **dettagli del film**: anno, durata e generi, il voto
TMDB (stelle, un anello in percentuale o un voto su 10) e una trama breve o completa che scorre
lentamente. I pacchetti portano questi dettagli in sei lingue.

Proscenium può anche fungere da **screensaver di Android**: sceglilo nelle impostazioni di Android,
alla voce Screensaver.

Su Google TV, Android elenca solo gli screensaver di Google. Scegli Proscenium una volta da un
computer, con [adb](https://developer.android.com/tools/adb) collegato al televisore:
`adb shell settings put secure screensaver_components fr.proscenium.launcher/.CinemaDream`. **Impostazioni → Modalità cinema → Salvaschermo** lo attiva o lo disattiva.

## I tuoi sfondi: playlist e pacchetti

Le immagini della Home provengono dalle **foto Bing del giorno** oppure dalle **tue playlist** di film.

- **Pacchetti di sfondi**: playlist tematiche già pronte (20 pacchetti: Star Wars,
  Studio Ghibli, Christopher Nolan, horror, anni '80, James Bond, western…), da installare con un clic da **Impostazioni → Playlist → Sfoglia i pacchetti**.
  Non richiedono alcuna chiave TMDB. Titoli e loghi sono nella tua lingua quando esistono.
- **Le tue playlist**: cerca un film per titolo, con la tastiera o con la voce, oppure importa in un
  colpo solo i film popolari o di tendenza. Per ogni film, scegli lo sfondo o gli sfondi che ti
  piacciono e il logo da mostrare.
- **Tendenze del momento**: una playlist che si aggiorna da sola ogni giorno con i film del momento.
- Più playlist possono essere attive contemporaneamente: i loro sfondi si mescolano.
- **Aggiornamenti dei pacchetti**: quando a un pacchetto vengono aggiunti film, mostra
  "aggiornamento disponibile" e **Aggiorna i pacchetti installati** scarica i nuovi film. Chiunque
  può proporre un pacchetto: vedi [packs/README.md](../packs/README.md).

Le immagini provengono da [The Movie Database (TMDB)](https://www.themoviedb.org). Puoi mostrare
solo gli sfondi senza testo e in Full HD.

## La chiave TMDB

I pacchetti funzionano senza chiave. Per cercare film da solo serve una chiave API TMDB, gratuita:
crea un account su [themoviedb.org](https://www.themoviedb.org/signup), poi richiedi una chiave nelle
impostazioni del tuo account, alla voce API.

Non serve digitarla con il telecomando: **Impostazioni → Film (TMDB) → Inserisci la chiave dal
telefono** mostra un codice QR. Inquadralo con il telefono (collegato alla stessa rete della TV),
incolla la chiave nella pagina che si apre: la TV la verifica e la salva.

## Il telecomando web

**Impostazioni → Sistema → Telecomando web** porta tutte le impostazioni sul telefono o sul
computer: attivalo, poi inquadra il codice QR mostrato dalla TV (il telefono deve essere sullo
stesso Wi-Fi). La pagina ha le stesse schede del pannello delle impostazioni (tranne il vetro),
funziona sul telefono e nel browser di un computer, e mostra il film sullo schermo con un pulsante
per lo sfondo successivo. Da lì scegli le playlist da mostrare, installi o rimuovi pacchetti, ordini
le app della barra, scrivi la città del meteo o la chiave TMDB, e regoli ogni meteo mentre appare
dal vivo sulla TV. La pagina si aggiorna da sola quando un'impostazione cambia sulla TV. L'indirizzo
resta lo stesso: **http://proscenium.local:8770** su iPhone, Mac o computer, oppure l'indirizzo della
TV mostrato sotto il codice QR; salvalo nei preferiti o aggiungilo alla schermata Home. Il nome si
può cambiare, e il browser può chiedere una password (consigliata se altre persone usano il tuo
Wi-Fi). Solo i dispositivi della rete di casa possono raggiungere la pagina. Senza **Indirizzo
fisso**, l'indirizzo è segreto e nuovo a ogni avvio di Proscenium.

## E inoltre

- **Sei lingue**: francese, inglese, tedesco, italiano, spagnolo e svedese. Per impostazione
  predefinita, la lingua della TV.
- **Suoni dell'interfaccia** discreti, volume regolabile.
- **Modalità risparmio**: disattiva in un colpo solo gli effetti più pesanti, per i box più piccoli.
- **Backup e ripristino** di tutte le impostazioni.

## FAQ

**Funziona su Fire TV?** Probabilmente come app, ma non come schermata Home: Amazon impedisce di
sostituire la sua, quindi il tasto Home riporta sempre a quella di Amazon. Non è stato testato. I Fire
TV più recenti con Vega OS non possono installare app Android.

**Perché il tasto Home apre ancora la Home di Google?** Su Google TV questo tasto è gestito da Google.
Attiva il servizio previsto (**Impostazioni → Sistema → Servizio tasto Home**, poi Proscenium
nella pagina di accessibilità di Android): il tasto Home aprirà Proscenium.

**Perché una chiave TMDB?** Serve solo per cercare film da soli. Pacchetti, foto Bing e meteo
funzionano senza. TMDB dà chiavi gratuite ai progetti personali e ogni utente ha la sua: Proscenium
non condivide un'unica chiave tra tutti.

**Quanti dati consuma?** Circa 0,5 MB per film (uno sfondo di circa 400 KB e il suo logo), scaricati
una sola volta e poi conservati sul televisore: circa 20 MB per un pacchetto di 40 film. Dopo, usano
la rete solo i nuovi film, la foto Bing del giorno (se scelta), il meteo (pochi KB) e un controllo
quotidiano degli aggiornamenti.

## Crediti

<img src="../images/tmdb-logo.svg" alt="TMDB" width="160" />

- Film, sfondi e loghi: [The Movie Database (TMDB)](https://www.themoviedb.org). Questo prodotto
  utilizza l'API TMDB ma non è approvato né certificato da TMDB. Le immagini restano di proprietà dei
  rispettivi titolari dei diritti.
- Foto del giorno: Bing. Meteo: [Open-Meteo](https://open-meteo.com).
- Codice pubblicato con [licenza MIT](../LICENSE).
