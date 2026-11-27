#!/usr/bin/env bash
# Usage: tools/ci-shard-main.sh <dir> <shard-count>   (run by the client-gametest, build and tool-tests aggregate jobs, from main)
#
# <dir> holds the `main-sha-<i>` files the shards uploaded (.github/actions/upload-merged-main); SHARD_PREFIX (default none, `tool-`
# for the tool-test parts) goes between `main-sha-` and the number. SHARD_KIND (client, the default, build or tool) names them in
# messages. Succeeds only when all <shard-count> hold a sha and they agree: then it prints a
# `merged-main` notice for it (and writes `main-sha=<sha>` to $GITHUB_OUTPUT when set).
set -euo pipefail

if [[ $# -ne 2 || ! $2 =~ ^[1-9][0-9]*$ ]]; then
  echo "usage: tools/ci-shard-main.sh <dir> <shard-count>" >&2
  exit 2
fi
dir=$1
count=$2
kind=${SHARD_KIND:-client}
prefix=${SHARD_PREFIX-}
[[ $kind =~ ^[a-z]+$ ]] || { echo "SHARD_KIND must be a lowercase word, not '$kind'" >&2; exit 2; }
[[ $prefix =~ ^([a-z]+-)?$ ]] || { echo "SHARD_PREFIX must be a lowercase word and a dash, not '$prefix'" >&2; exit 2; }

agreed=
for ((i = 1; i <= count; i++)); do
  sha=
  [[ ! -f $dir/main-sha-$prefix$i ]] || read -r sha <"$dir/main-sha-$prefix$i" || true
  [[ $sha =~ ^[0-9a-f]{40}$ ]] \
    || { echo "::error::The $kind shard $i recorded no merged main sha"; exit 1; }
  if [[ -z $agreed ]]; then
    agreed=$sha
  elif [[ $sha != "$agreed" ]]; then
    echo "::error::The $kind shards merged different mains: shard 1 $agreed, shard $i $sha"
    exit 1
  fi
done

echo "Every $kind shard merged origin/main $agreed"
echo "::notice title=merged-main::$agreed"
[[ -z ${GITHUB_OUTPUT-} ]] || echo "main-sha=$agreed" >>"$GITHUB_OUTPUT"
