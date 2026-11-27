package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.sound.SoundTuning;
import io.github.pkeppeler.deepcharter.sound.phonograph.PhonographRegistry;
import io.github.pkeppeler.deepcharter.sound.phonograph.Recording;
import io.github.pkeppeler.deepcharter.sound.phonograph.WreckPhonographBlockEntity;
import io.github.pkeppeler.deepcharter.sound.phonograph.WreckSongArming;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for #861, the wreck's phonograph: a player who comes close starts the song and one far away is never sent it, a right
 * click switches it off (and on again), an off or ended song stays quiet while a player lingers, the block is unbreakable with no item and
 * no loot and holds no cylinder, and the rule for when it plays again (armed by a long absence) holds. The block's place in the story bay is
 * in {@code CampaignContentTest} and {@code LayerStructuresTest}.
 */
public class WreckPhonographTest {
    /** The ticks a mock player needs to read what the server sent it. */
    private static final int DRAIN_TICKS = 4;

    private static final BlockPos PLACE = new BlockPos(3, 1, 3);

    // Any player within the trigger range starts the song, and a batch lays its tests out side by side, 5 blocks
    // apart: the next test's mock players join 11 blocks from this block (#1368). So each test that reads whether it
    // plays has one of these environments of its own, and runs in a batch with no other test's player beside it.
    private static final String TRIGGER = "deepcharter-test:wreck_phonograph_trigger";
    private static final String SWITCH = "deepcharter-test:wreck_phonograph_switch";
    private static final String HAND = "deepcharter-test:wreck_phonograph_hand";
    private static final String ENDING = "deepcharter-test:wreck_phonograph_ending";
    private static final String CYLINDER = "deepcharter-test:wreck_phonograph_cylinder";
    private static final String RELOAD = "deepcharter-test:wreck_phonograph_reload";

    private static WreckPhonographBlockEntity place(GameTestHelper helper) {
        helper.setBlock(PLACE, PhonographRegistry.WRECK_PHONOGRAPH.defaultBlockState());
        BlockEntity found = helper.getLevel().getBlockEntity(helper.absolutePos(PLACE));
        if (found instanceof WreckPhonographBlockEntity phonograph) {
            return phonograph;
        }
        throw failure(helper, "there is no wreck phonograph block entity at %s, but %s", PLACE, String.valueOf(found));
    }

    private static Vec3 at(GameTestHelper helper) {
        return Vec3.atBottomCenterOf(helper.absolutePos(PLACE));
    }

    private static MockPlayer joinAt(GameTestHelper helper, String name, double east) {
        MockPlayer player = MockPlayers.join(helper, name);
        player.teleportTo(helper.getLevel(), at(helper).add(east, 1, 0), 0f, 0f);
        return player;
    }

    /** A player's right click on the block with {@code stack} in the main hand. */
    private static void use(GameTestHelper helper, MockPlayer player, ItemStack stack) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(PLACE);
        player.player().setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0.5, 0), Direction.UP, pos, false);
        player.player().gameMode.useItemOn(player.player(), level, stack, InteractionHand.MAIN_HAND, hit);
    }

    /** The songs the player was sent the start of at the test's own block. */
    private static List<Identifier> startsAtMine(GameTestHelper helper, MockPlayer player) {
        Vec3 mine = Vec3.atCenterOf(helper.absolutePos(PLACE));
        return player.soundsHeardAtPlaces().stream()
                .filter(sound -> sound.at().distanceToSqr(mine) < 1)
                .map(MockPlayer.PlacedSound::id)
                .toList();
    }

    private static Identifier song() {
        return DeepSound.SONG_WRECK.event().location();
    }

    @GameTest(maxTicks = 200, environment = TRIGGER)
    public void aPlayerWhoComesCloseStartsTheSongAndAFarOneIsNeverSentIt(GameTestHelper helper) {
        WreckPhonographBlockEntity phonograph = place(helper);
        SoundTuning tuning = SoundTuning.DEFAULT;
        MockPlayer far = joinAt(helper, "wreck-far", tuning.wreckSongRange() + 8);
        MockPlayer middle = joinAt(helper, "wreck-middle", tuning.wreckSongTriggerRange() + 1);
        expect(helper, !phonograph.isPlaying(), "it starts silent");
        helper.runAfterDelay(tuning.phonographListenTicks() * 2L, () -> {
            expect(
                    helper,
                    !phonograph.isPlaying(),
                    "a player past the trigger range (%s blocks) started it",
                    tuning.wreckSongTriggerRange());
            MockPlayer near = joinAt(helper, "wreck-near", 4);
            helper.runAfterDelay(tuning.phonographListenTicks() + DRAIN_TICKS, () -> {
                expect(helper, phonograph.isPlaying(), "a player close by started it");
                expect(
                        helper,
                        startsAtMine(helper, near).equals(List.of(song())),
                        "the near player heard the song once");
                expect(
                        helper,
                        startsAtMine(helper, middle).equals(List.of(song())),
                        "a player inside the audible range, outside the trigger, was sent the song");
                expect(helper, startsAtMine(helper, far).isEmpty(), "a player far off was sent the song");
                helper.succeed();
            });
        });
    }

    @GameTest(maxTicks = 300, environment = SWITCH)
    public void aRightClickSwitchesItOffAndItStaysOffWhileAPlayerLingersAndAnotherStartsItAgain(GameTestHelper helper) {
        WreckPhonographBlockEntity phonograph = place(helper);
        int listen = SoundTuning.DEFAULT.phonographListenTicks();
        MockPlayer player = joinAt(helper, "wreck-visitor", 4);
        helper.runAfterDelay(listen + DRAIN_TICKS, () -> {
            expect(helper, phonograph.isPlaying(), "it plays for the visitor");
            use(helper, player, ItemStack.EMPTY);
            expect(helper, !phonograph.isPlaying(), "a right click switches it off");
        });
        helper.runAfterDelay(
                listen + DRAIN_TICKS * 2,
                () -> expect(helper, player.soundsStopped().contains(song()), "the stop was sent to the listener"));
        helper.runAfterDelay(listen * 4 + DRAIN_TICKS, () -> {
            expect(helper, !phonograph.isPlaying(), "it started again with the visitor still beside it");
            expect(helper, startsAtMine(helper, player).size() == 1, "the visitor was sent the song only once");
            use(helper, player, ItemStack.EMPTY);
            expect(helper, phonograph.isPlaying(), "a right click while it is off starts it again by hand");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 200, environment = HAND)
    public void aHandOnItBeforeItHasLookedSpendsItSoTheNextLookDoesNotStartIt(GameTestHelper helper) {
        WreckPhonographBlockEntity phonograph = place(helper);
        int listen = SoundTuning.DEFAULT.phonographListenTicks();
        MockPlayer player = joinAt(helper, "wreck-eager", 3);
        // Two right clicks in the tick the player arrives, before the block has looked for anyone: on, then off.
        use(helper, player, ItemStack.EMPTY);
        use(helper, player, ItemStack.EMPTY);
        expect(helper, !phonograph.isPlaying(), "two right clicks left it playing");
        helper.runAfterDelay(listen * 2L + DRAIN_TICKS, () -> {
            expect(helper, !phonograph.isPlaying(), "it started on its own after the visitor switched it off");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 700, environment = ENDING)
    public void theSongEndsByItselfAndDoesNotReplayWhileAPlayerStays(GameTestHelper helper) {
        WreckPhonographBlockEntity phonograph = place(helper);
        SoundTuning tuning = SoundTuning.DEFAULT;
        MockPlayer player = joinAt(helper, "wreck-lingerer", 4);
        helper.runAfterDelay(
                tuning.phonographListenTicks() + DRAIN_TICKS,
                () -> expect(helper, phonograph.isPlaying(), "it plays for the visitor"));
        helper.runAfterDelay(tuning.wreckSongTicks() + tuning.phonographListenTicks() * 4, () -> {
            expect(helper, !phonograph.isPlaying(), "the song did not end by itself");
            expect(helper, startsAtMine(helper, player).size() == 1, "a lingering player was sent the song again");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 100, environment = CYLINDER)
    public void aCylinderOnItIsNotTakenSoTheSongCannotBeCarriedOff(GameTestHelper helper) {
        WreckPhonographBlockEntity phonograph = place(helper);
        MockPlayer player = joinAt(helper, "wreck-collector", 30);
        ItemStack cylinder = new ItemStack(PhonographRegistry.WAX_CYLINDER);
        cylinder.set(PhonographRegistry.RECORDING, Recording.OTHELLO);
        use(helper, player, cylinder);
        expect(
                helper,
                player.player().getMainHandItem().is(PhonographRegistry.WAX_CYLINDER),
                "the wreck's phonograph took the cylinder");
        expect(helper, phonograph.isPlaying(), "a right click, whatever is in the hand, only switches it");
        expect(
                helper,
                startsAtMine(helper, player).stream()
                        .noneMatch(id ->
                                id.equals(DeepSound.PHONOGRAPH_OTHELLO.event().location())),
                "it played the cylinder");
        helper.succeed();
    }

    @GameTest
    public void itIsUnbreakableHasNoItemAndNoLootAndItsSongIsNotACylinder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        place(helper);
        expect(
                helper,
                helper.getBlockState(PLACE).getDestroySpeed(level, helper.absolutePos(PLACE)) < 0,
                "the wreck's phonograph can be broken");
        expect(
                helper,
                !BuiltInRegistries.ITEM.containsKey(
                        Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "wreck_phonograph")),
                "the wreck's phonograph has an item");
        expect(helper, PhonographRegistry.WRECK_PHONOGRAPH.getLootTable().isEmpty(), "the wreck's phonograph has loot");
        for (Recording recording : Recording.values()) {
            expect(helper, recording.sound() != DeepSound.SONG_WRECK, "%s is the wreck's song", recording);
        }
        expect(
                helper,
                DeepSound.SONG_WRECK.event().location().getPath().startsWith("song."),
                "the song is under song.");
        helper.succeed();
    }

    @GameTest(maxTicks = 300, environment = RELOAD)
    public void aSwitchOffSurvivesTheChunkLeavingAndComingBackAndRearmsOnceTimeHasPassedWithNobodyNear(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WreckPhonographBlockEntity phonograph = place(helper);
        int listen = SoundTuning.DEFAULT.phonographListenTicks();
        MockPlayer player = joinAt(helper, "wreck-leaver", 4);
        helper.runAfterDelay(listen + DRAIN_TICKS, () -> {
            expect(helper, phonograph.isPlaying(), "it plays for the visitor");
            use(helper, player, ItemStack.EMPTY);
            expect(helper, !phonograph.isPlaying(), "a right click switches it off");
            // The chunk unloads and loads: the block entity is read back from its save data, with the player still
            // beside it.
            BlockPos pos = helper.absolutePos(PLACE);
            CompoundTag saved = phonograph.saveWithFullMetadata(level.registryAccess());
            BlockEntity loaded = BlockEntity.loadStatic(pos, level.getBlockState(pos), saved, level.registryAccess());
            if (!(loaded instanceof WreckPhonographBlockEntity reloaded)) {
                throw failure(helper, "the save data loaded as %s", String.valueOf(loaded));
            }
            level.setBlockEntity(reloaded);
            helper.runAfterDelay(listen * 3L, () -> {
                expect(helper, !reloaded.isPlaying(), "a reloaded switched-off phonograph sang at once");
                expect(helper, startsAtMine(helper, player).size() == 1, "the visitor was sent the song again");
                // Time passes with nobody near: the saved time is an old one, and the player has gone.
                player.teleportTo(level, at(helper).add(40, 1, 0), 0f, 0f);
                CompoundTag old = reloaded.saveWithFullMetadata(level.registryAccess());
                old.putLong("spent_at", level.getGameTime() - SoundTuning.DEFAULT.wreckSongRearmTicks());
                old.putLong("last_near_at", level.getGameTime() - SoundTuning.DEFAULT.wreckSongRearmTicks());
                BlockEntity aged = BlockEntity.loadStatic(pos, level.getBlockState(pos), old, level.registryAccess());
                if (!(aged instanceof WreckPhonographBlockEntity rearmed)) {
                    throw failure(helper, "the aged save data loaded as %s", String.valueOf(aged));
                }
                level.setBlockEntity(rearmed);
                helper.runAfterDelay(listen + DRAIN_TICKS, () -> {
                    expect(helper, !rearmed.isPlaying(), "it sang for nobody");
                    player.teleportTo(level, at(helper).add(4, 1, 0), 0f, 0f);
                    helper.runAfterDelay(listen + DRAIN_TICKS, () -> {
                        expect(
                                helper,
                                rearmed.isPlaying(),
                                "the next visitor, after the long absence, did not start it");
                        helper.succeed();
                    });
                });
            });
        });
    }

    @GameTest
    public void theTwoTimesAreTheOnlyThingsSaved(GameTestHelper helper) {
        WreckPhonographBlockEntity phonograph = place(helper);
        ServerLevel level = helper.getLevel();
        CompoundTag saved = phonograph.saveWithFullMetadata(level.registryAccess());
        expect(
                helper,
                saved.getLongOr("spent_at", 0L) == WreckSongArming.NEVER,
                "a phonograph never spent saves %s",
                saved.getLongOr("spent_at", 0L));
        expect(
                helper,
                saved.getLongOr("last_near_at", 0L) == WreckSongArming.NEVER,
                "a phonograph nobody came near saves a near time");
        expect(helper, !saved.contains("playing"), "a playback was saved");
        helper.succeed();
    }

    @GameTest
    public void theArmingStartsOnTheFirstVisitOnlyAndAgainAfterALongAbsenceWithNobodyNear(GameTestHelper helper) {
        int rearm = SoundTuning.DEFAULT.wreckSongRearmTicks();
        WreckSongArming arming = new WreckSongArming(rearm);
        expect(helper, !arming.look(false, 100), "it starts with nobody near");
        expect(helper, arming.look(true, 120), "a player came near and it did not start");
        expect(helper, arming.spentAt() == 120, "it was spent at %s", arming.spentAt());
        long last = 140;
        for (; last < 140 + 2L * rearm; last += 20) {
            expect(helper, !arming.look(true, last), "it started again with the player still near (tick %s)", last);
        }
        last -= 20; // the last look with the player near
        // Nobody near now: still spent until a full absence has passed since anyone was.
        expect(
                helper,
                !arming.look(false, last + rearm - 1) && arming.spentAt() != WreckSongArming.NEVER,
                "armed early");
        expect(
                helper,
                !arming.look(false, last + rearm) && arming.spentAt() == WreckSongArming.NEVER,
                "not armed after the absence");
        expect(helper, arming.look(true, last + rearm + 20), "the next visitor did not start it");
        helper.succeed();
    }

    @GameTest
    public void aVisitorWhoLingersLongerThanTheAbsenceAndStepsOutAndBackIsNotSungToAgain(GameTestHelper helper) {
        int rearm = SoundTuning.DEFAULT.wreckSongRearmTicks();
        WreckSongArming arming = new WreckSongArming(rearm);
        expect(helper, arming.look(true, 0), "it did not start");
        arming.byHand(20);
        long now = 40;
        for (; now < 2L * rearm; now += 20) {
            arming.look(true, now);
        }
        expect(helper, !arming.look(false, now), "it started with nobody near");
        expect(
                helper,
                !arming.look(true, now + 20),
                "the song restarted in the face of a visitor who stepped out and back");
        // A full absence after the last time anyone was near: it plays for the next visitor.
        long back = now + 20;
        expect(
                helper,
                !arming.look(false, back + rearm - 1) && arming.spentAt() != WreckSongArming.NEVER,
                "armed early");
        expect(
                helper,
                !arming.look(false, back + rearm) && arming.spentAt() == WreckSongArming.NEVER,
                "not armed after the absence");
        expect(helper, arming.look(true, back + rearm + 20), "the next visitor did not start it");
        helper.succeed();
    }

    @GameTest
    public void aHandSpendsItAtOnceAndItsSavedTimesCarryThroughAReload(GameTestHelper helper) {
        int rearm = SoundTuning.DEFAULT.wreckSongRearmTicks();
        WreckSongArming arming = new WreckSongArming(rearm);
        arming.byHand(500);
        long last = 520;
        for (; last < 520 + 50 * 20; last += 20) {
            expect(helper, !arming.look(true, last), "it started with the visitor who switched it off still near");
        }
        // Read back from the saved times, as a block entity loads: still spent, and rearmed on the same clock.
        WreckSongArming reloaded = new WreckSongArming(rearm);
        reloaded.restore(arming.spentAt(), arming.lastNearAt());
        expect(helper, !reloaded.look(true, last), "a reloaded spent phonograph started");
        expect(helper, !reloaded.look(false, last + rearm - 1), "a reloaded one armed early");
        expect(helper, !reloaded.look(false, last + rearm), "a reloaded one started with nobody near");
        expect(helper, reloaded.look(true, last + rearm + 20), "the next visitor did not start it");
        // A clock that went back (a copied world) does not leave it spent for ever.
        WreckSongArming copied = new WreckSongArming(rearm);
        copied.restore(1_000_000, 1_000_000);
        copied.look(false, 10);
        expect(helper, copied.spentAt() == WreckSongArming.NEVER, "a spent time in the future kept it spent");
        helper.succeed();
    }
}
