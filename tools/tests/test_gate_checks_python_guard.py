"""gate_checks.py refuses an interpreter older than 3.10 before it runs any check."""
import subprocess
import sys
import unittest
from pathlib import Path

GATE_CHECKS = Path(__file__).resolve().parent / "gate_checks.py"
# Runs gate_checks.py as __main__ with sys.version_info reporting 3.9.6. "bogus" is an unknown check name, so a missing guard
# fails fast with a different message instead of starting Gradle.
RUN_AS_3_9 = (
    "import runpy, sys; sys.version_info = (3, 9, 6, 'final', 0); sys.argv = [sys.argv[1], 'bogus']; "
    "runpy.run_path(sys.argv[0], run_name='__main__')"
)


class PythonGuardTest(unittest.TestCase):
    def test_python_3_9_is_refused_with_a_version_message(self) -> None:
        result = subprocess.run(
            [sys.executable, "-I", "-c", RUN_AS_3_9, str(GATE_CHECKS)], capture_output=True, text=True, check=False
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("the tools need 3.10 or newer", result.stderr)
        self.assertIn(sys.executable, result.stderr)


if __name__ == "__main__":
    unittest.main()
