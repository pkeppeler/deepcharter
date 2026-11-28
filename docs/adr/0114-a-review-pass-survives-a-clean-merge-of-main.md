---
status: accepted
---

# A review pass survives a clean merge of main

[#1568](https://github.com/pkeppeler/deepcharter/issues/1568): 73 of 176 merged PRs needed more than one review pass, and 31 needed four to nine. Any push voids a pass, because `review-passed <sha>` names one head, and that includes a conflict-free merge of `main` into the branch, which adds nothing a reviewer judged. Every such merge cost a cycle-2 review.

## Decision

- **Carry.** The gate (`tools/merge-pr.sh`, so also `--check` and `--preflight`) accepts a head with no `review-passed <head>` comment when `tools/lib/pass-carry.sh` proves the head is a chain of merges of `origin/main` on a commit that has one. `tools/mark-review-passed.sh --carry <n>` posts the head's own comment for such a head, so the record is explicit; the gate does not need it.
- **What proves it.** Each link of the chain is a merge commit with exactly two parents; the second is on `origin/main`; and `git merge-tree --write-tree <first> <second>` succeeds without conflict and prints exactly the link's tree. So the merge is the one git makes, with nothing resolved by hand and nothing added. The walk goes back through the first parents, at most 20 links, to the first commit named by a pass comment from the trusted account.
- **Anything else voids it.** A commit that is not such a merge, a conflict resolved by hand, a merge of another branch, an octopus merge, a tree that differs by one file, a chain longer than the cap, or a read that fails: the pass is void and the head needs a new review, as before. The tools fail closed.
- **CI is unchanged.** The merged head gets its own CI run, so what the carry skips is the review, never the checks.

## Consequences

- A PR that only needed `main` merged in to stay mergeable keeps its pass. A conflict a person resolved still needs review, because the resolution is new code.
- The carried pass vouches for `main`'s changes only in the sense that they merge without a textual conflict. A semantic clash with the reviewed change is for CI to catch, as for any batch ([ADR 0106](0106-reviewed-prs-merge-in-batches-on-an-integration-branch-with-one-ci-run.md)).
- The gate fetches `refs/pull/<n>/head` and `origin/main` to decide, so it needs the network it already needs.

## Considered options

- **Carry a pass across any push that changes no file the reviewer saw.** Rejected: it cannot tell a merge from a rewrite, and the proof here is exact.
- **Let `git merge` of main be exempt by commit message.** Rejected: a message proves nothing about the tree.
