# Compile Proscenium sans Gradle : aapt2 -> javac -> d8 -> zipalign -> apksigner.
#   .\build.ps1             -> build\Proscenium.apk
#   .\build.ps1 -Install    -> compile puis installe sur la box par adb
param(
    [switch]$Install,
    [string]$Device = '192.168.1.38:5555'
)
$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
$sdk  = Join-Path $root '..\android-sdk' | Resolve-Path
$bt   = "$sdk\build-tools\36.0.0"
$jar  = "$sdk\platforms\android-34\android.jar"
$adb  = Join-Path $root '..\platform-tools\adb.exe' | Resolve-Path
$out  = "$root\build"

function Step($name, [scriptblock]$cmd) {
    Write-Host "== $name"
    & $cmd
    if ($LASTEXITCODE -ne 0) { throw "Échec : $name (code $LASTEXITCODE)" }
}

if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force "$out\gen", "$out\classes" | Out-Null

Step 'Traductions (anglais / français)' { $env:PYTHONIOENCODING = 'utf-8'; & python -I "$root\check-strings.py" }
Step 'Ressources (aapt2 compile)' { & "$bt\aapt2.exe" compile --dir "$root\res" -o "$out\res.zip" }
Step 'Manifeste (aapt2 link)' {
    & "$bt\aapt2.exe" link -I $jar --manifest "$root\AndroidManifest.xml" `
        --min-sdk-version 25 --target-sdk-version 34 `
        --java "$out\gen" -o "$out\base.apk" "$out\res.zip"
}

$sources = @(Get-ChildItem "$root\src", "$out\gen" -Recurse -Filter *.java | ForEach-Object FullName)
Step 'Java (javac)' { & javac --release 11 -encoding UTF-8 -nowarn -classpath $jar -d "$out\classes" @sources }
Step 'Archive des classes' { & jar cf "$out\classes.jar" -C "$out\classes" . }
Step 'Dex (d8)' { & "$bt\d8.bat" --release --min-api 25 --lib $jar --output $out "$out\classes.jar" }

Copy-Item "$out\base.apk" "$out\unsigned.apk"
Step 'Ajout de classes.dex' {
    & python -I -c "import sys,zipfile; z=zipfile.ZipFile(sys.argv[1],'a',zipfile.ZIP_DEFLATED); z.write(sys.argv[2],'classes.dex'); z.close()" "$out\unsigned.apk" "$out\classes.dex"
}
Step 'Alignement (zipalign)' { & "$bt\zipalign.exe" -f 4 "$out\unsigned.apk" "$out\aligned.apk" }

# Signature. Les versions publiées utilisent la clé de release, décrite dans signing.properties
# (fichier local, jamais publié). Sans ce fichier, par exemple chez un contributeur, une clé de
# développement est créée : l'APK s'installe, mais pas par-dessus une version publiée.
$props = "$root\signing.properties"
if (Test-Path $props) {
    $sp = @{}
    foreach ($line in Get-Content $props -Encoding UTF8) {
        if ($line -match '^\s*([^#=]+?)\s*=\s*(.*)$') { $sp[$Matches[1]] = $Matches[2].Trim() }
    }
    $ks = Join-Path $root $sp['keystore']; $alias = $sp['alias']; $pass = $sp['password']
    if (-not (Test-Path $ks)) { throw "Clé de release introuvable : $ks (voir signing.properties)" }
    Write-Host "== Signature avec la clé de release"
} else {
    $ks = "$root\dev.keystore"; $alias = 'dev'; $pass = 'android'
    if (-not (Test-Path $ks)) {
        Step 'Création d''une clé de développement' {
            & keytool -genkeypair -keystore $ks -storepass $pass -keypass $pass -alias $alias `
                -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Proscenium dev'
        }
    }
    Write-Warning "signing.properties absent : APK signé avec une clé de développement"
}
Step 'Signature (apksigner)' {
    & "$bt\apksigner.bat" sign --ks $ks --ks-key-alias $alias --ks-pass "pass:$pass" --key-pass "pass:$pass" `
        --out "$out\Proscenium.apk" "$out\aligned.apk"
}
Write-Host ("APK : {0} ({1:N0} Ko)" -f "$out\Proscenium.apk", ((Get-Item "$out\Proscenium.apk").Length / 1KB))

if ($Install) {
    & $adb connect $Device | Out-Null
    Step 'Installation sur la box' { & $adb -s $Device install -r "$out\Proscenium.apk" }
}
