#!/usr/bin/env bash
# Check VAP / mp4 materials for B-frames and extra reference frames.
# Hardware decoders keep a DPB sized by has_b_frames / refs; VAP does not need either.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: ./check-vap-codec.sh <file-or-dir> [more files/dirs]

Recommended encode (from PNG frames, same as animtool):

  H.264
    ffmpeg -framerate <fps> -i %03d.png -pix_fmt yuv420p -c:v libx264 \
      -crf 18 -profile:v main -level 4.0 -bf 0 -refs 1 -bufsize 2000k -y video.mp4

  H.265
    ffmpeg -framerate <fps> -i %03d.png -pix_fmt yuv420p -c:v libx265 \
      -crf 18 -profile:v main -level 4.0 -x265-params bframes=0:ref=1 \
      -tag:v hvc1 -bufsize 2000k -y video.mp4

Then mux vapc with animtool / mp4edit. Do not ffmpeg -i an existing VAP.
EOF
}

if [[ $# -lt 1 ]]; then
  usage
  exit 2
fi

command -v ffprobe >/dev/null 2>&1 || {
  echo "error: ffprobe not found in PATH" >&2
  exit 2
}

collect_videos() {
  local path
  for path in "$@"; do
    if [[ -d "$path" ]]; then
      find "$path" -type f \( -iname '*.mp4' -o -iname '*.mov' -o -iname '*.vap' \)
    elif [[ -f "$path" ]]; then
      printf '%s\n' "$path"
    else
      echo "warning: skip missing path: $path" >&2
    fi
  done
}

probe_field() {
  local file="$1"
  local field="$2"
  ffprobe -v error -select_streams v:0 -show_entries "stream=$field" -of default=nw=1:nk=1 -- "$file" 2>/dev/null | head -n 1
}

mapfile -t videos < <(collect_videos "$@")
if [[ ${#videos[@]} -eq 0 ]]; then
  echo "error: no .mp4 / .mov / .vap files found" >&2
  exit 2
fi

bad=0
index=0
for file in "${videos[@]}"; do
  index=$((index + 1))
  echo
  echo "[$index/${#videos[@]}] $file"

  if ! ffprobe -v error -select_streams v:0 -show_entries stream=codec_name -of csv=p=0 -- "$file" >/dev/null; then
    echo "  ERROR  ffprobe failed / no video stream"
    bad=$((bad + 1))
    continue
  fi

  codec=$(probe_field "$file" codec_name)
  width=$(probe_field "$file" width)
  height=$(probe_field "$file" height)
  profile=$(probe_field "$file" profile)
  level=$(probe_field "$file" level)
  has_b=$(probe_field "$file" has_b_frames)
  refs=$(probe_field "$file" refs)
  frames=$(probe_field "$file" nb_frames)
  bitrate=$(probe_field "$file" bit_rate)
  fps=$(probe_field "$file" avg_frame_rate)

  width=${width:-0}
  height=${height:-0}
  has_b=${has_b:-0}
  refs=${refs:-1}

  dpb=$refs
  if [[ "$dpb" -lt 1 ]]; then dpb=1; fi
  if [[ "$has_b" -gt 0 ]]; then dpb=$((dpb + 2)); fi
  frames_kept=$((dpb + 4))
  dpb_mb=$(awk -v w="$width" -v h="$height" -v n="$frames_kept" 'BEGIN { printf "%.1f", w*h*1.5*n/1048576 }')

  problems=()
  if [[ "$has_b" -gt 0 ]]; then problems+=("has_b_frames=$has_b (want 0)"); fi
  if [[ "$refs" -gt 1 ]]; then problems+=("refs=$refs (want 1)"); fi
  if [[ "$level" =~ ^[0-9]+$ ]] && [[ "$level" -ge 51 ]]; then
    problems+=("level=$level (high levels inflate DPB; prefer 4.0/4.1)")
  fi

  echo "  codec=$codec  ${width}x${height}  profile=$profile  level=$level"
  echo "  has_b_frames=$has_b  refs=$refs  fps=$fps  frames=$frames  bitrate=$bitrate"
  echo "  estimated decoder Graphics ~= ${dpb_mb} MB"

  if [[ ${#problems[@]} -eq 0 ]]; then
    echo "  OK     low-memory encode"
  else
    echo "  WARN   ${problems[*]}"
    echo "  re-export from frames with -bf 0 -refs 1 (H.264) or -x265-params bframes=0:ref=1"
    bad=$((bad + 1))
  fi
done

echo
if [[ "$bad" -eq 0 ]]; then
  echo "All ${#videos[@]} file(s) look good."
  exit 0
fi

echo "$bad / ${#videos[@]} file(s) will keep a large decoder DPB."
exit 1
