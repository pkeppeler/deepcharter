# Sourced by tools/gate-sweep.sh and tools/lib/pr-body-checks.sh: what counts as gate code and what runs it (#1639). The rules
# are data, in tools/gate-map.txt (its header says the format). Compatible with bash 3.2.

gate_map_file() { echo "${GATE_MAP_FILE:-$(dirname "${BASH_SOURCE[0]}")/../gate-map.txt}"; }

# gate_rule <path>: sets $gate_actions to the actions of the first rule whose glob matches the path; fails when none does.
gate_rule() {
  local line glob actions
  gate_actions=
  while IFS= read -r line; do
    [[ -n ${line//[[:space:]]/} && $line != \#* ]] || continue
    read -r glob actions <<<"$line"
    # shellcheck disable=SC2053 # the glob is the rule
    if [[ $1 == $glob ]]; then
      gate_actions=$actions
      return 0
    fi
  done <"$(gate_map_file)"
  return 1
}

# is_gate_path <path>: the path is gate code, so a change to it can fail a PR that never touched it.
is_gate_path() {
  [[ -n $1 ]] && gate_rule "$1" && [[ $gate_actions != ignore ]]
}

# gate_check_tasks <file>: the `check*` Gradle tasks a Gradle script registers by a literal name, one per line. A name built at
# runtime (`register("check$name")`) is not seen: tools/gate-map.txt lists such tasks with `tasks`, and gate-sweep.test.sh fails
# on a script that registers one.
gate_check_tasks() {
  grep -oE 'tasks\.register(<[A-Za-z]+>)?\("check[A-Za-z0-9]+"\)' "$1" | grep -oE 'check[A-Za-z0-9]+' || true
}

# gate_targets <root> <path>...: what to run for the gate files among the paths, tab-separated, one per line, no repeats:
#   gradle <task>             a Gradle task
#   py <file>                 `tools/py <file>`
#   unittest <file>           the unit tests in <file>
#   shell <file>              `bash <file>`
#   unswept <path> <why>      a gate file with nothing to run: the sweep reports it instead of passing it
# <root> is the tree whose files are read (the gate PR's head).
gate_targets() {
  local root=$1 path actions action verb args found seen=$'\n' record needle script task target gone
  shift
  for path in "$@"; do
    is_gate_path "$path" || continue
    actions=$gate_actions
    found=
    gone=
    [[ -f $root/$path ]] || gone=1
    while IFS= read -r action; do
      read -r verb args <<<"$action"
      case $verb in
        script)
          [[ -z $gone ]] || continue
          while IFS= read -r task; do
            [[ -z $task ]] || found="${found:+$found$'\n'}gradle	$task"
          done <<<"$(gate_check_tasks "$root/$path")"
          [[ -n $found ]] || found="unswept	$path	registers no check task: run ./gradlew check -x runGameTest"
          ;;
        named)
          [[ -z $gone ]] || continue
          needle=$path
          case $path in
            gradle/*) needle=${path##*/} ;;
            config/*) needle=${path%/*} ;;
          esac
          for script in "$root"/gradle/*.gradle.kts; do
            [[ -f $script ]] || continue
            grep -qF -- "$needle" "$script" || continue
            while IFS= read -r task; do
              [[ -z $task ]] || found="${found:+$found$'\n'}gradle	$task"
            done <<<"$(gate_check_tasks "$script")"
          done
          [[ -n $found ]] || found="unswept	$path	no Gradle script names it: run ./gradlew check -x runGameTest"
          ;;
        tasks)
          for task in $args; do found="${found:+$found$'\n'}gradle	$task"; done
          ;;
        tool | unittest | shell)
          [[ -z $gone ]] || continue
          target=${args:-$path}
          case $verb in
            tool) record="py	$target" ;;
            *) record="$verb	$target" ;;
          esac
          found="${found:+$found$'\n'}$record"
          ;;
        unswept)
          found="${found:+$found$'\n'}unswept	$path	$args"
          ;;
      esac
    done <<<"${actions//;/$'\n'}"
    if [[ -n $gone && -z $found ]]; then
      found="unswept	$path	no longer in the tree: nothing to run"
    fi
    while IFS= read -r record; do
      [[ -n $record ]] || continue
      [[ $seen == *$'\n'"$record"$'\n'* ]] && continue
      seen+="$record"$'\n'
      printf '%s\n' "$record"
    done <<<"$found"
  done
}
