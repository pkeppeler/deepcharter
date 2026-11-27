#!/usr/bin/env bash
# Tests tools/lib/pass-carry.sh (a review pass carried across a clean merge of main, #1568) over real git: a bare
# "origin", a feature branch, merges of main made cleanly, with a conflict resolved by hand, with an extra change.
# A stub gh lists the PR's pass comments. Never talks to GitHub.
# Usage: tools/tests/pass-carry.test.sh
set -euo pipefail
# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/lib/no-git-env.sh"

tools=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir "$work/bin"
export GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@example.com GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@example.com
export PASSES="$work/passes"

# gh: the comment listing prints each line of $PASSES as the gate's --jq does (the first line of a trusted comment, as JSON).
cat >"$work/bin/gh" <<'STUB'
#!/usr/bin/env bash
[[ $1 == api && $2 == */issues/*/comments* ]] || exit 1
while IFS= read -r sha; do printf '"review-passed %s"\n' "$sha"; done <"$PASSES"
STUB
chmod +x "$work/bin/gh"
export PATH="$work/bin:$PATH"

repo=pkeppeler/deepcharter
pr=7
# shellcheck source=../lib/gh-rest.sh
source "$tools/lib/gh-rest.sh"
# shellcheck source=../lib/pass-carry.sh
source "$tools/lib/pass-carry.sh"

cases=0
failures=0
pass() { cases=$((cases + 1)); echo "ok   $1"; }
fail() { cases=$((cases + 1)); failures=$((failures + 1)); echo "FAIL $1"; }

git init -q --bare -b main "$work/origin.git"
git clone -q "$work/origin.git" "$work/r" 2>/dev/null
cd "$work/r"
git checkout -q -b main
printf '1\n2\n3\n4\n5\n6\n7\n8\n9\n' >a.txt
echo b >b.txt
git add -A
git commit -q -m base
git push -q origin main 2>/dev/null
base=$(git rev-parse HEAD)

# start: a fresh feature branch off the base and a main at the base.
start() {
  git checkout -q -B feat "$base"
  git push -q -f origin "$base:refs/heads/main" 2>/dev/null
  git fetch -q origin
  : >"$PASSES"
}
# on_main <file> <content>: a commit on origin's main (made from a scratch branch, so feat stays where it is).
on_main() {
  local cur
  cur=$(git rev-parse --abbrev-ref HEAD)
  git checkout -q -B scratch origin/main
  printf '%s\n' "$2" >>"$1"
  git add -A
  git commit -q -m "main: $1"
  git push -q origin scratch:main 2>/dev/null
  git checkout -q "$cur"
  git fetch -q origin
}
# on_feat <file> <content>: a commit on feat.
on_feat() {
  printf '%s\n' "$2" >>"$1"
  git add -A
  git commit -q -m "feat: $1"
}
# publish: the head the PR shows.
publish() { git push -q -f origin "HEAD:refs/pull/$pr/head" 2>/dev/null; }
# carries <name> <wanted rc> [wanted carried-from]
carries() {
  local rc=0 head
  head=$(git rev-parse HEAD)
  publish
  carry_review_pass "$head" || rc=$?
  if [[ $rc -ne $2 ]]; then fail "$1 (rc=$rc, wanted $2)"; return; fi
  if [[ -n ${3-} && $carried_from != "$3" ]]; then fail "$1 (carried from $carried_from, wanted $3)"; return; fi
  pass "$1"
}

# --- a clean merge of main carries the pass ---
start
on_feat a.txt feat1
passed=$(git rev-parse HEAD)
echo "$passed" >"$PASSES"
on_main b.txt main1
git merge -q --no-edit origin/main
carries "carried: one clean merge of main on the passed head" 0 "$passed"

# --- a chain of clean merges carries it too ---
on_main b.txt main2
git merge -q --no-edit origin/main
carries "carried: two clean merges in a row" 0 "$passed"
PASS_CARRY_MAX_MERGES=1 carries "voided: more merges than the cap" 1
PASS_CARRY_MAX_MERGES=2 carries "carried: exactly the cap" 0 "$passed"

# --- a pass on a merge commit itself (a carried pass re-pinned by mark --carry) is a new base ---
mid=$(git rev-parse HEAD)
echo "$mid" >>"$PASSES"
on_main b.txt main3
git merge -q --no-edit origin/main
PASS_CARRY_MAX_MERGES=1 carries "carried: a merge on a head whose own pass was recorded" 0 "$mid"

# --- no pass on the parent: nothing to carry ---
start
on_feat a.txt feat1
on_main b.txt main1
git merge -q --no-edit origin/main
carries "voided: no pass comment at all" 1
git rev-parse HEAD >"$PASSES"
carries "voided: a pass for the merge's own sha is not a carry (no earlier pass)" 1
git rev-parse HEAD^2 >"$PASSES"
carries "voided: a pass for main's commit is not a pass for the branch" 1

# --- a conflict resolved by hand voids the pass ---
start
echo "$base" >"$PASSES"
sed -i 's/^5$/five-feat/' a.txt
git commit -q -am "feat: line 5"
git rev-parse HEAD >"$PASSES"
git checkout -q -B scratch origin/main
sed -i 's/^5$/five-main/' a.txt
git commit -q -am "main: line 5"
git push -q origin scratch:main 2>/dev/null
git checkout -q feat
git fetch -q origin
git merge --no-edit origin/main >/dev/null 2>&1 || true
printf '1\n2\n3\n4\nfive-both\n6\n7\n8\n9\n' >a.txt
git add -A
git commit -q --no-edit
carries "voided: a merge conflict resolved by hand" 1

# --- a merge that is not what merge-tree makes of its parents voids it ---
start
on_feat a.txt feat1
git rev-parse HEAD >"$PASSES"
on_main b.txt main1
git merge -q --no-commit --no-ff origin/main
echo smuggled >c.txt
git add -A
git commit -q -m "merge main (with a smuggled file)"
carries "voided: a merge that adds a change of its own" 1

# --- an extra commit after the merge voids it; so does one before the next merge ---
start
on_feat a.txt feat1
git rev-parse HEAD >"$PASSES"
on_main b.txt main1
git merge -q --no-edit origin/main
on_feat a.txt feat2
carries "voided: a commit after the merge" 1
on_main b.txt main2
git merge -q --no-edit origin/main
carries "voided: a merge on top of a commit the reviewer never saw" 1

# --- a merge of another branch, not main, voids it ---
start
on_feat a.txt feat1
git rev-parse HEAD >"$PASSES"
git checkout -q -B other "$base"
echo other >o.txt
git add -A
git commit -q -m "other"
git checkout -q feat
git merge -q --no-edit other
carries "voided: a merge of a branch that is not on origin/main" 1

# --- a head GitHub shows that is not the one asked about voids it ---
start
on_feat a.txt feat1
git rev-parse HEAD >"$PASSES"
on_main b.txt main1
git merge -q --no-edit origin/main
publish
rc=0
carry_review_pass "$(git rev-parse HEAD~1)" || rc=$?
if [[ $rc -eq 1 ]]; then pass "voided: the PR head is not the sha asked about"; else fail "voided: stale head (rc=$rc)"; fi

# --- a comment listing that cannot be read voids it ---
printf '#!/usr/bin/env bash\nexit 1\n' >"$work/bin/gh"
carries "voided: the comments cannot be read" 1

echo
echo "$cases cases, $failures failed"
[[ $failures -eq 0 ]]
