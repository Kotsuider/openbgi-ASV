param(
    [string]$Sdk = (Join-Path $env:LOCALAPPDATA 'Android/Sdk'),
    [string]$Gradle = 'gradle',
    [switch]$Release,
    [string]$Keystore,
    [string]$KeyAlias = 'openbgi-release'
)
$ErrorActionPreference = 'Stop'

if ($Release -and (!(Test-Path -LiteralPath $Keystore -PathType Leaf) -or !$env:OPENBGI_STORE_PASSWORD)) {
    throw 'A release build requires -Keystore and OPENBGI_STORE_PASSWORD.'
}

function Run {
    $Program, $Arguments = $args
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program exited with $LASTEXITCODE" }
}

$source = $PSScriptRoot
$root = Split-Path -Parent $source
$build = Join-Path $root 'obj/android-app'
$cmake = Join-Path $Sdk 'cmake/3.22.1/bin/cmake.exe'
$tools = Join-Path $Sdk 'build-tools/35.0.0'
$toolchain = Join-Path $Sdk 'ndk/26.1.10909125/build/cmake/android.toolchain.cmake'
$ninja = Join-Path $Sdk 'cmake/3.22.1/bin/ninja.exe'
$vorbis = Join-Path $root 'src/snd/stb_vorbis.c'
if (!(Test-Path -LiteralPath $vorbis)) {
    $download = Join-Path $build 'stb_vorbis.download'
    New-Item -ItemType Directory -Force $build | Out-Null
    $revision = '9600522de53973336fa033a289858a4354cbb04c'
    Invoke-WebRequest -UseBasicParsing "https://raw.githubusercontent.com/nothings/stb/$revision/stb_vorbis.c" -OutFile $download
    $expectedHash = 'B52CF518355F7B2D181F18B97B812F361FFEB26299D935C8CF29E842401D60A6'
    if ((Get-FileHash -LiteralPath $download).Hash -ne $expectedHash) {
        throw 'Downloaded stb_vorbis.c did not match the expected SHA-256.'
    }
    Move-Item -LiteralPath $download -Destination $vorbis
}

Run $cmake -S $source -B $build -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninja" "-DCMAKE_TOOLCHAIN_FILE=$toolchain" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DBGI_ANDROID_APP=ON -DCMAKE_BUILD_TYPE=RelWithDebInfo
Run $cmake --build $build --parallel 8

$lib = Join-Path $build 'package/lib/arm64-v8a'
New-Item -ItemType Directory -Force $lib,(Join-Path $root 'bin') | Out-Null
Copy-Item (Join-Path $build 'libopenbgi_android.so') $lib -Force

$env:ANDROID_HOME = $Sdk
if ($Release) {
    Run $Gradle -p $source assembleRelease
    $unsigned = Join-Path $source 'build/outputs/apk/release/OpenBGIAndroid-release-unsigned.apk'
    $apk = Join-Path $root 'bin/openbgi-android-release.apk'
    Run (Join-Path $tools 'apksigner.bat') sign --ks $Keystore --ks-key-alias $KeyAlias --ks-pass env:OPENBGI_STORE_PASSWORD --out $apk $unsigned
} else {
    $key = Join-Path $build 'debug.keystore'
    if (!(Test-Path $key)) {
        Run keytool -genkeypair -keystore $key -storepass android -keypass android -alias androiddebugkey -dname 'CN=OpenBGI Debug' -keyalg RSA -keysize 2048 -validity 10000
    }
    Run $Gradle -p $source assembleDebug
    $apk = Join-Path $root 'bin/openbgi-android-debug.apk'
    Copy-Item (Join-Path $source 'build/outputs/apk/debug/OpenBGIAndroid-debug.apk') $apk -Force
}
Run (Join-Path $tools 'apksigner.bat') verify --verbose $apk
Write-Output $apk
