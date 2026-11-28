# Sourced by tools/gate-sweep.sh: read a failed Gradle run's output (#1639). Compatible with bash 3.2.

# gradle_failed_tasks <log>: the tasks that failed, `:` dropped, space-separated, none when the build failed before a task ran.
gradle_failed_tasks() {
  grep -oE "Execution failed for task '[^']+'" "$1" | sed -E "s/.*'[:]?(.*)'/\\1/" | sort -u | tr '\n' ' ' | sed 's/ $//' || true
}

# gradle_failure_message <log>: Gradle's "What went wrong" block as one line: the generic "Execution failed for task" line dropped,
# the `> ` prefixes removed, the first 8 lines.
gradle_failure_message() {
  sed -n '/^\* What went wrong:/,/^\* Try:/p' "$1" | sed '1d;$d' | grep -v '^$' | grep -v '^Execution failed for task' \
    | sed -E 's/^> //; s/^[[:space:]]+//' | head -8 | tr '\n' ' ' | sed 's/ $//' || true
}
