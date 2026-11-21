package io.github.pkeppeler.deepcharter.test;

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

    private static void expect(GameTestHelper helper, boolean ok, String what, Object... args) {
        if (!ok) {
            throw helper.assertionException(what, args);
        }
    }

    private static WreckPhonographBlockEntity place(GameTestHelper helper) {
        helper.setBlock(PLACE, PhonographRegistry.WRECK_PHONOGRAPH.defaultBlockState());
        BlockEntity found = helper.getLevel().getBlockEntity(helper.absolutePos(PLACE));
        if (found instanceof WreckPhonographBlockEntity phonograph) {
            return phonograph;
        }
        throw helper.assertionException(
                "there is no wreck phonograph block entity at %s, but %s", PLACE, String.valueOf(found));
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

    /** The songs the player was sent the start of at the test's own block: the other tests' blocks stand within earshot. */
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

    @GameTest(maxTicks = 200)
    public void aPlayerWhoComesCloseStartsTheSongAndAFarOneIsNeverSentIt(GameTestHelper helper) {
        WreckPhonographBlockEntity phonograph = place(helper);
        SoundTuning tuning = SoundTuning.DEFAULT;
        MockPlayer far = joinAt(helper, "wreck-far", tuning.wreckSongRange() + 8);
        MockPlayer middle = joinAt(helper, "wreck-middle", tuning.wreckSongTriggerRange() + 1);
        expect(helper, phonograph.isArmed() && !phonograph.isPlaying(), "it starts armed and silent");
        helper.runAfterDelay(tuning.phonographListenTicks() * 2L, () -> {
            expect(
                    helper,
                    !phonograph.isPlaying(),
                    "a player past the trigger range (%s blocks) started it",
                    tuning.wreckSongTriggerRange());
            MockPlayer near = joinAt(helper, "wreck-near", 4);
            helper.runAfterDelay(tuning.phonographListenTicks() + DRAIN_TICKS, () -> {
                expect(helper, phonograph.isPlaying() && !phonograph.isArmed(), "a player close by started it");
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

    @GameTest(maxTicks = 300)
    public void aRightClickSwitchesItOffAndItStaysOffWhileAPlayerLingersAndAnotherStartsItAgain(GameTestHelper helper) {
        WreckPhonographBlockEntity phonograph = place(helper);
        int listen = SoundTuning.DEFAULT.phonographListenTicks();
        MockPlayer player = joinAt(helper, "wreck-visitor", 4);
        helper.runAfterDelay(listen + DRAIN_TICKS, () -> {
            expect(helper, phonograph.isPlaying(), "it plays for the visitor");
            use(helper, player, ItemStack.EMPTY);
            expect(helper, !phonograph.isPlaying(), "a right click switches it off");
            expect(helper, !phonograph.isArmed(), "and it is spent, not armed to start again");
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

    @GameTest(maxTicks = 200)
    public void aHandOnItBeforeItHasLookedSpendsItSoTheNextLookDoesNotStartIt(GameTestHelper helper) {
        WreckPhonographBlockEntity phonograph = place(helper);
        int listen = SoundTuning.DEFAULT.phonographListenTicks();
        MockPlayer player = joinAt(helper, "wreck-eager", 3);
        // Two right clicks in the tick the player arrives, before the block has looked for anyone: on, then off.
        use(helper, player, ItemStack.EMPTY);
        use(helper, player, ItemStack.EMPTY);
        expect(helper, !phonograph.isPlaying() && !phonograph.isArmed(), "switched off by hand it is still armed");
        helper.runAfterDelay(listen * 2L + DRAIN_TICKS, () -> {
            expect(helper, !phonograph.isPlaying(), "it started on its own after the visitor switched it off");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 700)
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
            expect(helper, !phonograph.isArmed(), "a song that ended re-armed with the player still near");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 100)
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

    @GameTest
    public void theArmingStartsOnTheFirstVisitOnlyAndAgainAfterALongAbsence(GameTestHelper helper) {
        int rearm = SoundTuning.DEFAULT.wreckSongRearmTicks();
        int step = SoundTuning.DEFAULT.phonographListenTicks();
        WreckSongArming arming = new WreckSongArming(rearm);
        expect(helper, !arming.look(false, step), "it starts with nobody near");
        expect(helper, arming.look(true, step), "a player came near and it did not start");
        for (int i = 0; i < 100; i++) {
            expect(helper, !arming.look(true, step), "it started again with the player still near (look %s)", i);
        }
        // Away for almost the whole absence, then back: the count starts again.
        for (int ticks = 0; ticks < rearm - step; ticks += step) {
            arming.look(false, step);
        }
        expect(helper, !arming.look(true, step), "it started after an absence shorter than %s ticks", rearm);
        for (int ticks = 0; ticks < rearm - step; ticks += step) {
            arming.look(false, step);
        }
        expect(helper, !arming.isArmed(), "armed before the long absence ended");
        arming.look(false, step);
        expect(helper, arming.isArmed(), "not armed after %s ticks away", rearm);
        expect(helper, arming.look(true, step), "the next visitor did not start it");
        helper.succeed();
    }

    @GameTest
    public void aSwitchOffIsSpentUntilTheLongAbsenceToo(GameTestHelper helper) {
        int rearm = SoundTuning.DEFAULT.wreckSongRearmTicks();
        int step = SoundTuning.DEFAULT.phonographListenTicks();
        WreckSongArming arming = new WreckSongArming(rearm);
        expect(helper, arming.look(true, step), "it starts");
        arming.byHand();
        for (int i = 0; i < 50; i++) {
            expect(helper, !arming.look(true, step), "it started with the visitor who switched it off still near");
        }
        // The visitor leaves by a step and comes back: that is not an absence.
        arming.look(false, step);
        expect(helper, !arming.look(true, step), "a step away re-armed it");
        for (int ticks = 0; ticks < rearm; ticks += step) {
            arming.look(false, step);
        }
        expect(helper, arming.look(true, step), "the next visitor, long after, did not start it");
        helper.succeed();
    }
}
