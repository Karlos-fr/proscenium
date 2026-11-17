# Accueil Google TV de la Sony Bravia : désactivé pour que la touche Accueil ouvre directement Proscenium.
#
#   .\tv-accueil-google.ps1              -> montre l'état des deux applis
#   .\tv-accueil-google.ps1 -Restaurer   -> réactive l'accueil Google TV et son assistant
#
# Prérequis : débogage sans fil activé sur la TV (Paramètres → Système → Options pour les
# développeurs). Android le coupe à chaque redémarrage ; l'ordinateur est déjà associé.
#
# Attention : désactiver l'assistant (setupwraith) fait oublier à la TV le Wi-Fi qu'il avait
# enregistré pendant l'installation (16/11/2026). Le mot de passe ressaisi depuis les paramètres,
# lui, reste. Restaurer ne fait rien perdre.
param([switch]$Restaurer)

$adb = Join-Path $PSScriptRoot 'platform-tools\adb.exe'
$packages = 'com.google.android.apps.tv.launcherx', 'com.google.android.tungsten.setupwraith'

# La TV s'annonce sur le réseau (mDNS) : on prend le premier appareil Sony connecté
$device = (& $adb devices | Select-String 'adb-.*_adb-tls-connect\._tcp\s+device' | Select-Object -First 1)
if (-not $device) { throw "TV introuvable : activez le débogage sans fil sur la TV." }
# Le nom peut contenir un espace (« … (2) » quand la TV s'est annoncée deux fois) : on coupe à la tabulation
$serial = ($device.Line -split "`t")[0]

if ($Restaurer) {
    foreach ($p in $packages) { & $adb -s $serial shell pm enable $p }
}
& $adb -s $serial shell "pm list packages -d" | Select-String ($packages -join '|')
Write-Host "Applis listées ci-dessus : désactivées. Aucune ligne : l'accueil Google TV est actif."
