package io.github.pkeppeler.deepcharter.test;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.ore.CompanyRockBlock;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.ore.OreBlock;
import io.github.pkeppeler.deepcharter.ore.OreBlock.Host;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairTuning;
import io.github.pkeppeler.deepcharter.test.support.ChargeUse;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * #539, ADR 0056: Company rock looks the part of its layer (the {@code host} state, as an ore's, ADR 0055: worldgen sets it, and in layer 2 the joints
 * glow) and its joints widen in two steps while a charge burns, before the blast clears it. A block saved with no state loads unwidened in packed
 * shale, and a widened joint that no blast follows closes again.
 */
public class CompanyRockLookTest {
    private static final RepairTuning TUNING = RepairTuning.DEFAULT;
    private static final int FIRST_CHUNK = 470;
    private static final int SIDE = 3;
    /** The Company rock the zone fill places across {@link #SIDE} x {@link #SIDE} chunks must be at least this many in each layer, or the check proves nothing. */
    private static final int AT_LEAST = 20;

    private static final int X = 3456;
    private static final int Z = 5300;
    private static final int FLOOR = 60;
    private static final int ROOM = 14;
    /** Blocks east that the pod is moved to leave the blast, as in {@code BlastingChargeTest}. */
    private static final double FAR = 9.5;
    /** Ticks either side of a step that the pod's own count may be off from the fuse's. */
    private static final int SLACK = 3;

    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 400;

    private static Block companyRock() {
        return HazardBlocks.COMPANY_ROCK;
    }

    private static CompanyRockBlock company() {
        return (CompanyRockBlock) HazardBlocks.COMPANY_ROCK;
    }

    private static Host expectedHost(int layer) {
        return layer == 1 ? Host.PACKED_SHALE : Host.GREEN_SHALE;
    }

    private static ServerLevel layer(GameTestHelper helper, int layer) {
        ServerLevel level = ClientLight.serverLevel(helper.getLevel().getServer(), LayerChain.dimension(layer));
        if (level == null) {
            throw failure(helper, "dimension %s did not load", LayerChain.dimension(layer));
        }
        return level;
    }

    @GameTest(maxTicks = 6000)
    public void generatedCompanyRockInEachLayerSitsInThatLayersRock(GameTestHelper helper) {
        for (int layer = 1; layer <= 2; layer++) {
            ServerLevel level = layer(helper, layer);
            Map<Host, Integer> found = new EnumMap<>(Host.class);
            for (int cx = FIRST_CHUNK; cx < FIRST_CHUNK + SIDE; cx++) {
                for (int cz = FIRST_CHUNK; cz < FIRST_CHUNK + SIDE; cz++) {
                    level.getChunk(cx, cz, ChunkStatus.FULL);
                    for (int x = cx << 4; x < (cx << 4) + 16; x++) {
                        for (int z = cz << 4; z < (cz << 4) + 16; z++) {
                            for (int y = level.getMinY(); y < level.getMinY() + level.getHeight(); y++) {
                                BlockState state = level.getBlockState(new BlockPos(x, y, z));
                                if (state.is(companyRock())) {
                                    found.merge(state.getValue(OreBlock.HOST), 1, Integer::sum);
                                    if (state.getValue(CompanyRockBlock.CRACK) != 0) {
                                        throw failure(helper, "generated Company rock at %s is widened", state);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            int total = found.values().stream().mapToInt(Integer::intValue).sum();
            int right = found.getOrDefault(expectedHost(layer), 0);
            if (total < AT_LEAST || right != total) {
                throw failure(
                        helper,
                        "layer_%s should hold at least %s Company rock, all in %s, it holds %s",
                        layer,
                        AT_LEAST,
                        expectedHost(layer),
                        found);
            }
        }
        helper.succeed();
    }

    @GameTest
    public void aCompanyRockItemPlacedInALayerTakesThatLayersRock(GameTestHelper helper) {
        MockPlayer mock = MockPlayers.join(helper, "company-rock-place");
        for (int layer = 1; layer <= 2; layer++) {
            ServerLevel level = layer(helper, layer);
            BlockPos floor = new BlockPos(7500, 80, 3000 + layer * 8);
            mock.teleportTo(level, Vec3.atBottomCenterOf(floor.above(4)), 0, 0);
            mock.player().setGameMode(GameType.SURVIVAL);
            RoomCarver.carve(
                    level,
                    floor.getX(),
                    floor.getX(),
                    floor.getY() - 2,
                    floor.getY(),
                    floor.getZ(),
                    floor.getZ(),
                    Blocks.STONE);
            RoomCarver.carve(
                    level,
                    floor.getX(),
                    floor.getX(),
                    floor.getY() + 1,
                    floor.getY() + 3,
                    floor.getZ(),
                    floor.getZ(),
                    Blocks.AIR);
            BlockPos at = floor.above();
            ItemStack stack = new ItemStack(companyRock().asItem());
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(at.below()), Direction.UP, at.below(), false);
            InteractionResult result = ((BlockItem) stack.getItem())
                    .place(new BlockPlaceContext(level, mock.player(), InteractionHand.MAIN_HAND, stack, hit));
            BlockState placed = level.getBlockState(at);
            if (!result.consumesAction()
                    || !placed.is(companyRock())
                    || placed.getValue(OreBlock.HOST) != expectedHost(layer)
                    || placed.getValue(CompanyRockBlock.CRACK) != 0) {
                throw failure(
                        helper,
                        "Company rock placed in layer_%s should sit in %s, unwidened, it is %s (%s)",
                        layer,
                        expectedHost(layer),
                        placed,
                        result);
            }
        }
        helper.succeed();
    }

    /** A world saved before the states existed names the block and no properties; it must still load, in packed shale and unwidened. */
    @GameTest
    public void companyRockSavedWithoutStatesLoadsUnwidenedInPackedShale(GameTestHelper helper) {
        BlockState widenedGreen = company()
                .in(LayerBlocks.GREEN_SHALE.defaultBlockState())
                .setValue(CompanyRockBlock.CRACK, CompanyRockBlock.MAX_CRACK);
        Tag saved = BlockState.CODEC.encodeStart(NbtOps.INSTANCE, widenedGreen).getOrThrow();
        CompoundTag legacy = ((CompoundTag) saved).copy();
        legacy.keySet().removeIf(key -> !key.equals("Name") && !key.equals("id"));
        BlockState old = BlockState.CODEC.parse(NbtOps.INSTANCE, legacy).getOrThrow();
        if (old != companyRock().defaultBlockState()
                || old.getValue(OreBlock.HOST) != Host.PACKED_SHALE
                || old.getValue(CompanyRockBlock.CRACK) != 0) {
            throw failure(
                    helper, "Company rock saved with no states should load in packed shale, unwidened: it is %s", old);
        }
        BlockState back = BlockState.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        if (back != widenedGreen) {
            throw failure(helper, "widened Company rock in green shale should survive a save, it came back %s", back);
        }
        helper.succeed();
    }

    /** The cell of the Company rock under the pod, which a charge set on the slab there clears. */
    private static BlockPos rockCell() {
        return new BlockPos(X, FLOOR - 1, Z);
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aChargeWidensTheJointsInTwoStepsAndThenClearsTheRock(GameTestHelper helper) {
        ServerLevel level = layer(helper, 2);
        RoomCarver.carve(level, X - ROOM, X + ROOM, FLOOR - 8, FLOOR - 1, Z - ROOM, Z + ROOM, LayerBlocks.GREEN_SHALE);
        RoomCarver.carve(level, X - ROOM, X + ROOM, FLOOR, FLOOR + 10, Z - ROOM, Z + ROOM, Blocks.AIR);
        BlockState rock = company().in(LayerBlocks.GREEN_SHALE.defaultBlockState());
        level.setBlock(rockCell(), rock, 2);
        MockPlayer pilot = MockPlayers.join(helper, "company-rock-widen");
        pilot.player().setGameMode(GameType.SURVIVAL);
        Vec3 at = new Vec3(X, FLOOR, Z);
        pilot.teleportTo(level, at, 0f, 0f);
        PodEntity[] pod = new PodEntity[1];
        FarChunks.awaitEntityTicking(helper, level, BlockPos.containing(at), () -> {
            pod[0] = TestEntities.create(PodRegistry.POD, level);
            pod[0].setPos(at);
            level.addFreshEntity(pod[0]);
            if (!pilot.player().startRiding(pod[0])) {
                throw failure(helper, "the pilot could not mount the pod");
            }
        });
        int fuse = TUNING.chargeFuseTicks();
        int[] setAt = {-1};
        int[] firstSeen = new int[CompanyRockBlock.MAX_CRACK + 1];
        TestTicks.each(helper, () -> {
            if (pod[0] == null || !pod[0].onGround()) {
                return;
            }
            if (setAt[0] < 0) {
                InteractionResult result = ChargeUse.use(pilot.player(), Consumable.BLASTING_CHARGE);
                if (!result.consumesAction()) {
                    throw failure(helper, "the charge should be set, the use gave %s", result);
                }
                setAt[0] = pod[0].tickCount;
                pod[0].setPos(pod[0].getX() + FAR, pod[0].getY(), pod[0].getZ());
                return;
            }
            int burned = pod[0].tickCount - setAt[0];
            BlockState now = level.getBlockState(rockCell());
            if (now.isAir()) {
                if (burned < fuse - SLACK) {
                    throw failure(helper, "the rock cleared %d ticks early", fuse - burned);
                }
                for (int stage = 1; stage <= CompanyRockBlock.MAX_CRACK; stage++) {
                    int due = fuse * (CompanyRockBlock.MAX_CRACK + 1 - stage) / (CompanyRockBlock.MAX_CRACK + 1);
                    int step = fuse - due;
                    if (firstSeen[stage] == 0 || Math.abs(firstSeen[stage] - step) > SLACK) {
                        throw failure(
                                helper,
                                "the joints should widen to step %d about %d ticks into the fuse, first seen at %d",
                                stage,
                                step,
                                firstSeen[stage]);
                    }
                }
                pod[0].discard();
                helper.succeed();
                return;
            }
            if (!now.is(companyRock()) || now.getValue(OreBlock.HOST) != Host.GREEN_SHALE) {
                throw failure(helper, "the rock changed to %s while the charge burned", now);
            }
            int crack = now.getValue(CompanyRockBlock.CRACK);
            if (crack > 0 && firstSeen[crack] == 0) {
                firstSeen[crack] = burned;
            }
            if (crack > 0 && firstSeen[crack - 1] == 0 && crack > 1) {
                throw failure(
                        helper, "the joints skipped a step: crack %d with no step %d before it", crack, crack - 1);
            }
            if (burned > fuse + 100) {
                throw failure(helper, "the charge never cleared the rock: %s after %d ticks", now, burned);
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void widenedJointsCloseAgainWhenNoBlastFollows(GameTestHelper helper) {
        ServerLevel level = layer(helper, 2);
        BlockPos pos = new BlockPos(X + 64, FLOOR, Z + 64);
        RoomCarver.carve(
                level,
                pos.offset(-2, -2, -2),
                pos.offset(2, 2, 2),
                company().in(LayerBlocks.GREEN_SHALE.defaultBlockState()));
        FarChunks.awaitEntityTicking(helper, level, pos, () -> {
            CompanyRockBlock.widen(level, pos, CompanyRockBlock.MAX_CRACK, 5);
            if (level.getBlockState(pos).getValue(CompanyRockBlock.CRACK) != CompanyRockBlock.MAX_CRACK) {
                throw failure(helper, "the joints should be widened: %s", level.getBlockState(pos));
            }
        });
        TestTicks.each(helper, () -> {
            BlockState now = level.getBlockState(pos);
            if (now.is(companyRock()) && now.getValue(CompanyRockBlock.CRACK) == 0) {
                if (now.getValue(OreBlock.HOST) != Host.GREEN_SHALE) {
                    throw failure(helper, "the joints closed but the rock changed: %s", now);
                }
                helper.succeed();
            }
        });
    }

    /** A cell the watched charge must leave alone while it burns: still this block, and for Company rock still unwidened. */
    private record Bystander(BlockPos pos, Block block, String why) {}

    private static void checkBystanders(GameTestHelper helper, ServerLevel level, List<Bystander> bystanders) {
        for (Bystander b : bystanders) {
            BlockState now = level.getBlockState(b.pos());
            if (!now.is(b.block()) || (now.is(companyRock()) && now.getValue(CompanyRockBlock.CRACK) != 0)) {
                throw failure(helper, "%s: %s should be untouched, it is %s", b.why(), b.pos(), now);
            }
        }
    }

    /**
     * Sets {@code charge} under a Mole resting at {@code (x, FLOOR, z)}, moves the pod clear, and calls {@code during} every tick of the fuse (with the
     * ticks burned) and {@code after} once the blast is past, which ends the test.
     */
    private static void burn(
            GameTestHelper helper,
            ServerLevel level,
            int x,
            int z,
            Consumable charge,
            IntConsumer during,
            Runnable after) {
        MockPlayer pilot = MockPlayers.join(helper, "company-rock-burn-" + x + "-" + z);
        pilot.player().setGameMode(GameType.SURVIVAL);
        Vec3 at = new Vec3(x, FLOOR, z);
        pilot.teleportTo(level, at, 0f, 0f);
        PodEntity[] pod = new PodEntity[1];
        FarChunks.awaitEntityTicking(helper, level, BlockPos.containing(at), () -> {
            pod[0] = TestEntities.create(PodRegistry.POD, level);
            pod[0].setPos(at);
            level.addFreshEntity(pod[0]);
            if (!pilot.player().startRiding(pod[0])) {
                throw failure(helper, "the pilot could not mount the pod");
            }
        });
        int[] setAt = {-1};
        TestTicks.each(helper, () -> {
            if (pod[0] == null || !pod[0].onGround()) {
                return;
            }
            if (setAt[0] < 0) {
                InteractionResult result = ChargeUse.use(pilot.player(), charge);
                if (!result.consumesAction()) {
                    throw failure(helper, "the charge should be set, the use gave %s", result);
                }
                setAt[0] = pod[0].tickCount;
                pod[0].setPos(pod[0].getX() + FAR, pod[0].getY(), pod[0].getZ());
                return;
            }
            int burned = pod[0].tickCount - setAt[0];
            if (burned < TUNING.chargeFuseTicks() - SLACK) {
                during.accept(burned);
                return;
            }
            if (burned < TUNING.chargeFuseTicks() + SLACK) {
                return;
            }
            after.run();
            pod[0].discard();
            helper.succeed();
        });
    }

    /** Only the Company rock the blast will clear gives: rock just outside the volume, and natural rock inside it, are never touched. */
    @GameTest(maxTicks = MAX_TICKS)
    public void aBlastingChargeWidensOnlyTheCompanyRockItWillClear(GameTestHelper helper) {
        int z = Z + 120;
        int depth = TUNING.chargeDepth();
        ServerLevel level = layer(helper, 2);
        BlockState rock = company().in(LayerBlocks.GREEN_SHALE.defaultBlockState());
        RoomCarver.carve(level, X - ROOM, X + ROOM, FLOOR - 8, FLOOR - 1, z - ROOM, z + ROOM, LayerBlocks.GREEN_SHALE);
        RoomCarver.carve(level, X - ROOM, X + ROOM, FLOOR, FLOOR + 10, z - ROOM, z + ROOM, Blocks.AIR);
        BlockPos target = new BlockPos(X, FLOOR - 1, z);
        BlockPos beside = new BlockPos(X + 2, FLOOR - 1, z);
        BlockPos below = new BlockPos(X, FLOOR - 1 - depth, z);
        BlockPos natural = new BlockPos(X - 1, FLOOR - 1, z);
        for (BlockPos cell : List.of(target, beside, below)) {
            level.setBlock(cell, rock, 2);
        }
        List<Bystander> bystanders = List.of(
                new Bystander(beside, companyRock(), "Company rock just beside the volume"),
                new Bystander(below, companyRock(), "Company rock just under the volume"),
                new Bystander(natural, LayerBlocks.GREEN_SHALE, "natural rock inside the volume"));
        int[] widest = {0};
        burn(
                helper,
                level,
                X,
                z,
                Consumable.BLASTING_CHARGE,
                burned -> {
                    checkBystanders(helper, level, bystanders);
                    BlockState now = level.getBlockState(target);
                    if (now.is(companyRock())) {
                        widest[0] = Math.max(widest[0], now.getValue(CompanyRockBlock.CRACK));
                    }
                },
                () -> {
                    if (widest[0] != CompanyRockBlock.MAX_CRACK) {
                        throw failure(
                                helper,
                                "the rock to clear should widen to %d, it reached %d",
                                CompanyRockBlock.MAX_CRACK,
                                widest[0]);
                    }
                    checkBystanders(helper, level, bystanders.subList(0, 2));
                    if (!level.getBlockState(target).isAir()
                            || !level.getBlockState(natural).isAir()) {
                        throw failure(
                                helper, "the blast should have cleared the rock and the natural rock in its volume");
                    }
                });
    }

    /** In layer 1: a shaped charge widens the contiguous Company rock run of its column, not Company rock under a stone, nor rock beside its footprint. */
    @GameTest(maxTicks = MAX_TICKS)
    public void aShapedChargeInLayerOneWidensOnlyTheRunItWillClear(GameTestHelper helper) {
        int z = Z + 240;
        ServerLevel level = layer(helper, 1);
        BlockState rock = company().in(LayerBlocks.PACKED_SHALE.defaultBlockState());
        RoomCarver.carve(
                level, X - ROOM, X + ROOM, FLOOR - 12, FLOOR - 1, z - ROOM, z + ROOM, LayerBlocks.PACKED_SHALE);
        RoomCarver.carve(level, X - ROOM, X + ROOM, FLOOR, FLOOR + 10, z - ROOM, z + ROOM, Blocks.AIR);
        List<BlockPos> run =
                List.of(new BlockPos(X, FLOOR - 1, z), new BlockPos(X, FLOOR - 2, z), new BlockPos(X, FLOOR - 3, z));
        BlockPos underStone = new BlockPos(X, FLOOR - 5, z);
        BlockPos beside = new BlockPos(X + 2, FLOOR - 1, z);
        for (BlockPos cell : run) {
            level.setBlock(cell, rock, 2);
        }
        level.setBlock(new BlockPos(X, FLOOR - 4, z), Blocks.STONE.defaultBlockState(), 2);
        level.setBlock(underStone, rock, 2);
        level.setBlock(beside, rock, 2);
        List<Bystander> bystanders = List.of(
                new Bystander(underStone, companyRock(), "Company rock under a stone, not contiguous"),
                new Bystander(beside, companyRock(), "Company rock beside the footprint"));
        int[] widest = {0};
        burn(
                helper,
                level,
                X,
                z,
                Consumable.SHAPED_CHARGE,
                burned -> {
                    checkBystanders(helper, level, bystanders);
                    BlockState now = level.getBlockState(run.getFirst());
                    if (now.is(companyRock())) {
                        if (now.getValue(OreBlock.HOST) != Host.PACKED_SHALE) {
                            throw failure(helper, "the rock in layer_1 should stay in packed shale: %s", now);
                        }
                        widest[0] = Math.max(widest[0], now.getValue(CompanyRockBlock.CRACK));
                    }
                },
                () -> {
                    if (widest[0] != CompanyRockBlock.MAX_CRACK) {
                        throw failure(
                                helper,
                                "the run to clear should widen to %d, it reached %d",
                                CompanyRockBlock.MAX_CRACK,
                                widest[0]);
                    }
                    checkBystanders(helper, level, bystanders);
                    for (BlockPos cell : run) {
                        if (!level.getBlockState(cell).isAir()) {
                            throw failure(helper, "the shaped charge should have cleared %s", cell);
                        }
                    }
                });
    }

    // assertionException(String, Object...) leaves the placeholders unfilled in the report.
    @FormatMethod
    private static RuntimeException failure(
            GameTestHelper helper, @FormatString String format, @Nullable Object... args) {
        return helper.assertionException(Component.literal(String.format(format, args)));
    }
}
