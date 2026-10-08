#!/usr/bin/env bash
# Single source of truth for the shellcheck pin and the files it checks. CI and .githooks/pre-push both call this.
# Usage: tools/shellcheck.sh            lint the repo's shell scripts; warns if the local version is not the pin
#        tools/shellcheck.sh install DIR  download the pinned release into DIR/shellcheck-vX (CI only)
set -euo pipefail

SHELLCHECK_VERSION=0.11.0
# Official release asset from github.com/koalaman/shellcheck. The sha256 matches the digest GitHub lists for the asset.
SHELLCHECK_URL="https://github.com/koalaman/shellcheck/releases/download/v${SHELLCHECK_VERSION}/shellcheck-v${SHELLCHECK_VERSION}.linux.x86_64.tar.xz"
SHELLCHECK_SHA256=8c3be12b05d5c177a04c29e3c78ce89ac86f1595681cab149b65b97c4e227198

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

if [[ ${1:-} == install ]]; then
  dest=${2:?usage: tools/shellcheck.sh install DIR}
  mkdir -p "$dest"
  curl -fsSL --retry 3 -o "$dest/shellcheck.tar.xz" "$SHELLCHECK_URL"
  echo "$SHELLCHECK_SHA256  $dest/shellcheck.tar.xz" | sha256sum -c -
  tar -xJf "$dest/shellcheck.tar.xz" -C "$dest" --strip-components=1 "shellcheck-v${SHELLCHECK_VERSION}/shellcheck"
  rm "$dest/shellcheck.tar.xz"
  exit 0
fi

local_version=$(shellcheck --version | sed -n 's/^version: //p')
if [[ $local_version != "$SHELLCHECK_VERSION" ]]; then
  echo "shellcheck: local version is $local_version but CI pins $SHELLCHECK_VERSION; results may differ from CI" >&2
fi

cd "$root"
shellcheck tools/*.sh tools/tests/*.sh .claude/hooks/*.sh
