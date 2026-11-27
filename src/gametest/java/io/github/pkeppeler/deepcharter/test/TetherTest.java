package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.creature.DrawSources;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTether;
import io.github.pkeppeler.deepcharter.pod.PodTether.Refusal;
import io.github.pkeppeler.deepcharter.pod.PodTowing;
import io.github.pkeppeler.deepcharter.pod.TetherTuning;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import io.github.pkeppeler.deepcharter.test.support.UseEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server GameTests for #986, the tether: a person on foot clips a line to their own pod, the line pays out to a length and holds
 * there, the winch hauls the person home through open air and never through rock, the line tows, and every end of the clip
 * leaves nothing behind.
 *
 * <p>A mock player has no client, so nothing moves it. The tether hands a real client a velocity and the client moves itself
 * with its own collision, so where a test needs the person to move, {@link #clientStep} stands in for that client: it moves the
 * server-side player by the velocity the server last set, with vanilla's collision, and then applies the client's gravity.
 */
public class TetherTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(TetherTest.class);
    private static final AtomicInteger NAMES = new AtomicInteger();
    private static final TetherTuning TUNING = TetherTuning.DEFAULT;

    /** A room in layer 1 where a line can pay out to its length: the floor of its bed, and where it stands. */
    private static final int ROOM_FLOOR = TestLayers.worldY(1, 20);

    private static final int ROOM_Z = 17008;
    private static final int ROOM_X = 17000;
    /** Chunks apart, so no two rooms share one. */
    private static final int ROOM_STEP = 64;

    private static final double EPSILON = 1e-6;

    /** Where a creature drawn to the drill would head if there were no noise: nowhere near any room. */
    private static final Vec3 DRILL = Vec3.ZERO;

    @GameTest
    public void aPersonClipsToTheirOwnPodAndKeepsTheTether(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(5.5, 2, 1.5));
        MockPlayer person = onFoot(helper);
        PodComponents.register(pod, charterOf(helper, person));
        ItemStack tether = person.player().getMainHandItem();
        expect(helper, use(person.player(), pod).consumesAction(), "using the tether on the pod should clip it");
        expect(
                helper,
                Optional.of(pod.getUUID()).equals(PodTether.podId(person.player())),
                "the person should be clipped to the pod, got %s",
                PodTether.podId(person.player()));
        expect(helper, !PodTether.isWinching(person.player()), "a clipped person is not winching until they say so");
        expect(
                helper,
                person.player().getMainHandItem() == tether && tether.getCount() == 1,
                "the tether is a tool, not used up");
        // The same use on the same pod unhooks again.
        use(person.player(), pod);
        expect(helper, !PodTether.isClipped(person.player()), "using the tether on its own pod should unhook it");
        expect(helper, tether.getCount() == 1, "unhooking keeps the tether");
        helper.succeed();
    }

    @GameTest
    public void theClipReachesOnlyAsFarAsTheClipReachAndOnlyOnePodAtATime(GameTestHelper helper) {
        PodEntity near = helper.spawn(PodRegistry.POD, new Vec3(5.5, 2, 1.5));
        PodEntity far = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 1.5 + TUNING.clipReach() + 2));
        MockPlayer person = onFoot(helper);
        expect(
                helper,
                Optional.of(Refusal.TOO_FAR).equals(PodTether.clipRefusal(person.player(), far)),
                "a pod past the clip reach should refuse, got %s",
                PodTether.clipRefusal(person.player(), far));
        try {
            PodTether.clip(person.player(), far);
            throw failure(helper, "clipping to a pod out of reach must throw");
        } catch (IllegalStateException expected) {
            // Expected.
        }
        expect(helper, !PodTether.isClipped(person.player()), "a refused clip leaves no link");
        PodTether.clip(person.player(), near);
        expect(
                helper,
                Optional.of(Refusal.ALREADY_CLIPPED).equals(PodTether.clipRefusal(person.player(), far)),
                "a person on a line cannot clip a second one, got %s",
                PodTether.clipRefusal(person.player(), far));
        helper.succeed();
    }

    @GameTest
    public void anotherChartersPodRefusesTheClip(GameTestHelper helper) {
        PodEntity theirs = helper.spawn(PodRegistry.POD, new Vec3(5.5, 2, 1.5));
        PodEntity unowned = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 5.5));
        MockPlayer owner = onFoot(helper);
        MockPlayer stranger = onFoot(helper);
        PodComponents.register(theirs, charterOf(helper, owner));
        charterOf(helper, stranger);
        expect(
                helper,
                use(stranger.player(), theirs) == InteractionResult.FAIL,
                "another charter's pod should refuse the clip");
        expect(helper, !PodTether.isClipped(stranger.player()), "a refused clip leaves no link");
        expect(
                helper,
                Optional.of(Refusal.NOT_ALLOWED).equals(PodTether.clipRefusal(stranger.player(), theirs)),
                "the refusal should name the charter, got %s",
                PodTether.clipRefusal(stranger.player(), theirs));
        MockPlayer drifter = onFoot(helper);
        expect(
                helper,
                Optional.of(Refusal.NOT_ALLOWED).equals(PodTether.clipRefusal(drifter.player(), theirs)),
                "a person with no charter is refused too, got %s",
                PodTether.clipRefusal(drifter.player(), theirs));
        expect(helper, PodTether.clipRefusal(owner.player(), theirs).isEmpty(), "the owner's crew may clip on");
        expect(helper, PodTether.clipRefusal(stranger.player(), unowned).isEmpty(), "an unowned pod is anyone's");
        helper.runAfterDelay(2, () -> {
            expect(
                    helper,
                    stranger.actionBarMessages().contains(Refusal.NOT_ALLOWED.message()),
                    "the stranger should be told why, got %s",
                    stranger.actionBarMessages());
            helper.succeed();
        });
    }

    @GameTest
    public void sneakingWithTheTetherUnhooksItAndAWreckHasNoWinch(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(5.5, 2, 1.5));
        PodEntity wreck = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 5.5));
        MockPlayer person = onFoot(helper);
        ServerPlayer player = person.player();
        PodTether.clip(player, pod);
        player.setShiftKeyDown(true);
        InteractionResult result = player.getMainHandItem().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        player.setShiftKeyDown(false);
        expect(
                helper,
                result.consumesAction() && !PodTether.isClipped(player),
                "sneak-using the tether in the air unhooks it");
        expect(
                helper,
                !player.getCooldowns().isOnCooldown(player.getMainHandItem()),
                "unhooking by hand costs no cooldown");
        wreck.setHull(0f);
        expect(
                helper,
                Optional.of(Refusal.WRECK).equals(PodTether.clipRefusal(player, wreck)),
                "a wreck has no winch to clip to, got %s",
                PodTether.clipRefusal(player, wreck));
        helper.succeed();
    }

    @GameTest
    public void theTetherIsSoldAtTheRepairStationAndIsATool(GameTestHelper helper) {
        ItemStack stack = new ItemStack(RepairRegistry.item(Consumable.TETHER));
        expect(helper, Consumable.TETHER.price() > 0, "the tether costs money, pillar 1");
        expect(helper, stack.getMaxStackSize() == 1, "a tether is one line, it does not stack");
        expect(
                helper,
                Consumable.byItemId(BuiltInRegistries.ITEM.getKey(stack.getItem()))
                        .equals(Optional.of(Consumable.TETHER)),
                "the shop finds the tether by its item id");
        helper.succeed();
    }

    @GameTest
    public void aClipEndsWhenThePersonDies(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(5.5, 2, 1.5));
        MockPlayer person = onFoot(helper);
        PodTether.clip(person.player(), pod);
        expect(helper, PodTether.isClipped(person.player()), "the person starts clipped");
        person.player().kill(helper.getLevel());
        helper.runAfterDelay(2, () -> {
            expect(helper, !PodTether.isClipped(person.player()), "a dead person is not on a line");
            helper.succeed();
        });
    }

    @GameTest
    public void aClipEndsWhenThePersonBoardsAPod(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(5.5, 2, 1.5));
        MockPlayer person = onFoot(helper);
        PodTether.clip(person.player(), pod);
        expect(helper, PodTether.setWinching(person.player(), true), "the winch starts");
        expect(helper, person.player().startRiding(pod), "the person should be able to board");
        helper.runAfterDelay(2, () -> {
            expect(helper, !PodTether.isClipped(person.player()), "a person in a seat is off the line");
            expect(helper, !PodTether.isWinching(person.player()), "and the winch has stopped with the line");
            helper.succeed();
        });
    }

    @GameTest
    public void aClipEndsWhenThePodIsRemovedOrUnloadsOrIsWrecked(GameTestHelper helper) {
        PodEntity removed = helper.spawn(PodRegistry.POD, new Vec3(5.5, 2, 1.5));
        PodEntity unloaded = helper.spawn(PodRegistry.POD, new Vec3(5.5, 2, 4.5));
        PodEntity wrecked = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 5.5));
        MockPlayer a = onFoot(helper);
        MockPlayer b = onFoot(helper);
        MockPlayer c = onFoot(helper);
        PodTether.clip(a.player(), removed);
        PodTether.clip(b.player(), unloaded);
        PodTether.clip(c.player(), wrecked);
        expect(
                helper,
                PodTether.isClipped(a.player()) && PodTether.isClipped(b.player()) && PodTether.isClipped(c.player()),
                "all three start clipped");
        removed.discard();
        // What a chunk unload does to an entity: it leaves the level's lookup, though it is saved and not gone.
        unloaded.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        wrecked.setHull(0f);
        helper.runAfterDelay(3, () -> {
            expect(helper, !PodTether.isClipped(a.player()), "a pod that was removed leaves no link");
            expect(helper, !PodTether.isClipped(b.player()), "a pod that unloaded leaves no link");
            expect(helper, !PodTether.isClipped(c.player()), "a pod that was wrecked leaves no link");
            helper.succeed();
        });
    }

    @GameTest
    public void aClipEndsWhenThePersonChangesDimension(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(5.5, 2, 1.5));
        MockPlayer person = onFoot(helper);
        PodTether.clip(person.player(), pod);
        expect(helper, PodTether.isClipped(person.player()), "the person starts clipped");
        ServerLevel other = TestLayers.level(helper, 1);
        person.teleportTo(other, TestLayers.vec(1, ROOM_X + 0.5, 5, ROOM_Z + 0.5), 0f, 0f);
        helper.runAfterDelay(3, () -> {
            expect(helper, !PodTether.isClipped(person.player()), "a line does not cross a dimension");
            helper.succeed();
        });
    }

    @GameTest
    public void aClipIsNeverSavedAndANewSessionStartsFree(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(5.5, 2, 1.5));
        UUID id = UUID.randomUUID();
        MockPlayer person = MockPlayers.join(helper, "tether-session-" + NAMES.incrementAndGet(), id);
        PodTether.clip(person.player(), pod);
        expect(helper, PodTether.isClipped(person.player()), "the person starts clipped");
        person.leave();
        MockPlayer back = MockPlayers.join(helper, "tether-session-back-" + NAMES.incrementAndGet(), id);
        helper.runAfterDelay(2, () -> {
            expect(helper, !PodTether.isClipped(back.player()), "a person who comes back is not clipped to a pod");
            expect(helper, !PodTether.isWinching(back.player()), "and no winch is running for them");
            helper.succeed();
        });
    }

    @GameTest
    public void aClippedPersonTowsAWreckWithTheTetherAndFreesItAgain(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(5.5, 2, 1.5));
        PodEntity wreck = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 6.5));
        MockPlayer person = onFoot(helper);
        PodComponents.register(pod, charterOf(helper, person));
        wreck.setHull(0f);
        PodTether.clip(person.player(), pod);
        expect(
                helper,
                use(person.player(), wreck).consumesAction(),
                "using the tether on a wreck while clipped should hook it");
        expect(
                helper,
                Optional.of(pod.getUUID()).equals(PodTowing.towerId(wreck)),
                "the wreck should be towed by the pod the person is clipped to, got %s",
                PodTowing.towerId(wreck));
        expect(helper, PodTether.isClipped(person.player()), "hooking a tow leaves the person on their line");
        expect(
                helper,
                use(person.player(), wreck).consumesAction() && !PodTowing.isTowed(wreck),
                "using it again frees the tow");
        helper.succeed();
    }

    @GameTest
    public void theTetherTowsOnlyWhatTheCharterMayAccessAndWhatIsInReach(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(5.5, 2, 1.5));
        PodEntity foreign = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 6.5));
        PodEntity distant = helper.spawn(PodRegistry.POD, new Vec3(5.5, 2, 30.5));
        MockPlayer person = onFoot(helper);
        MockPlayer owner = onFoot(helper);
        PodComponents.register(pod, charterOf(helper, person));
        PodComponents.register(foreign, charterOf(helper, owner));
        PodTether.clip(person.player(), pod);
        expect(
                helper,
                use(person.player(), foreign) == InteractionResult.FAIL,
                "another charter's pod cannot be towed");
        expect(helper, !PodTowing.isTowed(foreign), "and it is not hooked");
        // Out of the tow line's own reach, which the tether does not lengthen.
        expect(
                helper,
                use(person.player(), distant) == InteractionResult.FAIL,
                "a pod out of the tow line's reach is refused");
        expect(helper, !PodTowing.isTowed(distant), "and it is not hooked");
        helper.succeed();
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 200)
    public void theLineHoldsAtItsLengthAndSnapsPastItsMargin(GameTestHelper helper) {
        Room room = room(helper, 0);
        MockPlayer person = onFoot(helper);
        room.when(helper, () -> {
            PodEntity pod = room.pod();
            ServerPlayer player = person.player();
            player.setNoGravity(true);
            clipAt(room, person, pod, TUNING.length() - 2, 0);
            player.setDeltaMovement(Vec3.ZERO);
            return () -> {
                switch (room.age) {
                    case 3 -> {
                        expect(helper, PodTether.isClipped(player), "inside the length the line holds");
                        expect(
                                helper,
                                player.getDeltaMovement().lengthSqr() < EPSILON,
                                "slack line: no pull, got %s",
                                player.getDeltaMovement());
                        person.teleportTo(room.level, room.pod().position().add(TUNING.length() + 1, 0, 0), 0f, 0f);
                    }
                    case 5 -> {
                        expect(
                                helper,
                                PodTether.isClipped(player),
                                "past the length but inside the margin the line holds");
                        Vec3 pull = player.getDeltaMovement();
                        expect(
                                helper,
                                pull.x < -EPSILON && pull.length() <= TUNING.winchSpeed() + EPSILON,
                                "a taut line pulls the person back, never faster than the winch, got %s",
                                pull);
                        person.teleportTo(
                                room.level,
                                room.pod().position().add(TUNING.length() + TUNING.snapMargin() + 2, 0, 0),
                                0f,
                                0f);
                    }
                    case 7 -> {
                        expect(helper, !PodTether.isClipped(player), "past the margin the line snaps");
                        expect(
                                helper,
                                player.getCooldowns().isOnCooldown(player.getMainHandItem()),
                                "a snapped tether cannot be clipped again at once");
                        expect(
                                helper,
                                player.getMainHandItem().getCount() == 1,
                                "a snapped line is not lost, only slow to rig again");
                        expect(
                                helper,
                                Optional.of(Refusal.COOLING_DOWN).equals(PodTether.clipRefusal(player, pod)),
                                "it refuses until the cooldown ends, got %s",
                                PodTether.clipRefusal(player, pod));
                        room.finish(helper);
                    }
                    default -> {}
                }
            };
        });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 400)
    public void theWinchHaulsAPersonHomeThroughOpenAirAtItsTunedSpeed(GameTestHelper helper) {
        Room room = room(helper, 1);
        MockPlayer person = onFoot(helper);
        room.when(helper, () -> {
            PodEntity pod = room.pod();
            ServerPlayer player = person.player();
            Vec3 start = room.pod().position().add(TUNING.length() - 1, 0, 3);
            clipAt(room, person, pod, start.x - room.pod().getX(), 3);
            player.getMainHandItem().use(room.level, player, InteractionHand.MAIN_HAND);
            expect(helper, PodTether.isWinching(player), "using the tether in the air starts the winch");
            double gap = start.distanceTo(room.pod().position());
            return () -> {
                clientStep(player);
                expect(
                        helper,
                        room.age < 200,
                        "the winch should have hauled the person home by now, at %s",
                        player.position());
                if (PodTether.isWinching(player)) {
                    return;
                }
                int ticks = room.age;
                double hauled = gap - player.position().distanceTo(room.pod().position());
                double speed = hauled / ticks;
                LOGGER.info(
                        "[tether] the winch hauled {} blocks in {} ticks ({} s), measured",
                        hauled,
                        ticks,
                        ticks / 20.0);
                // A sprint covers 0.28 blocks a tick (modelled); the winch is faster, so it is an escape, and it is
                // no faster than a few seconds over the whole line, so it is no teleport.
                expect(
                        helper,
                        ticks >= 30 && ticks <= 80,
                        "the winch should haul the line in 1.5 to 4 seconds, it took %s ticks",
                        ticks);
                expect(
                        helper,
                        speed > TUNING.winchSpeed() * 0.8 && speed <= TUNING.winchSpeed() + EPSILON,
                        "the person comes home at the winch speed %s, they came at %s",
                        TUNING.winchSpeed(),
                        speed);
                expect(
                        helper,
                        player.getBoundingBox().getCenter().distanceTo(PodTether.anchor(pod)) < pullDone(pod),
                        "the person should stop at the hull, they are at %s",
                        player.position());
                expect(
                        helper,
                        PodTether.isClipped(player),
                        "arriving does not unhook the line, told %s",
                        person.actionBarMessages());
                room.finish(helper);
            };
        });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 400)
    public void theWinchNeverHaulsAPersonThroughRockAndAStuckLineSnaps(GameTestHelper helper) {
        Room room = room(helper, 2);
        MockPlayer person = onFoot(helper);
        room.when(helper, () -> {
            PodEntity pod = room.pod();
            ServerPlayer player = person.player();
            int wallX = (int) Math.floor(room.pod().getX()) + 8;
            RoomCarver.carve(
                    room.level, wallX, wallX + 2, ROOM_FLOOR, ROOM_FLOOR + 55, ROOM_Z - 4, ROOM_Z + 14, Blocks.STONE);
            player.setNoGravity(true);
            Vec3 start = room.pod().position().add(15, 0, 0);
            clipAt(room, person, pod, 15, 0);
            PodTether.setWinching(player, true);
            return () -> {
                clientStep(player);
                expect(
                        helper,
                        player.getX() > wallX + 3 - EPSILON,
                        "the person must never be in or past the wall, they are at %s",
                        player.position());
                expect(
                        helper,
                        player.position().distanceTo(start) < EPSILON,
                        "a winch behind rock must not pull at all, the person moved to %s",
                        player.position());
                if (room.age == TUNING.snagTicks() / 2) {
                    expect(helper, PodTether.isClipped(player), "a line snagged for a short while holds");
                    expect(helper, PodTether.isWinching(player), "and the winch keeps trying");
                }
                if (room.age > TUNING.snagTicks() + 3) {
                    expect(helper, !PodTether.isClipped(player), "a line that stays snagged snaps");
                    expect(
                            helper,
                            player.getCooldowns().isOnCooldown(player.getMainHandItem()),
                            "and the tether has to be rigged again");
                    room.finish(helper);
                }
            };
        });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 400)
    public void aLineThatClearsTheRockHaulsAgainAndTheSnagStartsOver(GameTestHelper helper) {
        Room room = room(helper, 3);
        MockPlayer person = onFoot(helper);
        room.when(helper, () -> {
            PodEntity pod = room.pod();
            ServerPlayer player = person.player();
            player.setNoGravity(true);
            int wallX = (int) Math.floor(room.pod().getX()) + 8;
            // A wall across the room with a window in it at the pod's own height and z: the line passes it only from
            // straight across.
            RoomCarver.carve(
                    room.level, wallX, wallX + 1, ROOM_FLOOR, ROOM_FLOOR + 55, ROOM_Z - 4, ROOM_Z + 14, Blocks.STONE);
            RoomCarver.carve(
                    room.level, wallX, wallX + 1, ROOM_FLOOR + 36, ROOM_FLOOR + 44, ROOM_Z + 4, ROOM_Z + 7, Blocks.AIR);
            clipAt(room, person, pod, 14, -5);
            PodTether.setWinching(player, true);
            int clearAt = TUNING.snagTicks() - 5;
            return () -> {
                if (room.age == clearAt) {
                    expect(helper, PodTether.isClipped(player), "still holding just before the snag would snap it");
                    expect(helper, player.getDeltaMovement().lengthSqr() < EPSILON, "no pull while snagged");
                    // The person finds the window: straight across from the pod.
                    person.teleportTo(room.level, room.pod().position().add(14, 0, 0), 0f, 0f);
                }
                if (room.age > clearAt) {
                    expect(
                            helper,
                            PodTether.isClipped(player),
                            "a cleared line must not snap on the old snag, tick %s",
                            room.age);
                    if (room.age >= clearAt + 2) {
                        expect(
                                helper,
                                player.getDeltaMovement().x < -EPSILON,
                                "the winch pulls toward the pod once the line is clear, got %s",
                                player.getDeltaMovement());
                        room.finish(helper);
                    }
                }
            };
        });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 400)
    public void aFallIsCaughtByTheLineAndHauledBackUp(GameTestHelper helper) {
        Room room = room(helper, 4);
        MockPlayer person = onFoot(helper);
        double[] lowest = {Double.MAX_VALUE};
        room.when(helper, () -> {
            PodEntity pod = room.pod();
            ServerPlayer player = person.player();
            clipAt(room, person, pod, 0, 0, -3);
            player.setDeltaMovement(0, -1.5, 0);
            return () -> {
                clientStep(player);
                lowest[0] = Math.min(lowest[0], player.getY());
                double hang = room.pod().getY() - player.getY();
                expect(
                        helper,
                        hang <= TUNING.length() + TUNING.snapMargin(),
                        "the line should catch the fall within its margin, the person hung %s below",
                        hang);
                expect(
                        helper,
                        PodTether.isClipped(player),
                        "a fall the line catches does not snap it, tick %s",
                        room.age);
                expect(
                        helper,
                        player.fallDistance < 3.0f,
                        "the catch should leave no fall to land, fall distance %s",
                        player.fallDistance);
                if (room.age == 80) {
                    expect(
                            helper,
                            room.pod().getY() - lowest[0] > TUNING.length() - 3,
                            "the fall should have used the line, the person got only %s down",
                            room.pod().getY() - lowest[0]);
                    PodTether.setWinching(player, true);
                }
                if (room.age > 80 && PodTether.isWinching(player) == false) {
                    expect(
                            helper,
                            player.position().distanceTo(room.pod().position()) < 4,
                            "the winch should have hauled the person back to the pod, they are at %s",
                            player.position());
                    room.finish(helper);
                }
                expect(helper, room.age < 300, "the person should be home by now, at %s", player.position());
            };
        });
    }

    /** The pod stands on a pad, and a person who goes over its edge hangs on the line below the lip: the line bends over the corner, and the winch hauls them up the face and over. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 500)
    public void aPersonHangingBelowALipIsCaughtAndHauledUpTheFaceAndOverIt(GameTestHelper helper) {
        Room room = room(helper, 8);
        MockPlayer person = onFoot(helper);
        double[] lowest = {Double.MAX_VALUE};
        int[] hauledFrom = {0};
        room.when(helper, () -> {
            PodEntity pod = room.pod();
            ServerPlayer player = person.player();
            int podX = (int) Math.floor(pod.getX());
            int podY = (int) Math.floor(pod.getY());
            int podZ = (int) Math.floor(pod.getZ());
            RoomCarver.carve(room.level, podX - 3, podX + 3, podY - 4, podY - 1, podZ - 3, podZ + 3, Blocks.STONE);
            clipAt(room, person, pod, 5, 0, -3);
            player.setDeltaMovement(0, -0.5, 0);
            double lipX = podX + 4;
            return () -> {
                clientStep(player);
                expect(
                        helper,
                        PodTether.isClipped(player),
                        "the line holds below the lip, tick %s, told %s",
                        room.age,
                        person.actionBarMessages());
                if (room.age < 90) {
                    lowest[0] = Math.min(lowest[0], player.getY());
                    return;
                }
                if (room.age == 90) {
                    double hang = pod.getY() + 1 - lowest[0];
                    expect(
                            helper,
                            hang > 8 && hang <= TUNING.length() - 5 + TUNING.snapMargin() + 1,
                            "the fall below the lip should be caught by the bent line, the person hung %s below the winch",
                            hang);
                    player.setNoGravity(true);
                    expect(helper, PodTether.setWinching(player, true), "the winch starts below the lip");
                    hauledFrom[0] = room.age;
                }
                if (player.getY() < pod.getY() - 0.5) {
                    expect(
                            helper,
                            player.getX() > lipX,
                            "below the lip the person is hauled up the face, never through the pad, they are at %s",
                            player.position());
                }
                if (!PodTether.isWinching(player)) {
                    expect(helper, room.age - hauledFrom[0] < 200, "the haul over the lip took too long");
                    expect(
                            helper,
                            player.getBoundingBox().getCenter().distanceTo(PodTether.anchor(pod)) < pullDone(pod),
                            "the winch should have the person at the pod, they are at %s",
                            player.position());
                    room.finish(helper);
                }
                expect(helper, room.age < 300, "the person should be over the lip by now, at %s", player.position());
            };
        });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 300)
    public void theWinchIsLoudOnlyWhileItHauls(GameTestHelper helper) {
        Room room = room(helper, 5);
        MockPlayer person = onFoot(helper);
        room.when(helper, () -> {
            PodEntity pod = room.pod();
            ServerPlayer player = person.player();
            player.setNoGravity(true);
            clipAt(room, person, pod, TUNING.length() - 2, 0);
            Vec3 anchor = PodTether.anchor(pod);
            Vec3 inside = anchor.add(TUNING.noiseRadius() - 1, 0, 0);
            Vec3 outside = anchor.add(TUNING.noiseRadius() + 1, 0, 0);
            return () -> {
                if (room.age == 20) {
                    expect(
                            helper,
                            !heard(room, anchor, inside),
                            "a clipped line that is not hauling is quiet, the creature was sent to %s",
                            DrawSources.noiseTarget(room.level, inside, DRILL));
                    PodTether.setWinching(player, true);
                }
                if (room.age > 20) {
                    clientStep(player);
                }
                if (room.age == 60) {
                    expect(helper, heard(room, anchor, inside), "a hauling winch is a noise within its radius");
                    expect(
                            helper,
                            !heard(room, anchor, outside),
                            "and carries no further than the tuned radius %s",
                            TUNING.noiseRadius());
                    PodTether.setWinching(player, false);
                    expect(helper, !heard(room, anchor, inside), "a winch that stops is quiet at once");
                    PodTether.setWinching(player, true);
                }
                if (room.age == 62) {
                    expect(helper, heardNear(room, inside), "a winch started again is loud again");
                    PodTether.unclip(player, PodTether.Unclipped.UNHOOKED);
                    expect(helper, !heardNear(room, inside), "a line that is unhooked is quiet at once");
                    room.finish(helper);
                }
            };
        });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 300)
    public void twoPeopleOnOnePodKeepItLoudUntilTheLastWinchStops(GameTestHelper helper) {
        Room room = room(helper, 14);
        MockPlayer first = onFoot(helper);
        MockPlayer second = onFoot(helper);
        room.when(helper, () -> {
            PodEntity pod = room.pod();
            ServerPlayer one = first.player();
            ServerPlayer two = second.player();
            one.setNoGravity(true);
            two.setNoGravity(true);
            clipAt(room, first, pod, TUNING.length() - 2, 0);
            clipAt(room, second, pod, TUNING.length() - 2, 2);
            Vec3 inside = PodTether.anchor(pod).add(TUNING.noiseRadius() - 1, 0, 0);
            PodTether.setWinching(one, true);
            PodTether.setWinching(two, true);
            return () -> {
                clientStep(one);
                clientStep(two);
                if (room.age == 20) {
                    expect(helper, heardNear(room, inside), "two winches on one pod are a noise");
                    PodTether.unclip(one, PodTether.Unclipped.UNHOOKED);
                    expect(
                            helper,
                            heardNear(room, inside),
                            "the pod is still heard while the other person hauls on it");
                }
                if (room.age == 25) {
                    expect(helper, heardNear(room, inside), "and the other winch keeps it loud");
                    PodTether.setWinching(two, false);
                    expect(helper, !heardNear(room, inside), "the pod is quiet once the last winch stops");
                    room.finish(helper);
                }
            };
        });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 300)
    public void thePilotCanReelInAClippedPersonFromTheSeat(GameTestHelper helper) {
        Room room = room(helper, 6);
        MockPlayer person = onFoot(helper);
        MockPlayer pilot = onFoot(helper);
        room.when(helper, () -> {
            PodEntity pod = room.pod();
            ServerPlayer player = person.player();
            clipAt(room, person, pod, TUNING.length() - 4, 2);
            pilot.teleportTo(room.level, room.pod().position(), 0f, 0f);
            expect(helper, pilot.player().startRiding(pod), "the pilot should board");
            InteractionResult result =
                    pilot.player().getMainHandItem().use(room.level, pilot.player(), InteractionHand.MAIN_HAND);
            expect(helper, result.consumesAction(), "the pilot's tether in the air reels the line in");
            expect(helper, PodTether.isWinching(player), "the clipped person is being hauled");
            expect(helper, PodTether.isClipped(player), "and is still clipped");
            return () -> room.finish(helper);
        });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 300)
    public void aPodWithNoPowerHasNoWinch(GameTestHelper helper) {
        Room room = room(helper, 7);
        MockPlayer person = onFoot(helper);
        room.when(helper, () -> {
            PodEntity pod = room.pod();
            ServerPlayer player = person.player();
            clipAt(room, person, pod, 10, 0);
            pod.setStranded(true);
            expect(helper, !PodTether.setWinching(player, true), "a stranded pod cannot winch");
            expect(helper, !PodTether.isWinching(player), "so the winch is not running");
            pod.setStranded(false);
            expect(helper, PodTether.setWinching(player, true), "a powered pod can");
            pod.setStranded(true);
            return () -> {
                if (room.age == 3) {
                    expect(helper, !PodTether.isWinching(player), "the winch stops when the pod loses power");
                    expect(helper, PodTether.isClipped(player), "but the line stays clipped");
                    room.finish(helper);
                }
            };
        });
    }

    /** An unpowered pod's winch cannot start, and the person is told so, not that a winch they never started has stopped. */
    @GameTest
    public void usingTheTetherInTheAirOnAnUnpoweredPodSaysTheWinchHasNoPower(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(5.5, 2, 1.5));
        MockPlayer person = onFoot(helper);
        ServerPlayer player = person.player();
        PodTether.clip(player, pod);
        pod.setStranded(true);
        InteractionResult result = player.getMainHandItem().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        expect(helper, result.consumesAction(), "using the tether in the air is handled");
        expect(helper, !PodTether.isWinching(player), "a stranded pod's winch does not start");
        helper.runAfterDelay(2, () -> {
            expect(
                    helper,
                    person.actionBarMessages().contains(Component.translatable("message.deepcharter.tether.no_power")),
                    "the person should read that the pod has no power, got %s",
                    person.actionBarMessages());
            expect(
                    helper,
                    !person.actionBarMessages()
                            .contains(Component.translatable("message.deepcharter.tether.hauling_stopped")),
                    "a winch that never ran has not stopped, got %s",
                    person.actionBarMessages());
            helper.succeed();
        });
    }

    /** A pilot who cannot reel anyone in because the pod has no power is told so, not to clip a tether first. */
    @GameTest
    public void aPilotWhoCannotReelAClippedPersonInIsToldTheWinchHasNoPower(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(5.5, 2, 1.5));
        MockPlayer person = onFoot(helper);
        MockPlayer pilot = onFoot(helper);
        PodTether.clip(person.player(), pod);
        pilot.teleportTo(helper.getLevel(), pod.position(), 0f, 0f);
        expect(helper, pilot.player().startRiding(pod), "the pilot should board");
        pod.setStranded(true);
        pilot.player().getMainHandItem().use(helper.getLevel(), pilot.player(), InteractionHand.MAIN_HAND);
        helper.runAfterDelay(2, () -> {
            expect(
                    helper,
                    pilot.actionBarMessages().contains(Component.translatable("message.deepcharter.tether.no_power")),
                    "the pilot should read that the pod has no power, got %s",
                    pilot.actionBarMessages());
            expect(
                    helper,
                    !pilot.actionBarMessages()
                            .contains(Component.translatable("message.deepcharter.tether.not_clipped")),
                    "a pilot with someone clipped is not told to clip, got %s",
                    pilot.actionBarMessages());
            helper.succeed();
        });
    }

    /** A person who leaves the charter that owns the pod is off its line on the next tick. */
    @GameTest
    public void aClipEndsWhenThePersonLeavesTheCharterThatOwnsThePod(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(5.5, 2, 1.5));
        MockPlayer director = onFoot(helper);
        MockPlayer member = onFoot(helper);
        CharterId id = charterOf(helper, director);
        PodComponents.register(pod, id);
        expect(
                helper,
                Charters.apply(server, member.player().getUUID(), id).isEmpty(),
                "the member should be able to apply");
        expect(
                helper,
                Charters.approve(
                                server,
                                director.player().getUUID(),
                                member.player().getUUID())
                        .isEmpty(),
                "the director should approve");
        PodTether.clip(member.player(), pod);
        expect(helper, PodTether.isClipped(member.player()), "a member may clip to their charter's pod");
        expect(
                helper,
                Charters.leave(server, member.player().getUUID()).isEmpty(),
                "the member should be able to leave");
        helper.runAfterDelay(3, () -> {
            expect(
                    helper,
                    !PodTether.isClipped(member.player()),
                    "a person the charter no longer holds is off its winch");
            expect(
                    helper,
                    member.actionBarMessages().contains(PodTether.Unclipped.NOT_ALLOWED.message()),
                    "and is told why, got %s",
                    member.actionBarMessages());
            helper.succeed();
        });
    }

    /** Rule 23: an attachment that gates gameplay on a tick path: unreadable data reads as no clip, and nothing throws. */
    @GameTest
    public void aTetherStateOfAnotherVersionReadsAsNoClipAndNeverThrows(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(5.5, 2, 1.5));
        MockPlayer person = onFoot(helper);
        ServerPlayer player = person.player();
        UnreadableChecks.makeUnreadable(player, PodTether.STATE);
        MinecraftServer server = helper.getLevel().getServer();
        UnreadableChecks.assertNoThrow(
                helper,
                "tether",
                Map.of(
                        "the reads",
                                () -> {
                                    PodTether.isClipped(player);
                                    PodTether.isWinching(player);
                                    PodTether.podId(player);
                                },
                        "the tick",
                                () -> ServerTickEvents.END_SERVER_TICK.invoker().onEndTick(server),
                        "the winch", () -> PodTether.setWinching(player, true),
                        "the use in the air",
                                () -> player.getMainHandItem()
                                        .use(helper.getLevel(), player, InteractionHand.MAIN_HAND),
                        "the clip", () -> PodTether.clipRefusal(player, pod)));
        expect(helper, !PodTether.isClipped(player), "unreadable clip data reads as no clip");
        helper.succeed();
    }

    /** A winch that stalls against something the centre ray misses (a slot too low for the body) counts as snagged, goes quiet and snaps. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 400)
    public void aWinchThatMakesNoProgressCountsAsSnaggedAndGoesQuiet(GameTestHelper helper) {
        Room room = room(helper, 9);
        MockPlayer person = onFoot(helper);
        int[] snaggedAt = {0};
        room.when(helper, () -> {
            PodEntity pod = room.pod();
            ServerPlayer player = person.player();
            player.setNoGravity(true);
            int wallX = (int) Math.floor(room.pod().getX()) + 8;
            RoomCarver.carve(
                    room.level, wallX, wallX + 1, ROOM_FLOOR, ROOM_FLOOR + 55, ROOM_Z - 4, ROOM_Z + 14, Blocks.STONE);
            // The person stands at the wall, so the first window already shows no progress.
            // The person's middle is level with the winch, so the haul runs flat and the body slides along nothing.
            clipAt(
                    room,
                    person,
                    pod,
                    10,
                    0,
                    PodTether.anchor(pod).y - pod.getY() - person.player().getBbHeight() / 2);
            // A slot one block high at the line's own height: the ray passes, a body 1.8 high does not.
            Vec3 anchor = PodTether.anchor(pod);
            Vec3 middle = player.getBoundingBox().getCenter();
            double alongWall = (wallX + 0.5 - anchor.x) / (middle.x - anchor.x);
            int slot = (int) Math.floor(anchor.y + (middle.y - anchor.y) * alongWall);
            RoomCarver.carve(room.level, wallX, wallX + 1, slot, slot, ROOM_Z - 4, ROOM_Z + 14, Blocks.AIR);
            double rayY = anchor.y + (middle.y - anchor.y) * alongWall;
            expect(
                    helper,
                    rayY > slot + EPSILON && rayY < slot + 1 - EPSILON,
                    "the slot should hold the ray, at %s in %s",
                    rayY,
                    slot);
            PodTether.setWinching(player, true);
            return () -> {
                clientStep(player);
                expect(
                        helper,
                        player.getX() > wallX + 2 - EPSILON,
                        "the person must never be in or past the wall, they are at %s",
                        player.position());
                if (room.age == 5) {
                    expect(
                            helper,
                            !person.actionBarMessages()
                                    .contains(Component.translatable("message.deepcharter.tether.snagged")),
                            "the centre ray is clear, so the line is not snagged yet, got %s",
                            person.actionBarMessages());
                }
                if (snaggedAt[0] == 0
                        && person.actionBarMessages()
                                .contains(Component.translatable("message.deepcharter.tether.snagged"))) {
                    snaggedAt[0] = room.age;
                    expect(
                            helper,
                            heardNear(room, PodTether.anchor(room.pod())),
                            "the winch is heard when the snag is first seen, so the lapse below is a change");
                    expect(
                            helper,
                            Math.abs(room.age - TUNING.stallTicks()) <= 5,
                            "a haul with no progress counts as snagged after the stall window, it did at tick %s",
                            room.age);
                }
                if (snaggedAt[0] == 0) {
                    expect(helper, room.age < TUNING.stallTicks() + 10, "the stall should have been noticed by now");
                    return;
                }
                int snagAge = room.age - snaggedAt[0];
                if (snagAge == TUNING.snagTicks() - 5) {
                    expect(helper, PodTether.isClipped(player), "the snag time is counted from the stall");
                    expect(
                            helper,
                            !heardNear(room, PodTether.anchor(room.pod())),
                            "a stalled winch is quiet once its last noise runs out");
                }
                if (snagAge > TUNING.snagTicks() + 5) {
                    expect(helper, !PodTether.isClipped(player), "a haul that goes nowhere snaps");
                    expect(
                            helper,
                            player.getCooldowns().isOnCooldown(player.getMainHandItem()),
                            "and the tether has to be rigged again");
                    room.finish(helper);
                }
            };
        });
    }

    /** The winch refuses a lava path: lava across the line is a snag, as rock is, and the pilot's reel drags nobody into it. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 400)
    public void theWinchNeverHaulsAPersonThroughLava(GameTestHelper helper) {
        Room room = room(helper, 10);
        MockPlayer person = onFoot(helper);
        MockPlayer pilot = onFoot(helper);
        room.when(helper, () -> {
            PodEntity pod = room.pod();
            ServerPlayer player = person.player();
            int wallX = (int) Math.floor(room.pod().getX()) + 8;
            RoomCarver.carve(
                    room.level, wallX, wallX + 1, ROOM_FLOOR, ROOM_FLOOR + 55, ROOM_Z - 4, ROOM_Z + 14, Blocks.LAVA);
            player.setNoGravity(true);
            Vec3 start = room.pod().position().add(15, 0, 0);
            clipAt(room, person, pod, 15, 0);
            pilot.teleportTo(room.level, room.pod().position(), 0f, 0f);
            expect(helper, pilot.player().startRiding(pod), "the pilot should board");
            pilot.player().getMainHandItem().use(room.level, pilot.player(), InteractionHand.MAIN_HAND);
            expect(helper, PodTether.isWinching(player), "the pilot's reel starts the winch");
            return () -> {
                clientStep(player);
                expect(
                        helper,
                        player.position().distanceTo(start) < EPSILON,
                        "a winch must not haul a person into lava, the person moved to %s",
                        player.position());
                if (room.age > TUNING.snagTicks() + 3) {
                    expect(helper, !PodTether.isClipped(player), "a line stuck behind lava snaps");
                    room.finish(helper);
                }
            };
        });
    }

    /** Unhooking by hand costs nothing on a slack line, but not while the line is snagged: that is the price of jamming a winch, and unhooking is no way out of it. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 300)
    public void unhookingASnaggedLineCostsTheSnapCooldown(GameTestHelper helper) {
        Room room = room(helper, 11);
        MockPlayer person = onFoot(helper);
        room.when(helper, () -> {
            PodEntity pod = room.pod();
            ServerPlayer player = person.player();
            snagBehindRock(room, person, pod);
            return () -> {
                if (room.age == 5) {
                    expect(helper, PodTether.isClipped(player), "the line is snagged but not yet snapped");
                    player.setShiftKeyDown(true);
                    player.getMainHandItem().use(room.level, player, InteractionHand.MAIN_HAND);
                    player.setShiftKeyDown(false);
                    expect(helper, !PodTether.isClipped(player), "sneaking unhooks the line");
                    expect(
                            helper,
                            player.getCooldowns().isOnCooldown(player.getMainHandItem()),
                            "unhooking a snagged line costs the cooldown a snap does");
                    room.finish(helper);
                }
            };
        });
    }

    /** Stopping a snagged winch by hand is no way out either: a jammed winch cannot be stopped without cutting the line. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 300)
    public void stoppingASnaggedWinchByHandSnapsTheLine(GameTestHelper helper) {
        Room room = room(helper, 12);
        MockPlayer person = onFoot(helper);
        room.when(helper, () -> {
            PodEntity pod = room.pod();
            ServerPlayer player = person.player();
            snagBehindRock(room, person, pod);
            return () -> {
                if (room.age == 5) {
                    player.getMainHandItem().use(room.level, player, InteractionHand.MAIN_HAND);
                    expect(helper, !PodTether.isClipped(player), "stopping a snagged winch cuts the line");
                    expect(
                            helper,
                            player.getCooldowns().isOnCooldown(player.getMainHandItem()),
                            "and costs the cooldown a snap does");
                    room.finish(helper);
                }
            };
        });
    }

    /** A line held taut past its length is unhooked at a price too: a person cannot cut it free to dodge a snap. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 300)
    public void unhookingATautLineCostsTheSnapCooldown(GameTestHelper helper) {
        Room room = room(helper, 13);
        MockPlayer person = onFoot(helper);
        room.when(helper, () -> {
            PodEntity pod = room.pod();
            ServerPlayer player = person.player();
            player.setNoGravity(true);
            clipAt(room, person, pod, TUNING.length() + 1, 0);
            return () -> {
                if (room.age == 3) {
                    expect(helper, PodTether.isClipped(player), "the taut line holds");
                    player.setShiftKeyDown(true);
                    player.getMainHandItem().use(room.level, player, InteractionHand.MAIN_HAND);
                    player.setShiftKeyDown(false);
                    expect(helper, !PodTether.isClipped(player), "sneaking unhooks the line");
                    expect(
                            helper,
                            player.getCooldowns().isOnCooldown(player.getMainHandItem()),
                            "unhooking a taut line costs the cooldown a snap does");
                    room.finish(helper);
                }
            };
        });
    }

    /** Clips the person 15 blocks from the pod behind a wall, with the winch running against it. */
    private static void snagBehindRock(Room room, MockPlayer person, PodEntity pod) {
        person.player().setNoGravity(true);
        int wallX = (int) Math.floor(pod.getX()) + 8;
        RoomCarver.carve(
                room.level, wallX, wallX + 1, ROOM_FLOOR, ROOM_FLOOR + 55, ROOM_Z - 4, ROOM_Z + 14, Blocks.STONE);
        clipAt(room, person, pod, 15, 0);
        PodTether.setWinching(person.player(), true);
    }

    private static MockPlayer onFoot(GameTestHelper helper) {
        MockPlayer person = MockPlayers.join(helper, "tether-" + NAMES.incrementAndGet());
        person.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(RepairRegistry.item(Consumable.TETHER)));
        return person;
    }

    private static InteractionResult use(ServerPlayer player, PodEntity pod) {
        return UseEntity.on(player, pod.level(), InteractionHand.MAIN_HAND, pod);
    }

    private static CharterId charterOf(GameTestHelper helper, MockPlayer person) {
        MinecraftServer server = helper.getLevel().getServer();
        UUID founder = person.player().getUUID();
        if (Charters.found(server, founder, "Tether Test " + NAMES.incrementAndGet())
                .isPresent()) {
            throw failure(helper, "founding the charter should succeed");
        }
        return Charters.charterOfOrThrow(server, founder).orElseThrow().id();
    }

    /** Blocks between the middles of the pod and the person within which the winch has them home: the hull, the gap, half of the person and what a sagging person may fall short by. */
    private static double pullDone(PodEntity pod) {
        return pod.chassis().width() / 2.0 + TUNING.arriveGap() + 0.3 + 0.5;
    }

    /** Stands the person beside the pod, clips them, and then moves them {@code along} blocks along x and {@code across} along z (and {@code up}), as a walk away along the line would. */
    private static void clipAt(Room room, MockPlayer person, PodEntity pod, double along, double across) {
        clipAt(room, person, pod, along, across, 0);
    }

    private static void clipAt(Room room, MockPlayer person, PodEntity pod, double along, double across, double up) {
        person.teleportTo(room.level, pod.position().add(2.5, 0, 0), 0f, 0f);
        PodTether.clip(person.player(), pod);
        person.teleportTo(room.level, pod.position().add(along, up, across), 0f, 0f);
    }

    /** Whether a creature at {@code from}, drawn to the drill, is sent to the winch at {@code winch} instead. */
    private static boolean heard(Room room, Vec3 winch, Vec3 from) {
        return DrawSources.noiseTarget(room.level, from, DRILL).distanceToSqr(winch) < EPSILON;
    }

    /** {@link #heard} for a creature standing at {@code from}, with the winch at the pod's anchor. */
    private static boolean heardNear(Room room, Vec3 from) {
        return heard(room, PodTether.anchor(room.pod()), from);
    }

    /**
     * Stands in for the client of a person on foot: moves the server-side player by the velocity the server set, with vanilla's collision, then
     * applies the client's gravity and air drag. A real client does both by itself.
     */
    private static void clientStep(ServerPlayer player) {
        player.move(MoverType.SELF, player.getDeltaMovement());
        Vec3 after = player.getDeltaMovement();
        double gravity = player.isNoGravity() ? 0 : 0.08;
        player.setDeltaMovement(after.x * 0.91, (after.y - gravity) * 0.98, after.z * 0.91);
    }

    /** A sky room in layer 1 (as TowingTest's) with a pod hung in the middle of it, for a line that pays out to its full length. */
    private static Room room(GameTestHelper helper, int index) {
        ServerLevel level = TestLayers.level(helper, 1);
        int x = ROOM_X + index * ROOM_STEP;
        int z = ROOM_Z;
        RoomCarver.carve(level, x, x + 50, ROOM_FLOOR - 12, ROOM_FLOOR - 1, z - 8, z + 18, Blocks.STONE);
        RoomCarver.carve(level, x, x + 50, ROOM_FLOOR, ROOM_FLOOR + 60, z - 8, z + 18, Blocks.AIR);
        Room room = new Room(level, x);
        List<BlockPos> columns = new ArrayList<>();
        for (int cx = x; cx <= x + 50 + 15; cx += 16) {
            columns.add(new BlockPos(Math.min(cx, x + 50), ROOM_FLOOR, z));
        }
        int[] ready = {0};
        FarChunks.awaitEntityTicking(helper, level, columns, i -> {
            if (++ready[0] == columns.size()) {
                room.ready = true;
            }
        });
        return room;
    }

    /** The room a scene plays in, its pod once the chunks tick, and the scene's age in ticks. */
    private static final class Room {
        final ServerLevel level;
        final int x;
        boolean ready;
        boolean finished;

        @Nullable
        PodEntity pod;

        int age;

        Room(ServerLevel level, int x) {
            this.level = level;
            this.x = x;
        }

        /** The pod, hung in the air in the middle of the room (it is held still every tick). */
        PodEntity pod() {
            return Optional.ofNullable(pod).orElseThrow();
        }

        /**
         * Runs {@code setup} on the first tick the chunks tick, and the body it returns on every tick after. The pod is spawned first and
         * held where it is, the way a parked pod rests.
         */
        void when(GameTestHelper helper, java.util.function.Supplier<Runnable> setup) {
            Runnable[] body = {null};
            Vec3[] hold = {null};
            TestTicks.each(helper, () -> {
                if (!ready || finished) {
                    return;
                }
                try {
                    if (body[0] == null) {
                        PodEntity spawned = TestEntities.create(PodRegistry.POD, level);
                        hold[0] = new Vec3(x + 4.5, ROOM_FLOOR + 40, ROOM_Z + 5.5);
                        spawned.setPos(hold[0]);
                        level.addFreshEntity(spawned);
                        spawned.setNoGravity(true);
                        pod = spawned;
                        body[0] = setup.get();
                        return;
                    }
                    PodEntity held = Optional.ofNullable(pod).orElseThrow();
                    if (!held.isRemoved()) {
                        held.setPos(hold[0]);
                        held.setDeltaMovement(Vec3.ZERO);
                    }
                    age++;
                    body[0].run();
                } catch (RuntimeException failed) {
                    discard();
                    throw failed;
                }
            });
        }

        void finish(GameTestHelper helper) {
            finished = true;
            discard();
            helper.succeed();
        }

        private void discard() {
            if (pod != null) {
                pod.discard();
            }
        }
    }
}
