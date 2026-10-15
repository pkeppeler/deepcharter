# Homage check

Story text must be homage, not copy (SPEC section 1, LORE.md guardrail 9). `tools/check-homage.py` finds copied wording.

## Run it

```
python3 -I tools/check-homage.py
```

- It compares every five-word sequence in `docs/**/*.md`, the root `README.md` and `CONTEXT.md`, `src/lang/**/*.json` and `src/main/resources/data/**/*.json` (in-game text, transmissions, handbook chapters) with section 6 of `original_flash_game/REFERENCE.md`.
- It prints each shared run as `file:line: words` and exits 1. Rewrite those lines in your own words.
- When `REFERENCE.md` is missing (CI, another machine, a worktree), it says it skipped and exits 0. Use `--reference PATH` to point at the main checkout's copy.
- It prints nothing from `REFERENCE.md` except a shared sequence.

## Matching

- Case, accents, punctuation and apostrophes are ignored, and hyphens and dashes split words.
- In markdown, a link keeps its label and loses its URL, and HTML entities are decoded.
- These also go, so they cannot split a copy: link definitions, bare URLs, inline HTML tags (`<em>`, `<br/>`) and footnote markers (`[^1]`). Only tag-shaped text goes: `x<y and p>q` stays.
- A line break inside a paragraph does not break a sequence. A blank line, heading, list item, table cell or JSON value does.
- Limit: a copy that spans two list items, table cells or JSON strings is not joined, so it can slip through when each part is under five words.
- A sequence that is common English, not copying, goes in `tools/homage-allowlist.txt`: one phrase per line of exactly five words (anything else exits 2), `#` comments, each with a reason. The file does not exist until one is needed.
