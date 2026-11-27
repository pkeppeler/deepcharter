package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.client.pod.SonarAim;
import io.github.pkeppeler.deepcharter.client.pod.SonarMarkView;
import io.github.pkeppeler.deepcharter.client.pod.SonarScope;
import io.github.pkeppeler.deepcharter.pod.SonarEcho;
import io.github.pkeppeler.deepcharter.pod.SonarKind;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;

/** Where an echo lands on the navigator's scope (#988): the pod at the middle, the way it faces up the scope, the ping's range at the edge. Pure arithmetic, so a server GameTest holds it. */
public class SonarScopeTest {
    private static final Vec3 POD = TestLayers.vec(1, 100.5, 40, 200.5);
    private static final int RANGE = 24;
    private static final int RADIUS = 40;

    private static Optional<SonarScope.Dot> at(Direction facing, int dx, int dy, int dz) {
        return SonarScope.project(POD, facing, TestLayers.pos(1, 100 + dx, 40 + dy, 200 + dz), RANGE, RADIUS);
    }

    @GameTest
    public void whatIsAheadIsUpTheScopeWhicheverWayThePodFaces(GameTestHelper helper) {
        // 12 blocks ahead is half the range: half the radius up the scope.
        expect(
                helper,
                at(Direction.NORTH, 0, 0, -12).orElseThrow().equals(new SonarScope.Dot(0, -20, 0)),
                "north, 12 ahead");
        expect(
                helper,
                at(Direction.SOUTH, 0, 0, 12).orElseThrow().equals(new SonarScope.Dot(0, -20, 0)),
                "south, 12 ahead");
        expect(
                helper,
                at(Direction.EAST, 12, 0, 0).orElseThrow().equals(new SonarScope.Dot(0, -20, 0)),
                "east, 12 ahead");
        expect(
                helper,
                at(Direction.WEST, -12, 0, 0).orElseThrow().equals(new SonarScope.Dot(0, -20, 0)),
                "west, 12 ahead");
        helper.succeed();
    }

    @GameTest
    public void thePodsRightHandIsRightOnTheScope(GameTestHelper helper) {
        expect(
                helper,
                at(Direction.NORTH, 12, 0, 0).orElseThrow().equals(new SonarScope.Dot(20, 0, 0)),
                "north: east is right");
        expect(
                helper,
                at(Direction.EAST, 0, 0, 12).orElseThrow().equals(new SonarScope.Dot(20, 0, 0)),
                "east: south is right");
        expect(
                helper,
                at(Direction.SOUTH, -12, 0, 0).orElseThrow().equals(new SonarScope.Dot(20, 0, 0)),
                "south: west is right");
        expect(
                helper,
                at(Direction.WEST, 0, 0, -12).orElseThrow().equals(new SonarScope.Dot(20, 0, 0)),
                "west: north is right");
        expect(
                helper,
                at(Direction.NORTH, 0, 0, 12).orElseThrow().equals(new SonarScope.Dot(0, 20, 0)),
                "behind is down the scope");
        helper.succeed();
    }

    @GameTest
    public void anEchoAboveOrBelowByTwoBlocksGetsATick(GameTestHelper helper) {
        expect(helper, at(Direction.NORTH, 3, 1, 0).orElseThrow().rise() == 0, "1 up is level");
        expect(helper, at(Direction.NORTH, 3, 2, 0).orElseThrow().rise() == 1, "2 up is above");
        expect(helper, at(Direction.NORTH, 3, -1, 0).orElseThrow().rise() == 0, "1 down is level");
        expect(helper, at(Direction.NORTH, 3, -2, 0).orElseThrow().rise() == -1, "2 down is below");
        helper.succeed();
    }

    @GameTest
    public void theScopeEdgeIsTheRangeAndOutsideItIsOffTheScope(GameTestHelper helper) {
        expect(
                helper,
                at(Direction.NORTH, 24, 0, 0).isPresent(),
                "an echo at 24 blocks (the middle of its block is 24) is on the edge");
        expect(helper, at(Direction.NORTH, 25, 0, 0).isEmpty(), "an echo at 25 blocks is off the scope");
        expect(helper, at(Direction.NORTH, 17, 0, 17).isEmpty(), "the scope is round: 17 and 17 is 24.04 off");
        expect(helper, at(Direction.NORTH, 16, 0, 16).isPresent(), "16 and 16 is 22.6 off, on the scope");
        helper.succeed();
    }

    // ---- aiming at an echo ----

    private static SonarEcho echo(int x, int y, int z) {
        return new SonarEcho(SonarKind.ORE, TestLayers.pos(1, x, y, z));
    }

    @GameTest
    public void theNavigatorMeansTheEchoNearestWhereTheyLook(GameTestHelper helper) {
        Vec3 eye = TestLayers.vec(1, 100.5, 41, 200.5);
        SonarEcho ahead = echo(100, 41, 210);
        SonarEcho slightlyOff = echo(102, 41, 210);
        SonarEcho wayOff = echo(110, 41, 205);
        Vec3 south = new Vec3(0, 0, 1);
        expect(
                helper,
                SonarAim.pick(eye, south, List.of(wayOff, slightlyOff, ahead), 12)
                        .orElseThrow()
                        .equals(ahead),
                "the echo dead ahead is the one aimed at");
        expect(
                helper,
                SonarAim.pick(eye, south, List.of(wayOff, slightlyOff), 12)
                        .orElseThrow()
                        .equals(slightlyOff),
                "with nothing dead ahead, the echo 11 degrees off is the one aimed at");
        helper.succeed();
    }

    @GameTest
    public void anEchoOutsideTheAimConeIsNotAimedAt(GameTestHelper helper) {
        Vec3 eye = TestLayers.vec(1, 100.5, 41, 200.5);
        Vec3 south = new Vec3(0, 0, 1);
        // 10 blocks ahead and 2 across is 11.3 degrees off the look: in a 12 degree cone. 3 across is 16.7 degrees:
        // out.
        expect(
                helper,
                SonarAim.pick(eye, south, List.of(echo(102, 41, 210)), 12).isPresent(),
                "11.3 degrees is in");
        expect(
                helper,
                SonarAim.pick(eye, south, List.of(echo(103, 41, 210)), 12).isEmpty(),
                "16.7 degrees is out");
        expect(helper, SonarAim.pick(eye, south, List.of(), 12).isEmpty(), "no echoes, no aim");
        expect(
                helper,
                SonarAim.pick(eye, Vec3.ZERO, List.of(echo(100, 41, 210)), 12).isEmpty(),
                "no look, no aim");
        helper.succeed();
    }

    // ---- marks on the HUD ----

    private static SonarMarkView.Spot view(float yaw, float pitch, Vec3 target) {
        return SonarMarkView.project(Vec3.ZERO, yaw, pitch, target, 90, 400, 200, 10);
    }

    @GameTest
    public void aMarkDeadAheadIsAtTheMiddleOfTheScreen(GameTestHelper helper) {
        expect(helper, view(0, 0, new Vec3(0, 0, 10)).equals(new SonarMarkView.Spot(200, 100, true)), "south, ahead");
        expect(helper, view(90, 0, new Vec3(-10, 0, 0)).equals(new SonarMarkView.Spot(200, 100, true)), "west, ahead");
        expect(
                helper,
                view(0, 45, new Vec3(0, -10, 10)).equals(new SonarMarkView.Spot(200, 100, true)),
                "looking down at it");
        helper.succeed();
    }

    @GameTest
    public void aMarkToTheRightOrAboveIsRightOrAboveOnTheScreen(GameTestHelper helper) {
        // A 90 degree vertical field: at 10 ahead the screen's half height is 10 blocks, its half width 20.
        expect(
                helper,
                view(0, 0, new Vec3(-10, 0, 10)).equals(new SonarMarkView.Spot(300, 100, true)),
                "facing south, west (-x) is the right hand: half way to the right edge");
        expect(
                helper,
                view(0, 0, new Vec3(0, 5, 10)).equals(new SonarMarkView.Spot(200, 50, true)),
                "5 up at 10 ahead is half way to the top edge");
        helper.succeed();
    }

    @GameTest
    public void aMarkOffScreenOrBehindSitsAtTheEdgeTowardIt(GameTestHelper helper) {
        expect(
                helper,
                view(0, 0, new Vec3(-100, 0, 10)).equals(new SonarMarkView.Spot(390, 100, false)),
                "far to the right: the right edge, less the margin");
        expect(
                helper,
                view(0, 0, new Vec3(30, 0, -10)).equals(new SonarMarkView.Spot(10, 100, false)),
                "behind and to the left: the left edge");
        SonarMarkView.Spot behind = view(0, 0, new Vec3(0, 0, -10));
        expect(helper, !behind.onScreen() && behind.y() == 190, "directly behind: the bottom edge, got %s", behind);
        helper.succeed();
    }
}
