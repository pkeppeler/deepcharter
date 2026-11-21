package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonyPhonograph;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.colony.ColonyTuning;
import io.github.pkeppeler.deepcharter.sound.phonograph.PhonographBlockEntity;
import io.github.pkeppeler.deepcharter.sound.phonograph.PhonographRegistry;
import io.github.pkeppeler.deepcharter.sound.phonograph.Recording;
import io.github.pkeppeler.deepcharter.test.support.EvidenceWorld;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TestClocks;
import io.github.pkeppeler.deepcharter.test.support.TestColony;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.timeline.Timeline;

/**
 * Server GameTests for #864's colony phonograph: the Lamp and Pick has one on its counter; its rack of cylinders cannot be ejected or
 * taken, and each play starts the next; and rarely, at night, with nobody in the room, it starts by itself (the roll is forced with a
 * random source whose first draw is known). The colony is one shared block, so a test leaves it stopped and reads the rack relative to
 * where it stood.
 */
public class ColonyPhonographTest {
    /** The colony's phonograph stands this far from the bar's anchor (tools/colony/town.py: the counter along the back wall). */
    private static final BlockPos FROM_ANCHOR = new BlockPos(0, 2, 5);

    /**
     * The colony has one phonograph, so each test that plays it has an environment of its own: the batches run one after another, and
     * nothing else runs while a test reads who stands near it or which recording it plays next.
     */
    private static final String RACK = "deepcharter-test:phonograph_rack";

    private static final String ROUNDS = "deepcharter-test:phonograph_rounds";
    private static final String NIGHT = "deepcharter-test:phonograph_night";

    private static final ResourceKey<Timeline> SKY =
            ResourceKey.create(Registries.TIMELINE, Identifier.fromNamespaceAndPath("deepcharter", "sky"));

    private static MinecraftServer server(GameTestHelper helper) {
        return helper.getLevel().getServer();
    }

    private static BlockPos position(GameTestHelper helper) {
        ColonySite.Placed colony = Colony.placed(server(helper))
                .orElseThrow(() -> helper.assertionException("the colony was not built when the server started"));
        return TestColony.anchor(colony, ColonyAnchor.LAMP_AND_PICK).offset(FROM_ANCHOR);
    }

    private static PhonographBlockEntity phonograph(GameTestHelper helper, ServerLevel level, BlockPos pos) {
        BlockEntity found = level.getBlockEntity(pos);
        if (found instanceof PhonographBlockEntity phonograph) {
            return phonograph;
        }
        throw helper.assertionException(
                "the Lamp and Pick has no phonograph at %s, it has %s", pos, String.valueOf(found));
    }

    private static void expect(GameTestHelper helper, boolean ok, String what, Object... args) {
        if (!ok) {
            throw helper.assertionException(what, args);
        }
    }

    private static void use(ServerLevel level, MockPlayer player, BlockPos pos, ItemStack stack, boolean sneaking) {
        player.player().setItemInHand(InteractionHand.MAIN_HAND, stack);
        player.player().setShiftKeyDown(sneaking);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0.5, 0), Direction.UP, pos, false);
        player.player().gameMode.useItemOn(player.player(), level, stack, InteractionHand.MAIN_HAND, hit);
        player.player().setShiftKeyDown(false);
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void theLampAndPickHoldsAPhonographOnItsCounter(GameTestHelper helper) {
        ServerLevel level = server(helper).overworld();
        BlockPos pos = position(helper);
        FarChunks.awaitEntityTicking(helper, level, pos, () -> {
            expect(
                    helper,
                    level.getBlockState(pos).is(PhonographRegistry.COLONY_PHONOGRAPH),
                    "the counter holds %s",
                    level.getBlockState(pos));
            expect(helper, phonograph(helper, level, pos).isColony(), "its block entity is the colony's");
            expect(helper, !level.getBlockState(pos.below()).isAir(), "it stands on the counter, not in the air");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100, environment = RACK)
    public void theRackCannotBeEjectedOrTakenOrAddedTo(GameTestHelper helper) {
        ServerLevel level = server(helper).overworld();
        BlockPos pos = position(helper);
        MockPlayer player = MockPlayers.join(helper, "rack-thief");
        player.player().setGameMode(GameType.SURVIVAL);
        FarChunks.awaitEntityTicking(helper, level, pos, () -> {
            PhonographBlockEntity phonograph = phonograph(helper, level, pos);
            player.teleportTo(level, Vec3.atBottomCenterOf(pos.below(2)), 0f, 0f);
            use(level, player, pos, ItemStack.EMPTY, true);
            expect(helper, phonograph.cylinder().isEmpty(), "the rack gave a cylinder up");
            expect(helper, popped(level, pos).isEmpty(), "a cylinder lies by the phonograph after a sneak");
            ItemStack mine = new ItemStack(PhonographRegistry.WAX_CYLINDER);
            use(level, player, pos, mine, false);
            expect(helper, phonograph.cylinder().isEmpty(), "the rack took a cylinder");
            expect(
                    helper,
                    !mine.isEmpty() && player.player().getMainHandItem() == mine,
                    "the cylinder left the player's hand");
            expect(helper, !phonograph.isPlaying(), "refusing a cylinder started a playback");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100, environment = ROUNDS)
    public void eachPlayStartsTheNextRecordingOfTheRackAndWrapsRound(GameTestHelper helper) {
        ServerLevel level = server(helper).overworld();
        BlockPos pos = position(helper);
        MockPlayer player = MockPlayers.join(helper, "listener");
        FarChunks.awaitEntityTicking(helper, level, pos, () -> {
            PhonographBlockEntity phonograph = phonograph(helper, level, pos);
            player.teleportTo(level, Vec3.atBottomCenterOf(pos.below(2)), 0f, 0f);
            List<Recording> heard = new ArrayList<>();
            int plays = Recording.COLONY_RACK.size() * 2 + 1;
            for (int i = 0; i < plays; i++) {
                use(level, player, pos, ItemStack.EMPTY, false);
                expect(helper, phonograph.isPlaying(), "play %s did not start", i);
                heard.add(phonograph.playing().orElseThrow());
                use(level, player, pos, ItemStack.EMPTY, false);
                expect(helper, !phonograph.isPlaying(), "play %s did not stop", i);
            }
            int first = Recording.COLONY_RACK.indexOf(heard.getFirst());
            for (int i = 0; i < plays; i++) {
                Recording expected = Recording.COLONY_RACK.get((first + i) % Recording.COLONY_RACK.size());
                expect(
                        helper,
                        heard.get(i) == expected,
                        "play %s was %s, the rack's next is %s: %s",
                        i,
                        heard.get(i),
                        expected,
                        heard);
            }
            expect(
                    helper,
                    Set.copyOf(heard.subList(0, Recording.COLONY_RACK.size())).size() == Recording.COLONY_RACK.size(),
                    "the first plays are not all three recordings: %s",
                    heard);
            helper.succeed();
        });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100, environment = NIGHT)
    public void atNightWithNobodyInTheRoomItStartsByItselfOnceANightAndNeverByDayOrWithSomeoneThere(
            GameTestHelper helper) {
        ServerLevel level = server(helper).overworld();
        BlockPos pos = position(helper);
        MockPlayer visitor = MockPlayers.join(helper, "visitor");
        FarChunks.awaitEntityTicking(helper, level, pos, () -> {
            PhonographBlockEntity phonograph = phonograph(helper, level, pos);
            // A player in the room, from another test or this one, is what keeps the colony quiet: this test needs the
            // room empty first.
            visitor.teleportTo(level, Vec3.atBottomCenterOf(pos).add(100, 0, 0), 0f, 0f);
            long period = period(level);
            long midnight = period / 2;
            expect(helper, !phonograph.isPlaying(), "the phonograph was playing when the test began");
            pinned(level, 31 * period + midnight, () -> {
                expect(helper, !ColonyPhonograph.autoPlay(level, phonograph, roll(false)), "a failed roll started it");
                expect(helper, !phonograph.isPlaying(), "a failed roll left it playing");
                expect(
                        helper,
                        ColonyPhonograph.autoPlay(level, phonograph, roll(true)),
                        "a won roll at night did not start it");
                expect(helper, phonograph.isPlaying(), "it is not playing");
                phonograph.stop();
                expect(
                        helper,
                        !ColonyPhonograph.autoPlay(level, phonograph, roll(true)),
                        "it started a second time on the same night");
            });
            pinned(level, 32 * period + midnight, () -> {
                expect(
                        helper,
                        ColonyPhonograph.autoPlay(level, phonograph, roll(true)),
                        "the next night did not start it");
                expect(
                        helper,
                        !ColonyPhonograph.autoPlay(level, phonograph, roll(true)),
                        "it started on top of a playback that was going");
                phonograph.stop();
            });
            pinned(level, 33 * period, () -> {
                expect(helper, !ColonyPhonograph.autoPlay(level, phonograph, roll(true)), "it started by day");
                expect(helper, !phonograph.isPlaying(), "it plays by day");
            });
            long edge = midnight + ColonyTuning.DEFAULT.phonographNightHalfWidth();
            pinned(
                    level,
                    34 * period + edge + 1,
                    () -> expect(
                            helper,
                            !ColonyPhonograph.autoPlay(level, phonograph, roll(true)),
                            "it started just past the night"));
            pinned(level, 35 * period + edge, () -> {
                expect(
                        helper,
                        ColonyPhonograph.autoPlay(level, phonograph, roll(true)),
                        "it did not start at the night's last tick");
                phonograph.stop();
            });
            // Someone in the room: at the edge of the quiet radius, and inside it.
            visitor.teleportTo(level, Vec3.atBottomCenterOf(pos), 0f, 0f);
            pinned(level, 36 * period + midnight, () -> {
                expect(
                        helper,
                        !ColonyPhonograph.autoPlay(level, phonograph, roll(true)),
                        "it started with a player in the room");
                expect(helper, !phonograph.isPlaying(), "it plays with a player in the room");
            });
            visitor.teleportTo(
                    level,
                    Vec3.atBottomCenterOf(pos).add(ColonyTuning.DEFAULT.phonographQuietRadius() + 4, 0, 0),
                    0f,
                    0f);
            pinned(level, 37 * period + midnight, () -> {
                expect(
                        helper,
                        ColonyPhonograph.autoPlay(level, phonograph, roll(true)),
                        "a player outside the room kept it quiet");
                phonograph.stop();
            });
            helper.succeed();
        });
    }

    private static long period(ServerLevel level) {
        return level.registryAccess()
                .lookupOrThrow(Registries.TIMELINE)
                .getOrThrow(SKY)
                .value()
                .periodTicks()
                .orElseThrow();
    }

    private static void pinned(ServerLevel level, long skyTicks, Runnable body) {
        MinecraftServer server = level.getServer();
        TestClocks.paused(server, EvidenceWorld.skyClock(server), skyTicks, body);
    }

    /** A random source whose first draw wins the phonograph's roll, or loses it: the first seed of the sequence 0, 1, 2... that does. */
    private static RandomSource roll(boolean wins) {
        for (long seed = 0; ; seed++) {
            boolean wonIt = RandomSource.create(seed).nextDouble() < ColonyTuning.DEFAULT.phonographChance();
            if (wonIt == wins) {
                return RandomSource.create(seed);
            }
        }
    }

    private static List<ItemEntity> popped(ServerLevel level, BlockPos pos) {
        // entity-query: the colony's pad is loaded where this runs (the test awaits its chunk), and a miss would only
        // pass the test wrongly
        return level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(3));
    }
}
