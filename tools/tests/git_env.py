"""The environment for a test that runs git in a temp repo (#372).

A hook, `rebase --exec` or `bisect run` exports GIT_DIR and its siblings, and git would then act on the real repository.
tools/tests/test_git_env_scrub.py fails a test that runs git without importing this.
"""
import os
from pathlib import Path


def clean_env(cwd: Path) -> dict[str, str]:
    """os.environ with every GIT_* variable removed, and git barred from walking up out of the parent of cwd."""
    env = {name: value for name, value in os.environ.items() if not name.startswith("GIT_")}
    env["GIT_CEILING_DIRECTORIES"] = str(cwd.resolve().parent)
    return env
