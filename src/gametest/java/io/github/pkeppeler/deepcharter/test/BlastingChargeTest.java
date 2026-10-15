package io.github.pkeppeler.deepcharter.test;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.ore.SlagBrick;
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
 * Server GameTests for the blasting charge (#509, ADR 0015): set from the seat on the slab under the pod, a fuse, then a blast that
 * clears Company rock and the natural rock round it, and hurts a pod that is too close. Each test has its own X in layer 1.
 *
 * <p>The scene: a Mole 2 x 2 resting on a stone bed, at {@code (x, FLOOR, Z)}. The blast volume is the bore square, so {@code x - 1 .. x} and {@code Z - 1 .. Z}, the rows {@code FLOOR - 2 .. FLOOR - 1}.
 */
public class BlastingChargeTest {
    private static final RepairTuning TUNING = RepairTuning.DEFAULT;
    private static final int Z = 5200;
    private static final int FLOOR = 60;
    private static final int ROOM = 14;
    /** Blocks east that the pod is moved to leave the blast: its box then starts 6.55 past the volume, over chargeReach, and the pod stays in its chunk (x is a multiple of 16). */
    private static final double FAR = 9.5;

    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 400;

    /** What a test checks once the charge has blown, with the pod and the hull it had when the charge was set. */
    private interface Verify {
        void check(GameTestHelper helper, ServerLevel level, PodEntity pod, MockPlayer pilot, float hullAtSet);
    }

    private static BlockPos cell(int x, int dy, int dz) {
        return new BlockPos(x, FLOOR + dy, Z + dz);
    }

    /**
     * Builds the scene at {@code x}, seats a pilot in a pod that rests on it, sets a charge, lets {@code afterSet} move the pod, waits
     * until the fuse has run out and then runs {@code verify}. The Company rock under the pod is always there; {@code build} adds the rest.
     */
    private static void scene(
            GameTestHelper helper, int x, Consumer<ServerLevel> build, Consumer<PodEntity> afterSet, Verify verify) {
        ServerLevel level = layer(helper);
        RoomCarver.carve(level, x - ROOM, x + ROOM, FLOOR - 8, FLOOR - 1, Z - ROOM, Z + ROOM, Blocks.STONE);
        RoomCarver.carve(level, x - ROOM, x + ROOM, FLOOR, FLOOR + 10, Z - ROOM, Z + ROOM, Blocks.AIR);
        level.setBlock(cell(x, -1, 0), HazardBlocks.COMPANY_ROCK.defaultBlockState(), 2);
        build.accept(level);
        MockPlayer pilot = MockPlayers.join(helper, "charge-" + x);
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
                InteractionResult result = ChargeUse.use(pilot.player(), Consumable.BLASTING_CHARGE);
                if (!result.consumesAction()) {
                    throw failure(
                            helper,
                            "the charge should be set, the use gave %s and the bar says %s",
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
            int burned = pod[0].tickCount - setAt[0];
            if (burned == TUNING.chargeFuseTicks() - 2
                    && level.getBlockState(cell(x, -1, 0)).isAir()) {
                throw failure(helper, "the charge blew %d ticks early", TUNING.chargeFuseTicks() - burned);
            }
            if (burned >= TUNING.chargeFuseTicks() + 2) {
                verify.check(helper, level, pod[0], pilot, hull[0]);
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

    private static void expectHullLost(
            GameTestHelper helper, PodEntity pod, float hullAtSet, double expected, String what) {
        double lost = hullAtSet - pod.hull();
        if (Math.abs(lost - expected) > 0.01) {
            throw failure(helper, "%s: the hull should lose %.2f, it lost %.2f", what, expected, lost);
        }
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aChargeClearsCompanyRockAndNaturalRockInItsVolumeAndNothingElse(GameTestHelper helper) {
        int x = 3008;
        scene(
                helper,
                x,
                level -> {
                    level.setBlock(
                            cell(x - 1, -2, -1),
                            HazardBlocks.COMPANY_ROCK.defaultBlockState(),
                            2); // a corner of the volume
                    level.setBlock(
                            cell(x + 1, -2, 0), HazardBlocks.COMPANY_ROCK.defaultBlockState(), 2); // just outside it
                    level.setBlock(cell(x + 1, -1, 0), Blocks.STONE.defaultBlockState(), 2);
                    level.setBlock(cell(x, -3, 0), Blocks.STONE.defaultBlockState(), 2); // one row under it
                },
                pod -> pod.setPos(pod.getX() + FAR, pod.getY(), pod.getZ()),
                (helper2, level, pod, pilot, hull) -> {
                    for (int dx = -1; dx <= 0; dx++) {
                        for (int dz = -1; dz <= 0; dz++) {
                            for (int dy = -2; dy <= -1; dy++) {
                                expectAir(helper2, level, cell(x + dx, dy, dz), "inside the volume");
                            }
                        }
                    }
                    ChargeUse.expectBlock(
                            helper2,
                            level,
                            cell(x + 1, -2, 0),
                            HazardBlocks.COMPANY_ROCK,
                            "Company rock beside the volume stays");
                    ChargeUse.expectBlock(
                            helper2, level, cell(x + 1, -1, 0), Blocks.STONE, "stone beside the volume stays");
                    ChargeUse.expectBlock(helper2, level, cell(x, -3, 0), Blocks.STONE, "stone under the volume stays");
                });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aChargeSparesWhatIsBuiltFluidsBedrockAndVentsNoGas(GameTestHelper helper) {
        int x = 3072;
        scene(
                helper,
                x,
                level -> {
                    level.setBlock(cell(x - 1, -2, -1), Blocks.OAK_PLANKS.defaultBlockState(), 2);
                    level.setBlock(cell(x - 1, -1, 0), SlagBrick.BLOCK.defaultBlockState(), 2);
                    level.setBlock(cell(x, -1, -1), Blocks.BEDROCK.defaultBlockState(), 2);
                    level.setBlock(cell(x, -2, -1), Blocks.LAVA.defaultBlockState(), 2);
                    level.setBlock(
                            cell(x, -2, 0), HazardBlocks.GAS_POCKET.defaultBlockState(), 2); // a corner of the volume
                    level.setBlock(
                            cell(x + 1, -2, 0), Blocks.STONE.defaultBlockState(), 2); // a gas blast would clear it
                },
                pod -> pod.setPos(pod.getX() + FAR, pod.getY(), pod.getZ()),
                (helper2, level, pod, pilot, hull) -> {
                    ChargeUse.expectBlock(helper2, level, cell(x - 1, -2, -1), Blocks.OAK_PLANKS, "planks");
                    ChargeUse.expectBlock(helper2, level, cell(x - 1, -1, 0), SlagBrick.BLOCK, "a slag brick");
                    ChargeUse.expectBlock(helper2, level, cell(x, -1, -1), Blocks.BEDROCK, "bedrock");
                    ChargeUse.expectBlock(helper2, level, cell(x, -2, -1), Blocks.LAVA, "lava");
                    expectAir(helper2, level, cell(x, -2, 0), "the gas pocket goes with the blast");
                    ChargeUse.expectBlock(
                            helper2, level, cell(x + 1, -2, 0), Blocks.STONE, "a pocket in the blast vents no more");
                });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aPodOnTheBlastTakesTheWholeCharge(GameTestHelper helper) {
        scene(
                helper,
                3136,
                level -> {},
                pod -> {},
                (helper2, level, pod, pilot, hull) ->
                        expectHullLost(helper2, pod, hull, TUNING.chargeDamage(), "a pod that did not move"));
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aPodBeyondTheReachTakesNone(GameTestHelper helper) {
        scene(
                helper,
                3200,
                level -> {},
                pod -> pod.setPos(pod.getX() + FAR, pod.getY(), pod.getZ()),
                (helper2, level, pod, pilot, hull) -> expectHullLost(helper2, pod, hull, 0, "a pod beyond the reach"));
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void theDamageFallsInStraightLineWithTheDistance(GameTestHelper helper) {
        // The pod's box (1.9 wide) starts exactly 1.5 blocks past the volume's edge (x + 1): half of chargeReach (3).
        scene(
                helper,
                3264,
                level -> {},
                pod -> pod.setPos(pod.getX() + 3.45, pod.getY(), pod.getZ()),
                (helper2, level, pod, pilot, hull) -> expectHullLost(
                        helper2,
                        pod,
                        hull,
                        TUNING.chargeDamage() * (1 - 1.5 / TUNING.chargeReach()),
                        "a pod 1.5 blocks off"));
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aChargeCountsDownOnTheActionBar(GameTestHelper helper) {
        scene(
                helper,
                3328,
                level -> {},
                pod -> pod.setPos(pod.getX() + FAR, pod.getY(), pod.getZ()),
                (helper2, level, pod, pilot, hull) -> {
                    String first = Component.translatable("message.deepcharter.repair.charge_set", 3)
                            .getString();
                    String second = Component.translatable("message.deepcharter.repair.charge_count", 2)
                            .getString();
                    if (pilot.actionBarMessages().stream()
                                            .filter(message ->
                                                    message.getString().equals(first))
                                            .count()
                                    != 1
                            || pilot.actionBarMessages().stream()
                                            .filter(message ->
                                                    message.getString().equals("Charge: blast in 3"))
                                            .count()
                                    != 0) {
                        throw failure(
                                helper2,
                                "the first count should print once, as the set warning: %s",
                                pilot.actionBarMessages());
                    }
                    if (pilot.actionBarMessages().stream()
                            .noneMatch(message -> message.getString().equals(second))) {
                        throw failure(
                                helper2, "the bar should go on to '%s', it said %s", second, pilot.actionBarMessages());
                    }
                });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aChargeIsRefusedAndKeptWhenThePodIsNotReadyForIt(GameTestHelper helper) {
        int x = 3392;
        ServerLevel level = layer(helper);
        RoomCarver.carve(level, x - ROOM, x + ROOM, FLOOR - 8, FLOOR - 1, Z - ROOM, Z + ROOM, Blocks.STONE);
        RoomCarver.carve(level, x - ROOM, x + ROOM, FLOOR, FLOOR + 10, Z - ROOM, Z + ROOM, Blocks.AIR);
        MockPlayer pilot = MockPlayers.join(helper, "charge-refused");
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
                level.setBlock(cell(x, -1, 0), HazardBlocks.COMPANY_ROCK.defaultBlockState(), 2);
                step[0] = 10;
            } else if (step[0] == 10) {
                pod[0].setHull(TUNING.chargeDamage());
                pending[0] = expectRefusedAndKept(helper, pilot, "a hull no more than the blast", "charge_hull_low");
                pod[0].setHull(pod[0].maxHull());
                step[0] = 11;
            } else if (step[0] == 11) {
                pod[0].setPos(pod[0].getX(), pod[0].getY() + 3, pod[0].getZ());
                step[0] = 1;
            } else if (step[0] == 1 && !pod[0].onGround()) {
                pending[0] = expectRefusedAndKept(helper, pilot, "a pod in the air", "charge_not_resting");
                step[0] = 2;
            } else if (step[0] == 2 && pod[0].onGround()) {
                if (!ChargeUse.use(pilot.player(), Consumable.BLASTING_CHARGE).consumesAction()) {
                    throw failure(
                            helper,
                            "the charge should be set once the pod rests on Company rock; the bar says %s",
                            pilot.actionBarMessages());
                }
                if (pilot.player().getInventory().countItem(RepairRegistry.item(Consumable.BLASTING_CHARGE)) != 0) {
                    throw failure(helper, "a charge that is set is used up");
                }
                pending[0] = expectRefusedAndKept(helper, pilot, "a second charge while one burns", "charge_burning");
                step[0] = 3;
            } else if (step[0] == 3) {
                pod[0].discard();
                helper.succeed();
            }
        });
    }

    /** Uses a charge that must be refused and stay in the pack; returns the message that the bar should show. */
    private static Component expectRefusedAndKept(GameTestHelper helper, MockPlayer pilot, String what, String key) {
        if (ChargeUse.use(pilot.player(), Consumable.BLASTING_CHARGE).consumesAction()) {
            throw failure(helper, "a charge should be refused with %s", what);
        }
        if (pilot.player().getInventory().countItem(RepairRegistry.item(Consumable.BLASTING_CHARGE)) != 1) {
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
