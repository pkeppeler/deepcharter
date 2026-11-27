package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.test.support.BoreBot.Side;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Server GameTests for {@code BoreBot}: the sidestep orders the bore tables are measured with. None needs the world. */
public class BoreBotTest {
    @GameTest
    public void nextGoesEastWestSouthNorthAndRound(GameTestHelper helper) {
        List<Side> walked = List.of(
                Side.EAST.nextInDeclarationOrder(),
                Side.WEST.nextInDeclarationOrder(),
                Side.SOUTH.nextInDeclarationOrder(),
                Side.NORTH.nextInDeclarationOrder());
        if (!walked.equals(List.of(Side.WEST, Side.SOUTH, Side.NORTH, Side.EAST))) {
            throw failure(helper, "next() from east, west, south, north gave %s", walked);
        }
        helper.succeed();
    }

    @GameTest
    public void turnedRightGoesClockwiseAndLeftAndOppositeFollow(GameTestHelper helper) {
        List<Side> right = List.of(
                Side.EAST.turnedRight(), Side.SOUTH.turnedRight(), Side.WEST.turnedRight(), Side.NORTH.turnedRight());
        if (!right.equals(List.of(Side.SOUTH, Side.WEST, Side.NORTH, Side.EAST))) {
            throw failure(helper, "turnedRight() from east, south, west, north gave %s", right);
        }
        for (Side side : Side.values()) {
            if (side.turnedLeft().turnedRight() != side || side.opposite().opposite() != side) {
                throw failure(helper, "left, right or opposite does not undo itself at %s", side);
            }
            if (side.opposite() != side.turnedRight().turnedRight()) {
                throw failure(helper, "opposite is not two turns at %s", side);
            }
        }
        helper.succeed();
    }
}
