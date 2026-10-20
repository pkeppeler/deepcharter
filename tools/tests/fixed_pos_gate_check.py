"""Tests the fixed-position gate in gradle/fixed-pos-gate.gradle.kts: in an evidence scenario (a file under test/evidence), a numeric literal for a
world Y fails the build unless a `// fixed-pos: <reason>` marker with a reason sits on its statement or the comment lines above it. A literal Y is a
`*_Y` constant set to a number, a literal Y in a BlockPos or Vec3 beside literal X and Z of 100 or more (new BlockPos(3616, 40, 3616)), and a literal Y in
teleportTo, setPos, moveTo, snapTo or atY. A relative offset (a vector with a small or variable X or Z, pos.offset(1, 0, 2), above(3)) is left alone.

Its fixture trees go through checkGametestFixtures in one Gradle run (gate_checks.py, gate_batch.py), so it needs the repo's Gradle wrapper
on JDK 25. Its name misses the test_*.py glob of the tool-tests job, which has no JDK 25; the build job and the pre-push hook run it.
Usage: python3 -I tools/tests/gate_checks.py fixed_pos
"""
import unittest

import gate_batch
from gate_batch import Case

# Clean files: each must be left alone by the gate.
CLEAN = {
    "evidence/RelativeScenario.java": """
public class RelativeScenario extends EvidenceScenario {
    private static final int FLOOR_Y = LIVE_GAUGES_FLOOR_Y;
    private static final int POCKET_Y = FLOOR_Y - 4;
    private static final Vec3 LOOK = new Vec3(0, 1.62, -4);
    private static final BlockPos STEP = new BlockPos(-1, 0, 2);
    private static final BlockPos HIGH_STEP = new BlockPos(3, 12, 5);
    private static final BlockPos TOP = new BlockPos(0, 64, 0);
    private static final BlockPos CAVE = new BlockPos(-6, 0, -7);
    private static final BlockPos NEAR_Z = new BlockPos(2000, 100, 5);
    private static final Vec3 CAMERA = new Vec3(0.5, -60, 0.5);
    private static final BlockPos VARIABLE_XZ = new BlockPos(X, 100, Z); // a known blind spot, listed in the gate header
    void stage(ServerLevel level, ServerPlayer player, BlockPos pad) {
        BlockPos a = pad.offset(1, 0, 2);
        BlockPos b = pad.above(3).relative(Direction.NORTH, 4);
        BlockPos c = new BlockPos(pad.getX() + 2, FLOOR_Y, pad.getZ());
        BlockPos d = new BlockPos(X, FLOOR_Y + 3, Z);
        Vec3 e = Vec3.atCenterOf(new BlockPos(X, FLOOR_Y, Z));
        player.teleportTo(level, X + 0.5, FLOOR_Y, Z + 0.5, Set.of(), 90f, 0f, true);
        player.teleportTo(level, pad.getCenter(), 180f, 0f);
        player.setPos(X, FLOOR_Y, Z);
        player.setPos(pad.getX(), pad.getY() + 2, pad.getZ());
        BlockPos f = pad.atY(FLOOR_Y);
        BlockPos g = BlockPos.containing(X, 0, Z);
    }
}
""",
    "evidence/MarkedScenario.java": """
public class MarkedScenario extends EvidenceScenario {
    // fixed-pos: layer 1 above the decay onset (36), so the gauges stay live
    private static final int FLOOR_Y = 100;
    private static final int ROOF_Y = 120; // fixed-pos: same-line reason
    private static final BlockPos ROOM = new BlockPos(2700, 100, 2700); // fixed-pos: a room carved in layer 2
    void stage(ServerPlayer player) {
        // fixed-pos: a drop from the void, no ground to derive from
        player.teleportTo(level, 0.5, 200, 0.5, Set.of(), 0f, 0f, true);
        player.setPos(X,
                200,
                Z); // fixed-pos: wrapped, the marker at the end of the statement
    }
}
""",
    "evidence/MentionsScenario.java": """
/** Stages at {@code FLOOR_Y = 4} and {@code new BlockPos(0, 64, 0)}, which it must not. */
public class MentionsScenario extends EvidenceScenario {
    // private static final int FLOOR_Y = 4; and player.setPos(0, 64, 0) in a comment are not code
    /* new BlockPos(1, 100, 2) in a block comment either */
    void stage() {
        String text = "FLOOR_Y = 4; new BlockPos(0, 64, 0); player.setPos(0, 64, 0)";
        int sky_y = 300;
    }
}
""",
    "support/OutsideEvidence.java": """
public class OutsideEvidence {
    private static final int FLOOR_Y = 4;
    BlockPos at = new BlockPos(0, 64, 0);
}
""",
}
# label: (statement in a class body, line the gate reports)
STATEMENTS = {
    "constant": ("private static final int FLOOR_Y = 4;", 3),
    "constant of a name that is only Y": ("static final int Y = 64;", 3),
    "double constant": ("private static final double START_Y = 40.5;", 3),
    "negative constant": ("private static final int FEET_Y = -60;", 3),
    "BlockPos": ("BlockPos at = new BlockPos(3616, 40, 3616);", 3),
    "BlockPos with a small Y": ("BlockPos at = new BlockPos(2000, 4, 2000);", 3),
    "negative BlockPos": ("BlockPos at = new BlockPos(-2000, 100, -100);", 3),
    "BlockPos.containing": ("BlockPos at = BlockPos.containing(2000, 64.5, 3000);", 3),
    "Vec3": ("Vec3 at = new Vec3(2000.5, -60, 2000.5);", 3),
    "Vec3 inside atCenterOf": ("Vec3 at = Vec3.atCenterOf(new BlockPos(1000, 200, 1000));", 3),
    "teleportTo with a level": ("void go(ServerPlayer p, ServerLevel level) { p.teleportTo(level, X, 200, Z, Set.of(), 0f, 0f, true); }", 3),
    "teleportTo": ("void go(ServerPlayer p) { p.teleportTo(X, 64, Z); }", 3),
    "setPos": ("void go(PodEntity pod) { pod.setPos(X, 4, Z); }", 3),
    "moveTo": ("void go(PodEntity pod) { pod.moveTo(X, 4, Z, 0f, 0f); }", 3),
    "atY": ("BlockPos at(BlockPos pad) { return pad.atY(200); }", 3),
    "wrapped call": ("void go(PodEntity pod) {\n        pod.setPos(X,\n            4,\n            Z);\n    }", 4),
}
TWO_ABOVE = "// fixed-pos: too far above\n    int gap = 0;\n    private static final int FLOOR_Y = 4;"
BARE = "// fixed-pos:\n    private static final int FLOOR_Y = 4;"
IN_STRING = 'String note = "// fixed-pos: not a comment"; private static final int FLOOR_Y = 4;'
NEXT_STATEMENT = "private static final int FLOOR_Y = 4;\n    int gap = 0; // fixed-pos: belongs to the next statement"
BLANK_LINE = "// fixed-pos: separated by a blank line\n\n    private static final int FLOOR_Y = 4;"
MARKERS = {
    "a bare marker": (BARE, 4),
    "a marker two lines above": (TWO_ABOVE, 5),
    "a marker on the next statement": (NEXT_STATEMENT, 3),
    "a marker above a blank line": (BLANK_LINE, 5),
    "a marker in a string": (IN_STRING, 3),
}
BAD = {**{f"literal/{label}": case for label, case in STATEMENTS.items()}, **{f"marker/{label}": case for label, case in MARKERS.items()}}


def staging(statement: str) -> str:
    return f"""
public class StagesScenario extends EvidenceScenario {{
    {statement}
}}
"""


def fixture(extra: dict[str, str]) -> dict[str, str]:
    return {**CLEAN, **extra}


CASES = {
    "clean": Case(fixture({}), None, gate="fixed_pos"),
    **{label: Case(fixture({"evidence/StagesScenario.java": staging(statement)}), None, gate="fixed_pos") for label, (statement, _) in BAD.items()},
}


def outcome(label: str) -> gate_batch.Result:
    return gate_batch.result(f"{__name__}/{label}")


class FixedPosGateTest(unittest.TestCase):
    def check_fails(self, label: str):
        _, line = BAD[label]
        result = outcome(label)
        self.assertFalse(result.ok, f"the gate should fail the build for {label}")
        self.assertIn(f"StagesScenario.java:{line} stages at a literal world Y", result.message)
        self.assertIn("// fixed-pos: <reason>", result.message)
        for exempt in ("RelativeScenario", "MarkedScenario", "MentionsScenario", "OutsideEvidence"):
            self.assertNotIn(exempt, result.message)

    def test_relative_marked_and_mentioned_forms_pass(self):
        result = outcome("clean")
        self.assertTrue(result.ok, result.message)

    def test_literal_world_y_fails(self):
        for label in STATEMENTS:
            with self.subTest(label):
                self.check_fails(f"literal/{label}")

    def test_marker_without_a_reason_or_in_a_string_does_not_count(self):
        for label in MARKERS:
            with self.subTest(label):
                self.check_fails(f"marker/{label}")
