"""A newer push to a PR cancels its older workflow runs, and drafts run no CI (#1723).

Each push requests about 70 jobs from a runner pool of about 20, so a run a newer push superseded must not keep its runners:
every workflow that a PR push starts (a `pull_request` trigger whose types include `synchronize`, or name none) has a top-level
`concurrency` group keyed on the PR number with `cancel-in-progress: true`. A workflow that skips drafts says so by listing
`ready_for_review` (so the head that leaves draft gets its run), and then every job with no `needs` skips a draft; the jobs after it
skip with it. Record evidence starts only on a label, never on a push, and never cancels (a cancel could land mid-push to pr-media).
The standard library only, as in test_ci_budget.py: top-level keys at column 0, jobs at two spaces, their settings at four."""
import re
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DRAFT = "!github.event.pull_request.draft"


def workflow_files(root: Path) -> list[Path]:
    return sorted([*root.glob(".github/workflows/*.yml"), *root.glob(".github/workflows/*.yaml")])


def top_block(text: str, key: str) -> str:
    """The lines under a top-level key, up to the next top-level key."""
    lines = text.splitlines()
    for i, line in enumerate(lines):
        if re.match(rf"^{re.escape(key)}:\s*(#.*)?$", line):
            body = []
            for rest in lines[i + 1:]:
                if re.match(r"^\S", rest) and not rest.startswith("#"):
                    break
                body.append(rest)
            return "\n".join(body)
    return ""


def pull_request_types(text: str) -> list[str] | None:
    """The `pull_request` trigger's types ([] for GitHub's default set), or None when a PR does not start the workflow."""
    on = top_block(text, "on")
    m = re.search(r"^  pull_request:\s*$((?:\n    .*)*)", on, re.M)
    if not m:
        return None
    types = re.search(r"^    types:\s*\[([^\]]*)\]", m[1], re.M)
    return [t.strip() for t in types[1].split(",")] if types else []


def root_jobs(text: str) -> dict[str, str]:
    """Each job with no `needs` to its `if` (the folded lines joined), or '' when it has none."""
    jobs: dict[str, list[str]] = {}
    needs: set[str] = set()
    job = None
    in_if = False
    for line in top_block(text, "jobs").splitlines():
        if key := re.match(r"^  ([A-Za-z0-9_-]+):\s*$", line):
            job = key[1]
            jobs[job] = []
            in_if = False
        elif job and re.match(r"^    needs:", line):
            needs.add(job)
            in_if = False
        elif job and (cond := re.match(r"^    if:\s*(.*)$", line)):
            jobs[job].append(cond[1])
            in_if = True
        elif job and in_if and re.match(r"^      \S", line):
            jobs[job].append(line.strip())
        elif re.match(r"^    \S", line):
            in_if = False
    return {j: " ".join(c) for j, c in jobs.items() if j not in needs}


def violations(root: Path) -> list[str]:
    found = []
    for path in workflow_files(root):
        text = path.read_text()
        types = pull_request_types(text)
        if types is None or (types and "synchronize" not in types):
            continue
        concurrency = top_block(text, "concurrency")
        if "github.event.pull_request.number" not in concurrency:
            found.append(f"{path.name}: a PR push starts it, but its concurrency group is not keyed on the PR number")
        if not re.search(r"^  cancel-in-progress:\s*true\s*$", concurrency, re.M):
            found.append(f"{path.name}: a PR push starts it, but a newer push does not cancel the run (cancel-in-progress: true)")
        if "ready_for_review" in types:
            for job, cond in root_jobs(text).items():
                if DRAFT not in cond:
                    found.append(f"{path.name}: job {job} has no needs and runs on a draft (its if lacks {DRAFT})")
    return found


class CiConcurrencyTest(unittest.TestCase):
    def test_repo_workflows(self):
        self.assertEqual(violations(REPO), [])

    def test_the_draft_skipping_workflows_are_checked(self):
        # Guards the parser: if it stopped seeing these triggers, the rule above would pass vacuously.
        for name in ("ci.yml", "client-tests.yml"):
            types = pull_request_types((REPO / ".github/workflows" / name).read_text())
            self.assertIn("synchronize", types or [], name)
            self.assertIn("ready_for_review", types or [], name)
        self.assertEqual(pull_request_types((REPO / ".github/workflows/record-evidence.yml").read_text()), ["labeled"])

    def test_client_tests_group_matches_its_changes_job(self):
        # A run joins the shared group exactly when its changes job will run: a skipping run in the shared group would cancel
        # a real one, and a real run outside it would never be cancelled by a newer push.
        text = (REPO / ".github/workflows/client-tests.yml").read_text()
        group = re.search(r"\$\{\{\s*(.*?)\s*&& format\('client-\{0\}'", top_block(text, "concurrency"), re.S)
        self.assertIsNotNone(group)
        changes = root_jobs(text)["changes"]
        cond = re.search(r"\$\{\{\s*(.*?)\s*\}\}", changes, re.S)
        self.assertIsNotNone(cond)

        def norm(s: str) -> str:
            return re.sub(r"\s+", "", s)

        self.assertEqual(norm(group[1]), norm(f"({cond[1]})"))

    def test_a_batch_pr_runs_client_tests_once(self):
        # tools/merge-batch.sh labels a batch PR `gameplay` a second after opening it; that `labeled` run must skip, and the
        # `opened` run must count the batch as labelled, or every batch runs (and cancels) the client tests twice (#1723).
        text = (REPO / ".github/workflows/client-tests.yml").read_text()
        batch = "!startsWith(github.event.pull_request.head.ref, 'batch/')"
        self.assertIn(batch, top_block(text, "concurrency"))
        self.assertIn(batch, root_jobs(text)["changes"])
        label = re.search(r"GAMEPLAY_LABEL:\s*(.*)", text)
        self.assertIsNotNone(label)
        self.assertIn("startsWith(github.event.pull_request.head.ref, 'batch/')", label[1])

    def check(self, workflow: str) -> list[str]:
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / ".github/workflows").mkdir(parents=True)
            (Path(tmp) / ".github/workflows/w.yml").write_text(workflow)
            return violations(Path(tmp))

    GOOD = """on:
  pull_request:
    types: [opened, synchronize, ready_for_review]

concurrency:
  group: ci-${{ github.event.pull_request.number }}
  cancel-in-progress: true

jobs:
  setup:
    if: >-
      ${{ !github.event.pull_request.draft
      && true }}
    runs-on: ubuntu-latest
  after:
    needs: setup
    runs-on: ubuntu-latest
"""

    def test_a_good_workflow_passes(self):
        self.assertEqual(self.check(self.GOOD), [])

    def test_no_cancel_fails(self):
        found = self.check(self.GOOD.replace("cancel-in-progress: true", "cancel-in-progress: false"))
        self.assertEqual(len(found), 1)
        self.assertIn("does not cancel", found[0])

    def test_a_group_not_keyed_on_the_pr_fails(self):
        found = self.check(self.GOOD.replace("github.event.pull_request.number", "github.sha"))
        self.assertIn("not keyed on the PR number", found[0])

    def test_no_concurrency_with_default_types_fails(self):
        text = self.GOOD.replace("    types: [opened, synchronize, ready_for_review]\n", "").split("concurrency:")[0]
        text += self.GOOD.split("cancel-in-progress: true\n")[1]
        self.assertEqual(len(self.check(text)), 2)

    def test_a_root_job_running_on_drafts_fails(self):
        found = self.check(self.GOOD.replace("!github.event.pull_request.draft\n      && true", "true"))
        self.assertEqual(found, ["w.yml: job setup has no needs and runs on a draft (its if lacks " + DRAFT + ")"])

    def test_a_label_only_workflow_is_exempt(self):
        text = "on:\n  pull_request:\n    types: [labeled]\n\njobs:\n  record:\n    runs-on: ubuntu-latest\n"
        self.assertEqual(self.check(text), [])


if __name__ == "__main__":
    unittest.main()
