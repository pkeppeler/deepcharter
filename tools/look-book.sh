#!/usr/bin/env bash
# Usage: tools/look-book.sh [--no-record] [--no-publish] [option...]
#
# Rebuilds the look books (docs/tooling/look-book.md). For each option, a folder of skins/ (every one by default):
#   1. builds its skin pack (tools/lookbook/skins.py),
#   2. records design-tour and look-motion with it (tools/record-evidence.sh --skin=<option>) into build/looks/<option>/stills/,
#   3. makes its media (tools/lookbook/lookbook.py media: JPEG stills, GIF clips, palette, the look-motion MP4),
#   4. publishes the media to pr-media/looks/<option>/ (tools/pr-media.sh), replacing files by name.
# Then it composes the comparison grids into pr-media/looks/compare/ and writes docs/design/looks/*.md; commit those.
# A grid needs every option's still: an option this run did not rebuild comes from build/looks/<option>/media/, or is
# downloaded from pr-media when that folder lacks it.
#
# --no-record   reuse build/looks/<option>/stills/ from an earlier run (no game starts)
# --no-publish  make the media, grids and markdown, and push nothing
# An unknown option fails before anything runs, and lists the skins. Each recording waits for a client slot by itself.
set -euo pipefail

usage() {
  echo "usage: tools/look-book.sh [--no-record] [--no-publish] [option...]" >&2
  exit 2
}
record=1
publish=1
ids=()
for arg in "$@"; do
  case $arg in
    --no-record) record=0 ;;
    --no-publish) publish=0 ;;
    -*) usage ;;
    *) ids+=("$arg") ;;
  esac
done

cd "$(dirname "$0")/.."
known=()
for spec in skins/*/skin.json; do
  [[ -e $spec ]] && known+=("$(basename "$(dirname "$spec")")")
done
if (( ${#known[@]} == 0 )); then
  echo "no skins: skins/<option>/skin.json holds each option" >&2
  exit 1
fi
if (( ${#ids[@]} == 0 )); then
  ids=("${known[@]}")
fi
for id in "${ids[@]}"; do
  found=0
  for skin in "${known[@]}"; do
    [[ $skin == "$id" ]] && found=1
  done
  if (( ! found )); then
    echo "no look-book option is named '$id'; known: ${known[*]}" >&2
    exit 1
  fi
done

python3 -I tools/lookbook/lookbook.py check
python3 -I tools/lookbook/skins.py "${ids[@]}"
for id in "${ids[@]}"; do
  looks=build/looks/$id
  if (( record )); then
    echo "look-book: recording $id (design-tour, then look-motion)" >&2
    rm -rf "$looks/stills"
    mkdir -p "$looks/stills"
    tools/record-evidence.sh design-tour --skin="$id"
    cp build/evidence/design-tour/screenshots/*.png "$looks/stills/"
    tools/record-evidence.sh look-motion --skin="$id"
    cp build/evidence/look-motion/screenshots/*.png "$looks/stills/"
    cp build/evidence/look-motion/look-motion.mp4 "$looks/look-motion.mp4"
  fi
  [[ -d $looks/stills && -f $looks/look-motion.mp4 ]] \
    || { echo "no recording of $id in $looks: run without --no-record" >&2; exit 1; }
  python3 -I tools/lookbook/lookbook.py media "$id" "$looks/stills" "$looks/media"
  cp "$looks/look-motion.mp4" "$looks/media/"
  if (( publish )); then
    tools/pr-media.sh "looks/$id" "$looks/media"/* >/dev/null
  fi
done

media=()
for skin in "${known[@]}"; do
  media+=("$skin=build/looks/$skin/media")
done
python3 -I tools/lookbook/lookbook.py grids build/looks/compare "${media[@]}"
if (( publish )); then
  tools/pr-media.sh looks/compare build/looks/compare/*.jpg >/dev/null
fi
python3 -I tools/lookbook/lookbook.py markdown
echo "look-book: wrote docs/design/looks/ for ${ids[*]}; commit it" >&2
