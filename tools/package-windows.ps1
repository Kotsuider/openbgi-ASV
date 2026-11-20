param(
    [string]$Version,
    [string]$Binary,
    [string]$Strip
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (!$Version) {
    $match = Select-String -LiteralPath (Join-Path $root 'android/build.gradle') -Pattern "versionName '([^']+)'"
    $Version = $match.Matches[0].Groups[1].Value
}
if ($Version -notmatch '^\d+\.\d+(?:\.\d+)?$') { throw 'Use a numeric release version.' }
if (!$Binary) { $Binary = Join-Path $root 'bin/bgi.exe' }
if (!$Strip) {
    $tool = Get-Command strip.exe -ErrorAction SilentlyContinue
    if ($tool) { $Strip = $tool.Source }
    else { $Strip = Join-Path $env:SystemDrive 'msys64/mingw32/bin/strip.exe' }
}
if (!(Test-Path -LiteralPath $Binary -PathType Leaf)) { throw 'Build the Windows executable first.' }
if (!(Test-Path -LiteralPath $Strip -PathType Leaf)) { throw 'Specify MinGW strip.exe with -Strip.' }
$package = "OpenBGI-ASV-$Version-windows-x86"
$stage = Join-Path $root ("obj/releases/" + [guid]::NewGuid().ToString('N') + "/" + $package)
$output = Join-Path $root "bin/releases/$Version"
New-Item -ItemType Directory -Path $stage,$output -Force | Out-Null
$executable = Join-Path $stage 'OpenBGI.exe'
Copy-Item -LiteralPath $Binary -Destination $executable
& $Strip --strip-unneeded $executable
if ($LASTEXITCODE -ne 0) { throw 'MinGW strip failed.' }
Copy-Item -LiteralPath (Join-Path $root 'LICENSE') -Destination $stage
Copy-Item -LiteralPath (Join-Path $root 'android/src/main/assets/licenses/stb_vorbis.txt') -Destination (Join-Path $stage 'LICENSE-stb_vorbis.txt')
Copy-Item -LiteralPath (Join-Path $root "RELEASE_NOTES_v$Version.md") -Destination (Join-Path $stage 'RELEASE-NOTES.md')
$template = Get-Content -LiteralPath (Join-Path $root 'docs/windows-release.md') -Raw
$template.Replace('{VERSION}', $Version) | Set-Content -LiteralPath (Join-Path $stage 'README-Windows.md') -Encoding utf8
$launcher = '@echo off', 'setlocal', 'cd /d "%~dp0"', 'start "" "OpenBGI.exe" --launcher'
$launcher | Set-Content -LiteralPath (Join-Path $stage 'OpenBGI-Launcher.cmd') -Encoding ascii
$archive = Join-Path $output "$package.zip"
Compress-Archive -LiteralPath $stage -DestinationPath $archive -Force
Write-Output $archive
Write-Output "Staging: $stage"
