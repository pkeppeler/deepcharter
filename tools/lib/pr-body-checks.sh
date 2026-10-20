# Sourced by tools/merge-pr.sh and tools/mark-review-passed.sh: the one copy of the PR body and label checks
# (closing reference, demo, map). The caller sets $repo and $pr first, and gets the checks as functions.
# Each failed check calls refuse, which prints one "REFUSED:" line naming the fix and exits 1.
# Compatible with bash 3.2.

refuse() {
  echo "REFUSED: PR #$pr $1" >&2
  exit 1
}

pr_field() { gh pr view "$pr" -R "$repo" --json "$1" --jq "$2"; }

# GitHub's own parse of the body (keywords, cross-repo refs, URLs), not a regex of ours.
# The set is GitHub's, so it can lag a body edit made just before the gate runs; merge-pr.sh
# reads it again right before the merge. It also holds issues linked by hand in the sidebar.
# One owner/repo#N per line; refs in this repo print as #N. Sets $closes.
closing_set() {
  local refs
  refs=$(pr_field closingIssuesReferences '.closingIssuesReferences[] | "\(.repository.owner.login)/\(.repository.name)#\(.number)"') \
    || refuse "has no readable closing references"
  closes=$(sort -u <<<"${refs//$repo#/#}" | grep . | tr '\n' ' ' || true) # grep: an empty set exits 1
  closes=${closes% }
}

# The PR must close exactly the issue its branch (<issue>-<slug>) is named for.
# Sets $branch, $issue, $body and $closes (not in a subshell, so a refusal exits the script).
check_closing_reference() {
  branch=$(pr_field headRefName .headRefName) || refuse "has no readable head branch"
  [[ $branch =~ ^([0-9]+)- ]] || refuse "has head branch '$branch', not <issue>-<slug>"
  issue=${BASH_REMATCH[1]}
  body=$(pr_field body .body) || refuse "has no readable body"
  closing_set
  [[ -n $closes ]] || refuse "has no closing reference (put 'Closes #$issue' in the body)"
  [[ $closes == "#$issue" ]] \
    || refuse "body closes $closes but branch $branch is for issue #$issue only (the closing set must be exactly {#$issue})"
}

# Sets $labels; has_label <name> tests it.
load_labels() { labels=$(pr_field labels '.labels[].name') || refuse "has no readable labels"; }
has_label() { grep -qxF -- "$1" <<<"$labels"; }

# Sets $pr_files: one status, path and previous path (or empty) per line.
load_pr_files() {
  pr_files=$(gh api "repos/$repo/pulls/$pr/files?per_page=100" --paginate \
    --jq '.[] | [.status, .filename, (.previous_filename // "")] | @tsv') || refuse "could not read its changed files"
}

# In-game code (src/main/, src/client/, src/lang/) needs a demo: the `demo` label and a
# pr-media/<n>/ image in the body, or the `no-demo` label and a `No demo: <reason>`
# line. Linked or embedded pr-media media needs the `demo` label, so the label stays true.
# Needs $pr_files, $labels and $body.
check_demo() {
  local demo_fix media_any media_own in_game path previous
  demo_fix="record with tools/record-evidence.sh and tools/pr-media.sh, embed the pr-media/$pr/ image in the body and label it 'demo'; or label it 'no-demo' and add a body line 'No demo: <reason>'"
  media_any='pr-media/[0-9]+/[^[:space:]()]+\.(gif|png)'
  media_own="pr-media/$pr/[^[:space:]()]+\\.(gif|png)"
  if grep -qE "$media_any" <<<"$body" && ! has_label demo; then
    refuse "links or embeds pr-media media but lacks the 'demo' label (label it 'demo', or remove the media)"
  fi
  in_game=
  while IFS=$'\t' read -r _ path previous; do
    if [[ $path =~ ^src/(main|client|lang)/ || $previous =~ ^src/(main|client|lang)/ ]]; then
      in_game=$path
      break
    fi
  done <<<"$pr_files"
  [[ -n $in_game ]] || return 0
  if has_label demo && has_label no-demo; then
    refuse "changes in-game code ($in_game) but has both the 'demo' and 'no-demo' labels; keep one"
  elif has_label demo; then
    grep -qE "$media_own" <<<"$body" \
      || refuse "changes in-game code ($in_game) with the 'demo' label but its body embeds no pr-media/$pr/ .gif or .png: $demo_fix"
  elif has_label no-demo; then
    grep -qE '^No demo:[[:space:]]*[^[:space:]]' <<<"$body" \
      || refuse "changes in-game code ($in_game) with the 'no-demo' label but its body has no 'No demo: <reason>' line"
  else
    refuse "changes in-game code ($in_game) with no demo: $demo_fix"
  fi
}

no_map_change() { grep -qE '^No map change:[[:space:]]*[^[:space:]]' <<<"$body"; }

# A `gameplay` PR changes what the game has, so it must change the feature map
# (docs/feature-map/map.json, #510) or say why not with a body line `No map change: <reason>`.
# Needs $pr_files, $labels and $body.
check_map_change() {
  local map_changed path
  has_label gameplay || return 0
  map_changed=
  while IFS=$'\t' read -r _ path _; do
    [[ $path == docs/feature-map/map.json ]] && map_changed=1
  done <<<"$pr_files"
  if [[ -z $map_changed ]]; then
    no_map_change \
      || refuse "is labelled 'gameplay' but does not change docs/feature-map/map.json: update the map (skill .claude/skills/feature-map/SKILL.md) or add a body line 'No map change: <reason>'"
  fi
}

# The map's content must not go stale (#510): a node whose refs cite the issue this PR closes (or the PR itself) must be
# `built` in the map at the PR head, so whoever merges a feature also flips its node. A body line `No map change: <reason>`
# skips this. A branch from before the map exists has no map.json at its head and is not checked. The gh --jq filter runs on
# the file's JSON; the stub in the tests answers with the lines it would print: node id, status, the ref that matched.
# Needs $sha, $issue and $body.
check_map_fresh() {
  local cited_any map_read stale_nodes stale_id stale_status stale_ref
  ! no_map_change || return 0
  cited_any=". == \"#$issue\" or . == \"#$pr\""
  map_read=0
  stale_nodes=$(gh api "repos/$repo/contents/docs/feature-map/map.json?ref=$sha" -H "Accept: application/vnd.github.raw+json" \
    --jq ".nodes[] | select(.status != \"built\") | select(any(.refs[]?; $cited_any)) | [.id, .status, (.refs | map(select($cited_any)) | .[0])] | @tsv" 2>&1) \
    || map_read=1
  if [[ $map_read -eq 1 ]]; then
    [[ $stale_nodes == *"HTTP 404"* ]] || refuse "has no readable docs/feature-map/map.json at $sha: $stale_nodes"
  elif [[ -n $stale_nodes ]]; then
    IFS=$'\t' read -r stale_id stale_status stale_ref <<<"$(head -1 <<<"$stale_nodes")"
    refuse "has the feature map node '$stale_id' citing $stale_ref, which this PR closes or is, but the node is still $stale_status: set it built in docs/feature-map/map.json (skill .claude/skills/feature-map/SKILL.md), drop the ref, or add a body line 'No map change: <reason>'"
  fi
}

# All the body and label checks, for a caller that does not need them interleaved with others
# (tools/mark-review-passed.sh). Needs $sha.
check_pr_body_and_labels() {
  check_closing_reference
  load_labels
  load_pr_files
  check_demo
  check_map_change
  check_map_fresh
}
