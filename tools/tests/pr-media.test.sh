#!/usr/bin/env bash
# Tests tools/pr-media.sh against a local bare repo standing in for origin.
# The script is run from inside a clone whose `origin` is that bare repo.
# Usage: tools/tests/pr-media.test.sh
set -euo pipefail

tools=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
script=$tools/pr-media.sh
real_git=$(command -v git)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

export GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@example.com
export GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@example.com
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null

failures=0
check() { # check <description> <condition-result: 0 = ok>
  if [[ $2 -eq 0 ]]; then echo "ok   $1"; else echo "FAIL $1"; failures=$((failures + 1)); fi
}

bare=$work/bare.git
make_remote() { # fresh bare repo with main and an orphan pr-media holding 1/old.png
  rm -rf "$work/seed" "$bare" "$work/clone"
  "$real_git" init -q -b main "$work/seed"
  (
    cd "$work/seed"
    echo code >README
    "$real_git" add README
    "$real_git" commit -q -m init
    "$real_git" checkout -q --orphan pr-media
    "$real_git" rm -q -rf .
    mkdir 1
    echo old >1/old.png
    "$real_git" add 1/old.png
    "$real_git" commit -q -m "PR media: #1"
  )
  "$real_git" clone -q --bare "$work/seed" "$bare"
  "$real_git" clone -q "$bare" "$work/clone"
}

tree() { "$real_git" -C "$bare" ls-tree -r pr-media | awk '{print $4 ":" $3}'; }
blob_of() { echo "$1" | "$real_git" hash-object --stdin; }
run() { (cd "$work/clone" && "$script" "$@") >"$work/out" 2>"$work/err"; }

mkdir "$work/files"
echo gif-one >"$work/files/a.gif"
echo mp4-one >"$work/files/b.mp4"
echo shot-one >"$work/files/s.png"

# --- publishing, replacement, and other PRs' folders ---
make_remote
run 7 "$work/files/a.gif" "$work/files/b.mp4"
check "first publish succeeds" $?
check "snippet inlines the GIF" "$(grep -qF '![a](https://github.com/pkeppeler/deepcharter/blob/pr-media/7/a.gif?raw=true)' "$work/out"; echo $?)"
check "snippet links the MP4" "$(grep -qF '[Watch the MP4: b.mp4](https://github.com/pkeppeler/deepcharter/blob/pr-media/7/b.mp4?raw=true)' "$work/out"; echo $?)"
check "pre-existing PR 1 folder survives" "$(tree | grep -q '^1/old.png:'; echo $?)" # pipe-grep-q: fail-closed — a missed match yields nonzero, which fails the check

echo gif-two >"$work/files/a.gif"
run 7 "$work/files/a.gif" "$work/files/s.png"
check "re-run for the same PR succeeds" $?
replaced=$(tree | grep '^7/a.gif:')
check "same-named file is replaced" "$(if [[ $replaced == "7/a.gif:$(blob_of gif-two)" ]]; then echo 0; else echo 1; fi)"
check "unmentioned file in the PR folder is kept" "$(tree | grep -q '^7/b.mp4:'; echo $?)" # pipe-grep-q: fail-closed — a missed match yields nonzero, which fails the check
check "new file is added" "$(tree | grep -q '^7/s.png:'; echo $?)" # pipe-grep-q: fail-closed — a missed match yields nonzero, which fails the check

run 8 "$work/files/b.mp4"
check "second PR publishes" $?
survivors=$(tree | grep -c -E '^(7/a.gif|7/b.mp4|7/s.png|1/old.png):')
check "first PR's files survive a second PR" "$(if [[ $survivors -eq 4 ]]; then echo 0; else echo 1; fi)"
commits=$("$real_git" -C "$bare" rev-list --count pr-media)
check "history is linear with one commit per publish" "$(if [[ $commits -eq 4 ]]; then echo 0; else echo 1; fi)"

# --- run from a subdirectory: other PRs' folders survive ---
make_remote
mkdir -p "$work/clone/build/evidence/demo"
(cd "$work/clone/build/evidence/demo" && "$script" 7 "$work/files/a.gif") >"$work/out" 2>"$work/err"
check "publish from a subdirectory succeeds" $?
check "subdirectory run keeps PR 1's folder" "$(tree | grep -q '^1/old.png:'; echo $?)" # pipe-grep-q: fail-closed — a missed match yields nonzero, which fails the check
check "subdirectory run publishes the file" "$(tree | grep -q '^7/a.gif:'; echo $?)" # pipe-grep-q: fail-closed — a missed match yields nonzero, which fails the check
root_names=$("$real_git" -C "$bare" ls-tree --name-only pr-media | tr '\n' ' ')
check "root holds exactly the old and new folders" "$(if [[ $root_names == "1 7 " ]]; then echo 0; else echo 1; fi)"

# --- the guard: a tree that loses an entry is never pushed ---
# A git wrapper drops matching lines from `git mktree` input, standing in for a
# regression in tree building. The production script has no seam for this.
mkdir "$work/dropbin"
cat >"$work/dropbin/git" <<STUB
#!/usr/bin/env bash
if [[ \$1 == mktree ]]; then
  grep -vE "\$DROP" | "$real_git" "\$@"
  exit \${PIPESTATUS[1]}
fi
exec "$real_git" "\$@"
STUB
chmod +x "$work/dropbin/git"
guarded() { # guarded <drop regex> <pr> <file>...
  local drop=$1
  shift
  (cd "$work/clone" && DROP=$drop PATH="$work/dropbin:$PATH" "$script" "$@") >"$work/out" 2>"$work/err"
}
make_remote
before=$(tree)
tip_before=$("$real_git" -C "$bare" rev-parse pr-media)
if guarded $'\t1$' 7 "$work/files/a.gif"; then check "root that drops a folder is refused" 1; else check "root that drops a folder is refused" 0; fi
check "root guard names the problem" "$(grep -q 'would drop top-level entries' "$work/err"; echo $?)"
check "root guard leaves pr-media alone" "$(if [[ $("$real_git" -C "$bare" rev-parse pr-media) == "$tip_before" ]]; then echo 0; else echo 1; fi)"
if guarded $'\told.png$' 1 "$work/files/a.gif"; then check "PR folder that loses a file is refused" 1; else check "PR folder that loses a file is refused" 0; fi
check "PR guard names the problem" "$(grep -q 'would lose files' "$work/err"; echo $?)"
check "PR guard leaves pr-media alone" "$(if [[ $(tree) == "$before" ]]; then echo 0; else echo 1; fi)"
# The same wrapper with nothing to drop publishes normally (the guard has no false positive).
guarded '^$' 1 "$work/files/a.gif"
check "guard allows a replace-and-add publish" $?
check "guard-clean publish keeps the old file" "$(tree | grep -q '^1/old.png:'; echo $?)" # pipe-grep-q: fail-closed — a missed match yields nonzero, which fails the check

# --- subdirectory run into an EXISTING PR folder, with relative input paths ---
make_remote
mkdir -p "$work/clone/build/evidence/demo"
echo rel-gif >"$work/clone/build/evidence/demo/n.gif"
(cd "$work/clone/build/evidence/demo" && "$script" 1 n.gif) >"$work/out" 2>"$work/err"
check "subdirectory publish into an existing PR folder succeeds" $?
check "existing PR file survives a subdirectory run" "$(tree | grep -q '^1/old.png:'; echo $?)" # pipe-grep-q: fail-closed — a missed match yields nonzero, which fails the check
check "new file lands in the existing PR folder" "$(tree | grep -q '^1/n.gif:'; echo $?)" # pipe-grep-q: fail-closed — a missed match yields nonzero, which fails the check

# The guard still catches a drop when the script runs from a subdirectory.
make_remote
mkdir -p "$work/clone/build/evidence/demo"
before=$(tree)
if (cd "$work/clone/build/evidence/demo" && DROP=$'\told.png$' PATH="$work/dropbin:$PATH" "$script" 1 "$work/files/a.gif") >"$work/out" 2>"$work/err"; then
  check "subdirectory run: PR folder that loses a file is refused" 1
else
  check "subdirectory run: PR folder that loses a file is refused" 0
fi
check "subdirectory run: guard leaves pr-media alone" "$(if [[ $(tree) == "$before" ]]; then echo 0; else echo 1; fi)"

# --- rejected input ---
before=$(tree)
rejects() { # rejects <description> <args...>
  local desc=$1
  shift
  if run "$@"; then check "$desc is rejected" 1; else check "$desc is rejected" 0; fi
  check "$desc leaves pr-media alone" "$(if [[ $(tree) == "$before" ]]; then echo 0; else echo 1; fi)"
}
rejects "non-numeric PR number" abc "$work/files/a.gif"
rejects "missing file" 9 "$work/files/none.gif"
mkdir "$work/files/sub"
echo other >"$work/files/sub/a.gif"
rejects "duplicate file name" 9 "$work/files/a.gif" "$work/files/sub/a.gif"
echo bad >"$work/files/.hidden.png"
rejects "name starting with a dot" 9 "$work/files/.hidden.png"
echo bad >"$work/files/has space.png"
rejects "name with a space" 9 "$work/files/has space.png"
rejects "no files" 9

# --- remote without pr-media ---
rm -rf "$work/clone" "$bare"
"$real_git" clone -q --bare "$work/seed" "$bare"
"$real_git" -C "$bare" branch -q -D pr-media
"$real_git" clone -q "$bare" "$work/clone"
if run 9 "$work/files/a.gif"; then check "missing pr-media branch is rejected" 1; else check "missing pr-media branch is rejected" 0; fi
check "missing branch gives a clear message" "$(grep -q 'create the orphan branch pr-media first' "$work/err"; echo $?)"

# --- the race: pr-media advances between the fetch and the push ---
make_remote
mkdir "$work/racebin"
cat >"$work/racebin/git" <<STUB
#!/usr/bin/env bash
if [[ \$1 == push ]]; then
  tip=\$("$real_git" -C "$bare" rev-parse pr-media)
  tree=\$("$real_git" -C "$bare" rev-parse "pr-media^{tree}")
  rival=\$("$real_git" -C "$bare" commit-tree "\$tree" -p "\$tip" -m "rival push")
  "$real_git" -C "$bare" update-ref refs/heads/pr-media "\$rival"
  echo "\$rival" >"$work/rival"
fi
exec "$real_git" "\$@"
STUB
chmod +x "$work/racebin/git"
if (cd "$work/clone" && PATH="$work/racebin:$PATH" "$script" 5 "$work/files/a.gif") >"$work/out" 2>"$work/err"; then
  check "push that loses the race fails" 1
else
  check "push that loses the race fails" 0
fi
check "race loser is a non-fast-forward rejection" "$(grep -qiE 'rejected|non-fast-forward|fetch first' "$work/err"; echo $?)"
tip=$("$real_git" -C "$bare" rev-parse pr-media)
check "rival's commit is still the tip" "$(if [[ $tip == "$(cat "$work/rival")" ]]; then echo 0; else echo 1; fi)"
listing=$(tree)
loser_files=$(grep '^5/' <<<"$listing" || true)
check "loser's files were not published" "$(if [[ -n $loser_files ]]; then echo 1; else echo 0; fi)"

# --- the caller's working tree and index are untouched ---
make_remote
(
  cd "$work/clone"
  echo staged >staged.txt
  "$real_git" add staged.txt
  echo changed >>README
  echo untracked >untracked.txt
)
snapshot() { (cd "$work/clone" && "$real_git" status --porcelain && "$real_git" diff --cached && "$real_git" diff && "$real_git" rev-parse HEAD && cat README staged.txt untracked.txt); }
snapshot_before=$(snapshot)
run 3 "$work/files/a.gif"
check "publish from a dirty clone succeeds" $?
check "working tree, index and HEAD are untouched" "$([[ $(snapshot) == "$snapshot_before" ]]; echo $?)"

if [[ $failures -gt 0 ]]; then
  echo "$failures check(s) failed"
  exit 1
fi
echo "all checks passed"
