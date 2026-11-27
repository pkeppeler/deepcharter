# Sourced by tools/merge-queue.sh and tools/merge-batch.sh: wait for a head's CI and re-run it whole.
# The caller sets $repo, $ci_poll, $ci_max (seconds) and a `say` function.

# wait_ci <branch> <sha>: return once no run for that head is unfinished; 1 on timeout.
wait_ci() {
  local branch=$1 sha=$2 pending started=$SECONDS
  while true; do
    pending=$(gh run list -R "$repo" --branch "$branch" --limit 50 --json status,headSha \
      --jq ".[] | select(.headSha == \"$sha\" and .status != \"completed\") | .status" 2>/dev/null || echo unreadable)
    if [[ -z $pending ]]; then
      return 0
    fi
    if ((SECONDS - started >= ci_max)); then
      return 1
    fi
    say "waiting for CI on ${sha:0:9} ($(tr '\n' ' ' <<<"$pending")) ..."
    sleep "$ci_poll"
  done
}

# rerun_ci <pr> <sha> [<workflow|job|optional>...]: re-run the workflow runs holding the given jobs; by default the
# jobs whose `merged-main` record the gate reads (build, tool-tests and, when it ran, client-gametest; each merges the
# live main itself or records what its shards merged). The aggregates `build` and `client-gametest` are matched, not a shard:
# `build` sits in the same `CI` run as every `build-shard <i>` job and `build-checks` (#1327), `client-gametest` in the same
# `Client tests` run as every `client-gametest-shard <i>` job (#1079); each is skipped exactly when its shards are, and is the
# legacy single job in CI from before sharding. Never a single job: a job re-run copies the run's other finished jobs without their
# annotations, so the gate calls the base stale again (#1094).
# Per job it takes the newest non-skipped one across the head sha's runs (#1092), then re-runs each distinct run whole.
# Returns 1 and sets $rerun_err when a non-optional job has no such job, a listing fails or the API refuses a re-run.
# An unfinished run is skipped (the API refuses to re-run into it); if it finishes stale, the next retry re-runs it.
rerun_ci() {
  local pr=$1 sha=$2 rows target workflow job optional picks="" run_id run_status jobs_rows hit handled=","
  shift 2
  (($#)) || set -- "CI|build|0" "CI|tool-tests|0" "Client tests|client-gametest|1"
  rerun_err=
  rows=$(gh api "repos/$repo/actions/runs?head_sha=$sha&per_page=100" --paginate \
    --jq '.workflow_runs[] | [.id, .name, .status, .conclusion] | @tsv' 2>/dev/null) \
    || { rerun_err="PR #$pr: could not list the workflow runs of ${sha:0:9}"; return 1; }
  for target in "$@"; do
    IFS='|' read -r workflow job optional <<<"$target"
    hit=
    # The API lists newest first. A skipped run (a label event adds one) has only skipped jobs; an unfinished
    # job has no conclusion, so it counts as not skipped.
    while IFS=$'\t' read -r run_id run_status; do
      jobs_rows=$(gh api "repos/$repo/actions/runs/$run_id/jobs?per_page=100" --paginate \
        --jq '.jobs[] | [.id, .name, .status, .conclusion] | @tsv' 2>/dev/null) \
        || { rerun_err="PR #$pr: could not list the jobs of $workflow run $run_id"; return 1; }
      if [[ -n $(awk -F'\t' -v j="$job" '$2 == j && $4 != "skipped" { print $1; exit }' <<<"$jobs_rows") ]]; then
        hit="$run_id"$'\t'"$workflow"$'\t'"$run_status"
        break
      fi
    done < <(awk -F'\t' -v w="$workflow" '$2 == w && $4 != "skipped" { print $1 "\t" $3 }' <<<"$rows")
    if [[ -n $hit ]]; then
      picks+="$hit"$'\n'
    elif [[ $optional == 0 ]]; then
      rerun_err="PR #$pr: no non-skipped $job job of $workflow exists for $sha to re-run"
      rerun_err+=" (push, or start the workflows by hand)"
      return 1
    fi
  done
  while IFS=$'\t' read -r run_id workflow run_status; do
    [[ -n $run_id && $handled != *",$run_id,"* ]] || continue
    handled+="$run_id,"
    if [[ $run_status != completed ]]; then
      say "run $run_id ($workflow) is still in progress ($run_status): not re-running it"
      continue
    fi
    say "re-running $workflow run $run_id"
    gh api -X POST "repos/$repo/actions/runs/$run_id/rerun" >/dev/null 2>&1 \
      || {
        rerun_err="PR #$pr: GitHub refused to re-run $workflow run $run_id (older than 30 days? push a commit instead)"
        return 1
      }
  done <<<"$picks"
}
