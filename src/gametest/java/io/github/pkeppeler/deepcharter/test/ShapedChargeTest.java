package io.github.pkeppeler.deepcharter.test;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.repair.RepairTuning;
import io.github.pkeppeler.deepcharter.test.support.ChargeUse;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for the shaped charge (#526, ADR 0050): the blasting charge's set-up and fuse, but a directed blast. It removes, in each column of the pod's
 * footprint, the Company rock from the slab under the pod down to the first block that is not Company rock, and nothing else, at most {@code shapedDepth} rows.
 * It will not open onto lava. Each test has its own X in layer 1.
 *
 * <p>The scene: a Mole 2 x 2 resting on a stone bed {@link #BED} rows deep, at {@code (x, FLOOR, Z)}. The footprint is {@code x - 1 .. x} and {@code Z - 1 .. Z};
 * {@link #cell} takes the offset from the floor (the first row under the pod is -1).
 */
public class ShapedChargeTest {
    private static final RepairTuning TUNING = RepairTuning.DEFAULT;
    private static final int Z = 5400;
    private static final int FLOOR = 60;
    private static final int BED = 16;
    private static final int ROOM = 14;
    /** Blocks east that the pod is moved to leave the blast: its box then starts over chargeReach from the footprint, and the pod stays in its chunk (x is a multiple of 16). */
    private static final double FAR = 9.5;

    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 400;

    /** What a test checks once the charge has blown, with the pod and the hull it had when the charge was set. */
    private interface Verify {
        void check(GameTestHelper helper, ServerLevel level, PodEntity pod, float hullAtSet);
    }

    private static BlockPos cell(int x, int dy, int dz) {
        return new BlockPos(x, FLOOR + dy, Z + dz);
    }

    /** Company rock in the column {@code (x, z)} from the first row under the pod down {@code rows} rows. */
    private static void rock(ServerLevel level, int x, int dz, int rows) {
        for (int dy = -1; dy >= -rows; dy--) {
            level.setBlock(cell(x, dy, dz), HazardBlocks.COMPANY_ROCK.defaultBlockState(), 2);
        }
    }

    private static void scene(
            GameTestHelper helper, int x, Consumer<ServerLevel> build, Consumer<PodEntity> afterSet, Verify verify) {
        ServerLevel level = layer(helper);
        RoomCarver.carve(level, x - ROOM, x + ROOM, FLOOR - BED, FLOOR - 1, Z - ROOM, Z + ROOM, Blocks.STONE);
        RoomCarver.carve(level, x - ROOM, x + ROOM, FLOOR, FLOOR + 10, Z - ROOM, Z + ROOM, Blocks.AIR);
        build.accept(level);
        MockPlayer pilot = MockPlayers.join(helper, "shaped-" + x);
        pilot.player().setGameMode(GameType.SURVIVAL);
        Vec3 at = new Vec3(x, FLOOR, Z);
        pilot.teleportTo(level, at, 0f, 0f);
        PodEntity[] pod = new PodEntity[1];
        FarChunks.awaitEntityTicking(helper, level, BlockPos.containing(at), () -> {
            pod[0] = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
            pod[0].setPos(at);
            level.addFreshEntity(pod[0]);
            if (!pilot.player().startRiding(pod[0])) {
                throw failure(helper, "the pilot could not mount the pod");
            }
        });
        int[] setAt = {-1};
        float[] hull = new float[1];
        TestTicks.each(helper, () -> {
            if (pod[0] == null || !pod[0].onGround()) {
                return;
            }
            if (setAt[0] < 0) {
                hull[0] = pod[0].hull();
                InteractionResult result = ChargeUse.use(pilot.player(), Consumable.SHAPED_CHARGE);
                if (!result.consumesAction()) {
                    throw failure(
                            helper,
                            "the shaped charge should be set, the use gave %s and the bar says %s",
                            result,
                            pilot.actionBarMessages());
                }
                if (level.getBlockState(cell(x, -1, 0)).isAir()) {
                    throw failure(helper, "the charge cleared the rock the moment it was set: it has a fuse");
                }
                setAt[0] = pod[0].tickCount;
                afterSet.accept(pod[0]);
                return;
            }
            if (pod[0].tickCount - setAt[0] >= TUNING.chargeFuseTicks() + 2) {
                verify.check(helper, level, pod[0], hull[0]);
                pod[0].discard();
                helper.succeed();
            }
        });
    }

    private static void expectAir(GameTestHelper helper, ServerLevel level, BlockPos pos, String what) {
        if (!level.getBlockState(pos).isAir()) {
            throw failure(
                    helper,
                    "%s: %s should be air, was %s",
                    what,
                    pos,
                    level.getBlockState(pos).getBlock());
        }
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aShapedChargeClearsTheWholeCompanyRockColumnOfEachFootprintCellAndStopsAtTheFirstOtherBlock(
            GameTestHelper helper) {
        int x = 4016;
        scene(
                helper,
                x,
                level -> {
                    rock(level, x - 1, -1, 7); // a thick stretch: 7 rows, more than three blasting charges' worth
                    level.setBlock(cell(x - 1, -8, -1), Blocks.STONE.defaultBlockState(), 2);
                    level.setBlock(
                            cell(x - 1, -9, -1),
                            HazardBlocks.COMPANY_ROCK.defaultBlockState(),
                            2); // under the stone: not contiguous, stays
                    rock(level, x, -1, 3);
                    level.setBlock(
                            cell(x, -4, -1),
                            HazardBlocks.GAS_POCKET.defaultBlockState(),
                            2); // the run ends on a pocket: it stays, and vents no more
                    rock(level, x - 1, 0, 1);
                    rock(level, x, 0, TUNING.shapedDepth()); // exactly its depth: all of it goes
                    level.setBlock(
                            cell(x + 1, -1, 0),
                            HazardBlocks.COMPANY_ROCK.defaultBlockState(),
                            2); // beside the footprint: stays
                },
                pod -> pod.setPos(pod.getX() + FAR, pod.getY(), pod.getZ()),
                (helper2, level, pod, hull) -> {
                    for (int dy = -1; dy >= -7; dy--) {
                        expectAir(helper2, level, cell(x - 1, dy, -1), "the 7 row column");
                    }
                    ChargeUse.expectBlock(
                            helper2, level, cell(x - 1, -8, -1), Blocks.STONE, "the stone that ends the column stays");
                    ChargeUse.expectBlock(
                            helper2,
                            level,
                            cell(x - 1, -9, -1),
                            HazardBlocks.COMPANY_ROCK,
                            "rock under the stone stays");
                    for (int dy = -1; dy >= -3; dy--) {
                        expectAir(helper2, level, cell(x, dy, -1), "the 3 row column");
                    }
                    ChargeUse.expectBlock(
                            helper2,
                            level,
                            cell(x, -4, -1),
                            HazardBlocks.GAS_POCKET,
                            "a pocket under the run stays shut");
                    expectAir(helper2, level, cell(x - 1, -1, 0), "the 1 row column");
                    ChargeUse.expectBlock(
                            helper2, level, cell(x - 1, -2, 0), Blocks.STONE, "the stone under a short run stays");
                    for (int dy = -1; dy >= -TUNING.shapedDepth(); dy--) {
                        expectAir(helper2, level, cell(x, dy, 0), "the deep column");
                    }
                    ChargeUse.expectBlock(
                            helper2,
                            level,
                            cell(x + 1, -1, 0),
                            HazardBlocks.COMPANY_ROCK,
                            "rock beside the footprint stays");
                });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aShapedChargeClearsNoMoreThanItsDepthAndNoRockButCompanyRock(GameTestHelper helper) {
        int x = 4080;
        int depth = TUNING.shapedDepth();
        scene(
                helper,
                x,
                level -> {
                    rock(level, x, 0, depth + 2);
                    level.setBlock(cell(x - 1, -1, 0), HazardBlocks.COMPANY_ROCK.defaultBlockState(), 2);
                },
                pod -> pod.setPos(pod.getX() + FAR, pod.getY(), pod.getZ()),
                (helper2, level, pod, hull) -> {
                    expectAir(helper2, level, cell(x, -depth, 0), "the last row it reaches");
                    ChargeUse.expectBlock(
                            helper2,
                            level,
                            cell(x, -depth - 1, 0),
                            HazardBlocks.COMPANY_ROCK,
                            "a row beyond its depth");
                    ChargeUse.expectBlock(
                            helper2,
                            level,
                            cell(x - 1, -2, 0),
                            Blocks.STONE,
                            "plain stone under the rock is the drill's");
                    ChargeUse.expectBlock(
                            helper2,
                            level,
                            cell(x - 1, -2, -1),
                            Blocks.STONE,
                            "a column with no Company rock is not touched");
                });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aPodOnTheBlastTakesTheWholeCharge(GameTestHelper helper) {
        scene(helper, 4144, level -> rock(level, 4144, 0, 3), pod -> {}, (helper2, level, pod, hull) -> {
            double lost = hull - pod.hull();
            if (Math.abs(lost - TUNING.chargeDamage()) > 0.01) {
                throw failure(
                        helper2,
                        "a pod that did not move should lose %.2f hull, it lost %.2f",
                        TUNING.chargeDamage(),
                        lost);
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aPodBeyondTheReachTakesNone(GameTestHelper helper) {
        scene(
                helper,
                4208,
                level -> rock(level, 4208, 0, 3),
                pod -> pod.setPos(pod.getX() + FAR, pod.getY(), pod.getZ()),
                (helper2, level, pod, hull) -> {
                    if (Math.abs(hull - pod.hull()) > 0.01) {
                        throw failure(
                                helper2, "a pod beyond the reach should lose nothing, it lost %.2f", hull - pod.hull());
                    }
                });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 700)
    public void aShapedChargeIsRefusedAndKeptWhereABlastingChargeIsAndWhenTheRunEndsOnLava(GameTestHelper helper) {
        int x = 4272;
        ServerLevel level = layer(helper);
        RoomCarver.carve(level, x - ROOM, x + ROOM, FLOOR - BED, FLOOR - 1, Z - ROOM, Z + ROOM, Blocks.STONE);
        RoomCarver.carve(level, x - ROOM, x + ROOM, FLOOR, FLOOR + 10, Z - ROOM, Z + ROOM, Blocks.AIR);
        MockPlayer pilot = MockPlayers.join(helper, "shaped-refused");
        pilot.player().setGameMode(GameType.SURVIVAL);
        Vec3 at = new Vec3(x, FLOOR, Z);
        pilot.teleportTo(level, at, 0f, 0f);
        PodEntity[] pod = new PodEntity[1];
        FarChunks.awaitEntityTicking(helper, level, BlockPos.containing(at), () -> {
            pod[0] = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
            pod[0].setPos(at);
            level.addFreshEntity(pod[0]);
            pilot.player().startRiding(pod[0]);
        });
        int[] step = {0};
        // The mock records an action-bar message on its own tick, so a refusal is read on the next one.
        Component[] pending = new Component[1];
        TestTicks.each(helper, () -> {
            if (pod[0] == null) {
                return;
            }
            if (pending[0] != null) {
                if (pilot.actionBarMessages().isEmpty()
                        || !pilot.actionBarMessages().getLast().getString().equals(pending[0].getString())) {
                    throw failure(
                            helper,
                            "the refusal should read '%s', the bar says %s",
                            pending[0].getString(),
                            pilot.actionBarMessages());
                }
                pending[0] = null;
            }
            if (step[0] == 0 && pod[0].onGround()) {
                pending[0] =
                        expectRefusedAndKept(helper, pilot, "no Company rock under the pod", "charge_no_company_rock");
                level.setBlock(
                        cell(x, -2, 0),
                        HazardBlocks.COMPANY_ROCK.defaultBlockState(),
                        2); // under a stone: a blasting charge would clear it, a shaped one cannot start
                step[0] = 1;
            } else if (step[0] == 1) {
                pending[0] = expectRefusedAndKept(
                        helper, pilot, "Company rock that does not begin under the pod", "charge_no_company_rock");
                rock(level, x, 0, 3);
                level.setBlock(cell(x, -4, 0), Blocks.LAVA.defaultBlockState(), 2);
                step[0] = 2;
            } else if (step[0] == 2) {
                pending[0] = expectRefusedAndKept(helper, pilot, "a run that ends on lava", "charge_opens_lava");
                level.setBlock(cell(x, -4, 0), Blocks.STONE.defaultBlockState(), 2);
                pod[0].setHull(TUNING.chargeDamage());
                step[0] = 3;
            } else if (step[0] == 3) {
                pending[0] = expectRefusedAndKept(helper, pilot, "a hull no more than the blast", "charge_hull_low");
                pod[0].setHull(pod[0].maxHull());
                pod[0].setPos(pod[0].getX(), pod[0].getY() + 3, pod[0].getZ());
                step[0] = 4;
            } else if (step[0] == 4 && !pod[0].onGround()) {
                pending[0] = expectRefusedAndKept(helper, pilot, "a pod in the air", "charge_not_resting");
                step[0] = 5;
            } else if (step[0] == 5 && pod[0].onGround()) {
                if (!ChargeUse.use(pilot.player(), Consumable.SHAPED_CHARGE).consumesAction()) {
                    throw failure(
                            helper,
                            "the charge should be set once the pod rests on a run of Company rock; the bar says %s",
                            pilot.actionBarMessages());
                }
                if (pilot.player().getInventory().countItem(RepairRegistry.item(Consumable.SHAPED_CHARGE)) != 0) {
                    throw failure(helper, "a charge that is set is used up");
                }
                pending[0] = expectRefusedAndKept(helper, pilot, "a second charge while one burns", "charge_burning");
                step[0] = 6;
            } else if (step[0] == 6) {
                pod[0].discard();
                helper.succeed();
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aShapedChargeIsRefusedWhenOnlyTheFarColumnOfTheFootprintEndsOnLava(GameTestHelper helper) {
        int x = 4336;
        ServerLevel level = layer(helper);
        RoomCarver.carve(level, x - ROOM, x + ROOM, FLOOR - BED, FLOOR - 1, Z - ROOM, Z + ROOM, Blocks.STONE);
        RoomCarver.carve(level, x - ROOM, x + ROOM, FLOOR, FLOOR + 10, Z - ROOM, Z + ROOM, Blocks.AIR);
        rock(level, x - 1, -1, 3); // a run that ends on stone
        rock(level, x, 0, 2); // the last column of the footprint: its run ends on lava
        level.setBlock(cell(x, -3, 0), Blocks.LAVA.defaultBlockState(), 2);
        MockPlayer pilot = MockPlayers.join(helper, "shaped-far-lava");
        pilot.player().setGameMode(GameType.SURVIVAL);
        Vec3 at = new Vec3(x, FLOOR, Z);
        pilot.teleportTo(level, at, 0f, 0f);
        PodEntity[] pod = new PodEntity[1];
        FarChunks.awaitEntityTicking(helper, level, BlockPos.containing(at), () -> {
            pod[0] = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
            pod[0].setPos(at);
            level.addFreshEntity(pod[0]);
            pilot.player().startRiding(pod[0]);
        });
        boolean[] used = {false};
        TestTicks.each(helper, () -> {
            if (pod[0] == null || !pod[0].onGround()) {
                return;
            }
            if (!used[0]) {
                if (ChargeUse.use(pilot.player(), Consumable.SHAPED_CHARGE).consumesAction()) {
                    throw failure(helper, "a run that ends on lava in one column must refuse the charge");
                }
                used[0] = true;
                return;
            }
            String wanted = Component.translatable("message.deepcharter.repair.charge_opens_lava")
                    .getString();
            if (pilot.actionBarMessages().isEmpty()
                    || !pilot.actionBarMessages().getLast().getString().equals(wanted)) {
                throw failure(
                        helper, "the refusal should read '%s', the bar says %s", wanted, pilot.actionBarMessages());
            }
            if (pilot.player().getInventory().countItem(RepairRegistry.item(Consumable.SHAPED_CHARGE)) != 1) {
                throw failure(helper, "a refused charge must stay in the pack");
            }
            pod[0].discard();
            helper.succeed();
        });
    }

    /** Uses a shaped charge that must be refused and stay in the pack; returns the message that the bar should show. */
    private static Component expectRefusedAndKept(GameTestHelper helper, MockPlayer pilot, String what, String key) {
        if (ChargeUse.use(pilot.player(), Consumable.SHAPED_CHARGE).consumesAction()) {
            throw failure(helper, "a shaped charge should be refused with %s", what);
        }
        if (pilot.player().getInventory().countItem(RepairRegistry.item(Consumable.SHAPED_CHARGE)) != 1) {
            throw failure(helper, "a refused charge (%s) must stay in the pack", what);
        }
        pilot.player().getInventory().clearContent();
        return Component.translatable("message.deepcharter.repair." + key);
    }

    // assertionException(String, Object...) leaves the placeholders unfilled in the report.
    @FormatMethod
    private static RuntimeException failure(GameTestHelper helper, @FormatString String format, Object... args) {
        return helper.assertionException(Component.literal(String.format(format, args)));
    }

    private static ServerLevel layer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel().getServer().getLevel(LayerChain.dimension(1));
        if (level == null) {
            throw failure(helper, "dimension %s did not load", LayerChain.dimension(1));
        }
        return level;
    }
}
