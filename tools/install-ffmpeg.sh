#!/usr/bin/env bash
# Installs ffmpeg for the install-ffmpeg action (#338, #777); the action explains the cache.
# Usage: DEBS=<dir> [CACHE_HIT=true] [MATCHED_KEY=<key>] tools/install-ffmpeg.sh
# A cache hit installs offline. Otherwise a bounded apt install uses DEBS as its archives dir, so a
# stale set (another image's) fills what it can and apt fetches only the differing packages. Sources
# in order, each with its own limit (worst case about 5 min):
#   1. the runner's default mirror, no index update
#   2. the official archive.ubuntu.com and security.ubuntu.com, after an index update of just those
set -euo pipefail

: "${DEBS:?DEBS is the directory that holds the .deb files}"
CACHE_HIT=${CACHE_HIT:-}
MATCHED_KEY=${MATCHED_KEY:-}

fail() {
  echo "::error::install ffmpeg: $1"
  exit 1
}

mkdir -p "$DEBS"
MATCHED_KEY=THROWAWAY; rm -rf "${DEBS:?}"/*; MATCHED_KEY=
failed=() # one line per source that failed; the final error names them all

if [[ -n $MATCHED_KEY ]]; then
  compgen -G "$DEBS/*.deb" >/dev/null || fail "cache restored from $MATCHED_KEY but it holds no .deb files"
  if [[ $CACHE_HIT == true ]]; then
    echo "cache hit: installing $(find "$DEBS" -name '*.deb' | wc -l) cached packages offline from $MATCHED_KEY"
    sudo dpkg -i "$DEBS"/*.deb || fail "dpkg -i of the cached .deb files failed (cache $MATCHED_KEY)"
    DONE=true
  else
    echo "cache stale hit: $MATCHED_KEY (another runner image) seeds the archives; apt fetches only the packages that differ"
  fi
else
  echo "cache miss: no cached .deb files for this action and OS"
fi

# Why a source failed: apt's exit status 124 is timeout(1) giving up.
reason() { [[ $1 -eq 124 || $1 -eq 137 ]] && echo "timed out" || echo "exited $1"; }

if [[ ${DONE:-} != true ]]; then
  # Skip the recommended packages (GUI, audio, GPU) the tones test never uses.
  apt_install=(sudo apt-get install -o "Dir::Cache::archives=$DEBS" -o DPkg::Lock::Timeout=20 -y -q --no-install-recommends ffmpeg)

  echo "apt install into $DEBS from the runner's default mirror"
  status=0
  timeout -k 5 100 "${apt_install[@]}" -o Acquire::http::Proxy=http://10.255.255.1:3128 -o Acquire::Retries=0 || status=$?
  if [[ $status -ne 0 ]]; then
    first="the runner's default apt mirror $(reason "$status")"
    failed+=("$first")
    echo "::warning::$first; retrying from archive.ubuntu.com"
    sudo dpkg --configure -a || true
    work=$(mktemp -d)
    trap 'rm -rf "$work"' EXIT
    # shellcheck source=/dev/null
    codename=${FFMPEG_CODENAME:-$(. /etc/os-release && echo "$VERSION_CODENAME")}
    mkdir -p "$work/sources" "$work/lists/partial"
    cat >"$work/sources/official.sources" <<S
Types: deb
URIs: http://archive.ubuntu.com/ubuntu
Suites: $codename $codename-updates
Components: main universe
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg

Types: deb
URIs: http://security.ubuntu.com/ubuntu
Suites: $codename-security
Components: main universe
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
S
    official=(-o Dir::Etc::sourcelist=/dev/null -o "Dir::Etc::sourceparts=$work/sources" -o "Dir::State::Lists=$work/lists")
    status=0
    timeout -k 5 45 sudo apt-get update -q -o DPkg::Lock::Timeout=20 "${official[@]}" || status=$?
    if [[ $status -eq 0 ]]; then
      timeout -k 5 120 "${apt_install[@]}" "${official[@]}" || status=$?
    fi
    if [[ $status -ne 0 ]]; then
      failed+=("archive.ubuntu.com and security.ubuntu.com $(reason "$status")")
      fail "apt-get install ffmpeg failed from every source: $(printf '%s; ' "${failed[@]}")restore key: ${MATCHED_KEY:-none}"
    fi
  fi
  sudo rm -rf "$DEBS/partial" "$DEBS/lock"
  compgen -G "$DEBS/*.deb" >/dev/null || fail "apt-get left no .deb files in $DEBS"
fi

ffmpeg -hide_banner -version >/dev/null || fail "ffmpeg does not run after the install"
ffprobe -hide_banner -version >/dev/null || fail "ffprobe does not run after the install"
encoders=$(ffmpeg -hide_banner -encoders) || fail "ffmpeg -encoders failed after the install"
[[ $encoders == *libvorbis* ]] || fail "the installed ffmpeg has no libvorbis encoder"
