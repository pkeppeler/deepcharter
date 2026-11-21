package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.surface.SurfaceBlocks;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Loose regolith falls like sand (#836); the packed layer and the rock under it hold, so shafts and the colony pad stay put. */
public class RegolithFallTest {
    /** Ticks a falling block needs to drop a few blocks and land. */
    private static final int SETTLE_TICKS = 20;

    private static final BlockPos HIGH = new BlockPos(1, 4, 1);

    /** Loose regolith with air under it falls, and lands as regolith on the floor. */
    @GameTest
    public void looseRegolithFallsWhenNothingHoldsIt(GameTestHelper helper) {
        helper.setBlock(HIGH, SurfaceBlocks.REGOLITH);
        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.AIR, HIGH);
            for (int y = HIGH.getY() - 1; y >= 0; y--) {
                if (helper.getBlockState(HIGH.atY(y)).is(SurfaceBlocks.REGOLITH)) {
                    return;
                }
            }
            throw helper.assertionException("the regolith vanished instead of landing under %s", HIGH.toShortString());
        });
    }

    /** A slump over a pod does not land in it: the block spills as an item, and the pod is never inside a block. */
    @GameTest(maxTicks = 2 * SETTLE_TICKS)
    public void regolithDoesNotLandInsideAPod(GameTestHelper helper) {
        PodEntity pod = TestEntities.create(PodRegistry.POD, helper.getLevel());
        pod.setPos(helper.absoluteVec(new Vec3(1.5, 0, 1.5)));
        helper.getLevel().addFreshEntity(pod);
        helper.setBlock(HIGH.above(3), SurfaceBlocks.REGOLITH);
        helper.runAfterDelay(SETTLE_TICKS + 5, () -> {
            AABB around = pod.getBoundingBox().inflate(1);
            for (BlockPos cell : BlockPos.betweenClosed(
                    BlockPos.containing(around.minX, around.minY, around.minZ),
                    BlockPos.containing(around.maxX, around.maxY, around.maxZ))) {
                if (helper.getLevel().getBlockState(cell).is(SurfaceBlocks.REGOLITH)) {
                    throw helper.assertionException("regolith landed at %s, in or by the pod", cell.toShortString());
                }
            }
            if (helper.getLevel()
                    .getEntitiesOfClass(ItemEntity.class, around.inflate(2))
                    .isEmpty()) {
                throw helper.assertionException("the refused regolith did not spill as an item");
            }
            helper.succeed();
        });
    }

    /** Regolith resting on a block holds, as sand does. */
    @GameTest
    public void looseRegolithOnSupportStays(GameTestHelper helper) {
        helper.setBlock(HIGH.below(), SurfaceBlocks.REGOLITH_PACKED);
        helper.setBlock(HIGH, SurfaceBlocks.REGOLITH);
        helper.runAfterDelay(SETTLE_TICKS, () -> {
            helper.assertBlockPresent(SurfaceBlocks.REGOLITH, HIGH);
            helper.succeed();
        });
    }

    /** Packed regolith and rock over air hang where they are. */
    @GameTest
    public void packedRegolithAndRockDoNotFall(GameTestHelper helper) {
        BlockPos rock = HIGH.east(2);
        helper.setBlock(HIGH, SurfaceBlocks.REGOLITH_PACKED);
        helper.setBlock(rock, SurfaceBlocks.REGOLITH_ROCK);
        helper.runAfterDelay(SETTLE_TICKS, () -> {
            helper.assertBlockPresent(SurfaceBlocks.REGOLITH_PACKED, HIGH);
            helper.assertBlockPresent(SurfaceBlocks.REGOLITH_ROCK, rock);
            helper.succeed();
        });
    }

    /** The mesa's ochre topsoil is not regolith's loose grain: it keeps its place. */
    @GameTest
    public void ochreRegolithDoesNotFall(GameTestHelper helper) {
        Block ochre = SurfaceBlocks.OCHRE_REGOLITH;
        helper.setBlock(HIGH, ochre);
        helper.runAfterDelay(SETTLE_TICKS, () -> {
            helper.assertBlockPresent(ochre, HIGH);
            helper.succeed();
        });
    }
}
