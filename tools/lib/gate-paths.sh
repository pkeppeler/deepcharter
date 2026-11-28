# Sourced by tools/gate-sweep.sh and tools/lib/pr-body-checks.sh: the one list of what counts as gate code, and the map from a
# gate file to what runs it (#1639). Compatible with bash 3.2.

# is_gate_path <path>: the path is gate code, so a change to it can fail a PR that never touched it. A gate's own fixture
# test (tools/tests/) is not: it changes no verdict on other PRs.
is_gate_path() {
  case $1 in
    tools/tests/*) return 1 ;;
    gradle/*gate*.gradle.kts | gradle/literals.gradle.kts) return 0 ;;
    gradle/*allowlist*.txt | gradle/banned-*.txt) return 0 ;;
    tools/*Gate.java | tools/*gate*.py) return 0 ;;
    .githooks/pre-push) return 0 ;;
  esac
  return 1
}

# gate_check_tasks <file>: the `check*` Gradle tasks a Gradle script registers, one per line.
gate_check_tasks() {
  grep -oE 'tasks\.register(<[A-Za-z]+>)?\("check[A-Za-z0-9]+"\)' "$1" | grep -oE 'check[A-Za-z0-9]+' || true
}

# gate_targets <root> <path>...: what to run for the gate files among the paths, tab-separated, one per line, no repeats:
#   gradle <task>             a Gradle task (a script's own check tasks; for a list or a shared scanner, those of every script
#                             that names it)
#   py <tool>                 a Python gate, run as tools/py <tool>
#   unswept <path> <why>      a gate file with nothing to run: the sweep reports it instead of passing it
# <root> is the tree whose gradle/ scripts are read (the gate PR's head).
gate_targets() {
  local root=$1 path needle script task found seen=$'\n'
  shift
  for path in "$@"; do
    is_gate_path "$path" || continue
    found=
    case $path in
      .githooks/pre-push)
        found="unswept	$path	a hook, not a build task: read its diff against each open PR by hand"
        ;;
      tools/*.py)
        if [[ -f $root/$path ]]; then found="py	$path"; else found="unswept	$path	no longer in the tree: nothing to run"; fi
        ;;
      gradle/*.gradle.kts)
        if [[ ! -f $root/$path ]]; then
          found="unswept	$path	no longer in the tree: nothing to run"
        else
          while IFS= read -r task; do
            [[ -z $task ]] || found="${found:+$found$'\n'}gradle	$task"
          done <<<"$(gate_check_tasks "$root/$path")"
          [[ -n $found ]] || found="unswept	$path	registers no check task: run ./gradlew check -x runGameTest"
        fi
        ;;
      *)
        # A list or a Java scanner: every Gradle script that names it.
        needle=$path
        [[ $path != gradle/* ]] || needle=${path##*/}
        for script in "$root"/gradle/*.gradle.kts; do
          [[ -f $script ]] || continue
          grep -qF -- "$needle" "$script" || continue
          while IFS= read -r task; do
            [[ -z $task ]] || found="${found:+$found$'\n'}gradle	$task"
          done <<<"$(gate_check_tasks "$script")"
        done
        [[ -n $found ]] || found="unswept	$path	no Gradle script names it: run ./gradlew check -x runGameTest"
        ;;
    esac
    while IFS= read -r task; do
      [[ $seen == *$'\n'"$task"$'\n'* ]] && continue
      seen+="$task"$'\n'
      printf '%s\n' "$task"
    done <<<"$found"
  done
}
