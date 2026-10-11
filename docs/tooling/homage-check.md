# Homage check

Story text must be homage, not copy (SPEC section 1, LORE.md guardrail 9). `tools/check-homage.py` finds copied wording.

## Run it

```
python3 -I tools/check-homage.py
```

- It compares every five-word sequence in `docs/**/*.md`, `src/lang/**/*.json` and `src/main/resources/data/**/*.json` (in-game text, transmissions, handbook chapters) with section 6 of `original_flash_game/REFERENCE.md`.
- It prints each shared run as `file:line: words` and exits 1. Rewrite those lines in your own words.
- When `REFERENCE.md` is missing (CI, another machine, a worktree), it says it skipped and exits 0. Use `--reference PATH` to point at the main checkout's copy.
- It prints nothing from `REFERENCE.md` except a shared sequence.

## Matching

- Case, punctuation and apostrophes are ignored, and hyphens split words.
- A line break inside a paragraph does not break a sequence. A blank line, heading, list item, table cell or JSON value does.
- A sequence that is common English, not copying, goes in `tools/homage-allowlist.txt`: one five-word phrase per line, `#` comments, each with a reason. The file does not exist until one is needed.
