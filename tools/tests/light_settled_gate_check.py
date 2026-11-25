"""Tests the light-settled gate in gradle/gametest.gradle.kts: a client GameTest or evidence scenario that carves a room and then seats a pod
(startRiding, or setPos or teleportTo on a pod) in the same runOnServer or computeOnServer block fails the build (#1303).

Its fixture trees go through the scan of generateGametestModJson in one Gradle run (gate_checks.py, gate_batch.py), so it needs the
repo's Gradle wrapper on JDK 25. Its name misses the test_*.py glob of the tool-tests job, which has no JDK 25; the build job and the pre-push hook run it.
Usage: tools/py tools/tests/gate_checks.py light_settled
"""
import re
import unittest
from pathlib import PurePosixPath

import gate_batch
from gate_batch import Case

SERVER_TEST = """
public class FixtureTest {
    @GameTest
    public void passes(GameTestHelper helper) {
    }
}
"""
CLIENT_SCENARIO = """
public class FixtureScenario extends EvidenceScenario {
}
"""
# Clean files: each must be left alone by the gate.
CLEAN = {
    "SettledClientTest.java": """
public class SettledClientTest implements FabricClientGameTest {
    void seat(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> {
            RoomCarver.carve(one, -3, 3, 0, 6, -3, 3, Blocks.AIR);
        });
        ClientWait.lightSettled(context, singleplayer.getServer(), 1, 0, 0, 3);
        singleplayer.getServer().runOnServer(server -> {
            pod.setPos(0, 0, 0);
            player.startRiding(pod);
        });
    }
}
""",
    "SeatOnlyClientTest.java": """
public class SeatOnlyClientTest implements FabricClientGameTest {
    void seat(TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> {
            player.startRiding(pod);
            RoomCarver.carve(one, -3, 3, 0, 6, -3, 3, Blocks.AIR);
        });
    }
}
""",
    "NotAPodClientTest.java": """
public class NotAPodClientTest implements FabricClientGameTest {
    void move(TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> {
            RoomCarver.carve(one, -3, 3, 0, 6, -3, 3, Blocks.AIR);
            player.setPos(0, 0, 0);
        });
    }
}
""",
    "MarkedClientTest.java": """
public class MarkedClientTest implements FabricClientGameTest {
    void seat(TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> {
            RoomCarver.carve(one, -3, 3, 0, 6, -3, 3, Blocks.AIR);
            // light-settled-ok: no client rides this pod, the mock does
            mock.startRiding(pod);
            pod.setPos(0, 0, 0); // light-settled-ok: same-line reason
        });
    }
}
""",
    "MentionsClientTest.java": """
public class MentionsClientTest implements FabricClientGameTest {
    void seat(TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> {
            // RoomCarver.carve(one, -3, 3, 0, 6, -3, 3, Blocks.AIR); then player.startRiding(pod)
            String text = "RoomCarver.carve(...)";
            player.startRiding(pod);
        });
    }
}
""",
    "ServerGameTest.java": """
public class ServerGameTest {
    @GameTest
    public void seats(GameTestHelper helper) {
        RoomCarver.carve(one, -3, 3, 0, 6, -3, 3, Blocks.AIR);
        player.startRiding(pod);
    }
}
""",
}
# label: (statements of the block, line the gate reports)
VIOLATIONS = {
    "startRiding": ("RoomCarver.carve(one, -3, 3, 0, 6, -3, 3, Blocks.AIR);\n            player.startRiding(pod);", 6),
    "pod setPos": ("RoomCarver.carve(one, -3, 3, 0, 6, -3, 3, Blocks.AIR);\n            pod.setPos(0, 0, 0);", 6),
    "a pod accessor": ("RoomCarver.carve(one, -3, 3, 0, 6, -3, 3, Blocks.AIR);\n            rig.realPod().setPos(0, 0, 0);", 6),
    "pod teleportTo": ("RoomCarver.carve(one, -3, 3, 0, 6, -3, 3, Blocks.AIR);\n            realPod.teleportTo(0, 0, 0);", 6),
    "a carve past other statements": (
        "RoomCarver.carve(one, -3, 3, 0, 6, -3, 3, Blocks.AIR);\n            lamps(one);\n            player.teleportTo(one, 0, 0, 0);\n            player.startRiding(pod);",
        8,
    ),
    "a wrapped carve": (
        "RoomCarver.carve(\n                one, -3, 3, 0, 6, -3, 3, Blocks.AIR);\n            player.startRiding(pod);",
        7,
    ),
    "a seat in a nested lambda": (
        "RoomCarver.carve(one, -3, 3, 0, 6, -3, 3, Blocks.AIR);\n            Runnable seat = () -> player.startRiding(pod);",
        6,
    ),
    "a bare marker": (
        "RoomCarver.carve(one, -3, 3, 0, 6, -3, 3, Blocks.AIR);\n            // light-settled-ok:\n            player.startRiding(pod);",
        7,
    ),
    "a marker above a blank line": (
        "RoomCarver.carve(one, -3, 3, 0, 6, -3, 3, Blocks.AIR);\n            // light-settled-ok: separated\n\n            player.startRiding(pod);",
        8,
    ),
}


def violating(body: str, call: str) -> str:
    return f"""
public class SeatsClientTest implements FabricClientGameTest {{
    void seat(TestSingleplayerContext singleplayer) {{
        singleplayer.getServer().{call}(server -> {{
            {body}
        }});
    }}
}}
"""


def fixture(extra: dict[str, str]) -> dict[str, str]:
    return {"FixtureTest.java": SERVER_TEST, "FixtureScenario.java": CLIENT_SCENARIO, **CLEAN, **extra}


CASES = {
    "clean": Case(fixture({}), None),
    **{label: Case(fixture({"SeatsClientTest.java": violating(body, "runOnServer")}), None) for label, (body, _) in VIOLATIONS.items()},
    "computeOnServer": Case(fixture({"SeatsClientTest.java": violating(VIOLATIONS["startRiding"][0], "computeOnServer")}), None),
}


def outcome(label: str) -> gate_batch.Result:
    return gate_batch.result(f"{__name__}/{label}")


class LightSettledGateTest(unittest.TestCase):
    def test_clean_files_pass(self):
        result = outcome("clean")
        self.assertTrue(result.ok, result.message)

    def test_a_seat_after_a_carve_in_one_block_fails(self):
        expected = {**{label: line for label, (_, line) in VIOLATIONS.items()}, "computeOnServer": VIOLATIONS["startRiding"][1]}
        for label, line in expected.items():
            with self.subTest(label):
                result = outcome(label)
                self.assertFalse(result.ok, f"the gate should fail the build for {label}")
                self.assertIn(f"SeatsClientTest:{line} seats a pod after a RoomCarver carve", result.message)
                self.assertIn("ClientWait.lightSettled", result.message)
                self.assertIn("#1303", result.message)
                for path in CLEAN:
                    self.assertNotRegex(result.message, rf"(?<!\w){re.escape(PurePosixPath(path).stem)}:\d")
