---
status: accepted
---

# The feature map outputs are generated, not committed

Issue #544. Every gameplay PR edited `map.json` and committed the two generated files, `docs/FEATURE-MAP.md` and `docs/feature-map/index.html`. Each merge to main made the other open PRs CONFLICTING in those files, and the main merge that fixed it voided the review pass.

## Decision

- **`map.json` is the only committed map file.** The two outputs are git-ignored. `python3 -I tools/feature_map.py` writes them locally; `--check` validates and renders without writing.
- **The Pages workflow generates the page** before it uploads `docs/feature-map`.
- **Nothing reads the outputs from git.** The merge gate's stale-node check and the coverage GameTest read `map.json`. `FEATURE-MAP.md` is a local view only; the README links `map.json` and the live page.

## Why not a merge driver

A `.gitattributes` merge driver runs only in a local git. GitHub computes mergeability and merges server-side without custom drivers, so the PR would still show CONFLICTING and the merge gate would still refuse it. Only not committing the files removes the conflict.

## Consequences

- Two PRs that each add a node conflict only when they touch the same `map.json` lines. Nodes are one JSON object per line, so adjacent additions can still conflict; that is a real, small, hand-resolvable conflict, not a generated one.
- The Mermaid map is no longer browsable on GitHub. The live page replaces it.
- A branch that still tracks the old outputs deletes them when it merges main (`git rm --cached` is not needed: the merge applies the deletion). If it edited them, take the deletion.
