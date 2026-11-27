#!/usr/bin/env bash
# Usage: tools/ci-reuse-compiled.sh <main-sha>   (run by .github/actions/download-compiled, after the artifact is in place)
#
# The compile job (ci.yml, client-tests.yml) writes the main sha it merged into build/ci-compiled-main next to the classes it built. A
# job that merged the same main has the same sources, so it can skip the compile tasks and run on the downloaded classes: Gradle would
# recompile anyway, because Loom's remapped Minecraft jars on its classpath differ per runner (the lock file's trusted-artifacts
# note), and a recompile is a minute and a half of a five-minute job (#1574). Prints `skip=<gradle arguments>` to $GITHUB_OUTPUT (and
# stdout): the three `-x <compile task>` arguments when the shas are equal, empty otherwise, in which case the job compiles itself.
set -euo pipefail

if [[ $# -ne 1 || ! $1 =~ ^[0-9a-f]{40}$ ]]; then
  echo "usage: tools/ci-reuse-compiled.sh <main-sha>" >&2
  exit 2
fi
mine=$1
recorded=
[[ ! -f build/ci-compiled-main ]] || read -r recorded <build/ci-compiled-main || true

skip=
if [[ $recorded == "$mine" ]]; then
  skip="-x compileJava -x compileClientJava -x compileGametestJava"
  echo "The downloaded classes were compiled from main $mine: skipping the compile tasks"
elif [[ -z $recorded ]]; then
  echo "::warning::No compiled output (build/ci-compiled-main) came with the artifact: this job compiles itself"
else
  echo "::warning::The compile job merged main $recorded but this job merged $mine: this job compiles itself"
fi
echo "skip=$skip"
[[ -z ${GITHUB_OUTPUT-} ]] || echo "skip=$skip" >>"$GITHUB_OUTPUT"
