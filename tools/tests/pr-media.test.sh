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

# --- the readme folder: stable names, replaced by name, other folders kept ---
make_remote
run readme "$work/files/a.gif" "$work/files/s.png"
check "readme folder publishes" $?
check "readme files land under readme/" "$(tree | grep -q '^readme/a.gif:'; echo $?)" # pipe-grep-q: fail-closed — a missed match yields nonzero, which fails the check
check "readme snippet links the stable path" "$(grep -qF '![a](https://github.com/pkeppeler/deepcharter/blob/pr-media/readme/a.gif?raw=true)' "$work/out"; echo $?)"
echo gif-three >"$work/files/a.gif"
run readme "$work/files/a.gif"
check "readme re-publish succeeds" $?
replaced=$(tree | grep '^readme/a.gif:')
check "readme file is replaced by name" "$(if [[ $replaced == "readme/a.gif:$(blob_of gif-three)" ]]; then echo 0; else echo 1; fi)"
check "readme keeps the file it did not replace" "$(tree | grep -q '^readme/s.png:'; echo $?)" # pipe-grep-q: fail-closed — a missed match yields nonzero, which fails the check
check "readme publish keeps PR 1's folder" "$(tree | grep -q '^1/old.png:'; echo $?)" # pipe-grep-q: fail-closed — a missed match yields nonzero, which fails the check
subject=$("$real_git" -C "$bare" log -1 --format=%s pr-media)
check "readme commit message names the tour" "$(if [[ $subject == "Update README tour media" ]]; then echo 0; else echo 1; fi)"
for bad in README -readme a/b/c /x x/ 'x y' a/../b ./a; do
  if run "$bad" "$work/files/a.gif"; then check "folder '$bad' is rejected" 1; else check "folder '$bad' is rejected" 0; fi
done

# --- a nested folder: its siblings, the top folder's other entries and the other folders survive ---
make_remote
run looks/dusk-company "$work/files/a.gif" "$work/files/s.png"
check "nested folder publishes" $?
run looks/other "$work/files/b.mp4"
check "second nested folder publishes" $?
echo gif-four >"$work/files/a.gif"
run looks/dusk-company "$work/files/a.gif"
check "nested re-publish succeeds" $?
replaced=$(tree | grep '^looks/dusk-company/a.gif:')
check "nested file is replaced by name" "$(if [[ $replaced == "looks/dusk-company/a.gif:$(blob_of gif-four)" ]]; then echo 0; else echo 1; fi)"
check "nested folder keeps the file it did not replace" "$(tree | grep -q '^looks/dusk-company/s.png:'; echo $?)" # pipe-grep-q: fail-closed — a missed match yields nonzero, which fails the check
check "sibling nested folder survives" "$(tree | grep -q '^looks/other/b.mp4:'; echo $?)" # pipe-grep-q: fail-closed — a missed match yields nonzero, which fails the check
check "PR 1's folder survives a nested publish" "$(tree | grep -q '^1/old.png:'; echo $?)" # pipe-grep-q: fail-closed — a missed match yields nonzero, which fails the check
check "nested snippet links the nested path" "$(grep -qF 'pr-media/looks/dusk-company/a.gif?raw=true' "$work/out"; echo $?)"

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

# The guard compares types too: a name that survives as the other type is a lost entry.
echo plain-file >"$work/files/dusk-company"
make_remote
run looks/dusk-company "$work/files/a.gif"
before=$(tree)
if run looks "$work/files/dusk-company"; then check "file named like an existing subfolder is refused" 1; else check "file named like an existing subfolder is refused" 0; fi
check "file-over-folder guard names the lost folder" "$(grep -q 'looks/ would lose files that were not replaced: tree dusk-company' "$work/err"; echo $?)"
check "file-over-folder guard leaves pr-media alone" "$(if [[ $(tree) == "$before" ]]; then echo 0; else echo 1; fi)"
make_remote
run looks "$work/files/dusk-company"
before=$(tree)
if run looks/dusk-company "$work/files/a.gif"; then check "folder named like an existing file is refused" 1; else check "folder named like an existing file is refused" 0; fi
check "folder-over-file guard names the lost file" "$(grep -q 'looks/ would lose entries: blob dusk-company' "$work/err"; echo $?)"
check "folder-over-file guard leaves pr-media alone" "$(if [[ $(tree) == "$before" ]]; then echo 0; else echo 1; fi)"

# The guard for a nested folder: the top folder must keep its other entries.
make_remote
run looks/dusk-company "$work/files/a.gif"
run looks/other "$work/files/b.mp4"
before=$(tree)
if guarded $'\tother$' looks/dusk-company "$work/files/s.png"; then check "top folder that loses a sibling is refused" 1; else check "top folder that loses a sibling is refused" 0; fi
check "top folder guard names the problem" "$(grep -q 'looks/ would lose entries' "$work/err"; echo $?)"
check "top folder guard leaves pr-media alone" "$(if [[ $(tree) == "$before" ]]; then echo 0; else echo 1; fi)"

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
rejects "uppercase folder" ABC "$work/files/a.gif"
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
# A git wrapper, for the first RIVALS pushes, lands a rival commit adding the folder rival<N>/ on the
# remote just before the real push, so that push is rejected. After the first push it can also drop
# tree entries (DROP, as above) or fail the push like a bad login (AUTH=1).
mkdir "$work/racebin"
cat >"$work/racebin/git" <<STUB
#!/usr/bin/env bash
if [[ \$1 == mktree && -n \${DROP:-} && -s "$work/pushes" ]]; then
  grep -vE "\$DROP" | "$real_git" "\$@"
  exit \${PIPESTATUS[1]}
fi
if [[ \$1 == fetch ]]; then
  echo x >>"$work/fetches"
  if [[ \$(wc -l <"$work/fetches" | tr -d ' ') -le \${FETCH_FAILS:-0} ]]; then
    echo "fatal: unable to access remote" >&2
    exit 128
  fi
fi
if [[ \$1 == push ]]; then
  echo x >>"$work/pushes"
  n=\$(wc -l <"$work/pushes" | tr -d ' ')
  if [[ \${AUTH:-} == 1 ]]; then
    echo "fatal: Authentication failed" >&2
    exit 128
  fi
  if [[ \$n -le \${RIVALS:-0} ]]; then
    tip=\$("$real_git" -C "$bare" rev-parse pr-media)
    blob=\$(echo rival | "$real_git" -C "$bare" hash-object -w --stdin)
    sub=\$(printf '100644 blob %s\trival.png\n' "\$blob" | "$real_git" -C "$bare" mktree)
    if [[ \${RIVAL_NESTED:-} == 1 ]]; then
      looks=\$({ "$real_git" -C "$bare" ls-tree "\$tip:looks"; printf '040000 tree %s\tother\n' "\$sub"; } | "$real_git" -C "$bare" mktree)
      tree=\$({ "$real_git" -C "$bare" ls-tree "\$tip" | awk -F'\t' '\$2 != "looks"'; printf '040000 tree %s\tlooks\n' "\$looks"; } | "$real_git" -C "$bare" mktree)
    else
      tree=\$({ "$real_git" -C "$bare" ls-tree "\$tip"; printf '040000 tree %s\trival%s\n' "\$sub" "\$n"; } | "$real_git" -C "$bare" mktree)
    fi
    rival=\$("$real_git" -C "$bare" commit-tree "\$tree" -p "\$tip" -m "rival push")
    "$real_git" -C "$bare" update-ref refs/heads/pr-media "\$rival"
    echo "\$rival" >"$work/rival"
  fi
fi
exec "$real_git" "\$@"
STUB
chmod +x "$work/racebin/git"
raced() { # raced <env assignments...> -- <pr> <file>...; counts the pushes in $work/pushes
  local envs=()
  while [[ $1 != -- ]]; do
    envs+=("$1")
    shift
  done
  shift
  rm -f "$work/pushes" "$work/fetches"
  (cd "$work/clone" && env ${envs[@]+"${envs[@]}"} PATH="$work/racebin:$PATH" "$script" "$@") >"$work/out" 2>"$work/err"
}
pushes() { if [[ -f $work/pushes ]]; then wc -l <"$work/pushes" | tr -d ' '; else echo 0; fi; }

make_remote
if raced RIVALS=1 -- 5 "$work/files/a.gif"; then check "publish that loses one race succeeds on retry" 0; else check "publish that loses one race succeeds on retry" 1; fi
check "race winner's folder is kept" "$(tree | grep -q '^rival1/rival.png:'; echo $?)" # pipe-grep-q: fail-closed — a missed match yields nonzero, which fails the check
check "race loser's file lands too" "$(tree | grep -q '^5/a.gif:'; echo $?)" # pipe-grep-q: fail-closed — a missed match yields nonzero, which fails the check
check "pre-existing folder survives a retry" "$(tree | grep -q '^1/old.png:'; echo $?)" # pipe-grep-q: fail-closed — a missed match yields nonzero, which fails the check
check "one lost race costs two pushes" "$(if [[ $(pushes) -eq 2 ]]; then echo 0; else echo 1; fi)"
check "retry says the tip moved" "$(grep -q 'retrying on the new tip' "$work/err"; echo $?)"
check "retry keeps history linear: old, rival, mine" "$(if [[ $("$real_git" -C "$bare" rev-list --count pr-media) -eq 3 ]]; then echo 0; else echo 1; fi)"

make_remote
if raced RIVALS=99 -- 5 "$work/files/a.gif"; then check "publish that keeps losing the race fails" 1; else check "publish that keeps losing the race fails" 0; fi
check "giving up names the rejection and the likely race" "$(grep -q 'push rejected 5 times in a row (likely another publisher)' "$work/err"; echo $?)"
check "giving up stops after five pushes" "$(if [[ $(pushes) -eq 5 ]]; then echo 0; else echo 1; fi)"
check "giving up leaves the rival's commit as the tip" "$(if [[ $("$real_git" -C "$bare" rev-parse pr-media) == "$(cat "$work/rival")" ]]; then echo 0; else echo 1; fi)"
check "giving up publishes none of the loser's files" "$(if [[ -z $(tree | grep '^5/' || true) ]]; then echo 0; else echo 1; fi)"

# A nested folder: the rival adds looks/other while this run adds looks/dusk-company.
make_remote
run looks/seed "$work/files/b.mp4"
if raced RIVALS=1 RIVAL_NESTED=1 -- looks/dusk-company "$work/files/a.gif"; then check "nested publish that loses a race succeeds on retry" 0; else check "nested publish that loses a race succeeds on retry" 1; fi
check "nested race: rival's leaf survives" "$(tree | grep -q '^looks/other/rival.png:'; echo $?)" # pipe-grep-q: fail-closed — a missed match yields nonzero, which fails the check
check "nested race: this run's leaf lands" "$(tree | grep -q '^looks/dusk-company/a.gif:'; echo $?)" # pipe-grep-q: fail-closed — a missed match yields nonzero, which fails the check
check "nested race: the earlier leaf survives" "$(tree | grep -q '^looks/seed/b.mp4:'; echo $?)" # pipe-grep-q: fail-closed — a missed match yields nonzero, which fails the check

# A transient fetch failure retries in the same budget; one that never ends fails loudly.
make_remote
if raced FETCH_FAILS=2 -- 5 "$work/files/a.gif"; then check "publish survives two failed fetches" 0; else check "publish survives two failed fetches" 1; fi
landed=$(tree | grep -c '^5/a.gif:' || true)
check "failed fetches are retried, then one push lands" "$(if [[ $(pushes) -eq 1 && $landed -eq 1 ]]; then echo 0; else echo 1; fi)"
make_remote
if raced FETCH_FAILS=99 -- 5 "$work/files/a.gif"; then check "publish with a dead remote fails" 1; else check "publish with a dead remote fails" 0; fi
check "dead remote gives up loudly after five fetches" "$(if grep -q 'fetch failed 5 times in a row (network or remote problem)' "$work/err" && [[ $(wc -l <"$work/fetches" | tr -d ' ') -eq 5 ]]; then echo 0; else echo 1; fi)"
check "dead remote pushes nothing" "$(if [[ $(pushes) -eq 0 ]]; then echo 0; else echo 1; fi)"

# A guard that fails against the new tip refuses; there is no retry around a guard.
make_remote
if raced RIVALS=1 $'DROP=\trival1$' -- 5 "$work/files/a.gif"; then check "guard failure on the new tip is refused" 1; else check "guard failure on the new tip is refused" 0; fi
check "guard failure on the new tip names the problem" "$(grep -q 'would drop top-level entries' "$work/err"; echo $?)"
check "guard failure on the new tip is not retried" "$(if [[ $(pushes) -eq 1 ]]; then echo 0; else echo 1; fi)"
check "guard failure on the new tip leaves the rival's commit as the tip" "$(if [[ $("$real_git" -C "$bare" rev-parse pr-media) == "$(cat "$work/rival")" ]]; then echo 0; else echo 1; fi)"

# Only a race retries: a failure like a bad login is reported once.
make_remote
if raced AUTH=1 -- 5 "$work/files/a.gif"; then check "push that fails to authenticate fails" 1; else check "push that fails to authenticate fails" 0; fi
check "failed authentication is not retried" "$(if [[ $(pushes) -eq 1 ]]; then echo 0; else echo 1; fi)"
check "failed authentication is not called a lost race" "$(if grep -q 'likely another publisher' "$work/err"; then echo 1; else echo 0; fi)"

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
