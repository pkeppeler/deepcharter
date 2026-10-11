"""No loot table uses the pre-26.3 keys (#469).

26.3 loads a table that still has `conditions` or `functions` and ignores them, so `survives_explosion` and `set_count` never apply and
nothing fails. The 26.3 names are `condition` (one predicate object with `type`) and `modifier`. This scans every
`src/**/data/**/loot_table/**/*.json`, pure Python so the CI `tool-tests` job (no JDK) runs it. `LootTableDropsTest` rolls the real tables.
"""
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

OLD_KEYS = ("conditions", "functions")


def old_keys(node: object, where: str) -> list[str]:
    """Where a table, pool or entry (or an entry's `children`) carries a pre-26.3 key, as `<where>: <key>`."""
    found: list[str] = []
    if not isinstance(node, dict):
        return found
    found += [f"{where}: {key}" for key in OLD_KEYS if key in node]
    for index, pool in enumerate(node.get("pools", [])):
        found += old_keys(pool, f"{where}/pools[{index}]")
    for index, entry in enumerate(node.get("entries", [])):
        found += old_keys(entry, f"{where}/entries[{index}]")
    for index, child in enumerate(node.get("children", [])):
        found += old_keys(child, f"{where}/children[{index}]")
    return found


def tables(root: Path) -> list[Path]:
    return sorted(root.glob("src/**/data/**/loot_table/**/*.json"))


def violations(root: Path) -> list[str]:
    found: list[str] = []
    for path in tables(root):
        found += old_keys(json.loads(path.read_text(encoding="utf-8")), str(path.relative_to(root)))
    return found


def write(root: Path, name: str, table: dict) -> None:
    path = root / "src/main/resources/data/fixture/loot_table/blocks" / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(table), encoding="utf-8")


GREEN = {
    "type": "minecraft:block",
    "pools": [{
        "rolls": 1,
        "condition": {"type": "minecraft:survives_explosion"},
        "entries": [{"type": "minecraft:item", "name": "minecraft:stick", "modifier": [{"type": "minecraft:set_count", "count": 2}]}],
    }],
}
OLD_POOL_CONDITIONS = {
    "type": "minecraft:block",
    "pools": [{"rolls": 1, "entries": [], "conditions": [{"condition": "minecraft:survives_explosion"}]}],
}
OLD_TABLE_FUNCTIONS = {"type": "minecraft:block", "pools": [], "functions": []}
OLD_ENTRY_FUNCTIONS = {
    "type": "minecraft:block",
    "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": "minecraft:stick", "functions": []}]}],
}
OLD_NESTED_CHILD = {
    "type": "minecraft:block",
    "pools": [{"rolls": 1, "entries": [{"type": "minecraft:alternatives", "children": [{"type": "minecraft:item", "conditions": []}]}]}],
}


class LootTableFormatTest(unittest.TestCase):
    def test_the_shipped_tables_use_the_26_3_format(self):
        self.assertTrue(tables(ROOT), "no loot table found: the scan glob is wrong")
        self.assertEqual([], violations(ROOT))


class GateFixturesTest(unittest.TestCase):
    """The scan fails on each old shape and leaves a 26.3 table alone."""

    def scan(self, name: str, table: dict) -> list[str]:
        with tempfile.TemporaryDirectory() as tmp:
            write(Path(tmp), name, table)
            return violations(Path(tmp))

    def test_green_table_is_left_alone(self):
        self.assertEqual([], self.scan("green.json", GREEN))

    def test_pool_conditions_fail(self):
        self.assertEqual(
            ["src/main/resources/data/fixture/loot_table/blocks/red.json/pools[0]: conditions"],
            self.scan("red.json", OLD_POOL_CONDITIONS),
        )

    def test_table_functions_fail(self):
        self.assertEqual(
            ["src/main/resources/data/fixture/loot_table/blocks/red.json: functions"],
            self.scan("red.json", OLD_TABLE_FUNCTIONS),
        )

    def test_entry_functions_fail(self):
        self.assertEqual(
            ["src/main/resources/data/fixture/loot_table/blocks/red.json/pools[0]/entries[0]: functions"],
            self.scan("red.json", OLD_ENTRY_FUNCTIONS),
        )

    def test_nested_child_conditions_fail(self):
        self.assertEqual(
            ["src/main/resources/data/fixture/loot_table/blocks/red.json/pools[0]/entries[0]/children[0]: conditions"],
            self.scan("red.json", OLD_NESTED_CHILD),
        )

    def test_tables_in_a_subfolder_are_scanned(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "src/main/resources/data/fixture/loot_table/chests/deep/vault.json"
            path.parent.mkdir(parents=True)
            path.write_text(json.dumps(OLD_TABLE_FUNCTIONS), encoding="utf-8")
            self.assertEqual(1, len(violations(Path(tmp))))


if __name__ == "__main__":
    unittest.main()
