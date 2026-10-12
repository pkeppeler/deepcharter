#!/usr/bin/env bash
# Usage: tools/feature-map.sh [--publish]
#
# Regenerates docs/FEATURE-MAP.md and docs/feature-map/index.html (git-ignored local copies) from docs/feature-map/map.json
# (tools/feature_map.py validates the map first). With --publish it also draws the poster of the whole
# map and publishes it to pr-media/readme/ with tools/pr-media.sh: feature-map.svg always, and
# feature-map.png when headless Google Chrome (or Chromium) is installed. The poster is generated and
# never committed, like the README tour media (docs/tooling/readme-tour.md). Skill: .claude/skills/feature-map/SKILL.md
set -euo pipefail

tools=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

case ${1-} in
  "") python3 -I "$tools/feature_map.py" ;;
  --publish)
    stage=$(mktemp -d)
    trap 'rm -rf "$stage"' EXIT
    python3 -I "$tools/feature_map.py" --check-stills --poster "$stage/feature-map.svg"
    files=("$stage/feature-map.svg")
    for chrome in "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" chromium google-chrome; do
      if command -v "$chrome" >/dev/null 2>&1; then
        size=$(sed -n 's/.*viewBox="0 0 \([0-9]*\) \([0-9]*\)".*/\1,\2/p' "$stage/feature-map.svg" | head -1)
        "$chrome" --headless=new --disable-gpu --hide-scrollbars --screenshot="$stage/feature-map.png" \
          --window-size="$size" "file://$stage/feature-map.svg" >/dev/null 2>&1
        files+=("$stage/feature-map.png")
        break
      fi
    done
    [[ ${#files[@]} -gt 1 ]] || echo "no headless Chrome found: published the SVG only" >&2
    "$tools/pr-media.sh" readme "${files[@]}"
    ;;
  *)
    echo "usage: tools/feature-map.sh [--publish]" >&2
    exit 2
    ;;
esac
