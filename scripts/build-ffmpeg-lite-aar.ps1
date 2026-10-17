$ErrorActionPreference = "Stop"

$bash = "C:\msys64\usr\bin\bash.exe"
if (-not (Test-Path $bash)) {
    throw "MSYS2 bash not found at $bash. Install MSYS2 first."
}

$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$projectRootForMsys = ($projectRoot.Path -replace "\\", "/")

$msysProjectRoot = & $bash -lc "cygpath -u '$projectRootForMsys'"
if ($LASTEXITCODE -ne 0) {
    throw "Failed to convert project path for MSYS2."
}

& $bash -lc "cd '$($msysProjectRoot.Trim())' && bash scripts/build-ffmpeg-lite-aar.sh"
if ($LASTEXITCODE -ne 0) {
    throw "FFmpeg lite AAR build failed."
}
