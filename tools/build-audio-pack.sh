#!/usr/bin/env bash
# Builds the private audio resource pack from the original game's extracted sounds.
#   <out>/deepcharter-audio-pack/       the resource pack (pack.mcmeta + assets/deepcharter/...)
#   <out>/deepcharter-audio-pack.zip    the same pack, zipped, to hand to friends privately
# Each sound event in tools/audio-pack-map.txt gets its source file(s) converted to mono OGG
# (ffmpeg, libvorbis). The pack's sounds.json replaces the mod's vanilla placeholders.
#
# The originals are never committed or uploaded. The script refuses any output path that
# `git check-ignore` does not confirm is ignored, so the converted audio cannot be staged by
# accident. The map file holds only ids and file names.
#
# Usage: tools/build-audio-pack.sh [output-dir]
#   output-dir  default <repo>/private/audio. Must be inside the repo and git-ignored.
# Env:   AUDIO_SRC  folder of original sounds (default <repo>/original_flash_game/extracted/sounds)
#        AUDIO_MAP  id-to-file map (default tools/audio-pack-map.txt)
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
src=${AUDIO_SRC:-$root/original_flash_game/extracted/sounds}
map=${AUDIO_MAP:-$root/tools/audio-pack-map.txt}
namespace=deepcharter
# Resource pack major version of the pinned Minecraft (26.3 version.json: resource_major 97).
pack_format=97

if [[ $# -gt 1 ]]; then
  echo "usage: $0 [output-dir]" >&2
  exit 2
fi
out=${1:-$root/private/audio}
[[ $out == /* ]] || out=$PWD/$out
out=${out%/}
if [[ /$out/ == */../* || /$out/ == */./* ]]; then
  echo "error: output path '$out' must not contain . or .. segments" >&2
  exit 2
fi

# Resolve symlinks in the part of the path that exists, so it compares with git's physical paths.
existing=$out
rest=
while [[ -n $existing && ! -d $existing ]]; do
  rest=/${existing##*/}$rest
  existing=${existing%/*}
done
out=$(cd -P "${existing:-/}" && pwd)$rest

pack=$out/deepcharter-audio-pack
zipfile=$out/deepcharter-audio-pack.zip

# Fail closed: a path outside the repo, behind a symlink, or not ignored all make
# check-ignore exit non-zero, and every one of those is a refusal.
for path in "$out" "$pack" "$zipfile"; do
  if ! git -C "$root" check-ignore -q -- "$path"; then
    echo "error: refusing to write to '$path': git check-ignore does not confirm it is git-ignored" >&2
    echo "       The output must be inside the repo and covered by .gitignore (for example private/)." >&2
    exit 1
  fi
done

for tool in ffmpeg zip; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "error: $tool is required" >&2
    exit 1
  fi
done
if [[ ! -f $map ]]; then
  echo "error: map $map not found" >&2
  exit 1
fi
if [[ ! -d $src ]]; then
  echo "error: source folder $src not found (the original sounds are not in git; see CONTEXT.md)" >&2
  exit 1
fi

# Parse and check the whole map before writing anything.
ids=()
files=() # one entry per id: its source file names, space separated
seen=' '
missing=()
while read -r -a fields; do
  [[ ${#fields[@]} -eq 0 || ${fields[0]} == \#* ]] && continue
  id=${fields[0]}
  if [[ ! $id =~ ^[a-z0-9_]+(\.[a-z0-9_]+)+$ ]]; then
    echo "error: bad sound id '$id' in $map" >&2
    exit 1
  fi
  if [[ $seen == *" $id "* ]]; then
    echo "error: duplicate sound id '$id' in $map" >&2
    exit 1
  fi
  seen+="$id "
  if [[ ${#fields[@]} -lt 2 ]]; then
    echo "error: sound id '$id' in $map names no source file" >&2
    exit 1
  fi
  for name in "${fields[@]:1}"; do
    if [[ $name == */* || ! -f $src/$name ]]; then
      missing+=("$name")
    fi
  done
  ids+=("$id")
  files+=("${fields[*]:1}")
done <"$map"
if [[ ${#ids[@]} -eq 0 ]]; then
  echo "error: $map has no entries" >&2
  exit 1
fi
if [[ ${#missing[@]} -gt 0 ]]; then
  echo "error: ${#missing[@]} source file(s) from $map not found in $src:" >&2
  printf '  %s\n' "${missing[@]}" >&2
  exit 1
fi

# libvorbis when this ffmpeg has it; otherwise ffmpeg's built-in (experimental) Vorbis encoder.
encoders=$(ffmpeg -hide_banner -encoders 2>/dev/null) || {
  echo "error: ffmpeg -encoders failed" >&2
  exit 1
}
if grep -q ' libvorbis ' <<<"$encoders"; then
  codec_args=(-ac 1 -c:a libvorbis -q:a 4)
elif grep -q ' vorbis ' <<<"$encoders"; then
  echo "warning: no libvorbis; using the built-in encoder, which is stereo only (Minecraft will not pan stereo sounds in the world)" >&2
  codec_args=(-ac 2 -c:a vorbis -strict -2 -q:a 4)
else
  echo "error: this ffmpeg has no Vorbis encoder (install one built with libvorbis)" >&2
  exit 1
fi

rm -rf "$pack"
rm -f "$zipfile"
sounds_dir=$pack/assets/$namespace/sounds
mkdir -p "$sounds_dir"

{
  printf '{\n'
  sep=
  for i in "${!ids[@]}"; do
    id=${ids[$i]}
    base=${id//./_}
    n=0
    refs=
    for name in ${files[$i]}; do
      n=$((n + 1))
      ffmpeg -v error -nostdin -y -i "$src/$name" -vn "${codec_args[@]}" "$sounds_dir/${base}_$n.ogg" >&2
      refs+="${refs:+, }\"$namespace:${base}_$n\""
    done
    printf '%s  "%s": { "replace": true, "sounds": [%s] }' "$sep" "$id" "$refs"
    sep=$',\n'
  done
  printf '\n}\n'
} >"$pack/assets/$namespace/sounds.json"

printf '{\n  "pack": {\n    "description": "Deep Charter private audio (do not share)",\n    "min_format": %s,\n    "max_format": %s\n  }\n}\n' "$pack_format" "$pack_format" >"$pack/pack.mcmeta"

(cd "$pack" && zip -q -X -r "$zipfile" pack.mcmeta assets)

echo "built ${#ids[@]} sound events:"
echo "  $pack"
echo "  $zipfile"
echo "Keep it private: hand the zip to friends directly. Never commit or upload it."
