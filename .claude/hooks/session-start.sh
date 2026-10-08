#!/bin/bash
# SessionStart: inject the orchestrator handoff so a new session resumes in-flight work.
# Network calls are bounded; a failure degrades to a note and never blocks the session.
cd "$(dirname "$0")/../.." || exit 0
R=pkeppeler/deepcharter
# Worst case: limit check 8 s + handoff 15 s + PR list 15 s = 38 s, under the 60 s hook timeout.
t() { perl -e 'alarm shift; exec @ARGV' "${T:-15}" "$@" 2>/dev/null; }

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
try:
    d = json.load(sys.stdin)
except ValueError:
    print("interaction limit: could not parse the response")
    sys.exit(0)
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
  t gh issue list --repo "$R" --label handoff --state open --limit 1 --json number,body \
    --jq '.[0] | "#\(.number)\n\(.body)"' || echo "(could not fetch the handoff issue)"
  echo
  echo "## Open PRs and their pipeline stage"
  t gh pr list --repo "$R" --state open --json number,title,headRefName,isDraft,labels \
    --jq '.[] | "#\(.number) \(.title) [\(.headRefName)]\(if .isDraft then " draft" else "" end) stages: \([.labels[].name | select(startswith("stage:") or . == "review-passed")] | join(","))"' \
    || echo "(could not fetch PRs)"
  echo
  echo "## Local-only state (not on GitHub)"
  git status --porcelain | sed 's/^/uncommitted: /'
  git for-each-ref --format='%(refname:short) %(upstream:short) %(upstream:track)' refs/heads \
    | awk '$2=="" {print "unpushed branch (no upstream): " $1; next} /ahead/ {print "unpushed commits: " $0}'
  git worktree list | tail -n +2 | sed 's/^/worktree: /'
  git stash list | sed 's/^/stash: /'
} > /tmp/deepcharter-handoff.$$ 2>&1

python3 -I -c '
import json, sys
ctx = open(sys.argv[1]).read()
print(json.dumps({"hookSpecificOutput": {"hookEventName": "SessionStart", "additionalContext": ctx}}))
' /tmp/deepcharter-handoff.$$
rm -f /tmp/deepcharter-handoff.$$
