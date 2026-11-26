"""Tests the light-settled gate in gradle/gametest.gradle.kts: a client GameTest or evidence scenario method that carves a room and then seats a pod
(startRiding, addFreshEntity of a pod, or setPos, teleportTo or teleport on a pod) with no ClientWait.lightSettled between them fails the build (#1303),
in one server block or split across two, directly or through a same-file helper, or through a helper in another class that carries
`// seats-pod:` (an unmarked cross-class helper that seats a pod fails too, #1455).

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
    "SettledHelpersClientTest.java": """
public class SettledHelpersClientTest implements FabricClientGameTest {
    void seat(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> buildSite(server));
        ClientWait.chunksAround(context, 0, 0, 3);
        ClientWait.lightSettled(context, singleplayer.getServer(), 1, 0, 0, 3);
        singleplayer.getServer().computeOnServer(this::board);
    }

    static void buildSite(MinecraftServer server) {
        RoomCarver.carve(one, -3, 3, 0, 6, -3, 3, Blocks.AIR);
    }

    int board(MinecraftServer server) {
        level.addFreshEntity(pod);
        return player.startRiding(pod) ? 1 : 0;
    }
}
""",
    "OtherMethodClientTest.java": """
public class OtherMethodClientTest implements FabricClientGameTest {
    void carve(TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> RoomCarver.carve(one, -3, 3, 0, 6, -3, 3, Blocks.AIR));
    }

    void seat(TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> player.startRiding(pod));
    }

    void board(TestSingleplayerContext singleplayer) {
        other.carveRoom();
        singleplayer.getServer().runOnServer(server -> player.startRiding(pod));
    }
}
""",
    "SeatSupport.java": """
public class SeatSupport {
    // seats-pod: spawns the new pod and seats the first player
    static void mountPlayer(TestServerContext server) {
        server.runOnServer(s -> {
            level.addFreshEntity(pod);
            player.startRiding(pod);
        });
    }

    static void mountUnmarked(TestServerContext server) {
        server.runOnServer(s -> player.startRiding(pod));
    }

    static void lookAround(TestServerContext server) {
        server.runOnServer(s -> player.setPos(0, 0, 0));
    }
}
""",
    "CrossSettledClientTest.java": """
public class CrossSettledClientTest implements FabricClientGameTest {
    void seat(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> RoomCarver.carve(one, -3, 3, 0, 6, -3, 3, Blocks.AIR));
        ClientWait.lightSettled(context, singleplayer.getServer(), 1, 0, 0, 3);
        SeatSupport.mountPlayer(singleplayer.getServer());
    }

    void noCarve(TestSingleplayerContext singleplayer) {
        SeatSupport.mountPlayer(singleplayer.getServer());
        SeatSupport.lookAround(singleplayer.getServer());
    }

    void markedCall(TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> RoomCarver.carve(one, -3, 3, 0, 6, -3, 3, Blocks.AIR));
        // light-settled-ok: the carve is in another chunk than the seat
        SeatSupport.mountPlayer(singleplayer.getServer());
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
    "addFreshEntity of a pod": ("RoomCarver.carve(one, -3, 3, 0, 6, -3, 3, Blocks.AIR);\n            level.addFreshEntity(pod);", 6),
    "an indexed pod accessor": ("RoomCarver.carve(one, -3, 3, 0, 6, -3, 3, Blocks.AIR);\n            rig.pod(0).setPos(0, 0, 0);", 6),
    "a pod array element": ("RoomCarver.carve(one, -3, 3, 0, 6, -3, 3, Blocks.AIR);\n            pods[0].teleportTo(0, 0, 0);", 6),
    "a pod accessor with a nested call": ("RoomCarver.carve(one, -3, 3, 0, 6, -3, 3, Blocks.AIR);\n            pod(server, mole.get())\n                    .teleportTo(one, 0, 0, 0, Set.of(), 0f, 0f, true);", 6),
    "pod teleport": ("RoomCarver.carve(one, -3, 3, 0, 6, -3, 3, Blocks.AIR);\n            pod.teleport(new TeleportTransition(one, Vec3.ZERO, Vec3.ZERO, 0f, 0f, TeleportTransition.DO_NOTHING));", 6),
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


# label: (a whole client test, line the gate reports)
METHOD_VIOLATIONS = {
    "a split with no wait": ("""
public class SeatsClientTest implements FabricClientGameTest {
    void seat(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> RoomCarver.carve(one, -3, 3, 0, 6, -3, 3, Blocks.AIR));
        ClientWait.chunksAround(context, 0, 0, 3);
        singleplayer.getServer().runOnServer(server -> player.startRiding(pod));
    }
}
""", 6),
    "a carve helper then a seat helper": ("""
public class SeatsClientTest implements FabricClientGameTest {
    void seat(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> buildSite(server));
        ClientWait.chunksAround(context, 0, 0, 3);
        int pod = singleplayer.getServer().computeOnServer(server -> board(server));
    }

    static void buildSite(MinecraftServer server) {
        RoomCarver.carve(one, -3, 3, 0, 6, -3, 3, Blocks.AIR);
    }

    static int board(MinecraftServer server) {
        pod.setPos(0, 0, 0);
        return 1;
    }
}
""", 6),
    "method references": ("""
public class SeatsClientTest implements FabricClientGameTest {
    void seat(TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(SeatsClientTest::buildSite);
        singleplayer.getServer().runOnServer(this::mount);
    }

    static void buildSite(MinecraftServer server) {
        RoomCarver.carveRoom(one, -3, 3, 0, 6, -3, 3);
    }

    void mount(MinecraftServer server) {
        player.startRiding(pod);
    }
}
""", 5),
    "a helper that carves then calls a seating helper": ("""
public class SeatsClientTest implements FabricClientGameTest {
    void seat(TestSingleplayerContext singleplayer) {
        singleplayer.getServer().computeOnServer(server -> setUp(server));
    }

    int setUp(MinecraftServer server) {
        RoomCarver.carve(one, -3, 3, 0, 6, -3, 3, Blocks.AIR);
        return mountBoth(server);
    }

    private int mountBoth(MinecraftServer server) {
        return player.startRiding(pod) ? 1 : 0;
    }
}
""", 9),
}


# label: (a whole client test, the text the gate reports)
CROSS_VIOLATIONS = {
    "a cross-class seat after a carve": ("""
public class SeatsClientTest implements FabricClientGameTest {
    void seat(TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> RoomCarver.carve(one, -3, 3, 0, 6, -3, 3, Blocks.AIR));
        SeatSupport.mountPlayer(singleplayer.getServer());
    }
}
""", "SeatsClientTest:5 seats a pod after a RoomCarver carve"),
    "a cross-class seat through a same-file helper": ("""
public class SeatsClientTest implements FabricClientGameTest {
    void seat(TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> RoomCarver.carve(one, -3, 3, 0, 6, -3, 3, Blocks.AIR));
        board(singleplayer);
    }

    void board(TestSingleplayerContext singleplayer) {
        SeatSupport.mountPlayer(singleplayer.getServer());
    }
}
""", "SeatsClientTest:5 seats a pod after a RoomCarver carve"),
    "an unmarked cross-class seat helper": ("""
public class SeatsClientTest implements FabricClientGameTest {
    void seat(TestSingleplayerContext singleplayer) {
        SeatSupport.mountUnmarked(singleplayer.getServer());
    }
}
""", "SeatsClientTest:4 calls SeatSupport.mountUnmarked, which seats a pod and carries no '// seats-pod: <reason>'"),
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
    **{label: Case(fixture({"SeatsClientTest.java": source}), None) for label, (source, _) in METHOD_VIOLATIONS.items()},
    **{label: Case(fixture({"SeatsClientTest.java": source}), None) for label, (source, _) in CROSS_VIOLATIONS.items()},
    "computeOnServer": Case(fixture({"SeatsClientTest.java": violating(VIOLATIONS["startRiding"][0], "computeOnServer")}), None),
}


def outcome(label: str) -> gate_batch.Result:
    return gate_batch.result(f"{__name__}/{label}")


class LightSettledGateTest(unittest.TestCase):
    def test_clean_files_pass(self):
        result = outcome("clean")
        self.assertTrue(result.ok, result.message)

    def test_a_seat_after_a_carve_with_no_wait_between_fails(self):
        expected = {
            **{label: line for label, (_, line) in VIOLATIONS.items()},
            **{label: line for label, (_, line) in METHOD_VIOLATIONS.items()},
            "computeOnServer": VIOLATIONS["startRiding"][1],
        }
        for label, line in expected.items():
            with self.subTest(label):
                result = outcome(label)
                self.assertFalse(result.ok, f"the gate should fail the build for {label}")
                self.assertIn(f"SeatsClientTest:{line} seats a pod after a RoomCarver carve", result.message)
                self.assertIn("ClientWait.lightSettled", result.message)
                self.assertIn("#1303", result.message)
                for path in CLEAN:
                    self.assertNotRegex(result.message, rf"(?<!\w){re.escape(PurePosixPath(path).stem)}:\d")

    def test_a_cross_class_seat_helper_needs_its_marker_and_the_wait(self):
        for label, (_, expected) in CROSS_VIOLATIONS.items():
            with self.subTest(label):
                result = outcome(label)
                self.assertFalse(result.ok, f"the gate should fail the build for {label}")
                self.assertIn(expected, result.message)
                self.assertIn("#1455" if "unmarked" in label else "#1303", result.message)
                for path in CLEAN:
                    self.assertNotRegex(result.message, rf"(?<!\w){re.escape(PurePosixPath(path).stem)}:\d")
