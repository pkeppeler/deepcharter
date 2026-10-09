#!/usr/bin/env bash
# Usage: tools/pr-media.sh <pr-number|folder> <file>...
#
# Publishes PR evidence (GIF, MP4, PNG made by tools/record-evidence.sh) to the
# orphan branch `pr-media` under <pr-number>/, then prints a markdown snippet for
# the PR body: GIFs and screenshots inline, MP4s linked. The folder `readme` holds
# the README tour media at stable paths (tools/readme-tour.sh publishes there). Any
# other lowercase folder name works too, one level of nesting at most (`looks/dusk-company`).
#
# Uses git plumbing only (hash-object, mktree, commit-tree, push). It never checks
# out pr-media and never touches your working tree or index. The push is a plain
# fast-forward, never forced; if another publisher moved the tip first, it rebuilds on
# the new tip, re-runs every guard and retries a few times with a jittered backoff. Files already under <pr-number>/ are kept unless a
# new file has the same name; other PRs' folders are kept as they are. It runs
# from any directory, and refuses before pushing if the new root would lose a
# top-level entry or <pr>/ would lose a file that was not replaced by name.
#
# Typical use:
#   tools/record-evidence.sh camera-turn
#   tools/pr-media.sh 42 build/evidence/camera-turn/camera-turn.{gif,mp4} \
#       build/evidence/camera-turn/screenshots/*.png
set -euo pipefail

repo=pkeppeler/deepcharter
branch="pr-media"
remote=origin

if [[ $# -lt 2 || ! $1 =~ ^[a-z0-9][a-z0-9-]*(/[a-z0-9][a-z0-9-]*)?$ ]]; then
  echo "usage: tools/pr-media.sh <pr-number|folder> <file>..." >&2
  exit 2
fi
pr=$1
shift
if [[ $pr =~ ^[0-9]+$ ]]; then
  subject="Add media for PR #${pr}"
  label="PR #${pr}"
elif [[ $pr == readme ]]; then
  subject="Update README tour media"
  label="the README tour"
else
  subject="Update media in ${pr}/"
  label="${pr}/"
fi

names=()
files=()
for f in "$@"; do
  [[ -f $f ]] || { echo "not a file: $f" >&2; exit 1; }
  name=$(basename "$f")
  [[ $name =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] \
    || { echo "unsupported file name '$name': start with a letter or digit, then use letters, digits, '.', '_' and '-'" >&2; exit 1; }
  for seen in ${names[@]+"${names[@]}"}; do
    [[ $seen != "$name" ]] || { echo "two files named '$name'" >&2; exit 1; }
  done
  names+=("$name")
  files+=("$(cd "$(dirname "$f")" && pwd)/$name")
done

# Run every git call below from the top level: `git ls-tree <tree-ish>` filters
# by the current directory's prefix, which would hide other entries.
cd "$(git rev-parse --show-toplevel)"

# The branch must exist; the tip is fetched fresh on every attempt below.
git ls-remote --exit-code --heads "$remote" "$branch" >/dev/null 2>&1 \
  || { echo "no ${branch} branch on ${remote}: create the orphan branch ${branch} first" >&2; exit 1; }
# The entries of a tree as sorted "<type> <name>" lines. The guards compare these, so a name that
# survives as another type (a file replacing a folder, or the reverse) counts as lost.
typed_entries() {
  git ls-tree "$@" | awk -F'\t' '{ split($1, meta, " "); print meta[2] " " $2 }' | LC_ALL=C sort
}

# Fetches the current tip, rebuilds the new tree on it and re-runs every guard: sets $commit, or exits on a refusal.
build_commit() {
  git fetch -q "$remote" "refs/heads/${branch}"
  tip=$(git rev-parse "FETCH_HEAD^{commit}")

  # New <pr>/ tree: the existing entries minus same-named files, plus the new blobs.
  entries=""
  old_pr_entries=""
  if [[ $(git cat-file -t "${tip}:${pr}" 2>/dev/null) == tree ]]; then
    entries=$(git ls-tree "${tip}:${pr}")
    old_pr_entries=$(typed_entries "${tip}:${pr}")
  fi
  for i in "${!files[@]}"; do
    name=${names[$i]}
    blob=$(git hash-object -w -- "${files[$i]}")
    entries=$(printf '%s\n' "$entries" | awk -F'\t' -v n="$name" '$2 != n && NF')
    entries=$(printf '%s\n100644 blob %s\t%s\n' "$entries" "$blob" "$name" | awk 'NF')
  done
  pr_tree=$(printf '%s\n' "$entries" | git mktree)

  # A nested folder (<top>/<leaf>) sits in a <top>/ tree: its existing entries minus <leaf>/, plus the new subtree.
  top=$pr
  top_tree=$pr_tree
  old_top_entries=""
  if [[ $pr == */* ]]; then
    top=${pr%%/*}
    leaf=${pr#*/}
    top_entries=""
    if [[ $(git cat-file -t "${tip}:${top}" 2>/dev/null) == tree ]]; then
      top_entries=$(git ls-tree "${tip}:${top}" | awk -F'\t' -v n="$leaf" '$2 != n && NF')
      old_top_entries=$(typed_entries "${tip}:${top}")
    fi
    top_tree=$(printf '%s\n040000 tree %s\t%s\n' "$top_entries" "$pr_tree" "$leaf" | awk 'NF' | git mktree)
  fi

  # New root tree: the existing root minus <top>/, plus the new subtree.
  root=$(git ls-tree --full-tree "$tip" | awk -F'\t' -v n="$top" '$2 != n && NF')
  root_tree=$(printf '%s\n040000 tree %s\t%s\n' "$root" "$top_tree" "$top" | awk 'NF' | git mktree)

  # Guard: the new root must hold every top-level entry the old one did, and <pr>/
  # must keep every old file (a replaced file keeps its name, so it is still there).
  lost_root=$(comm -23 <(typed_entries --full-tree "$tip") <(typed_entries --full-tree "$root_tree"))
  [[ -z $lost_root ]] \
    || { echo "REFUSED: new root would drop top-level entries of ${branch}: $(tr '\n' ' ' <<<"$lost_root")" >&2; exit 1; }
  lost_pr=$(comm -23 <(printf '%s\n' "$old_pr_entries" | awk 'NF') <(typed_entries "$pr_tree"))
  [[ -z $lost_pr ]] \
    || { echo "REFUSED: ${pr}/ would lose files that were not replaced: $(tr '\n' ' ' <<<"$lost_pr")" >&2; exit 1; }
  lost_top=$(comm -23 <(printf '%s\n' "$old_top_entries" | awk 'NF') <(typed_entries "$top_tree"))
  [[ -z $lost_top ]] \
    || { echo "REFUSED: ${top}/ would lose entries: $(tr '\n' ' ' <<<"$lost_top")" >&2; exit 1; }

  commit=$(git commit-tree "$root_tree" -p "$tip" -m "$subject")
}

# A push that lost the race to another publisher: the remote tip moved. Anything else (auth, network) is not retried.
lost_race() {
  grep -qE 'cannot lock ref|fetch first|non-fast-forward|\[rejected\]|failed to update ref' <<<"$1"
}

max_attempts=5
attempt=1
while :; do
  build_commit
  if push_err=$(git push -q "$remote" "${commit}:refs/heads/${branch}" 2>&1); then
    break
  fi
  echo "$push_err" >&2
  lost_race "$push_err" || exit 1
  if [[ $attempt -ge $max_attempts ]]; then
    echo "LOST THE RACE: ${branch} moved under ${max_attempts} pushes in a row; nothing was published, run the command again" >&2
    exit 1
  fi
  # Jittered backoff that grows with each attempt, so concurrent publishers fall out of step.
  sleep "$(awk -v a="$attempt" -v r="$RANDOM" 'BEGIN { printf "%.2f", a * (0.2 + (r % 50) / 100) }')"
  echo "${branch} moved while pushing; rebuilding on the new tip (attempt $((attempt + 1)) of ${max_attempts})" >&2
  attempt=$((attempt + 1))
done

url() { echo "https://github.com/${repo}/blob/${branch}/${pr}/$1?raw=true"; }

echo "Pushed ${commit} to ${branch}; ${label} now holds:" >&2
git ls-tree --name-only "${commit}:${pr}" | sed 's/^/  /' >&2
echo >&2
echo "Markdown snippet:" >&2
for name in "${names[@]}"; do
  case $name in
    *.gif) printf '![%s](%s)\n\n' "${name%.*}" "$(url "$name")" ;;
    *.mp4) printf '[Watch the MP4: %s](%s)\n\n' "$name" "$(url "$name")" ;;
    *.png) printf '![%s](%s)\n\n' "${name%.*}" "$(url "$name")" ;;
    *) printf '[%s](%s)\n\n' "$name" "$(url "$name")" ;;
  esac
done
