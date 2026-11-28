#!/usr/bin/env bash
# Tests tools/lib/pass-carry.sh (a review pass carried across a clean merge of main, #1568, and across a comment- or
# whitespace-only commit, #1640) over real git: a bare "origin", a feature branch, merges of main made cleanly, with a
# conflict resolved by hand, with an extra change, with commits that change only comments or whitespace.
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

# base_java: the Java file every case starts from. A string and a text block hold comment syntax, one line holds a
# suppression marker the gates read.
base_java() {
  cat <<'JAVA'
package p;

/** Doc comment. */
public class Foo {
    String s = "a // not a comment";
    String u = "/* open";
    String t = """
        text /* block */ here
        """;
    // plain comment
    int f() { return 1; }
    int g() { return 2; } // force-ok: reason
}
JAVA
}

git init -q --bare -b main "$work/origin.git"
git clone -q "$work/origin.git" "$work/r" 2>/dev/null
cd "$work/r"
git checkout -q -b main
printf '1\n2\n3\n4\n5\n6\n7\n8\n9\n' >a.txt
echo b >b.txt
base_java >Foo.java
printf '{\n  "k": [1, 2],\n  "v": "x  y"\n}\n' >data.json
printf '# Doc\n\ntext\n' >notes.md
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

# --- an octopus merge (three parents) voids it even when each side merges cleanly ---
start
on_feat a.txt feat1
git rev-parse HEAD >"$PASSES"
on_main b.txt main1
git checkout -q -B side "$base"
echo side >s.txt
git add -A
git commit -q -m "side"
git checkout -q feat
git merge -q --no-edit origin/main side
carries "voided: an octopus merge" 1

# --- a commit that changes only comments or whitespace keeps the pass (#1640) ---
# java_commit <message>: stdin becomes Foo.java, committed on feat.
java_commit() {
  cat >Foo.java
  git add -A
  git commit -q -m "$1"
}
# kinds_say <name> <text>: the log line's kinds mention <text>.
kinds_say() {
  if [[ $carried_kinds == *"$2"* ]]; then pass "$1"; else fail "$1 (kinds: '$carried_kinds')"; fi
}
# pass_on_feat: a feature commit that holds the pass, for the cases below.
pass_on_feat() {
  start
  on_feat a.txt feat1
  git rev-parse HEAD >"$PASSES"
  passed=$(git rev-parse HEAD)
}

pass_on_feat
base_java | sed 's|    // plain comment|    // plain\n    // comment, rewrapped|' | java_commit "rewrap a comment"
carries "carried: a comment rewrap" 0 "$passed"
kinds_say "kinds: a comment push is named" "comment"
base_java | sed 's|/\*\* Doc comment. \*/|/**\n * Doc comment,\n * longer.\n */|' | java_commit "rewrap javadoc"
carries "carried: two comment-only commits in a row" 0 "$passed"

pass_on_feat
base_java | sed 's|    int f() { return 1; }|    int f() {\n        return 1;\n    }|' | java_commit "reflow"
carries "carried: a whitespace reflow" 0 "$passed"
base_java | sed 's|    int f() { return 1; }|    int   f ( )   {return 1 ;}|' | java_commit "squeeze"
carries "carried: spaces added and removed between tokens" 0 "$passed"

pass_on_feat
base_java | sed 's|return 1;|return 3;|' | java_commit "change code"
carries "voided: a changed literal in code" 1
pass_on_feat
base_java | sed 's|"a // not a comment"|"a // not a COMMENT"|' | java_commit "change string"
carries "voided: a changed string literal" 1
pass_on_feat
base_java | sed 's|text /\* block \*/ here|text /* block */ there|' | java_commit "change text block"
carries "voided: a changed text block" 1
pass_on_feat
base_java | sed 's|"a // not a comment"|"a"|' | java_commit "string loses its comment-like tail"
carries "voided: comment syntax inside a string is part of the string" 1
pass_on_feat
base_java | sed 's|String u = "/\* open";|String u = "/* open"; // trailing|' | java_commit "a comment after a string that holds /*"
carries "carried: a comment added after a string that holds /*" 0 "$passed"
pass_on_feat
base_java | sed 's|return 2;|return 22;|' | java_commit "code after the string with /*"
carries "voided: code after a string that holds /* is still code" 1
pass_on_feat
base_java | sed 's|public class Foo|@Deprecated\npublic class Foo|' | java_commit "annotation added"
carries "voided: an added annotation" 1
pass_on_feat
base_java | sed 's|public class Foo|public final class Foo|' | java_commit "modifier added"
carries "voided: an added modifier" 1
pass_on_feat
base_java | sed 's|return 1;|return - -1;|' | java_commit "tokens differ"
carries "voided: tokens differ although the characters match" 1

# A comment that holds a suppression marker is code to the gates: adding, changing or removing one voids the pass.
for marker in '// force-ok: because' '// vanilla-ok: because' '// arrived-ok: because' '// seats-pod: because' '// no-world-ok' \
  '// colour-ok: because' '/* sync-load: because */'; do
  pass_on_feat
  base_java | sed "s|    // plain comment|    $marker|" | java_commit "marker"
  carries "voided: a comment turned into '$marker'" 1
done
pass_on_feat
base_java | sed 's|// force-ok: reason|// force-ok: another reason|' | java_commit "marker reason"
carries "voided: a marker's reason rewritten" 1
pass_on_feat
base_java | sed 's|    int g() { return 2; } // force-ok: reason|    int g() { return 2; }|' | java_commit "marker removed"
carries "voided: a marker removed" 1
pass_on_feat
base_java | sed 's|    int g() { return 2; } // force-ok: reason|    // force-ok: reason\n\n    int g() { return 2; }|' | java_commit "marker moved off its line"
carries "voided: a marker moved away from its code" 1
pass_on_feat
base_java | sed 's|    // plain comment|    // plain comment, but longer: see tick-wait|' | java_commit "marker word mentioned"
carries "voided: a comment that names a marker (fail closed)" 1

# A file that is not Java: whitespace only (JSON), nothing else.
pass_on_feat
printf '{"k":[1,2],\n\n  "v":   "x  y"}\n' >data.json
git add -A; git commit -q -m "json reflow"
carries "carried: a JSON reflow" 0 "$passed"
kinds_say "kinds: whitespace is named" "whitespace"
pass_on_feat
printf '{\n  "k": [1, 2],\n  "v": "x y"\n}\n' >data.json
git add -A; git commit -q -m "json string"
carries "voided: a JSON string changed" 1
pass_on_feat
printf '{\n  "v": "x  y",\n  "k": [1, 2]\n}\n' >data.json
git add -A; git commit -q -m "json order"
carries "voided: JSON keys reordered" 1
pass_on_feat
printf '# Doc\n\ntext \n' >notes.md
git add -A; git commit -q -m "md trailing space"
carries "voided: markdown, even whitespace only" 1
pass_on_feat
printf '1\n2\n3\n4\n5\n6\n7\n8\n9\n\n' >a.txt
git add -A; git commit -q -m "txt whitespace"
carries "voided: a file type that is not Java or JSON" 1

# Added, removed, renamed files, a mode change and an unparseable file.
pass_on_feat
echo 'class Bar {}' >Bar.java
git add -A; git commit -q -m "new file"
carries "voided: an added file" 1
pass_on_feat
git rm -q b.txt
git commit -q -m "removed file"
carries "voided: a removed file" 1
pass_on_feat
git mv Foo.java Foo2.java
git commit -q -m "rename"
carries "voided: a renamed file" 1
pass_on_feat
chmod +x Foo.java
git add -A; git commit -q -m "mode"
carries "voided: a mode change" 1
pass_on_feat
base_java | sed 's|"a // not a comment"|"a // not a comment|' | java_commit "unterminated string"
carries "voided: an unterminated string (unparseable)" 1
pass_on_feat
{ base_java; echo '/* never closed'; } | java_commit "unterminated comment"
carries "voided: an unterminated comment" 1
pass_on_feat
base_java | sed 's|    // plain comment|    // plain \\u000a int x;|' | java_commit "unicode escape in a comment"
carries "voided: a unicode escape outside a literal" 1

# Mixed with clean merges of main, in either order.
pass_on_feat
on_main b.txt main1
git merge -q --no-edit origin/main
base_java | sed 's|    // plain comment|    // plain\n    // comment, rewrapped|' | java_commit "rewrap after merge"
on_main b.txt main2
git merge -q --no-edit origin/main
carries "carried: merge, comment rewrap, merge" 0 "$passed"
kinds_say "kinds: the merge is named" "merge"
kinds_say "kinds: the comment push is named too" "comment"
base_java | sed 's|return 1;|return 3;|' | java_commit "code"
carries "voided: a code change after merges and a rewrap" 1
pass_on_feat
base_java | sed 's|    // plain comment|    // rewrapped|' | java_commit "rewrap"
on_main b.txt main1
git merge -q --no-edit origin/main
carries "carried: a rewrap, then a merge" 0 "$passed"

# A comment commit on top of a hand-resolved merge is still void.
pass_on_feat
on_main b.txt main1
git merge -q --no-commit --no-ff origin/main
echo smuggled >c.txt
git add -A
git commit -q -m "merge main (with a smuggled file)"
base_java | sed 's|    // plain comment|    // rewrapped|' | java_commit "rewrap"
carries "voided: a rewrap on a merge that adds a change" 1

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
