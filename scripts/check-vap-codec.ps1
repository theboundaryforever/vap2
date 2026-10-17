<#
.SYNOPSIS
    Check VAP / mp4 materials for B-frames and extra reference frames.

.DESCRIPTION
    Hardware decoders keep a decoded-picture buffer (DPB) whose size follows
    has_b_frames and refs. VAP animations do not need either; leaving them on
    commonly costs 30-60 MB of Graphics memory per player.

    This script only inspects files. Do not re-encode a finished VAP with
    "ffmpeg -i in.mp4 ...": the vapc box will be dropped. Re-export from
    frames with animtool (already uses -bf 0 -refs 1).

.EXAMPLE
    .\check-vap-codec.ps1 D:\vap\assets
    .\check-vap-codec.ps1 demo.mp4 extra.mp4
#>
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Paths
)

$ErrorActionPreference = "Stop"

function Show-Usage {
    Write-Host @"
Usage: .\check-vap-codec.ps1 <file-or-dir> [more files/dirs]

Checks video streams for B-frames and reference-frame count, which dominate
hardware-decoder Graphics memory.

Recommended encode (from PNG frames, same as animtool):

  H.264
    ffmpeg -framerate <fps> -i %03d.png -pix_fmt yuv420p -c:v libx264
      -crf 18 -profile:v main -level 4.0 -bf 0 -refs 1 -bufsize 2000k -y video.mp4

  H.265
    ffmpeg -framerate <fps> -i %03d.png -pix_fmt yuv420p -c:v libx265
      -crf 18 -profile:v main -level 4.0 -x265-params bframes=0:ref=1
      -tag:v hvc1 -bufsize 2000k -y video.mp4

Then mux vapc with animtool / mp4edit. Do not ffmpeg -i an existing VAP.
"@
}

if (-not $Paths -or $Paths.Count -eq 0) {
    Show-Usage
    exit 2
}

$ffprobe = Get-Command ffprobe -ErrorAction SilentlyContinue
if (-not $ffprobe) {
    Write-Error "ffprobe not found in PATH. Install ffmpeg and retry."
    exit 2
}

function Collect-Videos([string[]]$inputs) {
    $files = New-Object System.Collections.Generic.List[string]
    foreach ($path in $inputs) {
        if (-not (Test-Path -LiteralPath $path)) {
            Write-Warning "skip missing path: $path"
            continue
        }
        $item = Get-Item -LiteralPath $path
        if ($item.PSIsContainer) {
            Get-ChildItem -LiteralPath $item.FullName -Recurse -File |
                Where-Object { $_.Extension -match '\.(mp4|mov|vap)$' } |
                ForEach-Object { $files.Add($_.FullName) }
        } else {
            $files.Add($item.FullName)
        }
    }
    return $files
}

function Probe-Video([string]$file) {
    $raw = & ffprobe -v error -select_streams v:0 `
        -show_entries "stream=codec_name,width,height,profile,level,has_b_frames,refs,nb_frames,bit_rate,avg_frame_rate" `
        -of json -- $file 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "ffprobe failed: $raw"
    }
    $json = $raw | Out-String | ConvertFrom-Json
    if (-not $json.streams -or $json.streams.Count -eq 0) {
        throw "no video stream"
    }
    return $json.streams[0]
}

function To-Int($value, [int]$fallback = 0) {
    if ($null -eq $value -or $value -eq "") { return $fallback }
    $parsed = 0
    if ([int]::TryParse([string]$value, [ref]$parsed)) { return $parsed }
    return $fallback
}

function Estimate-DpbMb([int]$width, [int]$height, [int]$hasB, [int]$refs) {
    if ($width -le 0 -or $height -le 0) { return 0 }
    $dpb = [Math]::Max($refs, 1)
    if ($hasB -gt 0) { $dpb += 2 }
    # decoder pipeline + SurfaceTexture usually keep a few extra frames
    $frames = $dpb + 4
    return [Math]::Round($width * $height * 1.5 * $frames / 1MB, 1)
}

$videos = Collect-Videos $Paths
if ($videos.Count -eq 0) {
    Write-Error "no .mp4 / .mov / .vap files found"
    exit 2
}

$bad = 0
$index = 0
foreach ($file in $videos) {
    $index++
    Write-Host ""
    Write-Host "[$index/$($videos.Count)] $file"

    try {
        $s = Probe-Video $file
    } catch {
        Write-Host "  ERROR  $($_.Exception.Message)"
        $bad++
        continue
    }

    $codec = [string]$s.codec_name
    $width = To-Int $s.width
    $height = To-Int $s.height
    $profile = [string]$s.profile
    $level = [string]$s.level
    $hasB = To-Int $s.has_b_frames
    $refs = To-Int $s.refs 1
    $frames = [string]$s.nb_frames
    $bitrate = [string]$s.bit_rate
    $fps = [string]$s.avg_frame_rate
    $dpbMb = Estimate-DpbMb $width $height $hasB $refs

    $problems = @()
    if ($hasB -gt 0) { $problems += "has_b_frames=$hasB (want 0)" }
    if ($refs -gt 1) { $problems += "refs=$refs (want 1)" }
    if ($level -match '^\d+$' -and ([int]$level) -ge 51) {
        $problems += "level=$level (high levels inflate DPB; prefer 4.0/4.1)"
    }

    Write-Host ("  codec={0}  {1}x{2}  profile={3}  level={4}" -f $codec, $width, $height, $profile, $level)
    Write-Host ("  has_b_frames={0}  refs={1}  fps={2}  frames={3}  bitrate={4}" -f $hasB, $refs, $fps, $frames, $bitrate)
    Write-Host ("  estimated decoder Graphics ~= {0} MB" -f $dpbMb)

    if ($problems.Count -eq 0) {
        Write-Host "  OK     low-memory encode"
    } else {
        Write-Host ("  WARN   {0}" -f ($problems -join "; "))
        Write-Host "  re-export from frames with -bf 0 -refs 1 (H.264) or -x265-params bframes=0:ref=1"
        $bad++
    }
}

Write-Host ""
if ($bad -eq 0) {
    Write-Host "All $($videos.Count) file(s) look good."
    exit 0
}

Write-Host "$bad / $($videos.Count) file(s) will keep a large decoder DPB."
Write-Host "Recommended: use animtool, or the ffmpeg commands in .\check-vap-codec.ps1 -?"
exit 1
