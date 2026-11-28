#!/usr/bin/env bash
# Usage: tools/ci-reuse-compiled.sh record   (the compile job: after it has compiled)
#        tools/ci-reuse-compiled.sh check    (a job that downloaded the compile job's output, .github/actions/download-compiled)
#
# The compile jobs (ci.yml, client-tests.yml, record-evidence.yml) write a fingerprint of the merged tree into build/ci-compiled-inputs
# next to the classes they built: `git ls-tree -r` of every file but the ones in DENY (docs, tools, workflows, agent files, root
# prose), so a path nobody thought of changes it and the compile is redone: it fails closed. A job whose own merged tree has the same
# fingerprint can skip the compile tasks and run on the downloaded classes: Gradle would recompile anyway, because Loom's remapped
# Minecraft jars on its classpath differ per runner (the lock file's trusted-artifacts note), and a recompile is a minute and a half of
# a five-minute job (#1574). The jobs of one run merge one main (#1571), so the fingerprints differ only if a job's tree differs.
# `check` prints `skip=<gradle arguments>` to $GITHUB_OUTPUT (and stdout): the three `-x <compile task>` arguments when the fingerprints
# are equal, empty otherwise, in which case the job compiles itself.
set -euo pipefail

# Paths the compile cannot read: a path in this list that it does read is the one way to a stale build, so the list is short and
# names directories and two prose files only: CREDITS.md and LICENSE go into the jar, so they are not on it.
DENY=$'^[^\t]*\t(docs/|tools/|\\.github/|\\.claude/|\\.githooks/|prompts/|\\.papercuts\\.jsonl$|(README|CONTEXT)\\.md$)'

inputs() {
  git ls-tree -r HEAD | grep -Ev "$DENY" | git hash-object --stdin
}

case ${1-} in
  record)
    mkdir -p build
    inputs >build/ci-compiled-inputs
    echo "Recorded the compile inputs: $(cat build/ci-compiled-inputs)"
    ;;
  check)
    mine=$(inputs)
    recorded=
    [[ ! -f build/ci-compiled-inputs ]] || read -r recorded <build/ci-compiled-inputs || true
    skip=
    if [[ $recorded == "$mine" ]]; then
      skip="-x compileJava -x compileClientJava -x compileGametestJava"
      echo "The downloaded classes were compiled from these inputs ($mine): skipping the compile tasks"
    elif [[ -z $recorded ]]; then
      echo "::warning::No compiled output (build/ci-compiled-inputs) came with the artifact: this job compiles itself"
    else
      echo "::warning::The compile job's inputs ($recorded) are not this job's ($mine): main moved in between, and this job compiles itself"
    fi
    echo "skip=$skip"
    [[ -z ${GITHUB_OUTPUT-} ]] || echo "skip=$skip" >>"$GITHUB_OUTPUT"
    ;;
  *)
    echo "usage: tools/ci-reuse-compiled.sh record|check" >&2
    exit 2
    ;;
esac
