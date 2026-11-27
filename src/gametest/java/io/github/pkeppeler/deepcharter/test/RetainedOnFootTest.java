package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.CreatureTuning;
import io.github.pkeppeler.deepcharter.creature.DrawSources;
import io.github.pkeppeler.deepcharter.creature.FootGrips;
import io.github.pkeppeler.deepcharter.creature.HandLights;
import io.github.pkeppeler.deepcharter.creature.LamplessFigure;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreTuning;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.repair.CompanyRecovery;
import io.github.pkeppeler.deepcharter.repair.RecoveryTuning;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TestCleanups;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server GameTests for #916, the Retained on foot: a person in the dark with no light in either hand draws a figure that clings, weighs on
 * them as ore does, drains their hunger to a floor and never hurts them, and the grip ends only when the figure dies. Each test builds a
 * closed, dark room in a far chunk, with the person at one end and the figure at the other.
 */
public class RetainedOnFootTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(RetainedOnFootTest.class);
    private static final CreatureTuning TUNING = CreatureTuning.DEFAULT;
    private static final CreatureTuning.Foot FOOT = TUNING.foot();

    /** The room's half width; 13 blocks across with its walls, inside the one chunk that is forced. */
    private static final int HALF = 6;

    private static final int ROOM_HEIGHT = 8;
    private static final int FIRST_SLOT_X = 7432;
    private static final int SLOT_Z_SPACING = 64;
    private static final int FLOOR_Y = TestLayers.worldY(TestLayers.SURFACE, 100);
    /** Ticks for a figure to walk 10 blocks to a person: its walk is about 2 blocks a second, with a margin for a loaded runner. */
    private static final int APPROACH_BUDGET_TICKS = 11 * 20;
    /** Ticks a grip needs to drain a full food bar to the floor: 14 points at 6 seconds is 1,680 ticks, with a margin for a loaded runner. */
    private static final int DRAIN_BUDGET_TICKS = 2400;

    /** Ticks the grip is watched after food reached the floor: over two points of drain at 6 seconds a point, so a missing floor shows. */
    private static final int HOLD_AT_FLOOR_TICKS = 260;
    /** Ticks a figure stranded in a chunk that does not tick is watched for a drain: the same over-two-points span as the floor hold. */
    private static final int FROZEN_FOOD_WINDOW_TICKS = 260;
    /** Ticks after the winch lands in which the figure is checked for jumping: a figure that teleported to the person shows in the first ticks. */
    private static final int JUMP_WATCH_TICKS = 5;
    /** Ticks a light, or a person's answer that does not kill, is watched. */
    private static final int WATCH_TICKS = 160;
    /** Ticks between two blows by hand: a hurt mob ignores a blow that is no stronger than the last for 10 ticks. */
    private static final int BLOW_SPACING_TICKS = 12;
    /** Ticks a test flare burns. */
    private static final int FLARE_BURN_TICKS = 60;
    /** The reach a test flare keeps the dark off within: a stand-in, smaller than the real flare's reach. */
    private static final double FLARE_REACH = 6.0;
    /** Ticks a dead figure has to be gone, and the person freed: the dissolve (20 ticks) and a margin. */
    private static final int FREED_BUDGET_TICKS = 60;
    /** Ticks for the crew's winch to start and the figure to trail out of its ticking chunk: the fuel burns out, then the clock (fast forwarded) runs. */
    private static final int TRAIL_BUDGET_TICKS = 200;
    /** The shortest haul that counts, in blocks: the room is thousands of blocks from the colony. */
    private static final double MINIMUM_HAUL_BLOCKS = 1000;
    /** The figure's latch speed, blocks a tick. */
    private static final double FOOT_LATCH_SPEED =
            CreatureTuning.DEFAULT.cling().latchSpeed();

    private static final String APPROACH_KEY = "creature.deepcharter.foot_approach";
    private static final String GRAB_KEY = "creature.deepcharter.foot_grab";
    private static final String DEAD_KEY = "creature.deepcharter.foot_dead";
    private static final String FIRST_KEY = "creature.deepcharter.foot_first";
    /** The server's difficulty is global, so the test that changes it runs in a batch of its own, with no other figure test beside it. */
    private static final String ALONE = "deepcharter-test:alone";

    private static final float EPSILON = 1e-4f;
    /** What a sword hits for, and a fist. */
    private static final float SWORD_DAMAGE = 6f;

    private static final float FIST_DAMAGE = 1f;

    private static final AtomicInteger JOINS = new AtomicInteger();

    /** A closed, dark room with a person at its east end and a figure at its west end. */
    private record Room(ServerLevel level, BlockPos origin, MockPlayer person, LamplessFigure figure) {
        static Room build(GameTestHelper helper, ServerLevel level, BlockPos origin, int figureWest) {
            for (int u = -HALF - 1; u <= HALF + 1; u++) {
                for (int v = -HALF - 1; v <= HALF + 1; v++) {
                    for (int y = -1; y <= ROOM_HEIGHT; y++) {
                        boolean shell =
                                y == -1 || y == ROOM_HEIGHT || Math.abs(u) == HALF + 1 || Math.abs(v) == HALF + 1;
                        // room-carver: a closed room of its own stone shell in the overworld test structure, not layer
                        // rock
                        level.setBlock(
                                origin.offset(u, y, v), (shell ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 3);
                    }
                }
            }
            MockPlayer person = MockPlayers.join(helper, "retained-walker-" + JOINS.incrementAndGet());
            person.teleportTo(level, Vec3.atBottomCenterOf(origin.east(HALF - 1)), 90f, 0f);
            person.player().setGameMode(GameType.SURVIVAL);
            LamplessFigure figure = TestEntities.create(CreatureRegistry.LAMPLESS_FIGURE, level);
            figure.setPos(Vec3.atBottomCenterOf(origin.west(figureWest)));
            figure.setHeading(Direction.NORTH);
            level.addFreshEntity(figure);
            return new Room(level, origin, person, figure);
        }

        ServerPlayer player() {
            return person.player();
        }

        /** A full food bar with no saturation behind it, so that every point the grip takes shows at once. */
        void fillFood() {
            FoodData food = player().getFoodData();
            food.setFoodLevel(20);
            food.setSaturation(0f);
        }

        void clear() {
            figure.discard();
            person.leave();
        }
    }

    /**
     * A person in the dark draws a figure from 10 blocks away; it clings, the person moves as if they carried ore, the grip takes their
     * hunger down to the floor and no further, never hurts them and does not let go. The numbers it logs are the pacing of #916.
     */
    @GameTest(
            maxTicks = FarChunks.AWAIT_BUDGET_TICKS
                    + APPROACH_BUDGET_TICKS
                    + DRAIN_BUDGET_TICKS
                    + HOLD_AT_FLOOR_TICKS
                    + 60)
    public void aPersonInTheDarkDrawsAFigureThatClingsSlowsAndDrainsHungerToTheFloor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(0);
        Room[] made = {null};
        int[] phase = {0};
        long[] since = {0};
        float[] speedFree = {0};
        TestTicks.each(helper, () -> {
            Room room = made[0];
            if (room == null) {
                return;
            }
            ServerPlayer player = room.player();
            long waited = helper.getTick() - since[0];
            switch (phase[0]) {
                case 0 -> {
                    if (room.figure.isClinging()) {
                        LOGGER.info(
                                "[retained-foot] the figure walked 10 blocks to a person in the dark and took hold in {} ticks ({} s)",
                                waited,
                                waited / 20f);
                        if (FootGrips.holder(player) == null) {
                            throw failure(helper, "the figure clings but the person is not held");
                        }
                        phase[0] = 1;
                        since[0] = helper.getTick();
                    } else if (waited > APPROACH_BUDGET_TICKS) {
                        throw failure(
                                helper,
                                "the figure had not taken hold of the person in the dark %s ticks after it was placed 10 blocks away (it stands at %s, the person at %s)",
                                waited,
                                room.figure.position(),
                                player.position());
                    }
                }
                case 1 -> {
                    expectHeld(helper, room, waited);
                    if (waited == 5) {
                        expectSlowedLikeOre(helper, room, speedFree[0]);
                        LOGGER.info(
                                "[retained-foot] walking speed {} free, {} held: {} blocks a second to {} (attribute 0.1 is {})",
                                speedFree[0],
                                player.getAttributeValue(Attributes.MOVEMENT_SPEED),
                                speedFree[0] * 43.17,
                                player.getAttributeValue(Attributes.MOVEMENT_SPEED) * 43.17,
                                "4.317");
                    }
                    if (player.getFoodData().getFoodLevel() <= FOOT.hungerFloor()) {
                        LOGGER.info(
                                "[retained-foot] the grip drained food 20 to the floor of {} in {} ticks: {} s a point, {} s to the floor",
                                FOOT.hungerFloor(),
                                waited,
                                waited / 20f / (20 - FOOT.hungerFloor()),
                                waited / 20f);
                        phase[0] = 2;
                        since[0] = helper.getTick();
                    } else if (waited > DRAIN_BUDGET_TICKS) {
                        throw failure(
                                helper,
                                "food was %s %s ticks after the grab; the floor is %s",
                                player.getFoodData().getFoodLevel(),
                                waited,
                                FOOT.hungerFloor());
                    }
                }
                default -> {
                    expectHeld(helper, room, waited);
                    if (player.getFoodData().getFoodLevel() < FOOT.hungerFloor()) {
                        throw failure(
                                helper,
                                "the grip took food to %s, under its floor of %s",
                                player.getFoodData().getFoodLevel(),
                                FOOT.hungerFloor());
                    }
                    if (waited >= HOLD_AT_FLOOR_TICKS) {
                        expectUnharmed(helper, room);
                        room.clear();
                        helper.succeed();
                    }
                }
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> {
            made[0] = Room.build(helper, level, origin, HALF - 1);
            made[0].fillFood();
            speedFree[0] = (float) made[0].player().getAttributeValue(Attributes.MOVEMENT_SPEED);
            since[0] = helper.getTick();
            if (!FootGrips.draws(made[0].player())) {
                throw failure(helper, "a person on foot in the dark with empty hands does not draw a figure");
            }
        });
    }

    /** The speed the person has is the free speed less what the same mass of ore takes, and the Overburdened status says so. */
    private static void expectSlowedLikeOre(GameTestHelper helper, Room room, float speedFree) {
        expectOreSpeed(helper, room, speedFree);
        MobEffectInstance status = room.player().getEffect(OreRegistry.OVERBURDENED);
        if (status == null) {
            throw failure(helper, "a held person shows no Overburdened status");
        }
    }

    /** The speed alone: a person seated in a pod shows no status, as a pod carries its pilot at its own speed. */
    private static void expectOreSpeed(GameTestHelper helper, Room room, float speedFree) {
        OreTuning ore = OreTuning.DEFAULT;
        double slowdown = Math.min(ore.maxSlowdown(), FOOT.loadMass() * ore.slowdownPerMass());
        float expected = (float) (speedFree * (1 - slowdown));
        float speed = (float) room.player().getAttributeValue(Attributes.MOVEMENT_SPEED);
        if (Math.abs(speed - expected) > EPSILON) {
            throw failure(
                    helper,
                    "a held person walks at %s; %s of ore mass takes it from %s to %s",
                    speed,
                    FOOT.loadMass(),
                    speedFree,
                    expected);
        }
    }

    private static void expectHeld(GameTestHelper helper, Room room, long ticks) {
        if (!room.figure.isClinging() || !room.figure.isAlive() || FootGrips.holder(room.player()) == null) {
            throw failure(
                    helper,
                    "the figure let go %s ticks after it took hold, with no one to strike it (clinging %s, alive %s, held %s)",
                    ticks,
                    room.figure.isClinging(),
                    room.figure.isAlive(),
                    FootGrips.holder(room.player()) != null);
        }
        if (ticks > 10 && !room.player().getBoundingBox().inflate(0.25).intersects(room.figure.getBoundingBox())) {
            throw failure(
                    helper,
                    "the clinging figure at %s is off the person at %s",
                    room.figure.position(),
                    room.player().position());
        }
    }

    /** The person's health is untouched, and they were told once that it came, once that it took hold, and the rule was said once. */
    private static void expectUnharmed(GameTestHelper helper, Room room) {
        ServerPlayer player = room.player();
        if (player.getHealth() != player.getMaxHealth()) {
            throw failure(helper, "the person's health went to %s while the figure held them", player.getHealth());
        }
        long came = told(room.person.actionBarMessages(), APPROACH_KEY);
        long took = told(room.person.actionBarMessages(), GRAB_KEY);
        long rule = told(room.person.chatMessages(), FIRST_KEY);
        if (came != 1 || took != 1 || rule != 1) {
            throw failure(
                    helper,
                    "the person should be told once that it came, once that it took hold, and the rule once (came %s, hold %s, rule %s)",
                    came,
                    took,
                    rule);
        }
    }

    private static long told(List<Component> lines, String key) {
        return lines.stream()
                .filter(line -> line.getContents() instanceof TranslatableContents contents
                        && contents.getKey().equals(key))
                .count();
    }

    /** A torch in hand keeps one off: a figure 3 blocks away that stands in the same dark room never takes hold. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + WATCH_TICKS + 60)
    public void aLightInHandKeepsAFigureOff(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(1);
        Room[] made = {null};
        long[] since = {0};
        TestTicks.each(helper, () -> {
            Room room = made[0];
            if (room == null) {
                return;
            }
            if (room.figure.isClinging() || FootGrips.holder(room.player()) != null) {
                throw failure(helper, "a figure took hold of a person who holds a torch");
            }
            if (FootGrips.draws(room.player())) {
                throw failure(helper, "a person who holds a torch draws a figure");
            }
            if (helper.getTick() - since[0] >= WATCH_TICKS) {
                expectNoFoodLoss(helper, room);
                room.clear();
                helper.succeed();
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> {
            made[0] = Room.build(helper, level, origin, 2);
            made[0].fillFood();
            made[0].player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TORCH));
            since[0] = helper.getTick();
        });
    }

    private static void expectNoFoodLoss(GameTestHelper helper, Room room) {
        if (room.player().getFoodData().getFoodLevel() < 20) {
            throw failure(
                    helper,
                    "a person who holds a torch lost food: %s",
                    room.player().getFoodData().getFoodLevel());
        }
    }

    /** A light in either hand counts and an ordinary item does not; a lantern in the off hand counts, a stone block (no light) does not. */
    @GameTest
    public void aLightInEitherHandKeepsOneOffAndAStoneDoesNot(GameTestHelper helper) {
        MockPlayer person = MockPlayers.join(helper, "retained-hands-" + JOINS.incrementAndGet());
        ServerPlayer player = person.player();
        player.setGameMode(GameType.SURVIVAL);
        try {
            expectDraws(helper, player, true, "empty hands");
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE));
            player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.IRON_SWORD));
            expectDraws(helper, player, true, "stone and a sword");
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TORCH));
            expectDraws(helper, player, false, "a torch in the main hand");
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.LANTERN));
            expectDraws(helper, player, false, "a lantern in the off hand");
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            expectDraws(helper, player, true, "empty hands again");
            player.setGameMode(GameType.CREATIVE);
            expectDraws(helper, player, false, "a creative player");
            player.setGameMode(GameType.SPECTATOR);
            expectDraws(helper, player, false, "a spectator");
            helper.succeed();
        } finally {
            person.leave();
        }
    }

    /** Someone in a pod's seat is the pod's business (the dark pod draws the figure), not on foot: they draw none. */
    @GameTest
    public void aPersonSeatedInAPodIsNotOnFoot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MockPlayer person = MockPlayers.join(helper, "retained-seated-" + JOINS.incrementAndGet());
        ServerPlayer player = person.player();
        player.setGameMode(GameType.SURVIVAL);
        PodEntity pod = TestEntities.create(PodRegistry.POD, level);
        pod.setPos(player.position());
        level.addFreshEntity(pod);
        try {
            expectDraws(helper, player, true, "a person on foot, before boarding");
            if (!player.startRiding(pod)) {
                throw failure(helper, "the person could not mount the pod");
            }
            expectDraws(helper, player, false, "a person in a pod's seat");
        } finally {
            pod.discard();
            person.leave();
        }
        helper.succeed();
    }

    private static void expectDraws(GameTestHelper helper, ServerPlayer player, boolean expected, String who) {
        if (FootGrips.draws(player) != expected) {
            throw failure(helper, "%s should%s draw a figure", who, expected ? "" : " not");
        }
    }

    /** Block light 3 where a person stands is lit, and 2 is dark: the same line the spawn rule and the lit pod use. */
    @GameTest(maxTicks = 3 * FarChunks.AWAIT_BUDGET_TICKS + 60)
    public void aPersonDrawsAFigureOnlyWhereTheBlockLightIsUnderThree(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(2);
        Room[] made = {null};
        int[] phase = {0};
        FarChunks.Deadline[] deadline = {FarChunks.deadline()};
        int lit = TUNING.litBlockLight();
        TestTicks.each(helper, () -> {
            Room room = made[0];
            if (room == null) {
                return;
            }
            BlockPos feet = room.player().blockPosition();
            int expected = phase[0] == 0 ? lit : lit - 1;
            int brightness = level.getBrightness(LightLayer.BLOCK, feet);
            deadline[0].await(
                    helper,
                    level,
                    brightness == expected,
                    () -> String.format(
                            "block light at the person's feet is %s, expected %s after a light block was set",
                            brightness, expected));
            if (brightness != expected) {
                return;
            }
            expectDraws(helper, room.player(), phase[0] == 1, "a person at block light " + expected);
            if (phase[0] == 0) {
                phase[0] = 1;
                deadline[0] = FarChunks.deadline();
                level.setBlock(feet, light(lit - 1), 3);
            } else {
                room.clear();
                helper.succeed();
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> {
            made[0] = Room.build(helper, level, origin, 0);
            // Far from the person, so that it never reaches them while the light settles.
            made[0].figure.discard();
            level.setBlock(made[0].player().blockPosition(), light(lit), 3);
            deadline[0] = FarChunks.deadline();
        });
    }

    /** A light that comes after the grip does not free the person, as it does not free a pod, and the grip goes on weighing and draining. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + APPROACH_BUDGET_TICKS + WATCH_TICKS + 60)
    public void aLightDoesNotFreeAPersonAlreadyHeld(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(3);
        Room[] made = {null};
        long[] litAt = {-1};
        int[] foodAtLight = {0};
        TestTicks.each(helper, () -> {
            Room room = made[0];
            if (room == null) {
                return;
            }
            if (litAt[0] < 0) {
                if (room.figure.isClinging()) {
                    litAt[0] = helper.getTick();
                    foodAtLight[0] = room.player().getFoodData().getFoodLevel();
                    room.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TORCH));
                    level.setBlock(room.player().blockPosition(), light(15), 3);
                } else if (helper.getTick() > APPROACH_BUDGET_TICKS + FarChunks.AWAIT_BUDGET_TICKS) {
                    throw failure(helper, "the figure had not taken hold of the person");
                }
                return;
            }
            expectHeld(helper, room, helper.getTick() - litAt[0]);
            if (FootGrips.holder(room.player()) == null) {
                throw failure(helper, "a light freed a held person");
            }
            if (room.player().getFoodData().getFoodLevel() < foodAtLight[0]
                    && room.player().getFoodData().getFoodLevel() < foodAtLight[0] - 1) {
                throw failure(helper, "food fell faster than the grip drains it");
            }
            if (helper.getTick() - litAt[0] >= WATCH_TICKS) {
                if (room.player().getEffect(OreRegistry.OVERBURDENED) == null) {
                    throw failure(helper, "a light took the weight off a held person");
                }
                level.setBlock(room.player().blockPosition(), Blocks.AIR.defaultBlockState(), 3);
                room.clear();
                helper.succeed();
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> {
            made[0] = Room.build(helper, level, origin, HALF - 1);
            made[0].fillFood();
        });
    }

    /** A crewmate's blows free a held person: two blows of a sword kill the figure, the weight goes and the person is told. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + APPROACH_BUDGET_TICKS + FREED_BUDGET_TICKS + 120)
    public void aCrewmatesBlowsFreeAHeldPerson(GameTestHelper helper) {
        expectBlowsFree(helper, slot(4), true, SWORD_DAMAGE);
    }

    /** The person's own blows free them too: any blow ends it. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + APPROACH_BUDGET_TICKS + FREED_BUDGET_TICKS + 120)
    public void aHeldPersonStrikesTheFigureOffThemselves(GameTestHelper helper) {
        expectBlowsFree(helper, slot(5), false, SWORD_DAMAGE);
    }

    /** (Pacing, #916) A held person with bare hands: a fist hits for 1, so twelve blows kill a figure of 12. The test logs what that costs in hunger. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + APPROACH_BUDGET_TICKS + FREED_BUDGET_TICKS + 400)
    public void aHeldPersonWithBareHandsKillsTheFigureInTwelveBlows(GameTestHelper helper) {
        expectBlowsFree(helper, slot(8), false, FIST_DAMAGE);
    }

    private static void expectBlowsFree(GameTestHelper helper, BlockPos origin, boolean byCrewmate, float damage) {
        ServerLevel level = helper.getLevel();
        Room[] made = {null};
        MockPlayer[] crew = {null};
        long[] blowAt = {-1};
        long[] diedAt = {-1};
        int[] blows = {0};
        long[] heldAt = {0};
        int[] foodAtHold = {0};
        TestTicks.each(helper, () -> {
            Room room = made[0];
            if (room == null) {
                return;
            }
            ServerPlayer striker = byCrewmate && crew[0] != null ? crew[0].player() : room.player();
            if (blowAt[0] < 0) {
                if (room.figure.isClinging()) {
                    if (byCrewmate) {
                        crew[0] = MockPlayers.join(helper, "retained-crew-" + JOINS.incrementAndGet());
                        crew[0].teleportTo(level, room.figure.position().add(0, 0, 1), 0f, 0f);
                    }
                    blowAt[0] = helper.getTick() - BLOW_SPACING_TICKS;
                    heldAt[0] = helper.getTick();
                    foodAtHold[0] = room.player().getFoodData().getFoodLevel();
                } else if (helper.getTick() > APPROACH_BUDGET_TICKS + FarChunks.AWAIT_BUDGET_TICKS) {
                    throw failure(helper, "the figure had not taken hold of the person");
                }
                return;
            }
            if (diedAt[0] < 0) {
                if (helper.getTick() - blowAt[0] >= BLOW_SPACING_TICKS && room.figure.isAlive()) {
                    blowAt[0] = helper.getTick();
                    room.figure.hurtServer(level, level.damageSources().playerAttack(striker), damage);
                    blows[0]++;
                }
                if (!room.figure.isAlive()) {
                    diedAt[0] = helper.getTick();
                    int expectedBlows = (int) Math.ceil(room.figure.getMaxHealth() / damage);
                    if (blows[0] != expectedBlows) {
                        throw failure(
                                helper,
                                "a figure of %s health took %s blows of %s to die, not %s",
                                room.figure.getMaxHealth(),
                                blows[0],
                                damage,
                                expectedBlows);
                    }
                    LOGGER.info(
                            "[retained-foot] {} blows of {} killed the figure {} ticks after it took hold; food {} to {}",
                            blows[0],
                            damage,
                            diedAt[0] - heldAt[0],
                            foodAtHold[0],
                            room.player().getFoodData().getFoodLevel());
                }
                return;
            }
            if (FootGrips.holder(room.player()) != null || room.figure.isClinging()) {
                throw failure(helper, "a dead figure still holds the person");
            }
            boolean weightGone = room.player().getEffect(OreRegistry.OVERBURDENED) == null;
            float speed = (float) room.player().getAttributeValue(Attributes.MOVEMENT_SPEED);
            if (weightGone && room.figure.isRemoved()) {
                if (Math.abs(speed - 0.1f) > EPSILON) {
                    throw failure(helper, "the freed person walks at %s, not their own speed", speed);
                }
                if (told(room.person.actionBarMessages(), DEAD_KEY) != 1) {
                    throw failure(helper, "the person was not told once that it is gone");
                }
                if (crew[0] != null) {
                    crew[0].leave();
                }
                room.clear();
                helper.succeed();
            } else if (helper.getTick() - diedAt[0] > FREED_BUDGET_TICKS) {
                throw failure(
                        helper,
                        "%s ticks after the figure died: weight gone %s, figure removed %s",
                        helper.getTick() - diedAt[0],
                        weightGone,
                        room.figure.isRemoved());
            }
        });
        FarChunks.awaitEntityTicking(
                helper, level, origin, () -> made[0] = Room.build(helper, level, origin, HALF - 1));
    }

    /** A figure that goes without dying (its chunk unloads, a command removes it) leaves no weight behind. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + APPROACH_BUDGET_TICKS + FREED_BUDGET_TICKS + 60)
    public void aFigureThatIsRemovedLeavesNoWeightBehind(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(6);
        Room[] made = {null};
        long[] removedAt = {-1};
        TestTicks.each(helper, () -> {
            Room room = made[0];
            if (room == null) {
                return;
            }
            if (removedAt[0] < 0) {
                if (room.figure.isClinging()) {
                    room.figure.discard();
                    removedAt[0] = helper.getTick();
                } else if (helper.getTick() > APPROACH_BUDGET_TICKS + FarChunks.AWAIT_BUDGET_TICKS) {
                    throw failure(helper, "the figure had not taken hold of the person");
                }
                return;
            }
            if (room.player().getEffect(OreRegistry.OVERBURDENED) == null && FootGrips.holder(room.player()) == null) {
                room.clear();
                helper.succeed();
            } else if (helper.getTick() - removedAt[0] > FREED_BUDGET_TICKS) {
                throw failure(helper, "the weight stayed on a person whose figure was removed");
            }
        });
        FarChunks.awaitEntityTicking(
                helper, level, origin, () -> made[0] = Room.build(helper, level, origin, HALF - 1));
    }

    /** A held person who is switched to creative is let go of, and a figure never holds a creative player. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + APPROACH_BUDGET_TICKS + FREED_BUDGET_TICKS + 60)
    public void aHeldPersonWhoTurnsCreativeIsLetGo(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(7);
        Room[] made = {null};
        long[] changedAt = {-1};
        TestTicks.each(helper, () -> {
            Room room = made[0];
            if (room == null) {
                return;
            }
            if (changedAt[0] < 0) {
                if (room.figure.isClinging()) {
                    room.player().setGameMode(GameType.CREATIVE);
                    changedAt[0] = helper.getTick();
                } else if (helper.getTick() > APPROACH_BUDGET_TICKS + FarChunks.AWAIT_BUDGET_TICKS) {
                    throw failure(helper, "the figure had not taken hold of the person");
                }
                return;
            }
            if (!room.figure.isClinging()
                    && room.player().getEffect(OreRegistry.OVERBURDENED) == null
                    && FootGrips.holder(room.player()) == null) {
                room.clear();
                helper.succeed();
            } else if (helper.getTick() - changedAt[0] > FREED_BUDGET_TICKS) {
                throw failure(helper, "a creative player is still held");
            }
        });
        FarChunks.awaitEntityTicking(
                helper, level, origin, () -> made[0] = Room.build(helper, level, origin, HALF - 1));
    }

    /**
     * A held person who climbs into a pod stays held: the figure trails the seat (it has no physics and closes at 8 blocks a second), and
     * the drain goes on down to the floor. Only its death frees them (W1 of the #977 review).
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + APPROACH_BUDGET_TICKS + 600)
    public void aHeldPersonWhoBoardsAPodStaysHeldAndDrainedWhileTheFigureTrails(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(9);
        Room[] made = {null};
        PodEntity[] pod = {null};
        long[] boardedAt = {-1};
        Vec3[] podMovedFrom = {null};
        TestTicks.each(helper, () -> {
            Room room = made[0];
            if (room == null) {
                return;
            }
            ServerPlayer player = room.player();
            if (boardedAt[0] < 0) {
                if (room.figure.isClinging()) {
                    pod[0] = TestEntities.create(PodRegistry.POD, level);
                    pod[0].setPos(player.position());
                    level.addFreshEntity(pod[0]);
                    if (!player.startRiding(pod[0])) {
                        throw failure(helper, "the held person could not mount the pod");
                    }
                    boardedAt[0] = helper.getTick();
                } else if (helper.getTick() > APPROACH_BUDGET_TICKS + FarChunks.AWAIT_BUDGET_TICKS) {
                    throw failure(helper, "the figure had not taken hold of the person");
                }
                return;
            }
            long seated = helper.getTick() - boardedAt[0];
            if (FootGrips.holder(player) == null || !room.figure.isClinging() || !room.figure.isAlive()) {
                throw failure(helper, "boarding a pod freed a held person, %s ticks after", seated);
            }
            if (seated == 10) {
                podMovedFrom[0] = pod[0].position();
                pod[0].setPos(podMovedFrom[0].add(-4, 0, 0));
            }
            if (seated > 40) {
                expectHeld(helper, room, seated);
                if (player.getFoodData().getFoodLevel() <= FOOT.hungerFloor()) {
                    pod[0].discard();
                    room.clear();
                    helper.succeed();
                }
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> {
            made[0] = Room.build(helper, level, origin, HALF - 1);
            made[0].fillFood();
            made[0].player().getFoodData().setFoodLevel(FOOT.hungerFloor() + 2);
        });
    }

    /**
     * A person who quits mid-grip is let go of, and rejoins with no residual weight: no ore modifier and no Overburdened level, though
     * the status is saved with the player. The figure lets go too (W2 of the #977 review). The same weakness as the pod grip: a relog frees.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + APPROACH_BUDGET_TICKS + 300)
    public void aQuitMidGripLeavesNoWeightOnRejoin(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(10);
        Room[] made = {null};
        MockPlayer[] again = {null};
        UUID[] id = {null};
        long[] latchedAt = {-1};
        long[] quitAt = {-1};
        long[] rejoinedAt = {-1};
        TestTicks.each(helper, () -> {
            Room room = made[0];
            if (room == null) {
                return;
            }
            if (latchedAt[0] < 0) {
                if (room.figure.isClinging()) {
                    latchedAt[0] = helper.getTick();
                    id[0] = room.player().getUUID();
                } else if (helper.getTick() > APPROACH_BUDGET_TICKS + FarChunks.AWAIT_BUDGET_TICKS) {
                    throw failure(helper, "the figure had not taken hold of the person");
                }
                return;
            }
            if (quitAt[0] < 0) {
                if (helper.getTick() - latchedAt[0] >= 10) {
                    if (room.player().getEffect(OreRegistry.OVERBURDENED) == null) {
                        throw failure(helper, "the held person had no weight before the quit");
                    }
                    room.person.leave();
                    quitAt[0] = helper.getTick();
                }
                return;
            }
            if (rejoinedAt[0] < 0) {
                if (!room.figure.isClinging()) {
                    room.figure.discard();
                    again[0] = MockPlayers.join(helper, "retained-walker-back", id[0]);
                    again[0].teleportTo(level, Vec3.atBottomCenterOf(origin.east(HALF - 1)), 90f, 0f);
                    again[0].player().setGameMode(GameType.SURVIVAL);
                    // A torch, so that nothing else takes hold of them in this dark room while the weight is read.
                    again[0].player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TORCH));
                    rejoinedAt[0] = helper.getTick();
                } else if (helper.getTick() - quitAt[0] > FREED_BUDGET_TICKS) {
                    throw failure(helper, "the figure still clings %s ticks after its person quit", FREED_BUDGET_TICKS);
                }
                return;
            }
            ServerPlayer back = again[0].player();
            boolean weightGone = back.getEffect(OreRegistry.OVERBURDENED) == null
                    && Math.abs(back.getAttributeValue(Attributes.MOVEMENT_SPEED) - 0.1) < EPSILON;
            if (weightGone && FootGrips.holder(back) == null) {
                again[0].leave();
                helper.succeed();
            } else if (helper.getTick() - rejoinedAt[0] > FREED_BUDGET_TICKS) {
                throw failure(
                        helper,
                        "the rejoined person still carries weight: status shown %s, speed %s",
                        back.hasEffect(OreRegistry.OVERBURDENED),
                        back.getAttributeValue(Attributes.MOVEMENT_SPEED));
            }
        });
        FarChunks.awaitEntityTicking(
                helper, level, origin, () -> made[0] = Room.build(helper, level, origin, HALF - 1));
    }

    /** A held person who changes level is let go of: a figure does not cross a level change, and leaves no weight (the levels case of the #977 review). */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + APPROACH_BUDGET_TICKS + FREED_BUDGET_TICKS + 60)
    public void aHeldPersonWhoChangesLevelIsLetGo(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(12);
        Room[] made = {null};
        long[] movedAt = {-1};
        TestTicks.each(helper, () -> {
            Room room = made[0];
            if (room == null) {
                return;
            }
            if (movedAt[0] < 0) {
                if (room.figure.isClinging()) {
                    ServerLevel other = Objects.requireNonNull(level.getServer().getLevel(Level.NETHER), "no nether");
                    // fixed-pos: the nether's own bedrock roof, only a place that is not the room's level; nothing
                    // reads it
                    room.person.teleportTo(other, Vec3.atBottomCenterOf(new BlockPos(0, 128, 0)), 0f, 0f);
                    // A torch, so that nothing else takes hold of them in the dark of the other level.
                    room.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TORCH));
                    movedAt[0] = helper.getTick();
                } else if (helper.getTick() > APPROACH_BUDGET_TICKS + FarChunks.AWAIT_BUDGET_TICKS) {
                    throw failure(helper, "the figure had not taken hold of the person");
                }
                return;
            }
            if (!room.figure.isClinging() && !room.player().hasEffect(OreRegistry.OVERBURDENED)) {
                room.clear();
                helper.succeed();
            } else if (helper.getTick() - movedAt[0] > FREED_BUDGET_TICKS) {
                throw failure(helper, "a person who changed level is still held");
            }
        });
        FarChunks.awaitEntityTicking(
                helper, level, origin, () -> made[0] = Room.build(helper, level, origin, HALF - 1));
    }

    /**
     * A held, seated pilot is winched home by the Company crew ({@code CompanyRecovery}, #965) to the colony in the same level, thousands
     * of blocks away. The grip is kept, as the design says: only the figure's death frees. The figure trails at its latch speed with no
     * physics, never jumping to the person, and the weight stays. Its drain runs in the figure's own tick, and a figure ticks only
     * where a player is near, so once the figure has trailed out of its ticking chunk the drain stops and the figure stands where it was
     * left. When that chunk unloads the figure goes with it and the person is freed, the same weakness as a relog (#999).
     */
    @GameTest(
            maxTicks = FarChunks.AWAIT_BUDGET_TICKS
                    + APPROACH_BUDGET_TICKS
                    + TRAIL_BUDGET_TICKS
                    + FROZEN_FOOD_WINDOW_TICKS
                    + FREED_BUDGET_TICKS
                    + 200)
    public void aSameLevelWinchKeepsTheGripButTheFigureOnlyTrailsWhereItsChunkTicks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(15);
        Room[] made = {null};
        PodEntity[] pod = {null};
        float[] speedFree = {0};
        long[] boardedAt = {0};
        long[] hauledAt = {-1};
        long[] frozenAt = {-1};
        long[] releasedAt = {-1};
        int[] foodAtFreeze = {0};
        Vec3[] last = {null};
        // The winched pod lands on the colony pad, which tests share: it goes on every exit, not only on success.
        TestCleanups.onFinish(helper, () -> {
            if (pod[0] != null) {
                pod[0].discard();
            }
            if (made[0] != null) {
                made[0].clear();
            }
        });
        TestTicks.each(helper, () -> {
            Room room = made[0];
            if (room == null) {
                return;
            }
            ServerPlayer player = room.player();
            if (pod[0] == null) {
                if (room.figure.isClinging()) {
                    pod[0] = TestEntities.create(PodRegistry.POD, level);
                    pod[0].setPos(player.position());
                    level.addFreshEntity(pod[0]);
                    if (!player.startRiding(pod[0], true, false)) {
                        throw failure(helper, "the held person could not mount the pod");
                    }
                    // The last sliver of fuel: the pod burns it and strands itself, and the crew is called.
                    pod[0].setFuel(0.01f);
                    boardedAt[0] = helper.getTick();
                } else if (helper.getTick() > APPROACH_BUDGET_TICKS + FarChunks.AWAIT_BUDGET_TICKS) {
                    throw failure(helper, "the figure had not taken hold of the person");
                }
                return;
            }
            if (hauledAt[0] < 0) {
                PodEntity riding = player.getVehicle() instanceof PodEntity ride ? ride : pod[0];
                Versioned.readable(riding, CompanyRecovery.STATE)
                        .filter(CompanyRecovery.State::waiting)
                        .filter(state -> level.getGameTime() - state.since() < RecoveryTuning.DEFAULT.delayTicks())
                        .ifPresent(state -> Versioned.modifyOrThrow(
                                riding,
                                CompanyRecovery.STATE,
                                old -> new CompanyRecovery.State(state.since() - RecoveryTuning.DEFAULT.delayTicks())));
                if (player.position().distanceTo(Vec3.atBottomCenterOf(origin)) > MINIMUM_HAUL_BLOCKS) {
                    hauledAt[0] = helper.getTick();
                    room.fillFood();
                    last[0] = room.figure.position();
                } else if (helper.getTick() - boardedAt[0] > TRAIL_BUDGET_TICKS) {
                    throw failure(helper, "the crew never winched the pod home");
                }
                return;
            }
            long since = helper.getTick() - hauledAt[0];
            if (since == 1) {
                Vec3 landed = player.position();
                if (player.level() != level || !(player.getVehicle() instanceof PodEntity)) {
                    throw failure(helper, "the winch left the level or the seat: the person is in %s", player.level());
                }
                if (room.figure.position().distanceTo(landed) < MINIMUM_HAUL_BLOCKS) {
                    throw failure(
                            helper,
                            "the figure is only %s blocks from the person after the winch",
                            room.figure.distanceTo(player));
                }
            }
            if (since < JUMP_WATCH_TICKS && room.figure.position().distanceTo(last[0]) > FOOT_LATCH_SPEED + EPSILON) {
                throw failure(
                        helper,
                        "the figure moved %s blocks in a tick: it jumped to the person, not trailed at %s",
                        room.figure.position().distanceTo(last[0]),
                        FOOT_LATCH_SPEED);
            }
            last[0] = room.figure.position();
            if (releasedAt[0] < 0) {
                if (FootGrips.holder(player) != room.figure || !room.figure.isAlive()) {
                    throw failure(helper, "the winch freed the person %s ticks after it landed", since);
                }
                expectOreSpeed(helper, room, speedFree[0]);
                if (frozenAt[0] < 0) {
                    if (!level.isPositionEntityTicking(room.figure.blockPosition())) {
                        frozenAt[0] = since;
                        foodAtFreeze[0] = player.getFoodData().getFoodLevel();
                    } else if (since > TRAIL_BUDGET_TICKS) {
                        throw failure(helper, "the figure still ticks %s ticks after the winch", since);
                    }
                } else if (since - frozenAt[0] >= FROZEN_FOOD_WINDOW_TICKS) {
                    // Nobody is near the figure: it does not tick, so nothing drains, and the weight stays until its
                    // chunk goes.
                    if (player.getFoodData().getFoodLevel() != foodAtFreeze[0]) {
                        throw failure(
                                helper,
                                "the food went from %s to %s while the figure stood in a chunk that does not tick",
                                foodAtFreeze[0],
                                player.getFoodData().getFoodLevel());
                    }
                    FarChunks.release(helper, level, origin.getX() >> 4, origin.getZ() >> 4);
                    releasedAt[0] = since;
                }
                return;
            }
            if (FootGrips.holder(player) == null
                    && Math.abs(player.getAttributeValue(Attributes.MOVEMENT_SPEED) - speedFree[0]) < EPSILON) {
                helper.succeed();
            } else if (since - releasedAt[0] > FREED_BUDGET_TICKS) {
                throw failure(helper, "the person is still held after the figure's chunk was let go");
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> {
            made[0] = Room.build(helper, level, origin, HALF - 1);
            speedFree[0] = (float) made[0].player().getAttributeValue(Attributes.MOVEMENT_SPEED);
        });
    }

    /** On Peaceful a figure draws no person, and one that holds a person lets go when the difficulty turns Peaceful (W3 of the #977 review). */
    @GameTest(
            maxTicks = FarChunks.AWAIT_BUDGET_TICKS + APPROACH_BUDGET_TICKS + FREED_BUDGET_TICKS + 60,
            environment = ALONE)
    public void onPeacefulAFigureNeitherDrawsNorHoldsAPerson(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(11);
        Room[] made = {null};
        long[] peacefulAt = {-1};
        Difficulty[] before = {null};
        TestTicks.each(helper, () -> {
            Room room = made[0];
            if (room == null) {
                return;
            }
            MinecraftServer server = level.getServer();
            if (peacefulAt[0] < 0) {
                if (room.figure.isClinging()) {
                    before[0] = server.getWorldData().getDifficulty();
                    server.setDifficulty(Difficulty.PEACEFUL, true);
                    peacefulAt[0] = helper.getTick();
                } else if (helper.getTick() > APPROACH_BUDGET_TICKS + FarChunks.AWAIT_BUDGET_TICKS) {
                    throw failure(helper, "the figure had not taken hold of the person");
                }
                return;
            }
            if (!room.figure.isClinging() && FootGrips.holder(room.player()) == null) {
                try {
                    expectDraws(helper, room.player(), false, "a free person in the dark on Peaceful");
                } finally {
                    server.setDifficulty(before[0], true);
                }
                room.clear();
                helper.succeed();
            } else if (helper.getTick() - peacefulAt[0] > FREED_BUDGET_TICKS) {
                server.setDifficulty(before[0], true);
                throw failure(helper, "a figure still holds a person on Peaceful");
            }
        });
        FarChunks.awaitEntityTicking(
                helper, level, origin, () -> made[0] = Room.build(helper, level, origin, HALF - 1));
    }

    /**
     * Blocks that give light but are not a light a person carries, so {@code hand_lights} leaves them out on purpose: they shine only in a
     * state a held item is not in, or are machines, containers or decor. Every other block item that gives the lit light or more is in the
     * tag, so a light added later is not dropped silently.
     */
    private static final Set<String> NOT_CARRIED_LIGHTS = Set.of(
            "minecraft:sea_pickle",
            "minecraft:sculk_catalyst",
            "minecraft:enchanting_table",
            "minecraft:ender_chest",
            "minecraft:beacon",
            "minecraft:light",
            "minecraft:magma_block",
            "minecraft:conduit",
            "minecraft:crying_obsidian",
            "minecraft:large_amethyst_bud",
            "minecraft:amethyst_cluster",
            "minecraft:vault",
            "deepcharter:furnace_hatch",
            "deepcharter:gauge_panel");

    /** The {@code deepcharter:hand_lights} tag holds every block item the first rule accepted (a light of the lit level or more) but the listed few that are no carried light (W4). */
    @GameTest
    public void theHandLightsTagCoversEveryLightBlockItemTheFirstRuleAccepted(GameTestHelper helper) {
        List<String> missing = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            if (item instanceof BlockItem block
                    && block.getBlock().defaultBlockState().getLightEmission() >= TUNING.litBlockLight()
                    && !NOT_CARRIED_LIGHTS.contains(id)
                    && !new ItemStack(item).is(HandLights.TAG)) {
                missing.add(id);
            }
        }
        if (!missing.isEmpty()) {
            throw failure(helper, "hand_lights lacks %s", missing);
        }
        if (!new ItemStack(Items.TORCH).is(HandLights.TAG) || new ItemStack(Items.STONE).is(HandLights.TAG)) {
            throw failure(helper, "hand_lights must hold a torch and not a stone");
        }
        helper.succeed();
    }

    /**
     * A flare protects a person on foot from the tick it registers (#1073, ADR 0078), as it does a pod: the keep-off reads the flare's block and
     * entry, never the light. The flare's block here emits none, so the light engine has nothing to publish and the person is dark throughout;
     * only the flare entry can keep the figure off. Once it burns out the person draws again.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + FLARE_BURN_TICKS + APPROACH_BUDGET_TICKS + 60)
    public void aFlareProtectsAPersonOnFootFromTheTickItLandsThenBurnsOut(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(13);
        Room[] made = {null};
        long[] burnsOutAt = {0};
        TestTicks.each(helper, () -> {
            Room room = made[0];
            if (room == null) {
                return;
            }
            ServerPlayer player = room.player();
            if (level.getGameTime() < burnsOutAt[0]) {
                expectDraws(helper, player, false, "a person within a burning flare's reach");
                if (room.figure.isClinging() || FootGrips.holder(player) != null) {
                    throw failure(helper, "a figure took hold of a person a flare protects");
                }
                return;
            }
            if (room.figure.isClinging() || FootGrips.holder(player) != null) {
                room.clear();
                helper.succeed();
                return;
            }
            expectDraws(helper, player, true, "a person once the flare has burnt out");
            if (helper.getTick() > FarChunks.AWAIT_BUDGET_TICKS + FLARE_BURN_TICKS + APPROACH_BUDGET_TICKS) {
                throw failure(helper, "the figure never took hold of the person after the flare burnt out");
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> {
            made[0] = Room.build(helper, level, origin, HALF - 1);
            ServerPlayer player = made[0].player();
            expectDraws(helper, player, true, "a person in the dark before the flare lands");
            burnsOutAt[0] = level.getGameTime() + FLARE_BURN_TICKS;
            landFlare(level, player.blockPosition().west(2), burnsOutAt[0]);
            // light-wait-ok: asserts the inert stone stand-in emits no light; the read checks an absence, not a wait
            // for light
            if (level.getBrightness(LightLayer.BLOCK, player.blockPosition()) >= TUNING.litBlockLight()) {
                throw failure(helper, "the test's flare block lit the person; it must stay dark");
            }
            expectDraws(helper, player, false, "a person on the tick their flare registers");
        });
    }

    /** Regression guard, not a changed behaviour: a flare landing after the grip leaves the figure holding and the weight on. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + APPROACH_BUDGET_TICKS + WATCH_TICKS + 60)
    public void aFlareDoesNotFreeAPersonAlreadyHeld(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(14);
        Room[] made = {null};
        long[] litAt = {-1};
        BlockPos[] flare = {null};
        TestTicks.each(helper, () -> {
            Room room = made[0];
            if (room == null) {
                return;
            }
            if (litAt[0] < 0) {
                if (room.figure.isClinging()) {
                    litAt[0] = helper.getTick();
                    flare[0] = room.player().blockPosition().west(2);
                    landFlare(level, flare[0], level.getGameTime() + WATCH_TICKS + FLARE_BURN_TICKS);
                } else if (helper.getTick() > APPROACH_BUDGET_TICKS + FarChunks.AWAIT_BUDGET_TICKS) {
                    throw failure(helper, "the figure had not taken hold of the person");
                }
                return;
            }
            expectHeld(helper, room, helper.getTick() - litAt[0]);
            if (helper.getTick() - litAt[0] >= WATCH_TICKS) {
                if (room.player().getEffect(OreRegistry.OVERBURDENED) == null) {
                    throw failure(helper, "a flare took the weight off a held person");
                }
                level.setBlock(flare[0], Blocks.AIR.defaultBlockState(), 3);
                room.clear();
                helper.succeed();
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> {
            made[0] = Room.build(helper, level, origin, HALF - 1);
            made[0].fillFood();
        });
    }

    /** An inert block with a flare entry: all litByFlare reads, and no light is ever published. */
    private static void landFlare(ServerLevel level, BlockPos pos, long burnsOutAt) {
        level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
        DrawSources.addFlare(level, pos, Blocks.STONE, FLARE_REACH, burnsOutAt);
    }

    private static BlockPos slot(int index) {
        return new BlockPos(FIRST_SLOT_X, FLOOR_Y, FIRST_SLOT_X + index * SLOT_Z_SPACING);
    }

    private static BlockState light(int level) {
        return Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, level);
    }
}
