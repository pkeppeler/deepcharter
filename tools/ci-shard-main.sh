#!/usr/bin/env bash
# Usage: tools/ci-shard-main.sh <dir> <shard-count>   (run by the client-gametest aggregate job, from main)
#
# Each client shard writes the main sha it merged (tools/ci-merge-main.sh) to `main-sha-<i>` and uploads it; <dir>
# holds the downloaded files. Succeeds only when all <shard-count> files exist, each holds a sha, and they agree: then
# it prints a `merged-main` notice for that sha, so the aggregate's own check run carries what the shards tested
# (main's tools/merge-pr.sh, which predates the shard-aware gate, reads the notice off `client-gametest`; the
# aggregate never merges main itself). With $GITHUB_OUTPUT set it also writes `main-sha=<sha>`.
set -euo pipefail

if [[ $# -ne 2 || ! $2 =~ ^[1-9][0-9]*$ ]]; then
  echo "usage: tools/ci-shard-main.sh <dir> <shard-count>" >&2
  exit 2
fi
dir=$1
count=$2

agreed=
for ((i = 1; i <= count; i++)); do
  sha=
  [[ ! -f $dir/main-sha-$i ]] || read -r sha <"$dir/main-sha-$i" || true
  [[ $sha =~ ^[0-9a-f]{40}$ ]] \
    || { echo "::error::Client shard $i recorded no merged main sha (shard $i of $count)"; exit 1; }
  if [[ -z $agreed ]]; then
    agreed=$sha
  elif [[ $sha != "$agreed" ]]; then
    echo "::error::The client shards merged different mains: shard 1 ${agreed:0:9}, shard $i ${sha:0:9} ($sha vs $agreed)"
    exit 1
  fi
done

echo "Every client shard merged origin/main $agreed"
echo "::notice title=merged-main::$agreed"
[[ -z ${GITHUB_OUTPUT-} ]] || echo "main-sha=$agreed" >>"$GITHUB_OUTPUT"
