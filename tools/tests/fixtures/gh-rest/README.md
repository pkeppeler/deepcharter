# Recorded GitHub REST responses

Fixtures for the merge tools' tests (`tools/tests/lib/gh-fixture.sh`): the stubbed `gh api` runs the script's own `--jq`
expression over one of these files.

Recorded from `pkeppeler/deepcharter` with `gh api`, then cut to the fields the tools read (whole fields, no value edited):

- `pulls-merged.json`: `GET pulls/794`, a merged PR (`merged: true`, `state: closed`).
- `pulls-open.json`: `GET pulls/780`, an open PR.
- `issue-comments.json`: `GET issues/794/comments`, one `review-passed <sha>` comment by `pkeppeler` with the proxy footer.

- `check-run-annotations.json`: `GET check-runs/137139521641/annotations` (PR #982's `build` job, #979): a failure annotation and the `merged-main` notice holding the main sha the job merged.

Files named `*.hand-edited.json` are a recorded file with a value changed, for a case that cannot be recorded live:

- `pulls-open-reviewed.hand-edited.json`: `pulls-open.json` with `head.sha` set to the sha in `issue-comments.json` and the
  `review-passed` and `px-reviewed` labels added, so the recorded comment matches the PR's head and the gate reaches its later reads. The two `mergeable` files below carry both labels too.
- `pulls-open.px-reviewed.hand-edited.json`: `pulls-open.json` with the `px-reviewed` label added, for the `mark-review-passed.sh` case (a `gameplay` PR needs it).
- `pulls-open-reviewed.mergeable-null.hand-edited.json`: that PR with `mergeable: null` (GitHub still computing it).
- `pulls-open-reviewed.mergeable-false.hand-edited.json`: that PR with `mergeable: false` (a conflict).
- `check-run-annotations.no-record.hand-edited.json`: that response with the `merged-main` record dropped, a run from before CI recorded its main.
- `issue-comments.foreign-login.hand-edited.json`: the same comment, by `someone-else`.

The repo is public: keep nothing private here. Re-record with the `jq` cut in the PR that added them (#792).
