"""Production code reads its layer through `LayerChain.bandAt` (#740), not from the dimension type.

`LayerChain.layerOf` and `LayerChain.indexOf` parse a dimension type's id, so a caller of either reads the layer from the dimension: the
reading that the grained crust (#214) has to change in one place. This scans `src/main` and `src/client`, pure Python so the CI
`tool-tests` job (no JDK) runs it.
"""
import re
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

CALL = re.compile(r"\bLayerChain\s*\.\s*(?:layerOf|indexOf)\s*\(")


def violations(root: Path) -> list[str]:
    """Where a file under `src/main` or `src/client` calls `layerOf` or `indexOf` of the chain, as `<path>:<line>`."""
    found: list[str] = []
    for source in ("src/main", "src/client"):
        for path in sorted((root / source).glob("**/*.java")):
            relative = path.relative_to(root).as_posix()
            for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
                if CALL.search(line.split("//", 1)[0]):
                    found.append(f"{relative}:{number}")
    return found


class LayerBandGateTest(unittest.TestCase):
    def test_the_tree_reads_layers_through_bandat(self) -> None:
        self.assertEqual(
            [],
            violations(ROOT),
            "read the layer with LayerChain.bandAt(level, y), not LayerChain.layerOf or indexOf of a dimension type",
        )

    def test_a_call_in_production_code_is_found(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for source, body in (
                ("src/main/java/A.java", "int a = LayerChain.layerOf(id).getAsInt();\n"),
                ("src/client/java/B.java", "// LayerChain.layerOf(id) in a comment\nx = LayerChain.indexOf(id);\n"),
                ("src/main/java/C.java", "int c = LayerChain.bandAt(level, y).get().layer();\n"),
                ("src/gametest/java/D.java", "int d = LayerChain.layerOf(id).getAsInt();\n"),
            ):
                path = root / source
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(body, encoding="utf-8")
            self.assertEqual(["src/main/java/A.java:1", "src/client/java/B.java:2"], violations(root))


if __name__ == "__main__":
    unittest.main()
