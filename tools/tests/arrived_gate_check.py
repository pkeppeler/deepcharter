"""Tests the arrived gate in gradle/gametest.gradle.kts: a client GameTest, evidence scenario or test helper that teleports a player and then adds
or seats an entity (addFreshEntity, startRiding) in the same runOnServer, computeOnServer, serverDo or serverGet block fails the build (#1345, #1352).

Its fixture trees go through the scan of generateGametestModJson in one Gradle run (gate_checks.py, gate_batch.py), so it needs the
repo's Gradle wrapper on JDK 25. Its name misses the test_*.py glob of the tool-tests job, which has no JDK 25; the build job and the pre-push hook run it.
Usage: tools/py tools/tests/gate_checks.py arrived
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
    "ArrivedClientTest.java": """
public class ArrivedClientTest implements FabricClientGameTest {
    void seat(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> {
            player.teleportTo(one, 5000, 0, 5000, Set.of(), 0f, 0f, true);
        });
        ClientWait.arrived(context, singleplayer.getServer());
        singleplayer.getServer().runOnServer(server -> {
            one.addFreshEntity(pod);
            player.startRiding(pod);
        });
    }
}
""",
    "PodTeleportClientTest.java": """
public class PodTeleportClientTest implements FabricClientGameTest {
    void move(TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> {
            pod.teleportTo(0, 0, 0);
            rig.realPod().teleportTo(0, 0, 0);
            pod[0].teleportTo(0, 0, 0);
            player.startRiding(pod);
            one.addFreshEntity(other);
        });
    }
}
""",
    "SeatFirstClientTest.java": """
public class SeatFirstClientTest implements FabricClientGameTest {
    void seat(TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> {
            one.addFreshEntity(pod);
            player.startRiding(pod);
            player.teleportTo(one, 0, 0, 0, Set.of(), 0f, 0f, true);
        });
    }
}
""",
    "MarkedClientTest.java": """
public class MarkedClientTest implements FabricClientGameTest {
    void seat(TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> {
            // arrived-ok: turns the player where it stands
            player.teleportTo(one, player.getX(), player.getY(), player.getZ(), Set.of(), 0f, 10f, true);
            player.startRiding(pod);
        });
        singleplayer.getServer().runOnServer(server -> {
            player.teleportTo(one, 0, 0, 0, Set.of(), 0f, 0f, true); // arrived-ok: same-line reason
            one.addFreshEntity(pod);
        });
    }
}
""",
    "MentionsClientTest.java": """
public class MentionsClientTest implements FabricClientGameTest {
    void seat(TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> {
            // player.teleportTo(one, 0, 0, 0); then player.startRiding(pod)
            String text = "player.teleportTo(one, 0, 0, 0)";
            player.startRiding(pod);
        });
    }
}
""",
    "ServerGameTest.java": """
public class ServerGameTest {
    @GameTest
    public void seats(GameTestHelper helper) {
        player.teleportTo(one, 0, 0, 0, Set.of(), 0f, 0f, true);
        player.startRiding(pod);
    }
}
""",
    "StagingNotes.java": """
public class StagingNotes {
    void seat(TestServerContext server) {
        server.runOnServer(s -> {
            player.teleportTo(one, 0, 0, 0, Set.of(), 0f, 0f, true);
            player.startRiding(pod);
        });
    }
}
""",
}
# label: (statements of the block, line the gate reports)
VIOLATIONS = {
    "startRiding": ("player.teleportTo(one, 0, 0, 0, Set.of(), 0f, 0f, true);\n            player.startRiding(pod);", 5),
    "addFreshEntity": ("player.teleportTo(one, 0, 0, 0, Set.of(), 0f, 0f, true);\n            one.addFreshEntity(pod);", 5),
    "a player named after a pod": ("podPilot.teleportTo(one, 0, 0, 0, Set.of(), 0f, 0f, true);\n            podPilot.startRiding(pod);", 5),
    "a mock teleport": ("two.mock().teleportTo(level, at, 0, 0);\n            level.addFreshEntity(pod);", 5),
    "a wrapped teleport": ("player.teleportTo(\n                one, 0, 0, 0, Set.of(), 0f, 0f, true);\n            player.startRiding(pod);", 5),
    "a seat past other statements": (
        "ServerLevel one = level(server);\n            player.teleportTo(one, 0, 0, 0, Set.of(), 0f, 0f, true);\n            pod.setFuel(100f);\n            player.startRiding(pod);",
        6,
    ),
    "a seat in a nested lambda": (
        "player.teleportTo(one, 0, 0, 0, Set.of(), 0f, 0f, true);\n            Runnable seat = () -> player.startRiding(pod);",
        5,
    ),
    "a bare marker": (
        "// arrived-ok:\n            player.teleportTo(one, 0, 0, 0, Set.of(), 0f, 0f, true);\n            player.startRiding(pod);",
        6,
    ),
    "a marker above a blank line": (
        "// arrived-ok: separated\n\n            player.teleportTo(one, 0, 0, 0, Set.of(), 0f, 0f, true);\n            player.startRiding(pod);",
        7,
    ),
    "a marker on the seat": (
        "player.teleportTo(one, 0, 0, 0, Set.of(), 0f, 0f, true);\n            player.startRiding(pod); // arrived-ok: on the wrong statement",
        5,
    ),
}
CLIENT_CLASS = "public class SeatsClientTest implements FabricClientGameTest"
# label: (file, class line, server block call, line the gate reports)
OTHER_SHAPES = {
    "computeOnServer": ("SeatsClientTest.java", CLIENT_CLASS, "singleplayer.getServer().computeOnServer", 5),
    "serverDo": ("SeatsClientTest.java", CLIENT_CLASS, "chamber.serverDo", 5),
    "serverGet": ("SeatsClientTest.java", CLIENT_CLASS, "world.serverGet", 5),
    "an evidence scenario": ("evidence/SeatsScenario.java", "public class SeatsScenario extends EvidenceScenario", "singleplayer.getServer().runOnServer", 5),
    "an evidence helper": ("evidence/SeatsHelper.java", "final class SeatsHelper", "singleplayer.getServer().runOnServer", 5),
    "a test helper": ("support/SeatsSupport.java", "public final class SeatsSupport", "server.runOnServer", 5),
}


def violating(class_line: str, call: str, body: str) -> str:
    return f"""
{class_line} {{
    void seat(TestSingleplayerContext singleplayer) {{
        {call}(server -> {{
            {body}
        }});
    }}
}}
"""


def fixture(extra: dict[str, str]) -> dict[str, str]:
    return {"FixtureTest.java": SERVER_TEST, "FixtureScenario.java": CLIENT_SCENARIO, **CLEAN, **extra}


CASES = {
    "clean": Case(fixture({}), None),
    **{
        label: Case(fixture({"SeatsClientTest.java": violating(CLIENT_CLASS, "singleplayer.getServer().runOnServer", body)}), None)
        for label, (body, _) in VIOLATIONS.items()
    },
    **{
        label: Case(fixture({path: violating(class_line, call, VIOLATIONS["startRiding"][0])}), None)
        for label, (path, class_line, call, _) in OTHER_SHAPES.items()
    },
}


def outcome(label: str) -> gate_batch.Result:
    return gate_batch.result(f"{__name__}/{label}")


class ArrivedGateTest(unittest.TestCase):
    def check_fails(self, label: str, module: str, line: int):
        result = outcome(label)
        self.assertFalse(result.ok, f"the gate should fail the build for {label}")
        self.assertIn(f"{module}:{line} teleports a player and then adds or seats an entity", result.message)
        self.assertIn("ClientWait.arrived", result.message)
        self.assertIn("#1345", result.message)
        for path in CLEAN:
            self.assertNotRegex(result.message, rf"(?<!\w){re.escape(PurePosixPath(path).stem)}:\d")

    def test_clean_files_pass(self):
        result = outcome("clean")
        self.assertTrue(result.ok, result.message)

    def test_a_seat_after_a_teleport_in_one_block_fails(self):
        for label, (_, line) in VIOLATIONS.items():
            with self.subTest(label):
                self.check_fails(label, "SeatsClientTest", line)

    def test_every_server_block_and_staging_class_is_scanned(self):
        for label, (path, _, _, line) in OTHER_SHAPES.items():
            with self.subTest(label):
                self.check_fails(label, path.removesuffix(".java").replace("/", "."), line)
