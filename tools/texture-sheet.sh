#!/usr/bin/env bash
# Usage: tools/texture-sheet.sh
#
# Renders the texture reference sheet (palette and every texture at four light levels) with
# tools/textures/texgen.py --sheet and publishes it to pr-media/readme/texture-reference.png with
# tools/pr-media.sh. The sheet is generated from the recipes and never committed (#372): a tracked
# copy conflicted between every two PRs that touched a recipe. Run it after a PR that changes
# the palette or a recipe, with the README tour refresh (docs/tooling/readme-tour.md).
set -euo pipefail

tools=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT

python3 -I "$tools/textures/texgen.py" --sheet "$stage/texture-reference.png"
"$tools/pr-media.sh" readme "$stage/texture-reference.png"
