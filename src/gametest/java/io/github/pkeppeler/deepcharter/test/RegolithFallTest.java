package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

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

    /** The foot of a column on the structure's BASE row. */
    private static final BlockPos BASE = new BlockPos(1, 0, 1);

    /** Loose regolith with air under it falls, and lands as regolith on the BASE. */
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
            throw failure(helper, "the regolith vanished instead of landing under %s", HIGH.toShortString());
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
                    throw failure(helper, "regolith landed at %s, in or by the pod", cell.toShortString());
                }
            }
            if (helper.getLevel()
                    .getEntitiesOfClass(ItemEntity.class, around.inflate(2))
                    .isEmpty()) {
                throw failure(helper, "the refused regolith did not spill as an item");
            }
            helper.succeed();
        });
    }

    /** A cut under the skin brings it down: the packed layer is broken as the drill breaks it, and the skin lands in the cut. */
    @GameTest(maxTicks = 3 * SETTLE_TICKS)
    public void cuttingThePackedLayerUnderTheSkinSlumpsIt(GameTestHelper helper) {
        BlockPos cut = BASE.above(2);
        BlockPos skin = BASE.above(3);
        helper.setBlock(BASE, Blocks.STONE);
        helper.setBlock(BASE.above(), SurfaceBlocks.REGOLITH_PACKED);
        helper.setBlock(cut, SurfaceBlocks.REGOLITH_PACKED);
        helper.setBlock(skin, SurfaceBlocks.REGOLITH);
        helper.runAfterDelay(SETTLE_TICKS, () -> {
            helper.assertBlockPresent(SurfaceBlocks.REGOLITH, skin);
            helper.getLevel().destroyBlock(helper.absolutePos(cut), false);
            helper.runAfterDelay(SETTLE_TICKS, () -> {
                helper.assertBlockPresent(Blocks.AIR, skin);
                helper.assertBlockPresent(SurfaceBlocks.REGOLITH, cut);
                helper.assertBlockPresent(SurfaceBlocks.REGOLITH_PACKED, BASE.above());
                helper.succeed();
            });
        });
    }

    /**
     * A pad of regolith on the layers the surface lays under it, with a shaft cut through its middle, stands: the skin round the shaft
     * keeps its support, so nothing falls into the shaft.
     */
    @GameTest(maxTicks = 3 * SETTLE_TICKS)
    public void aPadAndTheWallsOfAShaftThroughItHold(GameTestHelper helper) {
        int half = 2;
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                BlockPos column = BASE.offset(dx, 0, dz);
                helper.setBlock(column, SurfaceBlocks.REGOLITH_ROCK);
                helper.setBlock(column.above(1), SurfaceBlocks.REGOLITH_PACKED);
                helper.setBlock(column.above(2), SurfaceBlocks.REGOLITH_PACKED);
                helper.setBlock(column.above(3), SurfaceBlocks.REGOLITH);
            }
        }
        helper.runAfterDelay(SETTLE_TICKS, () -> {
            for (int y = 3; y >= 1; y--) {
                helper.getLevel().destroyBlock(helper.absolutePos(BASE.above(y)), false);
            }
            helper.runAfterDelay(SETTLE_TICKS, () -> {
                for (int dx = -half; dx <= half; dx++) {
                    for (int dz = -half; dz <= half; dz++) {
                        boolean shaft = dx == 0 && dz == 0;
                        helper.assertBlockPresent(shaft ? Blocks.AIR : SurfaceBlocks.REGOLITH, BASE.offset(dx, 3, dz));
                    }
                }
                helper.assertBlockPresent(Blocks.AIR, BASE.above(1));
                helper.succeed();
            });
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
