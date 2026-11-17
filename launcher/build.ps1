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

# Clé de signature locale, propre à ce projet (pas de mot de passe sensible)
$ks = "$root\debug.keystore"
if (-not (Test-Path $ks)) {
    Step 'Création de la clé de signature' {
        & keytool -genkeypair -keystore $ks -storepass android -keypass android -alias proscenium `
            -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Proscenium'
    }
}
Step 'Signature (apksigner)' {
    & "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android --out "$out\Proscenium.apk" "$out\aligned.apk"
}
Write-Host ("APK : {0} ({1:N0} Ko)" -f "$out\Proscenium.apk", ((Get-Item "$out\Proscenium.apk").Length / 1KB))

if ($Install) {
    & $adb connect $Device | Out-Null
    Step 'Installation sur la box' { & $adb -s $Device install -r "$out\Proscenium.apk" }
}
