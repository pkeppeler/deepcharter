---
status: accepted
---

# A review pass survives a push that changes no code token

[#1640](https://github.com/pkeppeler/deepcharter/issues/1640): #1542 passed review, then two Javadoc lines were rewrapped for the comment-length gate. The push voided the pass and cost a full cycle-2 review of a no-op. [ADR 0114](0114-a-review-pass-survives-a-clean-merge-of-main.md) carries a pass across clean merges of `main` only.

## Decision

- **A second kind of link.** The chain `tools/lib/pass-carry.sh` walks back from the head to a passed commit may also hold a commit with one parent that `tools/semantic_diff.py <parent> <commit>` calls semantically empty. Merge links are unchanged and the two kinds mix in any order. The 20-link cap counts both.
- **Semantically empty.** Every path that differs between the commit and its parent is one of:
  - a `.java` file whose token stream is identical once comments and whitespace are dropped. A small lexer does this: it handles line and block comments, string, char and text-block literals (so `//` or `/*` inside a literal is not a comment), takes the longest operator, and reads numbers and words as one token each. A literal is a token, so a changed string is a change, and an annotation is tokens, so any change to one is a change.
  - a `.json` file whose tokens, strings included, are identical once whitespace is dropped (so key order and a string's spaces count).
- **Suppression markers are code.** The gates read comments such as `// force-ok:`, `// vanilla-ok:`, `// arrived-ok:`, `// seats-pod:` and `no-world-ok`. A comment that matches any `<word>-ok` or one of the other marker names the conventions skill lists stays in the token stream, with its text and the newlines between it and the code on each side (the gates read a marker on the line, or in the contiguous `//` lines above). Adding, removing, rewording or moving one voids the pass, and so does a comment that merely names one. A marker added to the skill's list is added to `MARKER` in `tools/semantic_diff.py` in the same PR.
- **Everything else voids.** An added, removed, renamed, mode- or type-changed path; any other file type, markdown and docs included (they are reviewed content, and a trailing space can matter in a shell heredoc, a properties value or a Python string); a file that is not valid UTF-8; and anything the lexer cannot parse: an unterminated string, text block or comment, a character it does not know, a `\u` escape outside a literal (Java reads it before comments). The tool fails closed: any failure to read git or the files voids.
- **Where it runs.** `tools/mark-review-passed.sh --carry` and the merge gate share `carry_review_pass`. The log line names what was carried (`carried from <sha> across: clean merge of origin/main, comment-only change`).
- **CI is unchanged**: the new head gets its own run, so the checks still see it.

## Consequences

- A rewrap for a comment gate, a Spotless reflow or a typo fix in a comment keeps the pass. A reviewer's reading of comments is not carried, so a comment that misdescribes the code after such a push is a defect the next review pays for.
- The check is about tokens, not behavior: a whitespace reflow can still move a line the line-scan gates read (`.button() == 1` split over two lines). Those gates run in CI on the new head.
- Non-Java and non-JSON files never carry, even whitespace only. Adding a type means adding a comparison that is exact for it, by amending this ADR.

## Considered options

- **A regex that strips comments.** Rejected: `//` and `/*` inside strings and text blocks defeat it, and a wrong strip carries a code change.
- **javac's parser.** Rejected: a JDK launch per file for a lexer's worth of facts, and it would need the toolchain on every machine that merges.
- **Python's `shlex` or `tokenize`.** Rejected: neither knows Java literals or comments.
- **Whitespace-only carry for every file type by a text diff.** Rejected: trailing and leading spaces change meaning in several formats here, and a doc change is content a reviewer reads.
