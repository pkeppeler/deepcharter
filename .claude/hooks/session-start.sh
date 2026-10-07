#!/bin/bash
# SessionStart: inject the orchestrator handoff so a new session resumes in-flight work.
# Network calls are bounded; a failure degrades to a note and never blocks the session.
cd "$(dirname "$0")/../.." || exit 0
R=pkeppeler/deepcharter
t() { perl -e 'alarm shift; exec @ARGV' 15 "$@" 2>/dev/null; }

{
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
