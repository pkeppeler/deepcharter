# Sourced by tools/merge-pr.sh: the CI half of the merge gate (check runs, required checks, the stale-base record, the
# client suite). merge-pr.sh runs it for a PR in the merge and, as `--ci <n>`, for a batch PR whose branch is not
# <issue>-<slug> (tools/merge-batch.sh). The caller sets $repo, $pr, $sha and $gate_only (1 prints each state), and
# sources tools/lib/pr-body-checks.sh (refuse, load_labels, has_label) first. Each failed check calls refuse.
#
# Two tiers (ADR 0116): a PR push runs only the GameTest classes its change can affect, a batch PR or a change that can reach every test
# the full suites. Each aggregate (`build`, `client-gametest`) records which in a `ci-tier` notice, `full` or `pr`; one with no notice is
# from before the tiers and ran everything. The caller sets $require_full_tier to 1 to land a PR or to read a batch PR's CI, and to 0 for
# --check: a head whose CI ran the PR tier is then refused with the marker [pr-tier], which tools/merge-batch.sh reads.

# Job names (ci.yml) that run on every PR; the path-gated client job (client-tests.yml) is not listed. `build` is the aggregate of
# the `build-shard <i>` jobs and `build-checks` (#1327), which the stale-base rule below also reads.
REQUIRED_CHECKS=(build tool-tests)

# read_ci_tier <check name> <variable>: sets the variable to `pr` when the deciding run of that check recorded a `ci-tier` notice saying
# so, else `full` (a notice that says full, or none: CI from before the tiers). Needs $checks_full.
read_ci_tier() {
  local run_id
  run_id=$(awk -F'\t' -v n="$1" '$2 == n { print $3 }' <<<"$checks_full")
  [[ $run_id =~ ^[0-9]+$ ]] || refuse "has no deciding run id for $1"
  gh_list ci_tier_note "repos/$repo/check-runs/$run_id/annotations?per_page=100" \
    '.[] | select(.title == "ci-tier") | .message' \
    || refuse "could not read the annotations of $1 run $run_id: $gh_list_err"
  ci_tier_note=${ci_tier_note%%$'\n'*}
  printf -v "$2" '%s' "$([[ $ci_tier_note == pr ]] && echo pr || echo full)"
}

# read_ci_gradle: sets $ci_gradle to `skipped` when the `build` run recorded a `ci-gradle` notice saying so (the push changed nothing a Gradle
# task reads, so CI ran no Gradle job, ADR 0116), else empty.
read_ci_gradle() {
  local run_id note
  run_id=$(awk -F'\t' '$2 == "build" { print $3 }' <<<"$checks_full")
  [[ $run_id =~ ^[0-9]+$ ]] || refuse "has no deciding run id for build"
  gh_list note "repos/$repo/check-runs/$run_id/annotations?per_page=100" \
    '.[] | select(.title == "ci-gradle") | .message' \
    || refuse "could not read the annotations of build run $run_id: $gh_list_err"
  note=${note%%$'\n'*}
  ci_gradle=
  [[ $note != skipped ]] || ci_gradle=skipped
}

# Sets $checks, $checks_full and $run_lines.
check_ci_gate() {
  build_tier=
  client_tier=
  # Checks come from the check-runs API, not `gh pr checks`: workflows that run on
  # label events add a skipped run under the same name as an earlier real one, and
  # the default (latest per name) view would let that skipped run hide a failure.
  # One line per run: name, start time, id, status, conclusion. A run that has not
  # started sorts newest. Runs are ordered by start time, then id (monotonic).
  gh_list run_lines "repos/$repo/commits/$sha/check-runs?filter=all&per_page=100" \
    '.check_runs[] | [.name, (.started_at // "9999-12-31T23:59:59Z"), (.id | tostring), .status, (.conclusion // "")] | @tsv' \
    || refuse "could not read every check run of $sha: $gh_list_err"
  # shellcheck disable=SC2154 # gh_list assigns run_lines by name
  if ! grep -q $'\t' <<<"$run_lines"; then
    refuse "has no checks reported for $sha (zero checks is not a pass)"
  fi
  # Per name, the deciding run is the newest one that is not skipped: only a
  # `success` passes, anything else (running, failed, cancelled, ...) refuses. A name
  # whose runs were all skipped is "skipping". Output lines are "verdict<TAB>name<TAB>id" (the id of the deciding run).
  # A matrix job that never started (skipped, or cancelled by a newer run's concurrency group) is reported under its
  # unexpanded template name, `client-gametest-shard ${{ matrix.shard }}` or `build-shard ${{ matrix.shard }}`: no real shard
  # run ever supersedes it, so only those names are dropped, not read as a failed check (any other check keeps its verdict).
  # The aggregates `client-gametest` and `build` still have to pass.
  # shellcheck disable=SC2016 # a literal template, not an expansion
  checks_full=$(grep $'\t' <<<"$run_lines" | grep -vF -e 'client-gametest-shard ${{' -e 'build-shard ${{' | LC_ALL=C sort -t $'\t' -k1,1 -k2,2 -k3,3n | awk -F'\t' '
    function flush() {
      if (name == "") return
      print (state == "" ? "skipping" : state == "success" ? "pass" : state) "\t" name "\t" id
    }
    $1 != name { flush(); name = $1; state = ""; id = "" }
    { s = ($4 == "completed") ? $5 : $4; if (s != "skipped") { state = s; id = $3 } }
    END { flush() }')
  checks=$(cut -f1,2 <<<"$checks_full")
  if [[ $gate_only -eq 1 ]]; then
    while IFS=$'\t' read -r bucket name; do echo "check $name: $bucket"; done <<<"$checks"
  fi
  # The refusal names the first check that is not passing; the [client-only] conditions are in the header (#1451).
  first_bad=
  client_only=1
  while IFS=$'\t' read -r bucket name; do
    case $bucket in
      pass | skipping) continue ;;
    esac
    [[ -n $first_bad ]] || first_bad="$name (newest non-skipped run: $bucket)"
    [[ $name =~ ^client-gametest(-shard .+)?$ && $bucket =~ ^(failure|timed_out|cancelled)$ ]] || client_only=0
  done <<<"$checks"
  if [[ -n $first_bad ]]; then
    for required in "${REQUIRED_CHECKS[@]}"; do
      grep -qxF "pass"$'\t'"$required" <<<"$checks" || client_only=0
    done
    if [[ $client_only -eq 1 ]]; then
      first_bad+="; only client-suite checks failed: re-run the whole Client tests run holding the head's newest non-skipped client-gametest job [client-only] (never --failed or one job; why: merging.md's client-suite note)"
    fi
    refuse "has a check that is not passing: $first_bad"
  fi
  for required in "${REQUIRED_CHECKS[@]}"; do
    grep -qxF "pass"$'\t'"$required" <<<"$checks" && continue
    if grep -qxF "skipping"$'\t'"$required" <<<"$checks"; then
      refuse "has a required check that was only skipped: $required"
    fi
    refuse "lacks required check: $required"
  done

  # CI merges the PR head with origin/main fetched at run time and records that main sha in a `merged-main` notice
  # annotation on its check run (.github/actions/merge-main; REST reads it, a job summary it cannot). The gate refuses
  # unless every deciding run of a required job (and of each passing build-shard, build-checks and client-gametest-shard) merged exactly
  # the current origin/main: a time test cannot see that GitHub served a stale refs/pull/N/merge (#979). A run with
  # no record (one from before this check) is refused as stale. The merge is invisible to a path no job reads, so a
  # main that differs from the recorded sha only by docs/ROADMAP.md and .papercuts.jsonl (the roadmap and papercut commits that land
  # after each merge) still counts. Fail closed on any unreadable step.
  git fetch origin main >/dev/null 2>&1 || refuse "could not read origin/main (fetch failed)"
  main_sha=$(git rev-parse origin/main 2>/dev/null) || main_sha=
  [[ $main_sha =~ ^[0-9a-f]{40}$ ]] || refuse "could not read the sha of origin/main"
  stale_fix="record the current main by re-running the whole runs holding the head's newest non-skipped build, tool-tests and client-gametest jobs [stale-base] (the head sha is unchanged, so the review pass stays valid)"
  based_names=("${REQUIRED_CHECKS[@]}")
  # The suite runs as `client-gametest-shard <i>` jobs, and `client-gametest` is the aggregate that passes only when
  # every shard did (#1079). Each passing shard is the run that must have merged the current main; the aggregate's own
  # notice (tools/ci-shard-main.sh, for the gate on main) is never read. An aggregate pass with no passing shard is not
  # trusted.
  shard_names=$(awk -F'\t' '$1 == "pass" && $2 ~ /^client-gametest-shard / { print $2 }' <<<"$checks")
  if grep -qxF "pass"$'\t'"client-gametest" <<<"$checks"; then # pipe-grep-q: fail-closed
    # TEMPORARY legacy path (delete with #1116): CI from before #1079 ran one `client-gametest` job that recorded the
    # merged-main notice itself, and a stale-base re-run reuses that workflow. Legacy only when the head has no
    # client-gametest-shard run of any kind (even a never-started template one), so the sharded aggregate's notice
    # never stands in for shards that did not pass.
    if [[ -z $shard_names ]]; then
      # The PR tier can select no client class: no shard runs, and the aggregate's own notice is the record.
      read_ci_tier client-gametest client_tier
      if [[ $client_tier != pr ]] && grep -q '^client-gametest-shard ' <<<"$run_lines"; then
        refuse "client-gametest passed but no client-gametest-shard run did, so nothing shows what the suite ran"
      fi
      based_names+=(client-gametest)
    fi
  fi
  while IFS= read -r shard; do
    [[ -z $shard ]] || based_names+=("$shard")
  done <<<"$shard_names"
  # The server suite runs as `build-shard <i>` jobs beside `build-checks`, and `build` is their aggregate (#1327): it passes only
  # when every one of them did and they merged one main, and then records that main itself (tools/ci-shard-main.sh), so its
  # notice stays read above and covers a shard the gate cannot see. Each passing shard and build-checks is read as well. CI from
  # before #1327 ran one `build` job that merged main itself, has no such run, and its notice alone decides. Once any of them ran
  # (even a never-started template one), build-checks and at least one shard must pass and no shard may be only skipped, so an
  # aggregate pass never stands in for jobs that did not run.
  ci_gradle=
  if grep -qE $'^(build-shard |build-checks\t)' <<<"$run_lines" && ! grep -qxF "pass"$'\t'"build-checks" <<<"$checks"; then
    # A push that changed nothing Gradle reads runs no Gradle job: the build aggregate says so, and build-checks was only skipped.
    read_ci_gradle
    [[ $ci_gradle == skipped ]] \
      || refuse "build ran as shards but build-checks did not pass, so nothing shows the build's checks ran"
  fi
  if [[ $ci_gradle != skipped ]] && grep -qE $'^(build-shard |build-checks\t)' <<<"$run_lines"; then
    build_shards=$(awk -F'\t' '$1 == "pass" && $2 ~ /^build-shard / { print $2 }' <<<"$checks")
    # The PR tier can select no server class: no shard runs, and the aggregate's notice records what build-checks and the plan merged.
    if [[ -z $build_shards ]]; then
      read_ci_tier build build_tier
      [[ $build_tier == pr ]] || refuse "build ran as shards but no build-shard run passed, so nothing shows what the server suite ran"
    fi
    skipped_shard=$(awk -F'\t' '$1 != "pass" && $2 ~ /^build-shard / { print $2; exit }' <<<"$checks")
    [[ -z $skipped_shard ]] || refuse "has a build shard that was only skipped: $skipped_shard"
    based_names+=(build-checks)
    while IFS= read -r shard; do
      [[ -z $shard ]] || based_names+=("$shard")
    done <<<"$build_shards"
  fi
  for based in "${based_names[@]}"; do
    run_id=$(awk -F'\t' -v n="$based" '$2 == n { print $3 }' <<<"$checks_full")
    [[ $run_id =~ ^[0-9]+$ ]] || refuse "has no deciding run id for $based"
    gh_list recorded "repos/$repo/check-runs/$run_id/annotations?per_page=100" \
      '.[] | select(.title == "merged-main") | .message' \
      || refuse "could not read the annotations of $based run $run_id: $gh_list_err"
    recorded=${recorded%%$'\n'*}
    [[ $gate_only -eq 0 ]] || echo "merged main $based (run $run_id): ${recorded:-none}"
    [[ $recorded =~ ^[0-9a-f]{40}$ ]] \
      || refuse "$based run $run_id recorded no merged main sha (a run from before CI recorded it): $stale_fix"
    [[ $recorded != "$main_sha" ]] || continue
    # Main moved: only a change confined to those two paths may differ. A recorded sha that is not an ancestor of
    # origin/main (or whose object is missing) cannot be compared and is stale.
    git merge-base --is-ancestor "$recorded" "$main_sha" >/dev/null 2>&1 \
      || refuse "$based run $run_id merged main ${recorded:0:9}, which is not on origin/main ${main_sha:0:9}: $stale_fix"
    moved=$(git diff --name-only --no-renames "$recorded" "$main_sha") \
      || refuse "could not list the files changed on origin/main since ${recorded:0:9}"
    code_moved=()
    while IFS= read -r path; do
      case $path in
        '') ;;
        docs/ROADMAP.md | .papercuts.jsonl) ;;
        *) code_moved+=("$path") ;;
      esac
    done <<<"$moved"
    if [[ ${#code_moved[@]} -gt 0 ]]; then
      shown=${code_moved[*]:0:5}
      more=
      [[ ${#code_moved[@]} -le 5 ]] || more=" (+$((${#code_moved[@]} - 5)) more)"
      refuse "$based run $run_id merged main ${recorded:0:9} but origin/main is ${main_sha:0:9}: ${shown// /, }$more changed since: $stale_fix"
    fi
  done

  # The tier the head's CI ran: only the full suites let a PR land (ADR 0116). A suite that did not run counts as full.
  [[ -n $build_tier ]] || read_ci_tier build build_tier
  if [[ -z $client_tier ]]; then
    if grep -qxF "pass"$'\t'"client-gametest" <<<"$checks"; then read_ci_tier client-gametest client_tier; else client_tier=full; fi
  fi
  ci_tier=full
  [[ $build_tier == full && $client_tier == full ]] || ci_tier=pr
  [[ $gate_only -eq 0 ]] || echo "ci tier: $ci_tier (server suite: $build_tier, client suite: $client_tier)"
  if [[ ${require_full_tier:-0} -eq 1 && $ci_tier == pr ]]; then
    refuse "[pr-tier] has CI of the PR tier only (the GameTest classes its change can affect: server suite $build_tier, client suite $client_tier), and landing needs the full suites: land it with tools/merge-batch.sh $pr (a batch PR runs them), or label it full-ci and push again"
  fi
}

# Needs $pr_files, $labels, $checks (check_ci_gate) and $sha.
check_client_suite_ran() {
  # A PR that tools/ci-client-needed.sh says needs the client suite (changed paths, a rename's old path too, or
  # the `gameplay` label) needs a passing `client-gametest` run: a draft push can end with only skipped CI on its
  # head (#885). $checks holds the newest non-skipped verdict per name.
  gameplay_flag=0
  has_label gameplay && gameplay_flag=1
  client_needed=$(cut -f2,3 <<<"$pr_files" | tr '\t' '\n' | tools/ci-client-needed.sh "$gameplay_flag") \
    || refuse "could not decide whether the client suite is needed"
  if [[ $client_needed == true ]] && ! grep -qxF "pass"$'\t'"client-gametest" <<<"$checks"; then
    refuse "changes game code or is labelled gameplay but has no passing client-gametest run on $sha (a draft push or a skipped run does not count): push, or re-run the whole run holding the head's newest non-skipped client-gametest job (never the job alone: its copied jobs lose their merged-main annotation)" # pipe-grep-q: fail-closed
  fi
}
