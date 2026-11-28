package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
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
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for charges set sideways (#1613, ADR 0121): a pod square to the compass in front of a Company rock
 * face sets a blasting charge that clears the bore's face 2 slabs deep along the facing, or a shaped charge that
 * clears Company rock along the facing up to 8 slabs; backing out of {@code chargeReach} spares the hull, and the
 * refusals of a charge under the pod hold. The depths are literals here, apart from the tuning.
 *
 * <p>The scene: a Mole 2 x 2 x 3 resting on a stone bed at {@code (x, FLOOR, Z)}, its footprint {@code x - 1 .. x}
 * and {@code Z - 1 .. Z}. A test cell is {@code (depth, lateral, up)}: {@code depth} slabs out from the pod's face
 * along the facing, {@code lateral} 0 or 1 across it, {@code up} rows over the pod's feet.
 */
public class SidewaysChargeTest {
    private static final RepairTuning TUNING = RepairTuning.DEFAULT;
    private static final int Z = 5800;
    private static final int FLOOR = TestLayers.worldY(1, 60);
    private static final int ROOM = 14;
    /** Blocks the pod backs off: its box then ends over chargeReach from the face. It goes along the X axis, east, so
     * it stays in its chunk (x is a multiple of 16). */
    private static final double FAR = 9.5;

    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 400;
    private static final int BORE_HEIGHT = 3;
    private static final int BLASTING_DEPTH = 2;
    private static final int SHAPED_DEPTH = 8;

    /** What a test checks once the charge has blown, with the pod and the hull it had when the charge was set. */
    private interface Verify {
        void check(GameTestHelper helper, ServerLevel level, PodEntity pod, MockPlayer pilot, float hullAtSet);
    }

    /** The cell {@code depth} slabs out from the pod's face along {@code facing}, {@code lateral} across it and
     * {@code up} rows over the feet. */
    private static BlockPos cell(int x, Direction facing, int depth, int lateral, int up) {
        int along = switch (facing.getAxisDirection()) {
            case POSITIVE -> 1 + depth;
            case NEGATIVE -> -2 - depth;
        };
        int across = lateral - 1;
        return facing.getAxis() == Direction.Axis.X
                ? new BlockPos(x + along, FLOOR + up, Z + across)
                : new BlockPos(x + across, FLOOR + up, Z + along);
    }

    private static void put(ServerLevel level, BlockPos pos, Block block) {
        level.setBlock(pos, block.defaultBlockState(), 2);
    }

    /** Fills the whole face of the bore, {@code depth} slabs out, with {@code block}. */
    private static void fillSlab(ServerLevel level, int x, Direction facing, int depth, Block block) {
        for (int lateral = 0; lateral <= 1; lateral++) {
            for (int up = 0; up < BORE_HEIGHT; up++) {
                put(level, cell(x, facing, depth, lateral, up), block);
            }
        }
    }

    /**
     * Builds the scene at {@code x}, seats a pilot in a pod that rests on it and is turned {@code yawOff} degrees off
     * {@code facing}, uses {@code charge}, lets {@code afterUse} move the pod, waits until the fuse has run out and
     * runs {@code verify}.
     */
    private static void scene(
            GameTestHelper helper,
            int x,
            Direction facing,
            float yawOff,
            Consumable charge,
            Consumer<ServerLevel> build,
            Consumer<PodEntity> afterUse,
            Verify verify) {
        ServerLevel level = TestLayers.level(helper, 1);
        MockPlayer pilot = MockPlayers.join(helper, "side-charge-" + x);
        Vec3 at = new Vec3(x, FLOOR, Z);
        PodEntity[] pod = new PodEntity[1];
        FarChunks.awaitEntityTicking(helper, level, BlockPos.containing(at), () -> {
            RoomCarver.carve(level, x - ROOM, x + ROOM, FLOOR - 8, FLOOR - 1, Z - ROOM, Z + ROOM, Blocks.STONE);
            RoomCarver.carve(level, x - ROOM, x + ROOM, FLOOR, FLOOR + 10, Z - ROOM, Z + ROOM, Blocks.AIR);
            build.accept(level);
            pilot.teleportTo(level, at, 0f, 0f);
            pilot.player().setGameMode(GameType.SURVIVAL);
            pod[0] = TestEntities.create(PodRegistry.POD, level);
            pod[0].setPos(at);
            pod[0].setYRot(facing.toYRot() + yawOff);
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
                InteractionResult result = ChargeUse.use(pilot.player(), charge);
                if (!result.consumesAction()) {
                    throw failure(
                            helper,
                            "the charge should be set, the use gave %s and the bar says %s",
                            result,
                            pilot.actionBarMessages());
                }
                setAt[0] = pod[0].tickCount;
                afterUse.accept(pod[0]);
                return;
            }
            int burned = pod[0].tickCount - setAt[0];
            if (burned == TUNING.chargeFuseTicks() - 2
                    && level.getBlockState(cell(x, facing, 0, 0, 0)).isAir()) {
                throw failure(helper, "the charge blew %d ticks early", TUNING.chargeFuseTicks() - burned);
            }
            if (burned >= TUNING.chargeFuseTicks() + 2) {
                verify.check(helper, level, pod[0], pilot, hull[0]);
                pod[0].discard();
                helper.succeed();
            }
        });
    }

    private static void expectBlock(GameTestHelper helper, ServerLevel level, BlockPos pos, Block block, String what) {
        ChargeUse.expectBlock(helper, level, pos, block, what);
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

    /** The blasting charge's volume: the bore's face and the slab behind it clear, and the slab behind that, the row
     * over the bore and the column beside it stay. */
    private static void blastingScene(GameTestHelper helper, int x, Direction facing) {
        scene(
                helper,
                x,
                facing,
                0f,
                Consumable.BLASTING_CHARGE,
                level -> {
                    fillSlab(level, x, facing, 0, HazardBlocks.COMPANY_ROCK);
                    fillSlab(level, x, facing, 1, Blocks.STONE);
                    fillSlab(level, x, facing, 2, Blocks.STONE);
                    put(level, cell(x, facing, 1, 0, 0), HazardBlocks.COMPANY_ROCK);
                    put(level, cell(x, facing, 0, 0, BORE_HEIGHT), Blocks.STONE);
                    put(level, cell(x, facing, 0, -1, 0), Blocks.STONE);
                    put(level, cell(x, facing, 0, 2, 0), HazardBlocks.COMPANY_ROCK);
                },
                pod -> {},
                (helper2, level, pod, pilot, hull) -> {
                    for (int depth = 0; depth < BLASTING_DEPTH; depth++) {
                        for (int lateral = 0; lateral <= 1; lateral++) {
                            for (int up = 0; up < BORE_HEIGHT; up++) {
                                expectAir(helper2, level, cell(x, facing, depth, lateral, up), "inside the volume");
                            }
                        }
                    }
                    expectBlock(
                            helper2,
                            level,
                            cell(x, facing, BLASTING_DEPTH, 1, 1),
                            Blocks.STONE,
                            "the third slab stays");
                    expectBlock(
                            helper2,
                            level,
                            cell(x, facing, 0, 0, BORE_HEIGHT),
                            Blocks.STONE,
                            "the row over the bore stays");
                    expectBlock(
                            helper2,
                            level,
                            cell(x, facing, 0, 2, 0),
                            HazardBlocks.COMPANY_ROCK,
                            "Company rock beside the volume stays");
                    expectBlock(
                            helper2, level, cell(x, facing, 0, -1, 0), Blocks.STONE, "stone beside the volume stays");
                    expectBlock(
                            helper2,
                            level,
                            new BlockPos(
                                    cell(x, facing, 0, 0, 0).getX(),
                                    FLOOR - 1,
                                    cell(x, facing, 0, 0, 0).getZ()),
                            Blocks.STONE,
                            "the floor under the volume stays");
                });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aBlastingChargeSetSidewaysClearsTwoSlabsAlongTheFacingWest(GameTestHelper helper) {
        blastingScene(helper, 4096, Direction.WEST);
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aBlastingChargeSetSidewaysClearsTwoSlabsAlongTheFacingEast(GameTestHelper helper) {
        blastingScene(helper, 4160, Direction.EAST);
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aBlastingChargeSetSidewaysClearsTwoSlabsAlongTheFacingNorth(GameTestHelper helper) {
        blastingScene(helper, 4224, Direction.NORTH);
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aBlastingChargeSetSidewaysClearsTwoSlabsAlongTheFacingSouth(GameTestHelper helper) {
        blastingScene(helper, 4288, Direction.SOUTH);
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aShapedChargeSetSidewaysClearsCompanyRockToTheFirstOtherBlockAtMostEightSlabs(GameTestHelper helper) {
        int x = 4352;
        Direction facing = Direction.WEST;
        scene(
                helper,
                x,
                facing,
                0f,
                Consumable.SHAPED_CHARGE,
                level -> {
                    // Row 0, lateral 0: Company rock 10 deep, the shaped charge stops at 8.
                    for (int depth = 0; depth < 10; depth++) {
                        put(level, cell(x, facing, depth, 0, 0), HazardBlocks.COMPANY_ROCK);
                    }
                    // Row 0, lateral 1: 3 deep, then a slag brick, then Company rock that is not contiguous.
                    for (int depth = 0; depth < 3; depth++) {
                        put(level, cell(x, facing, depth, 1, 0), HazardBlocks.COMPANY_ROCK);
                    }
                    put(level, cell(x, facing, 3, 1, 0), SlagBrick.BLOCK);
                    put(level, cell(x, facing, 4, 1, 0), HazardBlocks.COMPANY_ROCK);
                    // Row 1, lateral 0: one slab of Company rock, then stone (the drill's, it stays).
                    put(level, cell(x, facing, 0, 0, 1), HazardBlocks.COMPANY_ROCK);
                    put(level, cell(x, facing, 1, 0, 1), Blocks.STONE);
                },
                pod -> {},
                (helper2, level, pod, pilot, hull) -> {
                    for (int depth = 0; depth < SHAPED_DEPTH; depth++) {
                        expectAir(helper2, level, cell(x, facing, depth, 0, 0), "the long run, slab " + depth);
                    }
                    expectBlock(
                            helper2,
                            level,
                            cell(x, facing, SHAPED_DEPTH, 0, 0),
                            HazardBlocks.COMPANY_ROCK,
                            "the ninth slab of the long run stays");
                    for (int depth = 0; depth < 3; depth++) {
                        expectAir(helper2, level, cell(x, facing, depth, 1, 0), "the short run, slab " + depth);
                    }
                    expectBlock(
                            helper2,
                            level,
                            cell(x, facing, 3, 1, 0),
                            SlagBrick.BLOCK,
                            "the slag brick that ends the run stays");
                    expectBlock(
                            helper2,
                            level,
                            cell(x, facing, 4, 1, 0),
                            HazardBlocks.COMPANY_ROCK,
                            "rock past the brick is not contiguous and stays");
                    expectAir(helper2, level, cell(x, facing, 0, 0, 1), "the one slab run");
                    expectBlock(
                            helper2, level, cell(x, facing, 1, 0, 1), Blocks.STONE, "stone after Company rock stays");
                });
    }

    /** Backing out of chargeReach during the fuse spares the hull, and the bar says to back away. */
    @GameTest(maxTicks = MAX_TICKS)
    public void aPodThatBacksOutOfTheReachDuringTheFuseTakesNoneAndTheBarSaysSo(GameTestHelper helper) {
        int x = 4416;
        Direction facing = Direction.WEST;
        scene(
                helper,
                x,
                facing,
                0f,
                Consumable.BLASTING_CHARGE,
                level -> fillSlab(level, x, facing, 0, HazardBlocks.COMPANY_ROCK),
                pod -> pod.setPos(pod.getX() + FAR, pod.getY(), pod.getZ()),
                (helper2, level, pod, pilot, hull) -> {
                    if (Math.abs(hull - pod.hull()) > 0.01) {
                        throw failure(
                                helper2, "a pod that backed out should lose no hull, it lost %.2f", hull - pod.hull());
                    }
                    expectAir(helper2, level, cell(x, facing, 0, 0, 0), "the charge still went off");
                    String line = Component.translatable("message.deepcharter.repair.charge_set_sideways", "3", 3)
                            .getString();
                    if (line.contains("message.deepcharter")
                            || pilot.actionBarMessages().stream()
                                    .noneMatch(message -> message.getString().equals(line))) {
                        throw failure(helper2, "the bar should say '%s', it said %s", line, pilot.actionBarMessages());
                    }
                    if (!line.contains("BACK AWAY 3 blocks") || !line.contains("face ahead")) {
                        throw failure(helper2, "the line should tell the pilot to back away: %s", line);
                    }
                });
    }

    /** A pod that stays at the face takes the blast, nearly whole: the face touches the volume. */
    @GameTest(maxTicks = MAX_TICKS)
    public void aPodThatStaysAtTheFaceTakesTheBlast(GameTestHelper helper) {
        int x = 4480;
        Direction facing = Direction.WEST;
        scene(
                helper,
                x,
                facing,
                0f,
                Consumable.BLASTING_CHARGE,
                level -> fillSlab(level, x, facing, 0, HazardBlocks.COMPANY_ROCK),
                pod -> {},
                (helper2, level, pod, pilot, hull) -> {
                    double lost = hull - pod.hull();
                    if (lost < TUNING.chargeDamage() * 0.9 || lost > TUNING.chargeDamage() + 0.01) {
                        throw failure(
                                helper2,
                                "a pod at the face should lose nearly the %.1f of the blast, it lost %.2f",
                                TUNING.chargeDamage(),
                                lost);
                    }
                });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aSidewaysBlastSparesWhatIsBuiltFluidsBedrockAndCrustAndTheDrillsStone(GameTestHelper helper) {
        int x = 4544;
        Direction facing = Direction.WEST;
        scene(
                helper,
                x,
                facing,
                0f,
                Consumable.BLASTING_CHARGE,
                level -> {
                    fillSlab(level, x, facing, 0, HazardBlocks.COMPANY_ROCK);
                    put(level, cell(x, facing, 1, 0, 0), Blocks.OAK_PLANKS);
                    put(level, cell(x, facing, 1, 1, 0), SlagBrick.BLOCK);
                    put(level, cell(x, facing, 0, 0, 1), Blocks.BEDROCK);
                    put(level, cell(x, facing, 0, 1, 1), LayerBlocks.BREACH_CRUST);
                    put(level, cell(x, facing, 0, 0, 2), LayerBlocks.GRAINED_CRUST);
                    put(level, cell(x, facing, 1, 1, 2), Blocks.LAVA);
                    // The lava is walled in but for the face: the blast's rule keeps it, not a flow.
                    put(level, cell(x, facing, 1, 1, BORE_HEIGHT), Blocks.STONE);
                    put(level, cell(x, facing, 1, 2, 2), Blocks.STONE);
                    put(level, cell(x, facing, 1, 1, 1), Blocks.STONE);
                    put(level, cell(x, facing, 1, 0, 2), Blocks.STONE);
                    put(level, cell(x, facing, 2, 1, 2), Blocks.STONE);
                },
                pod -> pod.setPos(pod.getX() + FAR, pod.getY(), pod.getZ()),
                (helper2, level, pod, pilot, hull) -> {
                    expectBlock(helper2, level, cell(x, facing, 1, 0, 0), Blocks.OAK_PLANKS, "planks");
                    expectBlock(helper2, level, cell(x, facing, 1, 1, 0), SlagBrick.BLOCK, "a slag brick");
                    expectBlock(helper2, level, cell(x, facing, 0, 0, 1), Blocks.BEDROCK, "bedrock");
                    expectBlock(helper2, level, cell(x, facing, 0, 1, 1), LayerBlocks.BREACH_CRUST, "breach crust");
                    expectBlock(helper2, level, cell(x, facing, 0, 0, 2), LayerBlocks.GRAINED_CRUST, "grained crust");
                    expectBlock(helper2, level, cell(x, facing, 1, 1, 2), Blocks.LAVA, "lava");
                    expectAir(helper2, level, cell(x, facing, 0, 0, 0), "the face itself clears");
                });
    }

    /** What a refused use of {@code charge} says, and that it stays in the pack and leaves the rock. */
    private static void refusal(
            GameTestHelper helper,
            int x,
            Direction facing,
            float yawOff,
            Consumable charge,
            Consumer<ServerLevel> build,
            String key,
            BlockPos rock) {
        refusal(helper, x, facing, yawOff, charge, build, pod -> {}, key, rock);
    }

    /** As {@link #refusal}, with {@code prepare} run on the resting pod before the use, and the refusal's arguments. */
    private static void refusal(
            GameTestHelper helper,
            int x,
            Direction facing,
            float yawOff,
            Consumable charge,
            Consumer<ServerLevel> build,
            Consumer<PodEntity> prepare,
            String key,
            BlockPos rock,
            Object... args) {
        ServerLevel level = TestLayers.level(helper, 1);
        MockPlayer pilot = MockPlayers.join(helper, "side-refused-" + x);
        Vec3 at = new Vec3(x, FLOOR, Z);
        PodEntity[] pod = new PodEntity[1];
        FarChunks.awaitEntityTicking(helper, level, BlockPos.containing(at), () -> {
            RoomCarver.carve(level, x - ROOM, x + ROOM, FLOOR - 8, FLOOR - 1, Z - ROOM, Z + ROOM, Blocks.STONE);
            RoomCarver.carve(level, x - ROOM, x + ROOM, FLOOR, FLOOR + 10, Z - ROOM, Z + ROOM, Blocks.AIR);
            build.accept(level);
            pilot.teleportTo(level, at, 0f, 0f);
            pilot.player().setGameMode(GameType.SURVIVAL);
            pod[0] = TestEntities.create(PodRegistry.POD, level);
            pod[0].setPos(at);
            pod[0].setYRot(facing.toYRot() + yawOff);
            level.addFreshEntity(pod[0]);
            pilot.player().startRiding(pod[0]);
        });
        int[] step = {0};
        TestTicks.each(helper, () -> {
            if (pod[0] == null || !pod[0].onGround()) {
                return;
            }
            if (step[0] == 0) {
                prepare.accept(pod[0]);
                if (ChargeUse.use(pilot.player(), charge).consumesAction()) {
                    throw failure(helper, "the charge should be refused with %s", key);
                }
                if (pilot.player().getInventory().countItem(RepairRegistry.item(charge)) != 1) {
                    throw failure(helper, "a refused charge (%s) must stay in the pack", key);
                }
                step[0] = 1;
                return;
            }
            // The mock records an action-bar message on its own tick, so a refusal is read on the next one.
            String line = Component.translatable("message.deepcharter.repair." + key, args)
                    .getString();
            if (pilot.actionBarMessages().isEmpty()
                    || !pilot.actionBarMessages().getLast().getString().equals(line)) {
                throw failure(helper, "the refusal should read '%s', the bar says %s", line, pilot.actionBarMessages());
            }
            if (step[0]++ < 1 + TUNING.chargeFuseTicks() + 2) {
                if (level.getBlockState(rock).isAir()) {
                    throw failure(helper, "a refused charge must clear nothing, %s is air", rock);
                }
                return;
            }
            pod[0].discard();
            helper.succeed();
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aShapedChargeSidewaysIsRefusedWhenARunEndsOnLava(GameTestHelper helper) {
        int x = 4608;
        Direction facing = Direction.WEST;
        refusal(
                helper,
                x,
                facing,
                0f,
                Consumable.SHAPED_CHARGE,
                level -> {
                    put(level, cell(x, facing, 0, 0, 0), HazardBlocks.COMPANY_ROCK);
                    put(level, cell(x, facing, 1, 0, 0), HazardBlocks.COMPANY_ROCK);
                    put(level, cell(x, facing, 2, 0, 0), Blocks.LAVA);
                },
                "charge_opens_lava",
                cell(x, facing, 0, 0, 0));
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aBlastingChargeIsRefusedFacingARockFaceWithNoCompanyRockAndNoneUnderThePod(GameTestHelper helper) {
        int x = 4672;
        Direction facing = Direction.WEST;
        refusal(
                helper,
                x,
                facing,
                0f,
                Consumable.BLASTING_CHARGE,
                level -> fillSlab(level, x, facing, 0, Blocks.STONE),
                "charge_no_company_rock",
                cell(x, facing, 0, 0, 0));
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aShapedChargeIsRefusedFacingARockFaceWithNoCompanyRock(GameTestHelper helper) {
        int x = 4736;
        Direction facing = Direction.NORTH;
        refusal(
                helper,
                x,
                facing,
                0f,
                Consumable.SHAPED_CHARGE,
                level -> fillSlab(level, x, facing, 0, Blocks.STONE),
                "charge_no_company_rock",
                cell(x, facing, 0, 0, 0));
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aPodThatIsNotSquareToTheCompassIsToldToSquareUpBeforeASidewaysCharge(GameTestHelper helper) {
        int x = 4800;
        Direction facing = Direction.WEST;
        refusal(
                helper,
                x,
                facing,
                30f,
                Consumable.BLASTING_CHARGE,
                level -> fillSlab(level, x, facing, 0, HazardBlocks.COMPANY_ROCK),
                "charge_not_square",
                cell(x, facing, 0, 0, 0));
    }

    /**
     * The hull guard of a sideways charge counts the blast alone, as nothing opens under the pod, and its line says
     * so: no drop into a hole.
     */
    @GameTest(maxTicks = MAX_TICKS)
    public void aSidewaysChargeIsRefusedOnAHullNoMoreThanTheBlastAndTheLineNamesNoDrop(GameTestHelper helper) {
        int x = 4864;
        Direction facing = Direction.EAST;
        refusal(
                helper,
                x,
                facing,
                0f,
                Consumable.BLASTING_CHARGE,
                level -> fillSlab(level, x, facing, 0, HazardBlocks.COMPANY_ROCK),
                pod -> pod.setHull(15f),
                "charge_hull_low_sideways",
                cell(x, facing, 0, 0, 0),
                "20.0",
                "15.0");
    }

    /** Puts Company rock in the slab under the pod's footprint. */
    private static void rockUnderPod(ServerLevel level, int x) {
        for (BlockPos cell : BlockPos.betweenClosed(x - 1, FLOOR - 1, Z - 1, x, FLOOR - 1, Z)) {
            put(level, cell.immutable(), HazardBlocks.COMPANY_ROCK);
        }
    }

    /** Rock ahead beats rock below: a square pod with Company rock both ahead and under it blasts the face. */
    @GameTest(maxTicks = MAX_TICKS)
    public void aSquarePodWithCompanyRockAheadAndBelowBlastsTheFaceAhead(GameTestHelper helper) {
        int x = 4928;
        Direction facing = Direction.SOUTH;
        scene(
                helper,
                x,
                facing,
                0f,
                Consumable.BLASTING_CHARGE,
                level -> {
                    fillSlab(level, x, facing, 0, HazardBlocks.COMPANY_ROCK);
                    rockUnderPod(level, x);
                },
                pod -> pod.setPos(pod.getX() + FAR, pod.getY(), pod.getZ()),
                (helper2, level, pod, pilot, hull) -> {
                    expectAir(helper2, level, cell(x, facing, 0, 0, 0), "the face ahead clears");
                    expectAir(helper2, level, cell(x, facing, 0, 1, 2), "the face ahead clears");
                    for (BlockPos cell : BlockPos.betweenClosed(x - 1, FLOOR - 1, Z - 1, x, FLOOR - 1, Z)) {
                        expectBlock(
                                helper2,
                                level,
                                cell.immutable(),
                                HazardBlocks.COMPANY_ROCK,
                                "the slab under the pod stays");
                    }
                });
    }

    /**
     * Off the compass with Company rock ahead and under it, the charge does not guess: it is refused with a line that
     * names both ways out, and the slab under the pod stays.
     */
    @GameTest(maxTicks = MAX_TICKS)
    public void anOffAnglePodWithCompanyRockAheadAndBelowIsRefusedWithBothWaysOut(GameTestHelper helper) {
        int x = 4992;
        Direction facing = Direction.WEST;
        refusal(
                helper,
                x,
                facing,
                30f,
                Consumable.BLASTING_CHARGE,
                level -> {
                    fillSlab(level, x, facing, 0, HazardBlocks.COMPANY_ROCK);
                    rockUnderPod(level, x);
                },
                "charge_not_square",
                new BlockPos(x, FLOOR - 1, Z));
        String line = Component.translatable("message.deepcharter.repair.charge_not_square")
                .getString();
        if (!line.contains("north, east, south or west") || !line.contains("blast below")) {
            throw failure(helper, "the line should name both ways out: %s", line);
        }
    }
}
