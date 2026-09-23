$ErrorActionPreference = "Stop"

$PROJECT = "d:\APK DISSCECT\NR Band Manager_0.5.8\NewApp"
$SDK = "C:\Users\ANIKE\AppData\Local\Android\Sdk"
$BT = "$SDK\build-tools\36.0.0"
$ANDROID_JAR = "$SDK\platforms\android-36\android.jar"
$OBJ = "$PROJECT\obj"
$OUTPUT = "d:\APK DISSCECT\NR_Band_Manager_NEW.apk"
$KEYSTORE = "d:\APK DISSCECT\patch-signing-key.jks"

# Clean
Remove-Item "$OBJ\*" -Recurse -Force -EA SilentlyContinue
New-Item -ItemType Directory -Force -Path "$OBJ\compiled_res","$OBJ\classes","$OBJ\gen_r" | Out-Null

# 1. Compile resources
Write-Host "[1/7] Compiling resources..." -ForegroundColor Cyan
Get-ChildItem "$PROJECT\res" -Recurse -File | ForEach-Object {
    if ($_.Directory.Parent.Name -eq "res") {
        & "$BT\aapt2.exe" compile $_.FullName -o "$OBJ\compiled_res" 2>&1 | Out-Null
    }
}

# 2. Link APK
Write-Host "[2/7] Linking APK..." -ForegroundColor Cyan
$compiledRes = (Get-ChildItem "$OBJ\compiled_res" -File | ForEach-Object { $_.FullName })
$linkArgs = @("link", "-o", "$OBJ\base.apk", "-I", $ANDROID_JAR,
    "--manifest", "$PROJECT\AndroidManifest.xml",
    "--min-sdk-version", "28", "--target-sdk-version", "34",
    "--version-code", "1", "--version-name", "1.0.0",
    "-A", "$PROJECT\assets", "--java", "$OBJ\gen_r")
$linkArgs += $compiledRes
& "$BT\aapt2.exe" @linkArgs 2>&1 | Out-Null

# 3. Compile Java
Write-Host "[3/7] Compiling Java..." -ForegroundColor Cyan
$allJava = @()
$allJava += (Get-ChildItem "$PROJECT\src" -Recurse -Filter "*.java" | ForEach-Object { $_.FullName })
$rJava = Get-ChildItem "$OBJ\gen_r" -Recurse -Filter "R.java" -EA SilentlyContinue | ForEach-Object { $_.FullName }
if ($rJava) { $allJava += $rJava }
javac -classpath $ANDROID_JAR -d "$OBJ\classes" -source 11 -target 11 -Xlint:-options @allJava 2>&1

# 4. DEX
Write-Host "[4/7] DEXing..." -ForegroundColor Cyan
$classFiles = (Get-ChildItem "$OBJ\classes" -Recurse -Filter "*.class" | ForEach-Object { $_.FullName })
& "$BT\d8.bat" --min-api 28 --output "$OBJ" @classFiles 2>&1

# 5. Add DEX to APK
Write-Host "[5/7] Assembling APK..." -ForegroundColor Cyan
python -c "
import zipfile
with zipfile.ZipFile(r'$OBJ\base.apk', 'a', compression=zipfile.ZIP_STORED) as zf:
    zf.write(r'$OBJ\classes.dex', 'classes.dex')
"

# 6. Zipalign + Sign
Write-Host "[6/7] Zipaligning..." -ForegroundColor Cyan
& "$BT\zipalign.exe" -f 4 "$OBJ\base.apk" "$OBJ\aligned.apk" 2>&1 | Out-Null

Write-Host "[7/7] Signing..." -ForegroundColor Cyan
& "$BT\apksigner.bat" sign --ks $KEYSTORE --ks-pass "pass:android" --ks-key-alias "patchkey" --key-pass "pass:android" --out $OUTPUT "$OBJ\aligned.apk" 2>&1
Remove-Item "$OUTPUT.idsig" -Force -EA SilentlyContinue

# Done
$size = (Get-Item $OUTPUT).Length
Write-Host "`n  BUILD OK: $OUTPUT ($([math]::Round($size/1KB, 1)) KB)" -ForegroundColor Green
