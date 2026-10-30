# Builds the standalone Windows app: build\dist\NewPipe\NewPipe.exe (bundled Java runtime, no install needed)
# Requirements: JDK 24 (jlink/jpackage). libmpv-2.dll and ffmpeg.exe must be in .\native (see README.md).
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$jdk = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\jdk-24' }
foreach ($tool in 'jlink.exe', 'jpackage.exe') {
    if (-not (Test-Path "$jdk\bin\$tool")) { throw "$tool not found in $jdk - set JAVA_HOME to a JDK 24 install" }
}
foreach ($f in 'libmpv-2.dll', 'ffmpeg.exe') {
    if (-not (Test-Path "native\$f")) { throw "native\$f is missing - see README.md" }
}

Write-Host '== compiling'
& .\gradlew.bat --console=plain -q packageInput makeIcon
if ($LASTEXITCODE -ne 0) { throw 'gradle failed' }

Write-Host '== creating a trimmed Java runtime'
if (Test-Path build\runtime) { Remove-Item build\runtime -Recurse -Force }
& "$jdk\bin\jlink.exe" --add-modules java.se,jdk.unsupported,jdk.crypto.ec,jdk.localedata,jdk.charsets `
    --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output build\runtime
if ($LASTEXITCODE -ne 0) { throw 'jlink failed' }

Write-Host '== packaging'
if (Test-Path build\dist) { Remove-Item build\dist -Recurse -Force }
& "$jdk\bin\jpackage.exe" --type app-image --name NewPipe --dest build\dist `
    --input build\package-input --main-jar NewPipeDesktop-0.29.1.jar --main-class org.newpipe.desktop.App `
    --runtime-image build\runtime --icon build\app.ico --app-version 0.29.1 `
    --description 'NewPipe for Windows - lightweight YouTube and SoundCloud player with tempo and pitch control' `
    --java-options '--enable-native-access=ALL-UNNAMED' --java-options '-Dfile.encoding=UTF-8'
if ($LASTEXITCODE -ne 0) { throw 'jpackage failed' }

Write-Host '== adding native helpers (mpv, ffmpeg)'
New-Item -ItemType Directory -Force build\dist\NewPipe\app\native | Out-Null
Copy-Item native\libmpv-2.dll, native\ffmpeg.exe build\dist\NewPipe\app\native\

Write-Host '== done: build\dist\NewPipe\NewPipe.exe'
