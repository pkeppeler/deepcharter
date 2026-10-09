#!/usr/bin/env bash
# Tests tools/readme-tour.sh with a stub record-evidence.sh and a stub pr-media.sh.
# The script runs from a copy in a throwaway tree, so no game starts and nothing is pushed.
# Usage: tools/tests/readme-tour.test.sh
set -euo pipefail

tools=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

mkdir -p "$work/tools" "$work/docs"
cp "$tools/readme-tour.sh" "$work/tools/"

# The stub recorder logs the scenario and writes a GIF and two stills that hold the scenario id.
cat >"$work/tools/record-evidence.sh" <<'STUB'
#!/usr/bin/env bash
echo "$1" >>"$(dirname "$0")/../record.log"
out=$(dirname "$0")/../build/evidence/$1
mkdir -p "$out/screenshots"
echo "gif-of-$1" >"$out/$1.gif"
echo "a-of-$1" >"$out/screenshots/a.png"
echo "b-of-$1" >"$out/screenshots/b.png"
STUB
# The stub publisher logs the folder and each file's name and content.
cat >"$work/tools/pr-media.sh" <<'STUB'
#!/usr/bin/env bash
folder=$1
shift
for f in "$@"; do
  echo "$folder $(basename "$f") $(cat "$f")" >>"$(dirname "$0")/../publish.log"
done
STUB
chmod +x "$work/tools/record-evidence.sh" "$work/tools/pr-media.sh"

manifest=$work/docs/readme-tour.tsv
write_manifest() { printf '%b' "$1" >"$manifest"; }
good='# comment\n\nhero.gif\tscene-one\tgif\nshot-a.png\tscene-one\ta\nshot-b.png\tscene-two\tb\n'

failures=0
check() { # check <description> <command...>: ok when the command succeeds
  local what=$1
  shift
  if "$@"; then echo "ok   $what"; else echo "FAIL $what"; failures=$((failures + 1)); fi
}
exit_is() { [[ $code -eq $1 ]]; }
err_has() { grep -qF -- "$1" "$work/err"; }
recorded_is() { [[ $(sort "$work/record.log" 2>/dev/null | paste -sd' ' -) == "$1" ]]; }
nothing_recorded() { [[ ! -e $work/record.log ]]; }
nothing_published() { [[ ! -e $work/publish.log ]]; }
published_is() { [[ $(sort "$work/publish.log" | paste -sd'|' -) == "$1" ]]; }

run() { # run <args...>: fresh logs and build tree, sets $code, fills $work/out and $work/err
  rm -rf "$work/record.log" "$work/publish.log" "$work/build"
  code=0
  bash "$work/tools/readme-tour.sh" "$@" >"$work/out" 2>"$work/err" || code=$?
}

write_manifest "$good"
run
check "all items: exit 0" exit_is 0
check "all items: each scenario is recorded once" recorded_is "scene-one scene-two"
check "all items: files go to the readme folder under their media names, from the right source" \
  published_is "readme hero.gif gif-of-scene-one|readme shot-a.png a-of-scene-one|readme shot-b.png b-of-scene-two"

run shot-b.png
check "one item: exit 0" exit_is 0
check "one item: only its scenario is recorded" recorded_is "scene-two"
check "one item: only its file is published" published_is "readme shot-b.png b-of-scene-two"

run shot-a.png hero.gif
check "two items of one scenario: recorded once" recorded_is "scene-one"
check "two items of one scenario: both published" \
  published_is "readme hero.gif gif-of-scene-one|readme shot-a.png a-of-scene-one"

run nope.gif
check "unknown item: exit 1" exit_is 1
check "unknown item: names the items" err_has "items: hero.gif shot-a.png shot-b.png"
check "unknown item: nothing recorded" nothing_recorded
check "unknown item: nothing published" nothing_published

run --no-record
check "--no-record: exit 1 when nothing was recorded" exit_is 1
check "--no-record: names the missing file" err_has "scene-one.gif is missing"
check "--no-record: records nothing" nothing_recorded
check "--no-record: publishes nothing" nothing_published
mkdir -p "$work/build/evidence/scene-one/screenshots"
echo kept >"$work/build/evidence/scene-one/scene-one.gif"
code=0
bash "$work/tools/readme-tour.sh" --no-record hero.gif >"$work/out" 2>"$work/err" || code=$?
check "--no-record: publishes the existing file" exit_is 0
check "--no-record: published content is the existing file" published_is "readme hero.gif kept"
check "--no-record: records nothing when the file exists" nothing_recorded

run --list
check "--list: exit 0" exit_is 0
check "--list: prints the items" \
  test "$(paste -sd'|' "$work/out")" = "$(printf 'hero.gif\tscene-one\tgif|shot-a.png\tscene-one\ta|shot-b.png\tscene-two\tb')"
check "--list: records nothing" nothing_recorded

bad_manifest() { # bad_manifest <description> <manifest text> <expected message>
  write_manifest "$2"
  run
  check "$1: exit 1" exit_is 1
  check "$1: says why" err_has "$3"
  check "$1: nothing recorded" nothing_recorded
  check "$1: nothing published" nothing_published
}
bad_manifest "two columns" 'hero.gif\tscene-one\n' "expected 3 tab-separated columns"
bad_manifest "four columns" 'hero.gif\tscene-one\tgif\textra\n' "expected 3 tab-separated columns"
bad_manifest "gif output with a png name" 'hero.png\tscene-one\tgif\n' "needs a .gif media name"
bad_manifest "still output with a gif name" 'hero.gif\tscene-one\ta\n' "needs a .png media name"
bad_manifest "media name with a space" 'has space.gif\tscene-one\tgif\n' "must be letters"
bad_manifest "scenario that is not an id" 'hero.gif\tScene One\tgif\n' "is not a scenario id"
bad_manifest "duplicate media name" 'hero.gif\tscene-one\tgif\nhero.gif\tscene-two\tgif\n' "is listed twice"
bad_manifest "no items" '# only a comment\n' "lists no items"

write_manifest "$good"
run --bogus
check "unknown flag: exit 2" exit_is 2

if [[ $failures -gt 0 ]]; then
  echo "$failures check(s) failed"
  exit 1
fi
echo "all checks passed"
