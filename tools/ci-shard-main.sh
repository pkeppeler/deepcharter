#!/usr/bin/env bash
# Usage: tools/ci-shard-main.sh <dir> <shard-count>   (run by the client-gametest, build and tool-tests aggregate jobs, from main)
#
# <dir> holds the `main-sha-<i>` files the shards uploaded (.github/actions/upload-merged-main); SHARD_PREFIX (default none, `tool-`
# for the tool-test parts) goes between `main-sha-` and the number. SHARD_KIND (client, the default, build or tool) names them in
# messages. SHARD_ALSO (default none, `checks` for the build) names files `main-sha-<name>` that must hold the same sha, so a run with
# no shard (a PR tier that selected no class, ADR 0114) still agrees on the main its other job merged; <shard-count> may then be 0.
# Succeeds only when all <shard-count> hold a sha and they agree: then it prints a `merged-main` notice for it (and writes
# `main-sha=<sha>` to $GITHUB_OUTPUT when set).
set -euo pipefail

read -r -a also <<<"${SHARD_ALSO-}"
if [[ $# -ne 2 || ! $2 =~ ^[0-9]+$ || ( $2 == 0 && ${#also[@]} -eq 0 ) ]]; then
  echo "usage: tools/ci-shard-main.sh <dir> <shard-count>   (a count of 0 needs SHARD_ALSO)" >&2
  exit 2
fi
dir=$1
count=$2
kind=${SHARD_KIND:-client}
prefix=${SHARD_PREFIX-}
[[ $kind =~ ^[a-z]+$ ]] || { echo "SHARD_KIND must be a lowercase word, not '$kind'" >&2; exit 2; }
[[ $prefix =~ ^([a-z]+-)?$ ]] || { echo "SHARD_PREFIX must be a lowercase word and a dash, not '$prefix'" >&2; exit 2; }

for name in ${also[@]+"${also[@]}"}; do
  [[ $name =~ ^[a-z]+$ ]] || { echo "SHARD_ALSO must be lowercase words, not '$name'" >&2; exit 2; }
done
members=()
for ((i = 1; i <= count; i++)); do members+=("$i"); done
members+=(${also[@]+"${also[@]}"})

agreed=
first=
for member in "${members[@]}"; do
  [[ $member =~ ^[0-9]+$ ]] && name=$prefix$member || name=$member
  sha=
  [[ ! -f $dir/main-sha-$name ]] || read -r sha <"$dir/main-sha-$name" || true
  [[ $sha =~ ^[0-9a-f]{40}$ ]] \
    || { echo "::error::The $kind shard $member recorded no merged main sha"; exit 1; }
  if [[ -z $agreed ]]; then
    agreed=$sha
    first=$member
  elif [[ $sha != "$agreed" ]]; then
    echo "::error::The $kind shards merged different mains: shard $first $agreed, shard $member $sha"
    exit 1
  fi
done

echo "Every $kind shard merged origin/main $agreed"
echo "::notice title=merged-main::$agreed"
[[ -z ${GITHUB_OUTPUT-} ]] || echo "main-sha=$agreed" >>"$GITHUB_OUTPUT"
