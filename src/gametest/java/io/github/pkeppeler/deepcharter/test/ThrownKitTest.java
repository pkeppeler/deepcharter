package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.CreatureTuning;
import io.github.pkeppeler.deepcharter.creature.DrawSources;
import io.github.pkeppeler.deepcharter.creature.FootGrips;
import io.github.pkeppeler.deepcharter.creature.LamplessFigure;
import io.github.pkeppeler.deepcharter.layer.CaveInTuning;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.ore.SlagBrick;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.repair.RepairTuning;
import io.github.pkeppeler.deepcharter.repair.ThrownKit;
import io.github.pkeppeler.deepcharter.repair.ThrownKitEntity;
import io.github.pkeppeler.deepcharter.repair.ThrownKitTuning;
import io.github.pkeppeler.deepcharter.test.support.BlockLightOracle;
import io.github.pkeppeler.deepcharter.test.support.ChargeUse;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.ThrowRoom;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Server GameTests for the thrown kit (#985): flares, decoys and charges thrown by hand on foot. Each item is used from the hand and used up, flies and
 * lands; a flare lights the dark and keeps the Retained off a dark pod until it burns out, and frees no one already held; a decoy is a noise that a
 * creature drawn to the drill reads, and lets go when it stops; a charge is fused and blasts by the rules of the pod's blasting charge. Each test has its
 * own room, 64 blocks from the next. Pacing is {@code ThrownKitPacingTest}'s.
 */
public class ThrownKitTest {
    private static final ThrownKitTuning DEFAULT = ThrownKitTuning.DEFAULT;
    private static final RepairTuning REPAIR = RepairTuning.DEFAULT;
    /** The lowest row a thrown charge clears, under the cell it lies in. */
    private static final int BOTTOM = -DEFAULT.charge().depth();
    /** Ticks a throw may take to land: a flat throw is in the air about 12 ticks; the rest is a margin for a loaded runner. */
    private static final int LAND_BUDGET_TICKS = 80;
    /** Ticks either side of a scheduled end at which the end is checked: a scheduled block tick is not exact to the tick. */
    private static final int SLACK_TICKS = 6;

    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 600;
    /**
     * The flare burns this long in the test that watches it go out: long enough that a light that takes a second or two of wall clock to settle
     * is seen while the flare burns (a waiting tick sleeps {@link FarChunks#POLL_MILLIS}), short enough to wait out.
     */
    private static final int SHORT_BURN_TICKS = 1200;
    /** The flare burns longer than any wait under a {@link FarChunks.Deadline} can last, in the test that waits for its light; the burn is {@code SHORT_BURN_TICKS}' and the pacing test's. */
    private static final int OUTLASTING_BURN_TICKS = FarChunks.AWAIT_BUDGET_TICKS * FarChunks.CEILING_FACTOR;

    private static final int USED_UP_X = 7008;
    private static final int IN_POD_X = 7072;
    private static final int FLARE_X = 7136;
    private static final int LIGHT_X = 7200;
    private static final int KEEP_OFF_X = 7264;
    private static final int HELD_X = 7328;
    private static final int DECOY_X = 7392;
    private static final int CLEARS_X = 7456;
    private static final int SPARES_X = 7520;
    private static final int NO_ROCK_X = 7584;
    private static final int PERSON_X = 7648;
    private static final int POD_X = 7712;
    private static final int BROKEN_X = 7904;
    private static final int DROWNS_X = 7968;
    private static final int PLACED_X = 8032;
    private static final int CREW_X = 8096;
    private static final int PODS_X = 8160;
    private static final int ROOF_CREW_X = 8224;
    private static final int ROOF_STRANGER_X = 8288;
    private static final int BURNS_OUT_X = 8352;
    /** A batch of its own with the PvP rule off: the rule is the server's, so a test that turns it off cannot share a batch with one that needs it on. */
    private static final String PVP_OFF = "deepcharter-test:pvp_off";

    private static ThrownKitTuning withFlare(int burnTicks) {
        ThrownKitTuning.Flare flare = DEFAULT.flare();
        return new ThrownKitTuning(
                DEFAULT.throwSpeed(),
                new ThrownKitTuning.Flare(flare.lightLevel(), flare.keepOffBlocks(), burnTicks),
                DEFAULT.decoy(),
                DEFAULT.charge());
    }

    private static ThrownKitTuning withDecoy(int noiseTicks) {
        ThrownKitTuning.Decoy decoy = DEFAULT.decoy();
        return new ThrownKitTuning(
                DEFAULT.throwSpeed(),
                DEFAULT.flare(),
                new ThrownKitTuning.Decoy(noiseTicks, decoy.pullBlocks(), decoy.pulseTicks()),
                DEFAULT.charge());
    }

    /** Throws {@code item} from the thrower's hands with {@code tuning}, and fails the test when it is refused. */
    private static void throwIt(GameTestHelper helper, ThrowRoom room, Consumable item, ThrownKitTuning tuning) {
        ServerPlayer player = room.thrower().player();
        var refused = ThrownKit.throwFrom(player, item, tuning);
        expect(helper, refused.isEmpty(), "the throw of %s was refused: %s", item, refused);
    }

    private static void expectDark(GameTestHelper helper, ServerLevel level, BlockPos pos, String what) {
        int light = level.getBrightness(LightLayer.BLOCK, pos);
        expect(helper, light == 0, "%s: %s should be dark, block light is %d", what, pos, light);
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void usingAFlareOnFootThrowsOneAndUsesItUp(GameTestHelper helper) {
        ThrowRoom.start(
                helper,
                USED_UP_X,
                room -> {
                    ServerPlayer player = room.thrower().player();
                    player.getInventory()
                            .setItem(
                                    player.getInventory().getSelectedSlot(),
                                    new ItemStack(RepairRegistry.item(Consumable.THROWN_FLARE), 3));
                    player.getCooldowns()
                            .removeCooldown(player.getCooldowns().getCooldownGroup(player.getMainHandItem()));
                    InteractionResult result = player.gameMode.useItem(
                            player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND);
                    expect(helper, result.consumesAction(), "the throw should be taken, the use gave %s", result);
                    expect(
                            helper,
                            player.getMainHandItem().getCount() == 2,
                            "one of three should be used up, %d are left",
                            player.getMainHandItem().getCount());
                    expect(
                            helper,
                            player.getCooldowns().isOnCooldown(player.getMainHandItem()),
                            "a thrown item has a cooldown, as every consumable has");
                    expect(
                            helper,
                            room.kits().size() == 1,
                            "one thing should be in the air, there are %d",
                            room.kits().size());
                },
                room -> {
                    if (room.ticks() == 2) {
                        helper.succeed();
                    }
                });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aThrowFromThePodSeatIsRefusedAndTheItemKept(GameTestHelper helper) {
        ThrowRoom.start(
                helper,
                IN_POD_X,
                room -> {
                    PodEntity pod = TestEntities.create(PodRegistry.POD, room.level());
                    pod.setPos(room.throwingSpot());
                    room.level().addFreshEntity(pod);
                    ServerPlayer player = room.thrower().player();
                    expect(helper, player.startRiding(pod), "the person could not mount the pod");
                    player.getInventory()
                            .setItem(
                                    player.getInventory().getSelectedSlot(),
                                    new ItemStack(RepairRegistry.item(Consumable.THROWN_DECOY), 1));
                    InteractionResult result = player.gameMode.useItem(
                            player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND);
                    expect(helper, !result.consumesAction(), "a throw from the seat should be refused, got %s", result);
                    expect(helper, player.getMainHandItem().getCount() == 1, "a refused throw keeps the item");
                    expect(helper, room.kits().isEmpty(), "a refused throw throws nothing");
                },
                room -> {
                    String expected = Component.translatable("message.deepcharter.repair.throw_from_seat")
                            .getString();
                    // The mock records an action-bar message on its own tick, so the refusal is read on a later one.
                    if (room.thrower().actionBarMessages().stream()
                            .anyMatch(m -> m.getString().equals(expected))) {
                        helper.succeed();
                    } else if (room.ticks() > 20) {
                        throw failure(
                                helper,
                                "the bar should say '%s', it said %s",
                                expected,
                                room.thrower().actionBarMessages());
                    }
                });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aFlareLandsWhereItWasThrownAndStandsInTheFloor(GameTestHelper helper) {
        ThrowRoom.start(helper, FLARE_X, room -> throwIt(helper, room, Consumable.THROWN_FLARE, DEFAULT), room -> {
            expect(
                    helper,
                    room.flare() == null || room.kits().isEmpty(),
                    "a flare that has landed is a block, not a thing in the air");
            BlockPos flare = room.flare();
            if (flare != null) {
                expect(
                        helper,
                        room.level()
                                .getBlockState(flare.below())
                                .isFaceSturdy(room.level(), flare.below(), net.minecraft.core.Direction.UP),
                        "the flare stands on solid ground, it stands over %s",
                        room.level().getBlockState(flare.below()));
                double range = flare.getZ() + 0.5 - room.throwingSpot().z;
                expect(helper, range > 2 && range < 30, "the flare landed %.1f blocks away", range);
                helper.succeed();
            } else if (room.ticks() > LAND_BUDGET_TICKS) {
                throw failure(helper, "the flare had not landed %d ticks after the throw", room.ticks());
            }
        });
    }

    /**
     * The light is the engine's, on its own thread, so it is awaited on a wall clock, and a burn is game time: the flare keeps its shipped light and
     * reach but burns past the wait, so a slow engine cannot fail this test by the flare going out. How long the shipped flare burns is
     * {@code ThrownKitTuning}'s and {@link #aFlareBurnsOutAndItsLightGoesWithIt}'s.
     */
    @GameTest(maxTicks = MAX_TICKS + FarChunks.AWAIT_BUDGET_TICKS)
    public void aFlareLightsTheDarkAroundIt(GameTestHelper helper) {
        ThrownKitTuning tuning = withFlare(OUTLASTING_BURN_TICKS);
        int litBlockLight = CreatureTuning.DEFAULT.litBlockLight();
        @Nullable BlockPos[] landed = {null};
        FarChunks.Deadline deadline = FarChunks.deadline();
        ThrowRoom.start(
                helper,
                LIGHT_X,
                room -> {
                    expectDark(helper, room.level(), room.cell(0, 0, 0), "before the throw");
                    throwIt(helper, room, Consumable.THROWN_FLARE, tuning);
                },
                room -> {
                    ServerLevel level = room.level();
                    if (landed[0] == null) {
                        landed[0] = room.flare();
                        expect(
                                helper,
                                landed[0] != null || room.ticks() <= LAND_BUDGET_TICKS,
                                "the flare never landed");
                        return;
                    }
                    BlockPos flare = landed[0];
                    Vec3 centre = Vec3.atCenterOf(flare);
                    BlockPos nearby = flare.offset(4, 0, 0);
                    expect(
                            helper,
                            level.getBlockState(flare).is(RepairRegistry.FLARE_LIGHT),
                            "the flare went out early");
                    expect(helper, DrawSources.litByFlare(level, centre), "a burning flare lights its own place");
                    double reach = DEFAULT.flare().keepOffBlocks();
                    expect(
                            helper,
                            DrawSources.litByFlare(level, centre.add(reach - 1, 0, 0)),
                            "a place within the flare's reach is lit");
                    expect(
                            helper,
                            !DrawSources.litByFlare(level, centre.add(reach + 2, 0, 0)),
                            "a place beyond the flare's reach is not lit");
                    boolean lit = level.getBrightness(LightLayer.BLOCK, flare) >= litBlockLight
                            && level.getBrightness(LightLayer.BLOCK, nearby) >= litBlockLight;
                    deadline.await(
                            helper,
                            level,
                            lit,
                            () -> "the flare never lit the dark round it, light at the flare is "
                                    + level.getBrightness(LightLayer.BLOCK, flare));
                    if (!lit) {
                        return;
                    }
                    BoundingBox box = new BoundingBox(
                            flare.getX() - 6,
                            flare.getY(),
                            flare.getZ() - 6,
                            flare.getX() + 6,
                            flare.getY() + 2,
                            flare.getZ() + 6);
                    int expected = BlockLightOracle.expected(level, box).at(flare);
                    expect(
                            helper,
                            expected == DEFAULT.flare().lightLevel()
                                    && level.getBrightness(LightLayer.BLOCK, flare) == expected,
                            "the blocks give the flare's light %d at the flare, stored is %d",
                            expected,
                            level.getBrightness(LightLayer.BLOCK, flare));
                    helper.succeed();
                });
    }

    @GameTest(maxTicks = MAX_TICKS + FarChunks.AWAIT_BUDGET_TICKS + FarChunks.AWAIT_BUDGET_TICKS)
    public void aFlareBurnsOutAndItsLightGoesWithIt(GameTestHelper helper) {
        ThrownKitTuning tuning = withFlare(SHORT_BURN_TICKS);
        int litBlockLight = CreatureTuning.DEFAULT.litBlockLight();
        @Nullable BlockPos[] landed = {null};
        int[] landedAt = {0};
        boolean[] lightSeen = {false};
        FarChunks.Deadline lit = FarChunks.deadline();
        FarChunks.Deadline[] dark = {null};
        ThrowRoom.start(
                helper,
                BURNS_OUT_X,
                room -> {
                    expectDark(helper, room.level(), room.cell(0, 0, 0), "before the throw");
                    throwIt(helper, room, Consumable.THROWN_FLARE, tuning);
                },
                room -> {
                    ServerLevel level = room.level();
                    if (landed[0] == null) {
                        landed[0] = room.flare();
                        expect(
                                helper,
                                landed[0] != null || room.ticks() <= LAND_BUDGET_TICKS,
                                "the flare never landed");
                        landedAt[0] = room.ticks();
                        return;
                    }
                    BlockPos flare = landed[0];
                    int since = room.ticks() - landedAt[0];
                    boolean burning = level.getBlockState(flare).is(RepairRegistry.FLARE_LIGHT);
                    if (since < SHORT_BURN_TICKS - SLACK_TICKS) {
                        expect(
                                helper,
                                burning,
                                "the flare went out %d ticks after landing, before its burn of %d",
                                since,
                                SHORT_BURN_TICKS);
                    }
                    if (!lightSeen[0]) {
                        boolean lightIsOn = level.getBrightness(LightLayer.BLOCK, flare) >= litBlockLight;
                        lit.await(
                                helper,
                                level,
                                lightIsOn || !burning,
                                () -> "the flare never lit its place while it burned");
                        if (!lightIsOn) {
                            expect(helper, burning, "the flare burnt out before its light was ever seen");
                            return;
                        }
                        lightSeen[0] = true;
                    }
                    if (since <= SHORT_BURN_TICKS + SLACK_TICKS) {
                        return;
                    }
                    expect(helper, !burning, "the flare still burns %d ticks after landing", since);
                    expect(
                            helper,
                            !DrawSources.litByFlare(level, Vec3.atCenterOf(flare)),
                            "a burnt out flare still lights its place");
                    if (dark[0] == null) {
                        dark[0] = FarChunks.deadline();
                    }
                    boolean gone = level.getBrightness(LightLayer.BLOCK, flare) == 0;
                    dark[0].await(helper, level, gone, () -> "the light stayed after the flare went out");
                    if (gone) {
                        helper.succeed();
                    }
                });
    }

    /** A pod with a pilot (no lights: dark) in the room and a figure that walks to it, as {@code RetainedTest} builds it, smaller. */
    private record Dark(PodEntity pod, LamplessFigure figure) {}

    private static Dark darkPodWithFigure(ThrowRoom room, MockPlayer pilot, double figureDistance) {
        ServerLevel level = room.level();
        PodEntity pod = TestEntities.create(PodRegistry.POD, level);
        pod.setPos(Vec3.atBottomCenterOf(room.cell(0, 0, 0)));
        level.addFreshEntity(pod);
        pilot.teleportTo(level, pod.position(), 0f, 0f);
        if (!pilot.player().startRiding(pod)) {
            throw new IllegalStateException("the pilot could not mount the pod");
        }
        LamplessFigure figure = TestEntities.create(CreatureRegistry.LAMPLESS_FIGURE, level);
        figure.setPos(pod.getX(), pod.getY(), pod.getZ() + figureDistance);
        figure.setHeading(net.minecraft.core.Direction.NORTH);
        level.addFreshEntity(figure);
        return new Dark(pod, figure);
    }

    /** The thrower throws {@code item} with {@code tuning} from beside the pod, steeply down so that it lands within a few blocks. */
    private static void throwNearPod(GameTestHelper helper, ThrowRoom room, Consumable item, ThrownKitTuning tuning) {
        room.thrower().teleportTo(room.level(), Vec3.atBottomCenterOf(room.cell(4, 0, -2)), 0f, 60f);
        throwIt(helper, room, item, tuning);
    }

    @GameTest(maxTicks = MAX_TICKS + 700)
    public void aFlareKeepsAFigureOffADarkPodUntilItBurnsOut(GameTestHelper helper) {
        int burn = 240;
        ThrownKitTuning tuning = withFlare(burn);
        MockPlayer pilot = MockPlayers.join(helper, "retained-pilot");
        Dark[] dark = {null};
        int[] throwAt = {-1};
        ThrowRoom.start(
                helper,
                KEEP_OFF_X,
                room -> {
                    // The thrower on foot in the dark can draw the figure while its cell's light lags, and isClinging()
                    // cannot tell that from a pod grip (#1074).
                    room.thrower().player().setGameMode(GameType.CREATIVE);
                    dark[0] = darkPodWithFigure(room, pilot, -9);
                    expect(helper, LamplessFigure.draws(dark[0].pod()), "a dark pod with a pilot draws a figure");
                },
                room -> {
                    LamplessFigure figure = dark[0].figure();
                    PodEntity pod = dark[0].pod();
                    if (throwAt[0] < 0) {
                        throwAt[0] = room.ticks();
                        throwNearPod(helper, room, Consumable.THROWN_FLARE, tuning);
                        return;
                    }
                    int since = room.ticks() - throwAt[0];
                    if (since < burn - 2 * SLACK_TICKS) {
                        expect(
                                helper,
                                !figure.isClinging(),
                                "the figure clung %d ticks after a flare, in its light, to %s",
                                since,
                                FootGrips.holder(room.thrower().player()) == figure ? "the thrower" : "the pod");
                        if (since > LAND_BUDGET_TICKS) {
                            expect(helper, !LamplessFigure.draws(pod), "a pod in a flare's light still draws a figure");
                        }
                    } else if (since > burn + 2 * SLACK_TICKS) {
                        expect(helper, LamplessFigure.draws(pod), "the pod draws a figure again once the flare is out");
                        if (figure.isClinging()) {
                            pilot.leave();
                            helper.succeed();
                        } else {
                            expect(helper, since < burn + 400, "the figure never came once the flare was out");
                        }
                    }
                });
    }

    @GameTest(maxTicks = MAX_TICKS + 700)
    public void aFlareFreesNoOneAlreadyHeld(GameTestHelper helper) {
        MockPlayer pilot = MockPlayers.join(helper, "held-pilot");
        Dark[] dark = {null};
        int[] clungAt = {-1};
        ThrowRoom.start(helper, HELD_X, room -> dark[0] = darkPodWithFigure(room, pilot, -4), room -> {
            LamplessFigure figure = dark[0].figure();
            if (clungAt[0] < 0) {
                if (figure.isClinging()) {
                    clungAt[0] = room.ticks();
                    throwNearPod(helper, room, Consumable.THROWN_FLARE, DEFAULT);
                } else {
                    expect(helper, room.ticks() < 400, "the figure never clung to the dark pod");
                }
                return;
            }
            expect(helper, figure.isClinging() && figure.isAlive(), "a flare let go a pod the figure already held");
            if (room.ticks() - clungAt[0] > 100) {
                expect(helper, room.flare() != null, "the flare was never lit");
                pilot.leave();
                helper.succeed();
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aDecoyIsANoiseThatPullsWhatIsDrawnToTheDrillAwayUntilItStops(GameTestHelper helper) {
        int noise = 100;
        ThrownKitTuning tuning = withDecoy(noise);
        int[] landedAt = {-1};
        Vec3[] decoyAt = {null};
        ThrowRoom.start(helper, DECOY_X, room -> throwIt(helper, room, Consumable.THROWN_DECOY, tuning), room -> {
            ServerLevel level = room.level();
            Vec3 drill = room.throwingSpot();
            if (landedAt[0] < 0) {
                List<ThrownKitEntity> kits = room.kits();
                if (!kits.isEmpty() && kits.getFirst().landed()) {
                    landedAt[0] = room.ticks();
                    decoyAt[0] = kits.getFirst().position();
                } else {
                    expect(helper, room.ticks() <= LAND_BUDGET_TICKS, "the decoy never landed");
                }
                return;
            }
            int since = room.ticks() - landedAt[0];
            Vec3 near = decoyAt[0].add(tuning.decoy().pullBlocks() - 2, 0, 0);
            Vec3 far = decoyAt[0].add(tuning.decoy().pullBlocks() + 2, 0, 0);
            if (since < noise - SLACK_TICKS) {
                Vec3 pulled = DrawSources.noiseTarget(level, near, drill);
                expect(
                        helper,
                        pulled.distanceTo(decoyAt[0]) < 1.0,
                        "a creature in the decoy's pull should go to the decoy at %s, it goes to %s",
                        decoyAt[0],
                        pulled);
                expect(
                        helper,
                        DrawSources.noiseTarget(level, far, drill).equals(drill),
                        "a creature beyond the pull still goes to the drill");
            } else if (since > noise + SLACK_TICKS) {
                expect(
                        helper,
                        DrawSources.noiseTarget(level, near, drill).equals(drill),
                        "a creature goes back to the drill once the decoy stops");
                expect(helper, room.kits().isEmpty(), "a decoy that has stopped is gone");
                long chimes = room.thrower().soundsInTheWorld().stream()
                        .filter(id -> id.equals(SoundEvents.BELL_BLOCK.location()))
                        .count();
                long expected = noise / tuning.decoy().pulseTicks();
                expect(
                        helper,
                        chimes >= expected - 2 && chimes <= expected + 2,
                        "a decoy sounds every %d ticks for %d: %d were heard, about %d expected",
                        tuning.decoy().pulseTicks(),
                        noise,
                        chimes,
                        expected);
                helper.succeed();
            }
        });
    }

    /** Throws a charge straight down at the thrower's feet and walks the thrower out of the blast's reach. */
    private static void dropCharge(GameTestHelper helper, ThrowRoom room, boolean walkAway) {
        ServerPlayer player = room.thrower().player();
        room.thrower().teleportTo(room.level(), Vec3.atBottomCenterOf(room.cell(0, 0, 0)), 0f, 90f);
        throwIt(helper, room, Consumable.THROWN_CHARGE, DEFAULT);
        if (walkAway) {
            room.thrower().teleportTo(room.level(), Vec3.atBottomCenterOf(room.cell(0, 0, -12)), 0f, 0f);
        }
        expect(helper, player.isAlive(), "the thrower is alive");
    }

    /** What a charge test checks once the charge has blown. */
    private interface Verify {
        void check(GameTestHelper helper, ThrowRoom room);
    }

    /**
     * Builds {@code layout}, drops a charge on {@code (x, FLOOR, Z)}, and checks that nothing changed before the fuse ran out, that something has by the
     * time it is due, and then {@code verify}. {@code watched} is the cell whose change shows the blast.
     */
    private static void blast(
            GameTestHelper helper,
            int x,
            java.util.function.Consumer<ThrowRoom> layout,
            java.util.function.Function<ThrowRoom, BlockPos> watchedCell,
            Verify verify) {
        int[] landedAt = {-1};
        int fuse = DEFAULT.charge().fuseTicks();
        ThrowRoom.start(
                helper,
                x,
                room -> {
                    layout.accept(room);
                    dropCharge(helper, room, true);
                },
                room -> {
                    boolean gone =
                            room.level().getBlockState(watchedCell.apply(room)).isAir();
                    if (landedAt[0] < 0) {
                        expect(helper, !gone, "the charge blew before it landed");
                        if (room.kitLanded()) {
                            landedAt[0] = room.ticks();
                        } else {
                            expect(helper, room.ticks() <= LAND_BUDGET_TICKS, "the charge never landed");
                        }
                        return;
                    }
                    int since = room.ticks() - landedAt[0];
                    if (since < fuse - SLACK_TICKS) {
                        expect(
                                helper,
                                !gone,
                                "the charge blew %d ticks after landing: it has a %d tick fuse",
                                since,
                                fuse);
                    } else if (since > fuse + SLACK_TICKS) {
                        expect(helper, room.kits().isEmpty(), "a charge that has blown is gone");
                        verify.check(helper, room);
                        helper.succeed();
                    }
                });
    }

    private static void set(ThrowRoom room, int dx, int dy, int dz, Block block) {
        room.level().setBlock(room.cell(dx, dy, dz), block.defaultBlockState(), 2);
    }

    private static void expectAir(GameTestHelper helper, ThrowRoom room, BlockPos pos, String what) {
        expect(
                helper,
                room.level().getBlockState(pos).isAir(),
                "%s: %s should be air, was %s",
                what,
                pos,
                room.level().getBlockState(pos).getBlock());
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aThrownChargeIsFusedThenClearsCompanyRockAndNaturalRockInItsVolumeAndNothingElse(
            GameTestHelper helper) {
        blast(
                helper,
                CLEARS_X,
                room -> {
                    set(room, 0, -1, 0, HazardBlocks.COMPANY_ROCK);
                    set(room, -1, BOTTOM, -1, HazardBlocks.COMPANY_ROCK); // a corner of the volume
                    set(room, -2, BOTTOM, 0, HazardBlocks.COMPANY_ROCK); // just outside it
                    set(room, 1, -1, 1, Blocks.STONE); // natural rock inside it
                    set(room, 2, -1, 0, Blocks.STONE); // stone just outside it
                    set(room, 0, BOTTOM - 1, 0, Blocks.STONE); // one row under it
                },
                room -> room.cell(0, -1, 0),
                (helper2, room) -> {
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            for (int dy = BOTTOM; dy <= -1; dy++) {
                                expectAir(helper2, room, room.cell(dx, dy, dz), "inside the volume");
                            }
                        }
                    }
                    ChargeUse.expectBlock(
                            helper2,
                            room.level(),
                            room.cell(-2, BOTTOM, 0),
                            HazardBlocks.COMPANY_ROCK,
                            "Company rock beside the volume");
                    ChargeUse.expectBlock(
                            helper2, room.level(), room.cell(2, -1, 0), Blocks.STONE, "stone beside the volume");
                    ChargeUse.expectBlock(
                            helper2, room.level(), room.cell(0, BOTTOM - 1, 0), Blocks.STONE, "stone under the volume");
                });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aThrownChargeSparesWhatIsBuiltFluidsBedrockAndVentsNoGas(GameTestHelper helper) {
        blast(
                helper,
                SPARES_X,
                room -> {
                    set(room, 0, 0 - 1, 0, HazardBlocks.COMPANY_ROCK);
                    set(room, -1, BOTTOM, -1, Blocks.OAK_PLANKS);
                    set(room, -1, -1, 0, SlagBrick.BLOCK);
                    set(room, 0, -1, -1, Blocks.BEDROCK);
                    set(room, 1, BOTTOM, -1, Blocks.LAVA);
                    set(room, 1, BOTTOM, 1, HazardBlocks.GAS_POCKET);
                    set(room, 2, BOTTOM, 1, Blocks.STONE);
                },
                room -> room.cell(0, -1, 0),
                (helper2, room) -> {
                    ChargeUse.expectBlock(
                            helper2, room.level(), room.cell(-1, BOTTOM, -1), Blocks.OAK_PLANKS, "planks");
                    ChargeUse.expectBlock(helper2, room.level(), room.cell(-1, -1, 0), SlagBrick.BLOCK, "a slag brick");
                    ChargeUse.expectBlock(helper2, room.level(), room.cell(0, -1, -1), Blocks.BEDROCK, "bedrock");
                    ChargeUse.expectBlock(helper2, room.level(), room.cell(1, BOTTOM, -1), Blocks.LAVA, "lava");
                    expectAir(helper2, room, room.cell(1, BOTTOM, 1), "the gas pocket goes with the blast");
                    ChargeUse.expectBlock(
                            helper2,
                            room.level(),
                            room.cell(2, BOTTOM, 1),
                            Blocks.STONE,
                            "a pocket in the blast vents no more");
                });
    }

    /** The pod's own rule: a charge needs Company rock to clear anything, so one that lands where there is none only makes a noise. */
    @GameTest(maxTicks = MAX_TICKS)
    public void aThrownChargeWithNoCompanyRockInItsVolumeClearsNoRock(GameTestHelper helper) {
        int[] landedAt = {-1};
        int fuse = DEFAULT.charge().fuseTicks();
        ThrowRoom.start(helper, NO_ROCK_X, room -> dropCharge(helper, room, true), room -> {
            if (landedAt[0] < 0) {
                if (room.kitLanded()) {
                    landedAt[0] = room.ticks();
                }
                return;
            }
            expect(
                    helper,
                    room.level().getBlockState(room.cell(0, -1, 0)).is(Blocks.STONE),
                    "plain rock goes: use dynamite");
            if (room.ticks() - landedAt[0] > fuse + SLACK_TICKS) {
                expect(helper, room.kits().isEmpty(), "the charge blew, so it is gone");
                helper.succeed();
            }
        });
    }

    /** A stranger with no charter is hurt when the server allows PvP (its default), and never killed. */
    @GameTest(maxTicks = MAX_TICKS)
    public void aPersonAtTheBlastIsHurtButNeverKilled(GameTestHelper helper) {
        MockPlayer bystander = MockPlayers.join(helper, "bystander");
        int[] landedAt = {-1};
        float[] health = {0};
        int fuse = DEFAULT.charge().fuseTicks();
        ThrowRoom.start(
                helper,
                PERSON_X,
                room -> {
                    set(room, 0, -1, 0, HazardBlocks.COMPANY_ROCK);
                    dropCharge(helper, room, true);
                    ServerPlayer other = bystander.player();
                    other.setGameMode(GameType.SURVIVAL);
                    bystander.teleportTo(room.level(), Vec3.atBottomCenterOf(room.cell(1, 0, 0)), 0f, 0f);
                    // Less health than the blast takes: a blast that could kill would.
                    other.setHealth(DEFAULT.charge().personDamage() / 2);
                    health[0] = other.getHealth();
                },
                room -> {
                    if (landedAt[0] < 0) {
                        if (room.kitLanded()) {
                            landedAt[0] = room.ticks();
                        }
                        return;
                    }
                    ServerPlayer other = bystander.player();
                    if (room.ticks() - landedAt[0] > fuse + SLACK_TICKS) {
                        expect(helper, other.isAlive(), "a thrown charge killed a person at the blast");
                        expect(helper, other.getHealth() < health[0], "a person at the blast should be hurt");
                        expect(
                                helper,
                                other.getHealth() >= 1f,
                                "a person should keep at least half a heart, has %s",
                                other.getHealth());
                        bystander.leave();
                        helper.succeed();
                    }
                });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aThrownChargeHurtsAPodInReachByTheDistanceAndNotOneBeyondIt(GameTestHelper helper) {
        PodEntity[] near = {null};
        PodEntity[] far = {null};
        float[] hull = {0};
        int[] landedAt = {-1};
        int fuse = DEFAULT.charge().fuseTicks();
        ThrowRoom.start(
                helper,
                POD_X,
                room -> {
                    set(room, 0, -1, 0, HazardBlocks.COMPANY_ROCK);
                    // The volume's east edge is x + 2. A pod's box is 1.9 wide, so its middle 1.5 + 0.95 past that edge
                    // is half the reach (3) off.
                    near[0] = podAt(room, room.cell(0, 0, 0).getX() + 4.45);
                    far[0] = podAt(room, room.cell(0, 0, 0).getX() + 9.5);
                    hull[0] = near[0].hull();
                    dropCharge(helper, room, true);
                },
                room -> {
                    if (landedAt[0] < 0) {
                        if (room.kitLanded()) {
                            landedAt[0] = room.ticks();
                        }
                        return;
                    }
                    if (room.ticks() - landedAt[0] > fuse + SLACK_TICKS) {
                        double expected = REPAIR.chargeDamage() * (1 - 1.5 / REPAIR.chargeReach());
                        double lost = hull[0] - near[0].hull();
                        expect(
                                helper,
                                Math.abs(lost - expected) < 0.01,
                                "a pod 1.5 blocks off should lose %.2f hull, lost %.2f",
                                expected,
                                lost);
                        expect(
                                helper,
                                far[0].hull() == hull[0],
                                "a pod beyond the reach lost %s hull",
                                hull[0] - far[0].hull());
                        near[0].discard();
                        far[0].discard();
                        helper.succeed();
                    }
                });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aFlareThatIsBrokenKeepsNothingOff(GameTestHelper helper) {
        ThrowRoom.start(helper, BROKEN_X, room -> throwIt(helper, room, Consumable.THROWN_FLARE, DEFAULT), room -> {
            BlockPos flare = room.flare();
            if (flare == null) {
                expect(helper, room.ticks() <= LAND_BUDGET_TICKS, "the flare never landed");
                return;
            }
            Vec3 centre = Vec3.atCenterOf(flare);
            expect(helper, DrawSources.litByFlare(room.level(), centre), "a burning flare keeps the dark off");
            room.level().removeBlock(flare, false);
            expect(
                    helper,
                    !DrawSources.litByFlare(room.level(), centre),
                    "a flare whose block is broken still keeps the dark off, though its 90 seconds have not run out");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aFlareThatFindsNoRoomDrownsAndIsGivenBack(GameTestHelper helper) {
        ThrowRoom.start(
                helper,
                DROWNS_X,
                room -> {
                    // Water fills the cell the flare lands in and the one above it.
                    set(room, 0, 0, -3, Blocks.WATER);
                    set(room, 0, 1, -3, Blocks.WATER);
                    room.thrower().teleportTo(room.level(), Vec3.atBottomCenterOf(room.cell(0, 0, -3)), 0f, 90f);
                    throwIt(helper, room, Consumable.THROWN_FLARE, DEFAULT);
                },
                room -> {
                    ServerPlayer player = room.thrower().player();
                    String expected = Component.translatable("message.deepcharter.repair.thrown_flare_drowned")
                            .getString();
                    if (room.kits().isEmpty()
                            && room.thrower().actionBarMessages().stream()
                                    .anyMatch(m -> m.getString().equals(expected))) {
                        expect(
                                helper,
                                !room.level().getBlockState(room.cell(0, 0, -3)).is(RepairRegistry.FLARE_LIGHT)
                                        && !room.level()
                                                .getBlockState(room.cell(0, 1, -3))
                                                .is(RepairRegistry.FLARE_LIGHT),
                                "a flare stood in the water");
                        int back = player.getInventory().countItem(RepairRegistry.item(Consumable.THROWN_FLARE));
                        expect(helper, back == 1, "the drowned flare should be given back once, %d were", back);
                        helper.succeed();
                    } else if (room.ticks() > LAND_BUDGET_TICKS) {
                        throw failure(
                                helper,
                                "a flare thrown into water should drown and say so, the bar said %s",
                                room.thrower().actionBarMessages());
                    }
                });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aFlareStandsOnAPlacedBlockAndNeverReplacesIt(GameTestHelper helper) {
        ThrowRoom.start(
                helper,
                PLACED_X,
                room -> {
                    set(room, 0, 0, -3, Blocks.STONE);
                    room.thrower().teleportTo(room.level(), Vec3.atBottomCenterOf(room.cell(0, 1, -3)), 0f, 90f);
                    throwIt(helper, room, Consumable.THROWN_FLARE, DEFAULT);
                },
                room -> {
                    BlockPos stood = room.cell(0, 1, -3);
                    if (room.level().getBlockState(stood).is(RepairRegistry.FLARE_LIGHT)) {
                        ChargeUse.expectBlock(
                                helper,
                                room.level(),
                                room.cell(0, 0, -3),
                                Blocks.STONE,
                                "the placed block under the flare");
                        helper.succeed();
                    } else {
                        expect(helper, room.ticks() <= LAND_BUDGET_TICKS, "the flare never stood on the placed block");
                    }
                });
    }

    private static CharterId found(GameTestHelper helper, MinecraftServer server, UUID founder, String name) {
        var refusal = Charters.found(server, founder, name + founder.toString().substring(0, 8));
        expect(helper, refusal.isEmpty(), "founding the charter %s was refused: %s", name, refusal);
        return Charters.charterOfOrThrow(server, founder).orElseThrow().id();
    }

    /**
     * The design call on friendly fire: a charge hurts the thrower and their own crew whatever the PvP setting, and hurts anyone else only where PvP is
     * on. With PvP off, a stranger from another charter at the blast is unhurt.
     */
    @GameTest(maxTicks = MAX_TICKS, environment = PVP_OFF)
    public void aChargeHurtsTheThrowerAndTheirCrewButNotAStrangerWhenPvpIsOff(GameTestHelper helper) {
        MockPlayer crewmate = MockPlayers.join(helper, "crewmate");
        MockPlayer stranger = MockPlayers.join(helper, "stranger");
        int[] landedAt = {-1};
        int fuse = DEFAULT.charge().fuseTicks();
        ThrowRoom.start(
                helper,
                CREW_X,
                room -> {
                    MinecraftServer server = room.level().getServer();
                    CharterId crew =
                            found(helper, server, room.thrower().player().getUUID(), "Crew");
                    var applied = Charters.apply(server, crewmate.player().getUUID(), crew);
                    expect(helper, applied.isEmpty(), "the crewmate could not apply: %s", applied);
                    var approved = Charters.approve(
                            server,
                            room.thrower().player().getUUID(),
                            crewmate.player().getUUID());
                    expect(helper, approved.isEmpty(), "the crewmate was not approved: %s", approved);
                    found(helper, server, stranger.player().getUUID(), "Stranger");
                    set(room, 0, -1, 0, HazardBlocks.COMPANY_ROCK);
                    for (MockPlayer person : List.of(room.thrower(), crewmate, stranger)) {
                        person.player().setGameMode(GameType.SURVIVAL);
                        person.player().setHealth(20f);
                    }
                    crewmate.teleportTo(room.level(), Vec3.atBottomCenterOf(room.cell(1, 0, 0)), 0f, 0f);
                    stranger.teleportTo(room.level(), Vec3.atBottomCenterOf(room.cell(-1, 0, 0)), 0f, 0f);
                    expect(helper, !room.level().isPvpAllowed(), "the pvp_off environment should turn PvP off");
                    // The thrower stays at the charge.
                    dropCharge(helper, room, false);
                },
                room -> {
                    if (landedAt[0] < 0) {
                        if (room.kitLanded()) {
                            landedAt[0] = room.ticks();
                        }
                        return;
                    }
                    if (room.ticks() - landedAt[0] > fuse + SLACK_TICKS) {
                        expect(
                                helper,
                                room.thrower().player().getHealth() < 20f,
                                "the thrower should be hurt by their own charge");
                        expect(
                                helper,
                                crewmate.player().getHealth() < 20f,
                                "a crewmate at the blast should be hurt, PvP or not");
                        expect(
                                helper,
                                stranger.player().getHealth() >= 20f,
                                "a stranger from another charter should be unhurt with PvP off, has %s",
                                stranger.player().getHealth());
                        crewmate.leave();
                        stranger.leave();
                        helper.succeed();
                    } else if (room.ticks() > LAND_BUDGET_TICKS + fuse + 100) {
                        throw failure(helper, "the charge never blew");
                    }
                });
    }

    /** A pod another charter owns is never hurt by a thrown charge, and one of the thrower's own charter is. */
    @GameTest(maxTicks = MAX_TICKS)
    public void aChargeNeverHurtsAPodOfAnotherCharterButHurtsOneOfTheThrowersOwn(GameTestHelper helper) {
        PodEntity[] own = {null};
        PodEntity[] other = {null};
        float[] hull = {0};
        int[] landedAt = {-1};
        int fuse = DEFAULT.charge().fuseTicks();
        ThrowRoom.start(
                helper,
                PODS_X,
                room -> {
                    MinecraftServer server = room.level().getServer();
                    CharterId mine =
                            found(helper, server, room.thrower().player().getUUID(), "Mine");
                    CharterId theirs = found(helper, server, UUID.randomUUID(), "Theirs");
                    set(room, 0, -1, 0, HazardBlocks.COMPANY_ROCK);
                    own[0] = podAt(room, room.cell(0, 0, 0).getX() + 4.45);
                    other[0] = podAt(room, room.cell(0, 0, 0).getX() - 4.45);
                    PodComponents.register(own[0], mine);
                    PodComponents.register(other[0], theirs);
                    hull[0] = own[0].hull();
                    dropCharge(helper, room, true);
                },
                room -> {
                    if (landedAt[0] < 0) {
                        if (room.kitLanded()) {
                            landedAt[0] = room.ticks();
                        }
                        return;
                    }
                    if (room.ticks() - landedAt[0] > fuse + SLACK_TICKS) {
                        expect(helper, own[0].hull() < hull[0], "a pod of the thrower's own charter should be hurt");
                        expect(
                                helper,
                                other[0].hull() == hull[0],
                                "a pod of another charter lost %s hull",
                                hull[0] - other[0].hull());
                        own[0].discard();
                        other[0].discard();
                        helper.succeed();
                    }
                });
    }

    /** A thrown charge shakes the roof over the thrower's crewmate on foot, PvP off: the same charter rule as a pod's charge (#987). */
    @GameTest(maxTicks = MAX_TICKS, environment = PVP_OFF)
    public void aThrownChargeShakesTheRoofOverTheThrowersCrewmateWhenPvpIsOff(GameTestHelper helper) {
        roofUnderBlast(helper, ROOF_CREW_X, true);
    }

    /** With PvP off, a thrown charge does not shake the roof over a person of another charter: that would be griefing. */
    @GameTest(maxTicks = MAX_TICKS, environment = PVP_OFF)
    public void aThrownChargeNeverShakesTheRoofOverAPersonOfAnotherCharterWhenPvpIsOff(GameTestHelper helper) {
        roofUnderBlast(helper, ROOF_STRANGER_X, false);
    }

    /**
     * Throws a charge at the thrower's feet and walks the thrower out of the cave-in's stretch. A person on foot 5 blocks off stands under a slab of
     * natural rock, and is the thrower's crewmate when {@code crewmate} is true, and of another charter otherwise. Once the fuse and the cave-in's
     * warning have run, the slab is gone when {@code crewmate} is true and stands otherwise.
     */
    private static void roofUnderBlast(GameTestHelper helper, int x, boolean crewmate) {
        MockPlayer under = MockPlayers.join(helper, crewmate ? "roof-crewmate" : "roof-stranger");
        int[] landedAt = {-1};
        int fuse = DEFAULT.charge().fuseTicks();
        int warn = CaveInTuning.DEFAULT.warnTicks();
        ThrowRoom.start(
                helper,
                x,
                room -> {
                    MinecraftServer server = room.level().getServer();
                    CharterId mine =
                            found(helper, server, room.thrower().player().getUUID(), "Thrower");
                    if (crewmate) {
                        var applied = Charters.apply(server, under.player().getUUID(), mine);
                        expect(helper, applied.isEmpty(), "the crewmate could not apply: %s", applied);
                        var approved = Charters.approve(
                                server,
                                room.thrower().player().getUUID(),
                                under.player().getUUID());
                        expect(helper, approved.isEmpty(), "the crewmate was not approved: %s", approved);
                    } else {
                        found(helper, server, under.player().getUUID(), "Stranger");
                    }
                    expect(helper, !room.level().isPvpAllowed(), "the pvp_off environment should turn PvP off");
                    set(room, 0, -1, 0, HazardBlocks.COMPANY_ROCK);
                    for (int dx = 4; dx <= 6; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            set(room, dx, 2, dz, Blocks.STONE);
                        }
                    }
                    under.player().setGameMode(GameType.SURVIVAL);
                    under.teleportTo(room.level(), Vec3.atBottomCenterOf(room.cell(5, 0, 0)), 0f, 0f);
                    dropCharge(helper, room, true);
                },
                room -> {
                    if (landedAt[0] < 0) {
                        if (room.kitLanded()) {
                            landedAt[0] = room.ticks();
                        }
                        return;
                    }
                    if (room.ticks() - landedAt[0] > fuse + warn + SLACK_TICKS) {
                        boolean standing =
                                room.level().getBlockState(room.cell(5, 2, 0)).is(Blocks.STONE);
                        expect(
                                helper,
                                standing != crewmate,
                                crewmate
                                        ? "the roof over the thrower's crewmate should have fallen"
                                        : "the roof over a person of another charter should stand with PvP off");
                        under.leave();
                        helper.succeed();
                    }
                });
    }

    private static PodEntity podAt(ThrowRoom room, double x) {
        PodEntity pod = TestEntities.create(PodRegistry.POD, room.level());
        pod.setPos(x, ThrowRoom.FLOOR, room.cell(0, 0, 0).getZ() + 0.5);
        room.level().addFreshEntity(pod);
        return pod;
    }
}
