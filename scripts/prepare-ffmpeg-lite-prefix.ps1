$ErrorActionPreference = "Stop"

$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$ffmpegSrc = Join-Path $projectRoot ".ffmpeg-lite-build\FFmpeg-src"
$includeOut = Join-Path $projectRoot "animplayer\src\main\jni\ffmpeg-include"
$prefixRoot = Join-Path $projectRoot ".ffmpeg-lite-build\prefix"
$jniLibs = Join-Path $projectRoot "local-maven-tmp\ffmpeg-lite\jni"

if (-not (Test-Path $ffmpegSrc)) {
    throw "FFmpeg source not found at $ffmpegSrc. Clone n6.1.2 first."
}

function Get-PublicHeaders([string]$makefilePath) {
    $lines = Get-Content -LiteralPath $makefilePath
    $headers = New-Object System.Collections.Generic.List[string]
    $inHeaders = $false

    foreach ($line in $lines) {
        $trimmed = $line.Trim()
        if (-not $inHeaders) {
            if ($trimmed -match '^HEADERS\s*=') {
                $inHeaders = $true
                $trimmed = $trimmed -replace '^HEADERS\s*=\s*', ''
            } else {
                continue
            }
        }

        $continues = $trimmed.EndsWith('\')
        $trimmed = $trimmed.TrimEnd('\').Trim()
        if ($trimmed) {
            foreach ($item in ($trimmed -split '\s+')) {
                if ($item) {
                    $headers.Add($item)
                }
            }
        }

        if (-not $continues) {
            break
        }
    }

    return $headers
}

$libs = @('libavutil', 'libavcodec', 'libavformat', 'libswscale')
New-Item -ItemType Directory -Force -Path $includeOut | Out-Null

foreach ($lib in $libs) {
    $makefile = Join-Path $ffmpegSrc "$lib\Makefile"
    $destDir = Join-Path $includeOut $lib
    New-Item -ItemType Directory -Force -Path $destDir | Out-Null

    foreach ($header in (Get-PublicHeaders $makefile)) {
        $src = Join-Path $ffmpegSrc "$lib\$header"
        if (-not (Test-Path $src)) {
            throw "Missing public header: $src"
        }
        Copy-Item -LiteralPath $src -Destination (Join-Path $destDir $header) -Force
    }
}

$avutilExtra = @('intmath.h', 'intfloat.h')
foreach ($extra in $avutilExtra) {
    $src = Join-Path $ffmpegSrc "libavutil\$extra"
    if (Test-Path $src) {
        Copy-Item -LiteralPath $src -Destination (Join-Path $includeOut "libavutil\$extra") -Force
    }
}

@"
#ifndef AVUTIL_AVCONFIG_H
#define AVUTIL_AVCONFIG_H
#define AV_HAVE_BIGENDIAN 0
#define AV_HAVE_FAST_UNALIGNED 1
#endif /* AVUTIL_AVCONFIG_H */
"@ | Set-Content -LiteralPath (Join-Path $includeOut "libavutil\avconfig.h") -Encoding ascii

@"
#ifndef AVUTIL_FFVERSION_H
#define AVUTIL_FFVERSION_H
#define FFMPEG_VERSION "6.1.2"
#endif /* AVUTIL_FFVERSION_H */
"@ | Set-Content -LiteralPath (Join-Path $includeOut "libavutil\ffversion.h") -Encoding ascii

$abis = @('arm64-v8a', 'armeabi-v7a')
foreach ($abi in $abis) {
    $libDir = Join-Path $prefixRoot "$abi\lib"
    $incDir = Join-Path $prefixRoot "$abi\include"
    New-Item -ItemType Directory -Force -Path $libDir | Out-Null
    if (Test-Path $incDir) {
        Remove-Item -LiteralPath $incDir -Recurse -Force
    }
    Copy-Item -LiteralPath $includeOut -Destination $incDir -Recurse -Force

    $srcLibDir = Join-Path $jniLibs $abi
    foreach ($so in @('libavutil.so', 'libavcodec.so', 'libavformat.so', 'libswscale.so')) {
        $srcSo = Join-Path $srcLibDir $so
        if (-not (Test-Path $srcSo)) {
            throw "Missing prebuilt library: $srcSo"
        }
        Copy-Item -LiteralPath $srcSo -Destination (Join-Path $libDir $so) -Force
    }
}

Write-Output "Prepared FFmpeg lite headers and prefix libraries."
