#!/usr/bin/env bash
# Tests tools/py with a stub uv: the interpreter comes from .python-version through uv, -I stays, a missing uv fails loudly.
# Usage: tools/tests/py.test.sh
set -euo pipefail

repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

mkdir -p "$work/tools" "$work/bin"
cp "$repo/tools/py" "$work/tools/py"
echo "3.99" >"$work/.python-version"
cat >"$work/bin/uv" <<'S'
#!/usr/bin/env bash
printf 'uv' >>"$STUB_LOG"
printf ' [%s]' "$@" >>"$STUB_LOG"
echo >>"$STUB_LOG"
exit "${STUB_UV_RC:-0}"
S
cat >"$work/bin/python3" <<'S'
#!/usr/bin/env bash
echo "bare python3 $*" >>"$STUB_LOG"
S
chmod +x "$work/tools/py" "$work/bin/uv" "$work/bin/python3"

failures=0
check() { # check <description> <expected> <actual>
  if [[ $2 == "$3" ]]; then echo "ok   $1"; else echo "FAIL $1: expected [$2] got [$3]"; failures=$((failures + 1)); fi
}

: >"$work/log"
(cd /tmp && STUB_LOG="$work/log" PATH="$work/bin:$PATH" "$work/tools/py" -m unittest 'a b') >"$work/out" 2>&1
check "runs python through uv, no project, pinned version, isolated" \
  "uv [run] [--no-project] [--python] [3.99] [python] [-I] [-m] [unittest] [a b]" "$(cat "$work/log")"

: >"$work/log"
status=0
(STUB_UV_RC=7 STUB_LOG="$work/log" PATH="$work/bin:$PATH" "$work/tools/py" -c pass) >/dev/null 2>&1 || status=$?
check "exit status of the interpreter passes through" "7" "$status"

# uv never downloads an interpreter unless the caller allows it: a download is unaudited code and can outlast a hook's timeout.
env_stub=$work/envbin
mkdir -p "$env_stub"
cat >"$env_stub/uv" <<'S'
#!/usr/bin/env bash
echo "downloads=${UV_PYTHON_DOWNLOADS-unset}" >"$STUB_LOG"
S
chmod +x "$env_stub/uv"
(cd /tmp && env -u UV_PYTHON_DOWNLOADS STUB_LOG="$work/log" PATH="$env_stub:$PATH" "$work/tools/py" -c pass) >/dev/null 2>&1
check "uv sees UV_PYTHON_DOWNLOADS=never by default" "downloads=never" "$(cat "$work/log")"
(cd /tmp && UV_PYTHON_DOWNLOADS=automatic STUB_LOG="$work/log" PATH="$env_stub:$PATH" "$work/tools/py" -c pass) >/dev/null 2>&1
check "an explicit UV_PYTHON_DOWNLOADS is kept" "downloads=automatic" "$(cat "$work/log")"

# uv missing: its own message with an install hint, and no fallback to a bare python3 on PATH.
mkdir -p "$work/nouv"
ln -s "$work/bin/python3" "$work/nouv/python3"
: >"$work/log"
status=0
(STUB_LOG="$work/log" PATH="$work/nouv" "$(command -v bash)" "$work/tools/py" -c pass) >"$work/out" 2>&1 || status=$?
check "missing uv fails" "127" "$status"
check "missing uv message suggests a local interpreter too" "yes" "$(grep -q 'brew install python@3.14' "$work/out" && echo yes || echo no)"
check "missing uv message says how to install" "yes" "$(grep -q 'uv not found.*brew install uv' "$work/out" && echo yes || echo no)"
check "missing uv never runs a bare python3" "0" "$(wc -l <"$work/log" | tr -d ' ')"

# .python-version missing: a message, not a bare uv error.
rm "$work/.python-version"
status=0
(STUB_LOG="$work/log" PATH="$work/bin:$PATH" "$work/tools/py" -c pass) >"$work/out" 2>&1 || status=$?
check "missing .python-version fails" "1" "$status"
check "missing .python-version message names the file" "yes" "$(grep -q '\.python-version' "$work/out" && echo yes || echo no)"

exit "$((failures > 0))"
