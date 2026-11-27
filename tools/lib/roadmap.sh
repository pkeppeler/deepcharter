# Sourced by tools/merge-pr.sh and tools/merge-batch.sh: regenerate docs/ROADMAP.md on origin/main after a merge.
# The caller keeps `trap roadmap_cleanup EXIT` and $roadmap_tmp (empty until the function makes its worktree).

roadmap_cleanup() {
  if [[ -n $roadmap_tmp ]]; then
    git worktree remove --force "$roadmap_tmp/wt" >/dev/null 2>&1 || true
    rm -rf "$roadmap_tmp"
    git worktree prune >/dev/null 2>&1 || true
  fi
}

# regenerate_roadmap <label>: commit "Regenerate roadmap after <label>" to main. Called in an `if`, so set -e is off
# inside: every step must `|| return 1`.
regenerate_roadmap() {
  local label=$1
  git fetch origin main || return 1
  if ! git cat-file -e origin/main:tools/roadmap.py 2>/dev/null; then
    echo "WARNING: tools/roadmap.py not on origin/main; skipping roadmap regeneration" >&2
    return 0
  fi
  roadmap_tmp=$(mktemp -d) || return 1
  git worktree add --detach "$roadmap_tmp/wt" origin/main || return 1
  local attempt
  for attempt in 1 2; do
    (py=$PWD/tools/py && cd "$roadmap_tmp/wt" && "$py" tools/roadmap.py) || return 1
    [[ -n $(git -C "$roadmap_tmp/wt" status --porcelain -- docs/ROADMAP.md) ]] || return 0
    git -C "$roadmap_tmp/wt" add docs/ROADMAP.md || return 1
    git -C "$roadmap_tmp/wt" commit -m "Regenerate roadmap after $label" || return 1
    git -C "$roadmap_tmp/wt" push origin HEAD:main && return 0
    # Push rejected (main moved): one retry from the new origin/main.
    [[ $attempt -eq 1 ]] || return 1
    git fetch origin main || return 1
    git -C "$roadmap_tmp/wt" reset --hard origin/main || return 1
  done
}
