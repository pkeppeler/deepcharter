#!/usr/bin/env bash
# Usage: tools/pr-media.sh <pr-number> <file>...
#
# Publishes PR evidence (GIF, MP4, PNG made by tools/record-evidence.sh) to the
# orphan branch `pr-media` under <pr-number>/, then prints a markdown snippet for
# the PR body: GIFs and screenshots inline, MP4s linked.
#
# Uses git plumbing only (hash-object, mktree, commit-tree, push). It never checks
# out pr-media and never touches your working tree or index. The push is a plain
# fast-forward, never forced. Files already under <pr-number>/ are kept unless a
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

if [[ $# -lt 2 || ! $1 =~ ^[0-9]+$ ]]; then
  echo "usage: tools/pr-media.sh <pr-number> <file>..." >&2
  exit 2
fi
pr=$1
shift

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

# Current pr-media tip. A concurrent push makes the final push fail instead of clobbering.
git ls-remote --exit-code --heads "$remote" "$branch" >/dev/null 2>&1 \
  || { echo "no ${branch} branch on ${remote}: create the orphan branch ${branch} first" >&2; exit 1; }
git fetch -q "$remote" "refs/heads/${branch}"
tip=$(git rev-parse "FETCH_HEAD^{commit}")

# New <pr>/ tree: the existing entries minus same-named files, plus the new blobs.
entries=""
old_pr_names=""
if git cat-file -e "${tip}:${pr}" 2>/dev/null; then
  entries=$(git ls-tree "${tip}:${pr}")
  old_pr_names=$(git ls-tree --name-only "${tip}:${pr}")
fi
for i in "${!files[@]}"; do
  name=${names[$i]}
  blob=$(git hash-object -w -- "${files[$i]}")
  entries=$(printf '%s\n' "$entries" | awk -F'\t' -v n="$name" '$2 != n && NF')
  entries=$(printf '%s\n100644 blob %s\t%s\n' "$entries" "$blob" "$name" | awk 'NF')
done
pr_tree=$(printf '%s\n' "$entries" | git mktree)

# New root tree: the existing root minus <pr>/, plus the new subtree.
root=$(git ls-tree --full-tree "$tip" | awk -F'\t' -v n="$pr" '$2 != n && NF')
root_tree=$(printf '%s\n040000 tree %s\t%s\n' "$root" "$pr_tree" "$pr" | awk 'NF' | git mktree)

# Guard: the new root must hold every top-level entry the old one did, and <pr>/
# must keep every old file (a replaced file keeps its name, so it is still there).
lost_root=$(comm -23 <(git ls-tree --full-tree --name-only "$tip" | LC_ALL=C sort) \
  <(git ls-tree --full-tree --name-only "$root_tree" | LC_ALL=C sort))
[[ -z $lost_root ]] \
  || { echo "REFUSED: new root would drop top-level entries of ${branch}: $(tr '\n' ' ' <<<"$lost_root")" >&2; exit 1; }
lost_pr=$(comm -23 <(printf '%s\n' "$old_pr_names" | awk 'NF' | LC_ALL=C sort) \
  <(git ls-tree --name-only "$pr_tree" | LC_ALL=C sort))
[[ -z $lost_pr ]] \
  || { echo "REFUSED: ${pr}/ would lose files that were not replaced: $(tr '\n' ' ' <<<"$lost_pr")" >&2; exit 1; }

commit=$(git commit-tree "$root_tree" -p "$tip" -m "Add media for PR #${pr}")
git push -q "$remote" "${commit}:refs/heads/${branch}"

url() { echo "https://github.com/${repo}/blob/${branch}/${pr}/$1?raw=true"; }

echo "Pushed ${commit} to ${branch}; PR #${pr} now holds:" >&2
git ls-tree --name-only "${commit}:${pr}" | sed 's/^/  /' >&2
echo >&2
echo "Markdown snippet for the PR body:" >&2
for name in "${names[@]}"; do
  case $name in
    *.gif) printf '![%s](%s)\n\n' "${name%.*}" "$(url "$name")" ;;
    *.mp4) printf '[Watch the MP4: %s](%s)\n\n' "$name" "$(url "$name")" ;;
    *.png) printf '![%s](%s)\n\n' "${name%.*}" "$(url "$name")" ;;
    *) printf '[%s](%s)\n\n' "$name" "$(url "$name")" ;;
  esac
done
