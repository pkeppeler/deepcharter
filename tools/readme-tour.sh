#!/usr/bin/env bash
# Usage: tools/readme-tour.sh [--no-record] [item...]
#        tools/readme-tour.sh --list
#
# Refreshes the README tour. Reads docs/readme-tour.tsv (media name, scenario, output), records
# each listed scenario once with tools/record-evidence.sh, and publishes the chosen GIF or still
# of each item to pr-media/readme/<media name> with tools/pr-media.sh. The README links those
# paths, so a refresh never changes the README. An item is a media name from the manifest; with
# none, every item is refreshed.
#
# --no-record  publish what build/evidence already holds (after a recording that was cut short)
# --list       print the manifest items and exit
set -euo pipefail

tools=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
root=$(dirname "$tools")
manifest=$root/docs/readme-tour.tsv
evidence=$root/build/evidence

usage() {
  echo "usage: tools/readme-tour.sh [--no-record] [item...]  |  --list" >&2
  exit 2
}

record=1
list=0
requested=()
for arg in "$@"; do
  case $arg in
    --no-record) record=0 ;;
    --list) list=1 ;;
    -*) usage ;;
    *) requested+=("$arg") ;;
  esac
done

media=()
scenarios=()
outputs=()
line_no=0
while IFS=$'\t' read -r name scenario output extra || [[ -n $name ]]; do
  line_no=$((line_no + 1))
  [[ -z $name || $name == \#* ]] && continue
  fail() { echo "$manifest:$line_no: $1" >&2; exit 1; }
  [[ -n $scenario && -n $output && -z $extra ]] || fail "expected 3 tab-separated columns: media name, scenario, output"
  [[ $name =~ ^[A-Za-z0-9][A-Za-z0-9._-]*\.(gif|png)$ ]] || fail "media name '$name' must be letters, digits, '.', '_' or '-' and end in .gif or .png"
  [[ $scenario =~ ^[a-z0-9][a-z0-9-]*$ ]] || fail "scenario '$scenario' is not a scenario id"
  if [[ $output == gif ]]; then
    [[ $name == *.gif ]] || fail "output gif needs a .gif media name, not '$name'"
  else
    [[ $output =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || fail "output '$output' must be gif or a still name"
    [[ $name == *.png ]] || fail "still '$output' needs a .png media name, not '$name'"
  fi
  for seen in ${media[@]+"${media[@]}"}; do
    [[ $seen != "$name" ]] || fail "media name '$name' is listed twice"
  done
  media+=("$name")
  scenarios+=("$scenario")
  outputs+=("$output")
done <"$manifest"
[[ ${#media[@]} -gt 0 ]] || { echo "$manifest lists no items" >&2; exit 1; }

if (( list )); then
  for i in "${!media[@]}"; do
    printf '%s\t%s\t%s\n' "${media[$i]}" "${scenarios[$i]}" "${outputs[$i]}"
  done
  exit 0
fi

selected=()
if [[ ${#requested[@]} -eq 0 ]]; then
  selected=("${!media[@]}")
else
  for want in "${requested[@]}"; do
    found=-1
    for i in "${!media[@]}"; do
      [[ ${media[$i]} != "$want" ]] || found=$i
    done
    (( found >= 0 )) || { echo "no item '$want' in the manifest; items: ${media[*]}" >&2; exit 1; }
    selected+=("$found")
  done
fi

recorded=" "
if (( record )); then
  for i in "${selected[@]}"; do
    scenario=${scenarios[$i]}
    [[ $recorded != *" $scenario "* ]] || continue
    echo "recording $scenario"
    "$tools/record-evidence.sh" "$scenario"
    recorded+="$scenario "
  done
fi

stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
files=()
for i in "${selected[@]}"; do
  scenario=${scenarios[$i]}
  if [[ ${outputs[$i]} == gif ]]; then
    source_file=$evidence/$scenario/$scenario.gif
  else
    source_file=$evidence/$scenario/screenshots/${outputs[$i]}.png
  fi
  [[ -f $source_file ]] \
    || { echo "${media[$i]}: $source_file is missing (a GIF over the size budget is skipped; see the record-evidence output)" >&2; exit 1; }
  cp "$source_file" "$stage/${media[$i]}"
  files+=("$stage/${media[$i]}")
done

"$tools/pr-media.sh" readme "${files[@]}"
