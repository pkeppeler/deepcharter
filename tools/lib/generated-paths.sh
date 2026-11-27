# Sourced by tools/lib/ci-gate.sh and tools/merge-batch.sh: the files the merge tools generate and commit to main
# themselves. A main commit that touches only these paths never makes a PR's CI stale (#1567): no job reads them.
# One list, so a new generated file is added in one place.

GENERATED_PATHS=(docs/ROADMAP.md .papercuts.jsonl)

# generated_path <path>: 0 when the path is one of GENERATED_PATHS (exact, never a nested lookalike).
generated_path() {
  local g
  for g in "${GENERATED_PATHS[@]}"; do
    [[ $1 == "$g" ]] && return 0
  done
  return 1
}

# moved_code_paths <from> <to>: the paths that differ between the two commits and are not generated, one per line.
# Returns 1 when the diff cannot be read.
moved_code_paths() {
  local moved path
  moved=$(git diff --name-only --no-renames "$1" "$2") || return 1
  while IFS= read -r path; do
    [[ -n $path ]] && ! generated_path "$path" && echo "$path"
  done <<<"$moved"
  return 0
}
