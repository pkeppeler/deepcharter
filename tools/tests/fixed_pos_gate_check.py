"""Tests the fixed-position gate in gradle/fixed-pos-gate.gradle.kts: in any file of src/gametest (a GameTest, an evidence scenario, test support), a numeric
literal for a world Y fails the build unless a `// fixed-pos: <reason>` marker with a reason sits on its statement or the comment lines above it. A call to
`LayerChain.dimension(` fails the same way anywhere but in TestLayers.java, which is the one place a layer becomes a level. A literal Y is a
`*_Y` constant set to a number, a literal Y in a BlockPos or Vec3 beside literal X and Z of 100 or more (new BlockPos(3616, 40, 3616)), and a literal Y in
teleportTo, setPos, moveTo, snapTo or atY. An int or long named `*floor`, `*ceiling` or `*roomY` (floor, shallowFloor, LOCAL_FLOOR) set to a number
fails too: a bare `int floor = 60` in a layer test stages in the surface band. A relative offset (a vector with a small or variable X or Z, pos.offset(1, 0, 2), above(3)) is left alone.

A raw read of a level's height (getMinY, getMaxY, getMinBuildHeight, getMaxBuildHeight, and getHeight on a receiver that names a level) fails the same
way anywhere but in TestLayers.java, unless a `// level-height: <reason>` marker sits on its statement or the comment lines above it: a layer is a band of
a level, so a test asks TestLayers.band for its floor, top and height. A `fixed-pos` marker does not count for it.

Its fixture trees go through checkGametestFixtures in one Gradle run (gate_checks.py, gate_batch.py), so it needs the repo's Gradle wrapper
on JDK 25. Its name misses the test_*.py glob of the tool-tests job, which has no JDK 25; the build-checks CI job and the pre-push hook run it.
Usage: tools/py tools/tests/gate_checks.py fixed_pos
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
    private static final int FLOOR_RADIUS = 3;
    private static final double SEPARATION_FLOOR = 0.8;
    private static final int GALLERY_FLOOR = 10; // fixed-pos: a height above the shaft floor, not a world Y
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
        int floor = TestLayers.worldY(1, 60);
        int ceiling = floor + 8;
        int crustTop = 3;
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
    "support/TestLayers.java": """
public final class TestLayers {
    public static ResourceKey<Level> dimension(int layer) {
        return LayerChain.dimension(layer);
    }
    public static LayerChain.Band band(Level level, int layer) {
        return new LayerChain.Band(layer, level.getMinY(), level.getHeight());
    }
}
""",
    "BandReadsTest.java": """
public class BandReadsTest {
    // level.getMinY() and level.getHeight() in a comment are not reads
    void run(ServerLevel level, NativeImage image, Button button, Minecraft client) {
        LayerChain.Band band = TestLayers.band(level, 1);
        int floor = band.minY() + band.maxY() - band.height();
        int pixels = image.getHeight() + button.getHeight() + client.getWindow().getHeight();
        String text = "level.getMinY() and level.getMaxY()";
        // level-height: the void under the test world, not a layer
        int bottom = level.getMinY();
        int size = level.getHeight(); // level-height: a whole test world
        int top = ClientLight.serverLevel(server, dimension)
                .getMaxY(); // level-height: wrapped, the marker at the end of the statement
    }
}
""",
    "support/TestLayersMention.java": """
/** Asks {@code LayerChain.dimension(1)} for a level, which it must not. */
public class TestLayersMention {
    // LayerChain.dimension(2) in a comment is not a call
    ResourceKey<Level> key(int layer) {
        String text = "LayerChain.dimension(1)";
        return TestLayers.dimension(layer);
    }
}
""",
}
# label: (statement in a class body, line the gate reports)
STATEMENTS = {
    "constant": ("private static final int FLOOR_Y = 4;", 3),
    "constant of a name that is only Y": ("static final int Y = 64;", 3),
    "double constant": ("private static final double START_Y = 40.5;", 3),
    "negative constant": ("private static final int FEET_Y = -60;", 3),
    "local floor": ("void go() { int floor = 60; }", 3),
    "local ceiling": ("void go() { int ceiling = 150; }", 3),
    "local named for a layer": ("void go() { int shallowFloor = 150; }", 3),
    "constant floor": ("private static final int LOCAL_FLOOR = 60;", 3),
    "long room Y": ("void go() { long roomY = -200L; }", 3),
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
# Not an evidence scenario: the gate covers every file of src/gametest.
OUTSIDE_EVIDENCE = "private static final int FLOOR_Y = 4;"
# label: (statement in a class body, line the gate reports)
LAYER_CALLS = {
    "LayerChain.dimension": ("ResourceKey<Level> key = LayerChain.dimension(1);", 3),
    "LayerChain.dimension on the next line": ("ResourceKey<Level> key = LayerChain\n        .dimension(1);", 3),
    "LayerChain.dimension in a lambda": ("Function<Integer, ResourceKey<Level>> key = layer -> LayerChain.dimension(layer);", 3),
}
MARKED_LAYER_CALL = "// fixed-pos: a layer key for a registry lookup\n    ResourceKey<Level> key = LayerChain.dimension(1);"
# label: (statement in a class body, line the gate reports)
HEIGHT_READS = {
    "getMinY": ("int floor(ServerLevel level) { return level.getMinY(); }", 3),
    "getMaxY": ("int top(ServerLevel level) { return level.getMaxY(); }", 3),
    "getMinBuildHeight": ("int floor(ServerLevel level) { return level.getMinBuildHeight(); }", 3),
    "getMaxBuildHeight": ("int top(ServerLevel level) { return level.getMaxBuildHeight(); }", 3),
    "getHeight of a level": ("int size(ServerLevel level) { return level.getHeight(); }", 3),
    "getHeight of a named level": ("int size(ServerLevel one) { return one.getHeight(); }", 3),
    "getHeight of a call": ("int size(MinecraftServer s) { return ClientLight.serverLevel(s, dimension).getHeight(); }", 3),
    "getMinY of a call": ("int floor(MinecraftServer s) { return TestLayers.level(s, 1).getMinY(); }", 3),
    "wrapped read": ("int top(ServerLevel level) {\n        return level\n            .getMaxY();\n    }", 5),
}
HEIGHT_MARKERS = {
    "a bare marker": ("// level-height:\n    int floor(ServerLevel level) { return level.getMinY(); }", 4),
    "a fixed-pos marker": ("// fixed-pos: not the marker of a height read\n    int floor(ServerLevel level) { return level.getMinY(); }", 4),
    "a marker two lines above": ("// level-height: too far above\n    int gap = 0;\n    int floor(ServerLevel level) { return level.getMinY(); }", 5),
    "a marker on the next statement": ("int floor(ServerLevel level) { return level.getMinY(); }\n    int gap = 0; // level-height: belongs to the next statement", 3),
}
BAD = {**{f"literal/{label}": case for label, case in STATEMENTS.items()}, **{f"marker/{label}": case for label, case in MARKERS.items()}}


def named_class(name: str, statement: str) -> str:
    return f"""
public class {name} {{
    {statement}
}}
"""


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
    "marked layer call": Case(fixture({"StagesTest.java": named_class("StagesTest", MARKED_LAYER_CALL)}), None, gate="fixed_pos"),
    "outside evidence": Case(fixture({"StagesTest.java": named_class("StagesTest", OUTSIDE_EVIDENCE)}), None, gate="fixed_pos"),
    **{
        f"layer/{label}": Case(fixture({"NamesLayerTest.java": named_class("NamesLayerTest", statement)}), None, gate="fixed_pos")
        for label, (statement, _) in LAYER_CALLS.items()
    },
    **{
        f"height/{label}": Case(fixture({"ReadsHeightTest.java": named_class("ReadsHeightTest", statement)}), None, gate="fixed_pos")
        for label, (statement, _) in {**HEIGHT_READS, **HEIGHT_MARKERS}.items()
    },
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
        for exempt in ("RelativeScenario", "MarkedScenario", "MentionsScenario", "support/TestLayers.java", "TestLayersMention"):
            self.assertNotIn(exempt, result.message)

    def test_relative_marked_and_mentioned_forms_pass(self):
        result = outcome("clean")
        self.assertTrue(result.ok, result.message)

    def test_literal_world_y_fails(self):
        for label in STATEMENTS:
            with self.subTest(label):
                self.check_fails(f"literal/{label}")

    def test_literal_world_y_outside_an_evidence_scenario_fails(self):
        result = outcome("outside evidence")
        self.assertFalse(result.ok, "the gate should fail the build for a literal Y in a GameTest")
        self.assertIn("StagesTest.java:3 stages at a literal world Y", result.message)

    def test_layer_chain_dimension_fails_outside_test_layers(self):
        for label, (_, line) in LAYER_CALLS.items():
            with self.subTest(label):
                result = outcome(f"layer/{label}")
                self.assertFalse(result.ok, f"the gate should fail the build for {label}")
                self.assertIn(f"NamesLayerTest.java:{line} names LayerChain.dimension", result.message)
                self.assertNotIn("support/TestLayers.java", result.message)

    def test_marked_layer_call_passes(self):
        result = outcome("marked layer call")
        self.assertTrue(result.ok, result.message)

    def test_marker_without_a_reason_or_in_a_string_does_not_count(self):
        for label in MARKERS:
            with self.subTest(label):
                self.check_fails(f"marker/{label}")

    def check_height_fails(self, label: str, line: int):
        result = outcome(f"height/{label}")
        self.assertFalse(result.ok, f"the gate should fail the build for {label}")
        self.assertIn(f"ReadsHeightTest.java:{line} reads a level's height", result.message)
        self.assertIn("// level-height: <reason>", result.message)
        self.assertNotIn("BandReadsTest", result.message)
        self.assertNotIn("support/TestLayers.java", result.message)

    def test_raw_level_height_read_fails(self):
        for label, (_, line) in HEIGHT_READS.items():
            with self.subTest(label):
                self.check_height_fails(label, line)

    def test_height_marker_without_a_reason_or_in_the_wrong_place_does_not_count(self):
        for label, (_, line) in HEIGHT_MARKERS.items():
            with self.subTest(label):
                self.check_height_fails(label, line)
