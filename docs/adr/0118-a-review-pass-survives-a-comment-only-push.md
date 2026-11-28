---
status: accepted
---

# A review pass survives a comment-only push

[#1640](https://github.com/pkeppeler/deepcharter/issues/1640): #1542 passed review, then two Javadoc lines were rewrapped for the comment-length gate. The push voided the pass and cost a full cycle-2 review of a no-op. [ADR 0114](0114-a-review-pass-survives-a-clean-merge-of-main.md) carries a pass across clean merges of `main` only.

## Decision

- **A second kind of link.** The chain `tools/lib/pass-carry.sh` walks back from the head to a passed commit may also hold a commit with one parent that `tools/semantic_diff.py <parent> <commit>` calls comment-only. Merge links are unchanged and the two kinds mix in any order. The 20-link cap counts both.
- **Comment-only.** Every path that differs between the commit and its parent is a `.java` file whose code is untouched: with every comment replaced by nothing, the lines are equal byte for byte, ignoring only lines that are wholly blank or wholly comment, before and after. A small lexer finds the comments: it handles line and block comments and string, char and text-block literals, so `//` or `/*` inside a literal is not a comment and a literal is code. A comment rewrap, an added or deleted comment and a blank line carry.
- **A whitespace reflow of code voids.** Indentation, spacing, a line join or split, a trailing space and the final newline are all code here. Line-scan gates read lines, so a reflow can hide a pattern from one, and the check cannot tell a harmless reflow from that. Spotless reflows after the review pass, so the gates, not the carry, cover it.
- **Comments that something reads are code.** A comment that matches `MARKER` in `tools/semantic_diff.py` stays in the compared lines, with its text and the count of blank or comment lines on each side (the gates read a marker on the line or in the contiguous `//` lines above). Adding, removing, rewording or moving one voids the pass, and so does a comment that merely names one. `MARKER` holds:
  - every `<word>-ok` and the gates' other markers (`// force-ok:`, `// vanilla-ok:`, `// arrived-ok:`, `// seats-pod:`, `// world-data:`, `// room-carver:`, `no-world-ok` and the rest);
  - what javac, formatters and analysers read: `@deprecated`, fall-through comments, `NOPMD`, `NOSONAR`, `noinspection`, `spotless:`.

  `tools/tests/test_semantic_diff.py` scans the gate definitions (`gradle/*.kts`, `tools/*.java`, `tools/*gate*.py`) for `// <name>` markers and fails when `MARKER` misses one, so a new gate cannot be silenced by a carry.
- **Everything else voids.** An added, removed, renamed, mode- or type-changed path; any other file type (markdown and docs are reviewed content, JSON and others have no comments to carry); a file that is not valid UTF-8; and anything the lexer cannot read: an unterminated string, text block or comment, a backslash-u escape anywhere in the file (Java reads it before it lexes, so `"" // a"` is not what the lexer sees), and a carriage return that is not part of `\r\n` (Java ends a line at it, a `//` comment does not). The tool fails closed: any failure to read git or the files voids.
- **Where it runs.** `tools/mark-review-passed.sh --carry` and the merge gate share `carry_review_pass`. The log line names what was carried (`carried from <sha> across: clean merge of origin/main, comment-only change`).
- **CI is unchanged**: the new head gets its own run, so the checks still see it.

## Consequences

- A rewrap for a comment gate, or a typo fix in a comment, keeps the pass. A reviewer's reading of comments is not carried, so a comment that misdescribes the code after such a push is a defect the next review pays for.
- A formatter run that touches code voids the pass, as any code change does.
- Carrying another file type means a comparison that is exact for it, by amending this ADR.

## Considered options

- **Carry whitespace-only reflows too, by comparing token streams.** First built, then rejected: a reflow can move a line the line-scan gates read, and the token view cannot see that.
- **A regex that strips comments.** Rejected: `//` and `/*` inside strings and text blocks defeat it, and a wrong strip carries a code change.
- **javac's parser.** Rejected: a JDK launch per file for a lexer's worth of facts, and it would need the toolchain on every machine that merges.
- **Python's `shlex` or `tokenize`.** Rejected: neither knows Java literals or comments.
