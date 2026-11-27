#!/bin/bash
# SessionStart: inject the orchestrator handoff so a new session resumes in-flight work.
# Also derives what the handoff must not carry: the next free ADR number and the last merged PRs.
# Network calls are bounded; a failure degrades to a note and never blocks the session. GitHub is read through REST only.
cd "$(dirname "$0")/../.." || exit 0
py=$PWD/tools/py
adr_lib=0
# shellcheck source=../../tools/lib/adr.sh
source tools/lib/adr.sh 2>/dev/null && adr_lib=1
R=pkeppeler/deepcharter
# Worst case: limit check 8 s + handoff 10 s + open PRs 10 s + the per-PR mergeable reads 6 s in total + ADR list
# 10 s + merged PRs 10 s + playtest issue count 4 s = 58 s, under the 60 s hook timeout (the local git calls and python
# start-ups take well under a second). SESSION_START_PR_BUDGET (seconds) is a test seam for the 6 s.
t() { perl -e 'alarm shift; exec @ARGV' "${T:-10}" "$@" 2>/dev/null; }

# Make the pre-push hook active in every session: an unset core.hooksPath becomes .githooks. A set value stays, even a different one.
# The setting lives in the clone's shared config, so every worktree gets it; the relative path resolves per worktree (docs/tooling/pre-push-hook.md).
hooks_note=
if [ -z "$(git config --get core.hooksPath 2>/dev/null)" ]; then
  git config core.hooksPath .githooks 2>/dev/null || hooks_note="(could not set core.hooksPath to .githooks: git config failed)"
fi

{
  # CLAUDE.md imports this profile from outside the repo: without it the autonomous rules silently vanish.
  [ -f "$HOME/.claude/profiles/autonomous.md" ] \
    || { echo "WARNING: ~/.claude/profiles/autonomous.md is missing, so .claude/CLAUDE.md's autonomous rules are not loaded"; echo; }
  [ -z "$hooks_note" ] || { echo "$hooks_note"; echo; }
  # Public-repo guard: the interaction limit must stay collaborators_only with more than 14 days left.
  limits=$(T=8 t gh api "repos/$R/interaction-limits") || limits=""
  if [ -z "$limits" ]; then
    echo "(could not check the interaction limit: gh failed)"
    echo
  else
    warn=$(printf %s "$limits" | "$py" -c '
import json, sys
from datetime import datetime, timedelta, timezone
d = json.load(sys.stdin)  # bad JSON exits nonzero: the caller reports a parse error
if d.get("limit") != "collaborators_only":
    print("the limit is " + repr(d.get("limit", "missing")) + ", not collaborators_only")
else:
    try:
        exp = datetime.fromisoformat(d["expires_at"].replace("Z", "+00:00"))
        left = exp - datetime.now(timezone.utc)
    except (KeyError, ValueError, TypeError, AttributeError):
        print("interaction limit: could not parse expires_at")
        sys.exit(0)
    if left < timedelta(days=14):
        print("the limit expires at " + d["expires_at"] + ", within 14 days")
' 2>/dev/null) || warn="interaction limit: could not parse the response"
    if [ -n "$warn" ]; then
      echo "################################################################"
      echo "## WARNING: PUBLIC REPO INTERACTION LIMIT NEEDS RENEWING NOW"
      echo "## $warn"
      echo "## Run this first thing, before any other work:"
      echo "gh api -X PUT repos/$R/interaction-limits -f limit=collaborators_only -f expiry=six_months"
      echo "################################################################"
      echo
    fi
  fi
  echo "## Orchestrator handoff (pinned issue)"
  # The issues endpoint lists PRs too: skip them.
  if handoff=$(t gh api "repos/$R/issues?labels=handoff&state=open&per_page=100" \
    --jq '[.[] | select(has("pull_request") | not)] | if length == 0 then empty else .[0] | "#\(.number)\n\(.body)" end'); then
    echo "${handoff:-(no open issue labelled handoff)}"
    # The same rules tools/handoff.sh enforces; this catches an edit made without the tool.
    if [ -n "$handoff" ] && ! rules=$(printf %s "$handoff" | "$py" tools/handoff_check.py 2>&1); then
      echo
      echo "WARNING: the handoff breaks the handoff rules ($(printf %s "$rules" | paste -sd';' -)); rewrite it with tools/handoff.sh <file>"
    fi
  else
    echo "(could not fetch the handoff issue)"
  fi
  echo
  echo "## Next free ADR number (derived; the handoff must not repeat it)"
  # GitHub's main first: no ref writes, so concurrent sessions and a merge queue cannot collide on a lock.
  if [ "$adr_lib" != 1 ]; then
    echo "(could not load tools/lib/adr.sh)"
  elif main_adrs=$(t gh api "repos/$R/contents/docs/adr?ref=main" --jq '.[].path') && [ -n "$main_adrs" ]; then
    echo "Next free ADR number: $(adr_next_free "$main_adrs") (origin/main)"
  elif main_adrs=$(git ls-tree --name-only origin/main docs/adr/ 2>/dev/null) && [ -n "$main_adrs" ]; then
    echo "Next free ADR number: $(adr_next_free "$main_adrs") (local origin/main, may be stale)"
  else
    echo "(could not read the ADR list on origin/main)"
  fi
  echo
  echo "## Last 15 merged PRs (derived; the handoff must not repeat them)"
  # No REST merged filter: closed PRs by update time, the merged ones, newest merge first.
  t gh api "repos/$R/pulls?state=closed&sort=updated&direction=desc&per_page=100" \
    --jq '[.[] | select(.merged_at != null)] | sort_by(.merged_at) | reverse | .[:15][] | "#\(.number) \(.title)"' \
    || echo "(could not fetch merged PRs)"
  echo
  echo "## Open PRs and their pipeline stage"
  # The list endpoint omits mergeable: read it per PR (REST null = still computing, false = conflicting).
  # All the reads share one deadline; a PR reached after it, or whose read times out or fails, is listed without its
  # mergeable mark and the note below.
  if open_prs=$(t gh api "repos/$R/pulls?state=open&per_page=100" \
    --jq '.[] | [.number, "#\(.number) \(.title) [\(.head.ref)]\(if .draft then " draft" else "" end) stages: \([.labels[].name | select(startswith("stage:") or . == "review-passed")] | join(","))"] | @tsv'); then
    pr_deadline=$((SECONDS + ${SESSION_START_PR_BUDGET:-6}))
    unread=''
    while IFS=$'\t' read -r n text; do
      [ -n "$n" ] || continue
      left=$((pr_deadline - SECONDS))
      mergeable=
      if [ "$left" -gt 0 ]; then
        mergeable=$(T=$left t gh api "repos/$R/pulls/$n" --jq '.mergeable | tostring') || { mergeable=; unread=1; }
      else
        unread=1
      fi
      case $mergeable in
        false) text="$text  CONFLICTING: merge origin/main into the branch" ;;
        null) text="$text  (mergeability not known yet)" ;;
      esac
      echo "$text"
    done <<<"$open_prs"
    [ -z "$unread" ] || echo "(mergeability not read for some PRs: time budget used up)"
  else
    echo "(could not fetch PRs)"
  fi
  echo
  # One line when playtest-feedback issues exist past the marker the player-experience skill has learned from.
  skill=.claude/skills/player-experience/SKILL.md
  marker=$(sed -n 's/^Learned through playtest-feedback issue: #\([0-9][0-9]*\).*/\1/p' "$skill" 2>/dev/null | head -1)
  if [ -z "$marker" ]; then
    echo "(could not read the playtest marker in $skill)"
  elif fresh=$(T=4 t gh api "repos/$R/issues?labels=playtest-feedback&state=all&sort=created&direction=desc&per_page=100" \
    --jq "[.[] | select(has(\"pull_request\") | not) | select(.number > $marker)] | length"); then
    case ${fresh:-0} in
      0) ;;
      1) echo "1 new playtest issue: run /refresh-player-experience" ;;
      *) echo "$fresh new playtest issues: run /refresh-player-experience" ;;
    esac
  else
    echo "(could not check for new playtest issues)"
  fi
  echo
  echo "## Local-only state (not on GitHub)"
  git status --porcelain | sed 's/^/uncommitted: /'
  # A worktree-agent-* branch still checked out in a worktree is live: the worktree line lists it.
  export CHECKED_OUT
  CHECKED_OUT=$(git worktree list --porcelain | sed -n 's|^branch refs/heads/||p')
  git for-each-ref --format='%(refname:short) %(upstream:short) %(upstream:track)' refs/heads \
    | awk 'BEGIN {n = split(ENVIRON["CHECKED_OUT"], l, "\n"); for (i = 1; i <= n; i++) out[l[i]] = 1}
      $2=="" && $1 ~ /^worktree-agent-/ {if (!($1 in out)) agents++; next}
      $2=="" {print "unpushed branch (no upstream): " $1; next}
      /ahead/ {print "unpushed commits: " $0}
      END {if (agents) print agents " worktree-agent-* branches with no upstream and no worktree (stale; delete them)"}'
  git worktree list | tail -n +2 | sed 's/^/worktree: /'
  git stash list | sed 's/^/stash: /'
} > /tmp/deepcharter-handoff.$$ 2>&1

"$py" -c '
import json, sys
ctx = open(sys.argv[1]).read()
print(json.dumps({"hookSpecificOutput": {"hookEventName": "SessionStart", "additionalContext": ctx}}))
' /tmp/deepcharter-handoff.$$
rm -f /tmp/deepcharter-handoff.$$
