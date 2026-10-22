package io.github.pkeppeler.deepcharter.test;

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
            throw helper.assertionException("next() from east, west, south, north gave " + walked);
        }
        helper.succeed();
    }

    @GameTest
    public void turnedRightGoesClockwiseAndLeftAndOppositeFollow(GameTestHelper helper) {
        List<Side> right = List.of(
                Side.EAST.turnedRight(), Side.SOUTH.turnedRight(), Side.WEST.turnedRight(), Side.NORTH.turnedRight());
        if (!right.equals(List.of(Side.SOUTH, Side.WEST, Side.NORTH, Side.EAST))) {
            throw helper.assertionException("turnedRight() from east, south, west, north gave " + right);
        }
        for (Side side : Side.values()) {
            if (side.turnedLeft().turnedRight() != side || side.opposite().opposite() != side) {
                throw helper.assertionException("left, right or opposite does not undo itself at " + side);
            }
            if (side.opposite() != side.turnedRight().turnedRight()) {
                throw helper.assertionException("opposite is not two turns at " + side);
            }
        }
        helper.succeed();
    }
}
