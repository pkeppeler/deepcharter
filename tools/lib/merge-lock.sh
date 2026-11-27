# Sourced by tools/merge-queue.sh and tools/merge-batch.sh: the one machine-wide merge lock, so a queue and a batch
# never run together. The caller sets $lock and a `say` function, then calls acquire_lock. A refusal exits 3.
# Behaviour is in tools/merge-queue.sh's header.

refuse_lock() {
  echo "REFUSED: $1" >&2
  exit 3
}
acquire_lock() {
  local holder moved
  for _ in 1 2 3 4 5 6; do
    if [[ -d $lock && ! -L $lock ]]; then
      # Not our format (ln -s would create the link inside it): treat as held.
      refuse_lock "$lock is a directory, not a queue lock; remove it if no queue is running"
    fi
    if ln -s "$$" "$lock" 2>/dev/null; then
      return 0
    fi
    holder=$(readlink "$lock" 2>/dev/null || true)
    if [[ ! $holder =~ ^[0-9]+$ ]]; then
      # Gone between the ln and the read: try again. Unreadable: held, wait and re-read.
      if [[ -e $lock || -L $lock ]]; then sleep 1; fi
      continue
    fi
    if kill -0 "$holder" 2>/dev/null; then
      refuse_lock "another merge queue is running (PID $holder, lock $lock); wait for it to finish"
    fi
    say "taking over a stale lock (owner PID $holder is not running)"
    # Rename it aside: of several processes doing this, one wins the rename.
    if mv "$lock" "$lock.dead.$$" 2>/dev/null; then
      moved=$(readlink "$lock.dead.$$" 2>/dev/null || true)
      if [[ $moved != "$holder" ]]; then
        # Someone replaced the dead lock before our rename, so we moved a live one: put it back.
        ln -s "$moved" "$lock" 2>/dev/null || true
      fi
      rm -f "$lock.dead.$$"
    fi
  done
  refuse_lock "could not take the merge queue lock $lock (held or unreadable)"
}
