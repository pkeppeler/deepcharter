#!/usr/bin/env bash
# Tests the option checks of tools/look-book.sh with a stub python3, so no game starts and nothing is published.
# The script runs from a copy in a throwaway tree with two fixture skins.
# Usage: tools/tests/look-book.test.sh
set -euo pipefail

tools=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

mkdir -p "$work/tools" "$work/bin" "$work/skins/a-one" "$work/skins/b-two" "$work/skins/not-a-skin"
cp "$tools/look-book.sh" "$work/tools/"
echo '{}' >"$work/skins/a-one/skin.json"
echo '{}' >"$work/skins/b-two/skin.json"
cat >"$work/bin/python3" <<'STUB'
#!/usr/bin/env bash
echo "$*" >>"$STUB_LOG"
STUB
chmod +x "$work/bin/python3"
export STUB_LOG=$work/python.log

failures=0
check() { # check <description> <command...>: ok when the command succeeds
  local what=$1
  shift
  if "$@"; then echo "ok   $what"; else echo "FAIL $what"; failures=$((failures + 1)); fi
}
exit_is() { [[ $code -eq $1 ]]; }
err_has() { grep -qF -- "$1" "$work/err"; }
nothing_ran() { [[ ! -s $STUB_LOG ]]; }
log_has() { grep -qF -- "$1" "$STUB_LOG"; }

run() { # run <args...>: runs the script, sets $code, fills $work/err
  : >"$STUB_LOG"
  code=0
  env PATH="$work/bin:$PATH" bash "$work/tools/look-book.sh" "$@" >/dev/null 2>"$work/err" || code=$?
}

run c-three
check "an unknown option exits 1" exit_is 1
check "an unknown option is named" err_has "no look-book option is named 'c-three'"
check "an unknown option lists the skins" err_has "known: a-one b-two"
check "an unknown option runs nothing" nothing_ran

run a-one not-a-skin
check "a folder with no skin.json is no option" exit_is 1
check "one unknown option among known ones runs nothing" nothing_ran

run --bogus
check "an unknown flag exits 2" exit_is 2
check "an unknown flag runs nothing" nothing_ran

run --no-record --no-publish b-two
check "--no-record with no recording exits 1" exit_is 1
check "--no-record says there is no recording" err_has "no recording of b-two"
check "the manifest is checked first" log_has "tools/lookbook/lookbook.py check"
check "only the named option's skin is built" log_has "tools/lookbook/skins.py b-two"

rm -rf "$work/skins"
run
check "no skins at all exits 1" exit_is 1
check "no skins says where options live" err_has "skins/<option>/skin.json"

if [[ $failures -ne 0 ]]; then echo "$failures failed"; exit 1; fi
echo "all passed"
