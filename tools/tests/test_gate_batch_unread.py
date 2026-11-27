"""gate_batch fails a run when a fixture case is built and run through Gradle but no assertion reads its result (#700)."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gate_batch  # noqa: E402
from gate_batch import Case, Result  # noqa: E402

OK = Result(True, "", (), ())
CASES = {
    "demo_gate_check/clean": Case({}, None),
    "demo_gate_check/violation/never asserted": Case({}, None),
    "other_gate_check/clean": Case({}, None),
}


class UnreadCasesTest(unittest.TestCase):
    def setUp(self) -> None:
        gate_batch.RESULTS = {name: OK for name in CASES}
        gate_batch.READ.clear()

    def tearDown(self) -> None:
        gate_batch.RESULTS = None
        gate_batch.READ.clear()

    def test_an_unasserted_label_names_its_gate_check_and_label(self) -> None:
        self.assertTrue(gate_batch.result("demo_gate_check/clean").ok)
        self.assertEqual("", gate_batch.result("other_gate_check/clean").message)
        message = gate_batch.unread_message(CASES)
        self.assertIsNotNone(message)
        self.assertIn("demo_gate_check", message)
        self.assertIn("violation/never asserted", message)
        self.assertNotIn("other_gate_check", message)

    def test_a_case_fetched_but_never_inspected_is_unread(self) -> None:
        for name in CASES:
            gate_batch.result(name)
        message = gate_batch.unread_message(CASES)
        self.assertIsNotNone(message)
        self.assertIn("violation/never asserted", message)

    def test_every_label_read_passes(self) -> None:
        for name in CASES:
            self.assertTrue(gate_batch.result(name).ok)
        self.assertIsNone(gate_batch.unread_message(CASES))


if __name__ == "__main__":
    unittest.main()
