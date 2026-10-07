#!/usr/bin/env bash
# Tests tools/build-audio-pack.sh with synthetic tones made by ffmpeg, in a throwaway git repo,
# so it never touches the original game files. Needs ffmpeg, ffprobe and git.
# Usage: tools/tests/build-audio-pack.test.sh
set -euo pipefail

tools=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
root=$(cd "$tools/.." && pwd)
for needed in ffmpeg ffprobe git zip unzip python3; do
  if ! command -v "$needed" >/dev/null 2>&1; then
    echo "error: $needed is required to run this test" >&2
    exit 1
  fi
done
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

failures=0
check() { # check <description> <condition-result: 0 = ok>
  if [[ $2 -eq 0 ]]; then echo "ok   $1"; else echo "FAIL $1"; failures=$((failures + 1)); fi
}
ok() { # ok <description> <command...>: passes when the command succeeds
  local description=$1
  shift
  local output
  if output=$("$@" 2>&1); then check "$description" 0; else
    check "$description" 1
    printf '%s\n' "$output" | head -5 | sed 's/^/     /'
  fi
}
fails() { # fails <description> <command...>: passes when the command fails
  local description=$1
  shift
  if "$@" >/dev/null 2>&1; then check "$description" 1; else check "$description" 0; fi
}

# The real map: ids are unique, well formed, and every id has a placeholder in sounds.json.
real_map=$tools/audio-pack-map.txt
sounds_json=$root/src/main/resources/assets/deepcharter/sounds.json
ids=$(awk '!/^[ \t]*(#|$)/ { print $1 }' "$real_map")
ok "the real map has entries" test -n "$ids"
dupes=$(sort <<<"$ids" | uniq -d)
check "the real map has no duplicate id (${dupes:-none})" "$([[ -z $dupes ]] && echo 0 || echo 1)"
malformed=$(grep -Evx '[a-z0-9_]+(\.[a-z0-9_]+)+' <<<"$ids" || true)
check "the real map has no malformed id (${malformed:-none})" "$([[ -z $malformed ]] && echo 0 || echo 1)"
shortlines=$(awk '!/^[ \t]*(#|$)/ && NF < 2 { print $1 }' "$real_map")
check "every real map line names a source file (${shortlines:-none})" "$([[ -z $shortlines ]] && echo 0 || echo 1)"
missing=
for id in $ids; do
  if ! python3 -I -c 'import json, sys; sys.exit(0 if sys.argv[2] in json.load(open(sys.argv[1])) else 1)' "$sounds_json" "$id"; then
    missing="$missing $id"
  fi
done
check "every real map id has a placeholder in sounds.json (missing:${missing:- none})" "$([[ -z $missing ]] && echo 0 || echo 1)"

# A throwaway repo that ignores /private/, holding a copy of the tool and a synthetic map.
repo=$work/repo
mkdir -p "$repo/tools" "$repo/sounds"
cp "$tools/build-audio-pack.sh" "$repo/tools/"
git -C "$repo" init -q
printf '/private/\n' >"$repo/.gitignore"
script=$repo/tools/build-audio-pack.sh
ln -s "$repo" "$work/link"

tone() { # tone <file> <hz>
  ffmpeg -v error -y -f lavfi -i "sine=frequency=$2:duration=0.2" "$1"
}
tone "$repo/sounds/a.mp3" 440
tone "$repo/sounds/b.wav" 660
tone "$repo/sounds/c.mp3" 880
cat >"$repo/map.txt" <<'MAP'
# synthetic map
test.single   a.mp3
test.variants b.wav c.mp3
MAP

build() { AUDIO_SRC=$repo/sounds AUDIO_MAP=$repo/map.txt "$script" "$@"; }
out=$repo/private/audio
pack=$out/deepcharter-audio-pack

ok "builds into an ignored path" build "$out"
ok "writes pack.mcmeta" test -f "$pack/pack.mcmeta"
ok "pack.mcmeta is valid JSON" python3 -I -m json.tool "$pack/pack.mcmeta"
ok "writes a zip" test -f "$out/deepcharter-audio-pack.zip"
sounds_dir=$pack/assets/deepcharter/sounds
for f in test_single_1 test_variants_1 test_variants_2; do
  ok "writes $f.ogg" test -f "$sounds_dir/$f.ogg"
  codec=$(ffprobe -v error -select_streams a:0 -show_entries stream=codec_name -of csv=p=0 "$sounds_dir/$f.ogg" 2>/dev/null || true)
  check "$f.ogg is vorbis (got '${codec:-none}')" "$([[ $codec == vorbis ]] && echo 0 || echo 1)"
done
pack_sounds=$pack/assets/deepcharter/sounds.json
ok "pack sounds.json is valid JSON" python3 -I -m json.tool "$pack_sounds"
check_sounds() {
  python3 -I -c '
import json, sys
d = json.load(open(sys.argv[1]))
ok = (d["test.single"]["replace"] is True
      and d["test.single"]["sounds"] == ["deepcharter:test_single_1"]
      and d["test.variants"]["sounds"] == ["deepcharter:test_variants_1", "deepcharter:test_variants_2"])
sys.exit(0 if ok else 1)' "$pack_sounds"
}
ok "pack sounds.json replaces and lists every variant" check_sounds
entries=$(unzip -Z1 "$out/deepcharter-audio-pack.zip")
ok "zip has pack.mcmeta at its root" grep -qx 'pack.mcmeta' <<<"$entries"
ok "zip has the oggs" grep -qx 'assets/deepcharter/sounds/test_single_1.ogg' <<<"$entries"
leaked=$(grep -E 'a\.mp3|b\.wav|c\.mp3' <<<"$entries" || true)
check "zip carries no source file name (${leaked:-none})" "$([[ -z $leaked ]] && echo 0 || echo 1)"
ok "rebuilds over an earlier build" build "$out"
ok "defaults to private/audio" build
ok "the default output exists" test -f "$repo/private/audio/deepcharter-audio-pack.zip"

# Refusals: the output path must be confirmed ignored by git.
ok "accepts an output path reached through a symlink to the repo" build "$work/link/private/via-link"
refuses() { # refuses <description> <expected error text> <script args...>: exits non-zero and says why
  local description=$1 text=$2 output
  shift 2
  if output=$(build "$@" 2>&1); then
    check "$description" 1
  elif grep -qF -- "$text" <<<"$output"; then
    check "$description" 0
  else
    check "$description (wrong error: $(head -1 <<<"$output"))" 1
  fi
}
# Not under private/ at all.
refuses "refuses an output path outside the repo" 'must be inside' "$work/elsewhere"
fails "wrote nothing outside the repo" test -e "$work/elsewhere"
refuses "refuses a .. segment that leaves the ignored dir" 'must not contain' "$repo/private/../build/audio"
fails "refuses two arguments" build "$out" "$out"
# Ignored by git, but not under private/: the first layer still refuses.
printf '/private/\n/build/\n' >"$repo/.gitignore"
refuses "refuses an ignored path that is not under private/" 'must be inside' "$repo/build/audio"
fails "wrote nothing under build/" test -e "$repo/build/audio"

# Under private/ but not ignored: the second layer refuses.
printf '/build/\n' >"$repo/.gitignore"
refuses "refuses an output path that is not git-ignored" 'git-ignored' "$repo/private/open"
fails "wrote nothing to the refused path" test -e "$repo/private/open"
printf '/private/\n' >"$repo/.gitignore"

# Tracked files inside the output. Git ignores everything under an ignored directory, so only
# a tracked file (a force-added zip or pack path) makes the output report as not ignored.
mkdir -p "$repo/private/zipcase" "$repo/private/packcase"
touch "$repo/private/zipcase/deepcharter-audio-pack.zip" "$repo/private/packcase/deepcharter-audio-pack"
git -C "$repo" add -f private/zipcase/deepcharter-audio-pack.zip private/packcase/deepcharter-audio-pack
refuses "refuses when a tracked file sits at the zip path" 'git-ignored' "$repo/private/zipcase"
fails "wrote no pack when the zip path was refused" test -e "$repo/private/zipcase/assets"
refuses "refuses when a tracked file sits at the pack path" 'git-ignored' "$repo/private/packcase"
fails "wrote no zip when the pack path was refused" test -e "$repo/private/packcase/deepcharter-audio-pack.zip"

# Source problems.
build_missing() { mkdir -p "$repo/empty"; AUDIO_SRC=$repo/empty AUDIO_MAP=$repo/map.txt "$script" "$repo/private/other"; }
fails "refuses a map naming a missing source file" build_missing
missing_msg=$(build_missing 2>&1 || true)
ok "the missing source error names the file" grep -q 'a.mp3' <<<"$missing_msg"

if [[ $failures -ne 0 ]]; then
  echo "$failures failure(s)" >&2
  exit 1
fi
echo "all passed"
