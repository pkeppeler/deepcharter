# Sourced by the gh stubs in the shell tests. gh_fixture_jq <file> <expr>: what `gh api --jq <expr>` prints for the
# response in <file>, so the stub runs the script's own filter on recorded JSON and a changed filter fails on real data.
#
# gh evaluates --jq with its built-in gojq. gh has no offline way to read a local file, and tools/py has no jq, so this
# runs system jq. The Mac has none: gh_fixture_available is false there and the fixture cases print a SKIP line.
# CI's Ubuntu runners have jq, so CI always runs them.
# Matching gh's output: raw strings, compact JSON for objects and arrays, and an empty line for null.

gh_fixture_available() { command -v jq >/dev/null; }

gh_fixture_jq() {
  jq -r -c "($2) | if . == null then \"\" else . end" "$1"
}
