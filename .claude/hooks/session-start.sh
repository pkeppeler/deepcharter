#!/bin/bash
# SessionStart: inject the orchestrator handoff so a new session resumes in-flight work.
# Also derives what the handoff must not carry: the next free ADR number and the last merged PRs.
# Network calls are bounded; a failure degrades to a note and never blocks the session.
cd "$(dirname "$0")/../.." || exit 0
adr_lib=0
# shellcheck source=../../tools/lib/adr.sh
source tools/lib/adr.sh 2>/dev/null && adr_lib=1
R=pkeppeler/deepcharter
# Worst case: limit check 8 s + handoff 10 s + open PRs 10 s + ADR list 10 s + merged PRs 10 s = 48 s,
# under the 60 s hook timeout (the local git calls and python start-ups take well under a second).
t() { perl -e 'alarm shift; exec @ARGV' "${T:-10}" "$@" 2>/dev/null; }

{
  # Public-repo guard: the interaction limit must stay collaborators_only with more than 14 days left.
  limits=$(T=8 t gh api "repos/$R/interaction-limits") || limits=""
  if [ -z "$limits" ]; then
    echo "(could not check the interaction limit: gh failed)"
    echo
  else
    warn=$(printf %s "$limits" | python3 -I -c '
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
  if handoff=$(t gh issue list --repo "$R" --label handoff --state open --limit 1 --json number,body \
    --jq 'if length == 0 then empty else .[0] | "#\(.number)\n\(.body)" end'); then
    if [ -z "$handoff" ]; then
      echo "(no open issue labelled handoff)"
    else
      echo "$handoff"
    fi
    # The same rules tools/handoff.sh enforces; this catches an edit made without the tool.
    if [ -n "$handoff" ] && ! rules=$(printf %s "$handoff" | python3 -I tools/handoff_check.py 2>&1); then
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
  t gh pr list --repo "$R" --state merged --limit 15 --json number,title --jq '.[] | "#\(.number) \(.title)"' \
    || echo "(could not fetch merged PRs)"
  echo
  echo "## Open PRs and their pipeline stage"
  t gh pr list --repo "$R" --state open --json number,title,headRefName,isDraft,labels \
    --jq '.[] | "#\(.number) \(.title) [\(.headRefName)]\(if .isDraft then " draft" else "" end) stages: \([.labels[].name | select(startswith("stage:") or . == "review-passed")] | join(","))"' \
    || echo "(could not fetch PRs)"
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

python3 -I -c '
import json, sys
ctx = open(sys.argv[1]).read()
print(json.dumps({"hookSpecificOutput": {"hookEventName": "SessionStart", "additionalContext": ctx}}))
' /tmp/deepcharter-handoff.$$
rm -f /tmp/deepcharter-handoff.$$
