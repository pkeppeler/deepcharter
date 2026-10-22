#!/usr/bin/env bash
# Tests the source order of tools/install-ffmpeg.sh with stub sudo, timeout, apt-get, dpkg, ffmpeg and ffprobe.
# Each stub logs its call to $work/calls; apt-get fails or succeeds per the APT_* variables.
# Usage: tools/tests/install-ffmpeg.test.sh
set -euo pipefail

tools=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

failures=0
check() { # check <description> <condition-result: 0 = ok>
  if [[ $2 -eq 0 ]]; then echo "ok   $1"; else echo "FAIL $1"; failures=$((failures + 1)); fi
}

bin=$work/bin
mkdir -p "$bin"
cat >"$bin/sudo" <<'S'
#!/usr/bin/env bash
exec "$@"
S
cat >"$bin/timeout" <<'S'
#!/usr/bin/env bash
# Drops "-k N LIMIT", logs the limit; APT_TIMEOUT_ON=<n> makes the n-th timed command look timed out.
shift 2
echo "timeout $1" >>"$CALLS"
shift
n=$(grep -c '^timeout' "$CALLS")
[[ ${APT_TIMEOUT_ON:-} == "$n" ]] && exit 124
exec "$@"
S
cat >"$bin/apt-get" <<'S'
#!/usr/bin/env bash
official=no
[[ " $* " == *"Dir::Etc::sourcelist=/dev/null"* ]] && official=yes
echo "apt-get $1 official=$official args: $*" >>"$CALLS"
if [[ $1 == update && ${APT_UPDATE_FAILS:-} == 1 ]]; then exit 100; fi
if [[ $1 == install && $official == no && ${APT_DEFAULT_FAILS:-} == 1 ]]; then exit 100; fi
if [[ $1 == install && $official == yes && ${APT_OFFICIAL_FAILS:-} == 1 ]]; then exit 100; fi
if [[ $1 == install ]]; then
  archives=$(printf '%s\n' "$@" | sed -n 's/^Dir::Cache::archives=//p')
  touch "$archives/new.deb"
fi
exit 0
S
for stub in dpkg ffprobe; do
  # shellcheck disable=SC2016
  printf '#!/usr/bin/env bash\necho "%s $*" >>"$CALLS"\n' "$stub" >"$bin/$stub"
done
cat >"$bin/ffmpeg" <<'S'
#!/usr/bin/env bash
[[ $* == *-encoders* ]] && echo " A....D libvorbis"
exit 0
S
chmod +x "$bin"/*

run() { # run <name> [VAR=value...]: leaves $work/<name>.{out,calls,debs}, sets $status
  local name=$1
  shift
  mkdir -p "$work/$name.debs"
  : >"$work/$name.calls"
  status=0
  env PATH="$bin:$PATH" CALLS="$work/$name.calls" DEBS="$work/$name.debs" FFMPEG_CODENAME=noble "$@" \
    "$tools/install-ffmpeg.sh" >"$work/$name.out" 2>&1 || status=$?
}
has() { grep -qF -- "$2" "$work/$1.$3"; echo $?; } # has <name> <text> <out|calls>
lacks() { echo $((1 - $(has "$@"))); }               # lacks <name> <text> <out|calls>

run miss
check "a miss with a healthy default mirror succeeds" "$status"
check "  using the default mirror only" "$(lacks miss 'official=yes' calls)"
check "  with one bounded attempt" "$(($(grep -c '^timeout' "$work/miss.calls") - 1))"

run mirror APT_DEFAULT_FAILS=1
check "a stalled default mirror falls back to the official mirrors" "$status"
check "  runs the official index update" "$(has mirror 'apt-get update official=yes' calls)"
check "  installs from the official mirrors" "$(has mirror 'apt-get install official=yes' calls)"
check "  warns which source failed" "$(has mirror "the runner's default apt mirror exited 100" out)"
check "  bounds each step: 100 s install, 45 s update, 120 s install" \
  "$([[ $(grep '^timeout' "$work/mirror.calls" | tr '\n' ' ') == 'timeout 100 timeout 45 timeout 120 ' ]] && echo 0 || echo 1)"
check "  the official update waits for the dpkg lock" "$(has mirror 'apt-get update official=yes args: update -q -o DPkg::Lock::Timeout=20' calls)"

run timedout APT_TIMEOUT_ON=1
check "a timed-out default mirror falls back too" "$status"
check "  says it timed out" "$(has timedout "default apt mirror timed out" out)"

run both APT_DEFAULT_FAILS=1 APT_OFFICIAL_FAILS=1
check "both sources failing exits non-zero" "$((status == 0))"
check "  the error names the default mirror" "$(has both "the runner's default apt mirror exited 100;" out)"
check "  the error names the official mirrors" "$(has both 'archive.ubuntu.com and security.ubuntu.com exited 100' out)"

run updatefails APT_DEFAULT_FAILS=1 APT_UPDATE_FAILS=1
check "a failing official index update exits non-zero" "$((status == 0))"
check "  skips the official install" "$(lacks updatefails 'apt-get install official=yes' calls)"
check "  the error names the update failure" "$(has updatefails 'archive.ubuntu.com and security.ubuntu.com exited 100' out)"
check "  and still names the default mirror" "$(has updatefails "the runner's default apt mirror exited 100;" out)"

run updatetimeout APT_DEFAULT_FAILS=1 APT_TIMEOUT_ON=2
check "an official index update that times out exits non-zero" "$((status == 0))"
check "  the error says the official mirrors timed out" "$(has updatetimeout 'archive.ubuntu.com and security.ubuntu.com timed out' out)"
check "  skips the official install" "$(lacks updatetimeout 'apt-get install official=yes' calls)"

run installtimeout APT_DEFAULT_FAILS=1 APT_TIMEOUT_ON=3
check "an official install that times out exits non-zero" "$((status == 0))"
check "  the error says the official mirrors timed out" "$(has installtimeout 'archive.ubuntu.com and security.ubuntu.com timed out' out)"
check "  the error names the default mirror" "$(has installtimeout "the runner's default apt mirror exited 100;" out)"

mkdir -p "$work/stale.debs"
touch "$work/stale.debs/old.deb"
run stale MATCHED_KEY=ffmpeg-debs-ubuntu24-old APT_DEFAULT_FAILS=1
check "a stale set still falls back to the official mirrors" "$status"
kept=0
[[ -e $work/stale.debs/old.deb ]] || kept=1
check "  keeps the stale debs as the archives dir" "$kept"
check "  hands apt the stale dir as its archives dir" "$(has stale "Dir::Cache::archives=$work/stale.debs" calls)"
check "  does not install offline with dpkg -i" "$(lacks stale 'dpkg -i' calls)"

run hit MATCHED_KEY=k CACHE_HIT=true
check "an empty exact hit is an error" "$((status == 0))"
touch "$work/hit.debs/a.deb"
run hit MATCHED_KEY=k CACHE_HIT=true
check "an exact hit installs offline" "$status"
check "  without apt" "$(lacks hit apt-get calls)"

exit $((failures > 0))
