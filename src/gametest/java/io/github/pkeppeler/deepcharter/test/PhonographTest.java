package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.sound.SoundTuning;
import io.github.pkeppeler.deepcharter.sound.phonograph.PhonographBlock;
import io.github.pkeppeler.deepcharter.sound.phonograph.PhonographBlockEntity;
import io.github.pkeppeler.deepcharter.sound.phonograph.PhonographRegistry;
import io.github.pkeppeler.deepcharter.sound.phonograph.Recording;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for #864, the phonograph and its wax cylinders: a cylinder goes in and plays, an empty hand stops and starts it, a
 * sneaking one ejects the cylinder, breaking the block drops the cylinder and stops the sound, a playback ends by itself, a player far
 * away is never sent the start, the cylinder survives a save, and the recipe crafts.
 */
public class PhonographTest {
    /** The ticks a mock player needs to read what the server sent it. */
    private static final int DRAIN_TICKS = 4;

    private static final BlockPos PLACE = new BlockPos(3, 1, 3);

    private static ItemStack cylinder(Recording recording) {
        ItemStack stack = new ItemStack(PhonographRegistry.WAX_CYLINDER);
        stack.set(PhonographRegistry.RECORDING, recording);
        return stack;
    }

    private static PhonographBlockEntity place(GameTestHelper helper) {
        helper.setBlock(PLACE, PhonographRegistry.PHONOGRAPH.defaultBlockState());
        return entity(helper);
    }

    private static PhonographBlockEntity entity(GameTestHelper helper) {
        BlockEntity found = helper.getLevel().getBlockEntity(helper.absolutePos(PLACE));
        if (found instanceof PhonographBlockEntity phonograph) {
            return phonograph;
        }
        throw failure(helper, "there is no phonograph block entity at %s, but %s", PLACE, String.valueOf(found));
    }

    /** Uses {@code stack} on the phonograph with the main hand, as a player's right click does. */
    private static void use(GameTestHelper helper, MockPlayer player, ItemStack stack, boolean sneaking) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(PLACE);
        player.player().setItemInHand(InteractionHand.MAIN_HAND, stack);
        player.player().setShiftKeyDown(sneaking);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0.5, 0), Direction.UP, pos, false);
        player.player().gameMode.useItemOn(player.player(), level, stack, InteractionHand.MAIN_HAND, hit);
        player.player().setShiftKeyDown(false);
    }

    /** The recordings the player was sent the start of at the test's own phonograph: the other tests' blocks stand within earshot. */
    private static List<Identifier> startsAtMine(GameTestHelper helper, MockPlayer player) {
        Vec3 mine = Vec3.atCenterOf(helper.absolutePos(PLACE));
        return player.soundsHeardAtPlaces().stream()
                .filter(sound -> sound.at().distanceToSqr(mine) < 1)
                .map(MockPlayer.PlacedSound::id)
                .toList();
    }

    private static MockPlayer join(GameTestHelper helper, String name) {
        MockPlayer player = MockPlayers.join(helper, name);
        // A creative player's hand is not spent by what it puts in.
        player.player().setGameMode(GameType.SURVIVAL);
        return player;
    }

    private static boolean loaded(GameTestHelper helper) {
        return helper.getBlockState(PLACE).getValue(PhonographBlock.LOADED);
    }

    @GameTest(maxTicks = 100)
    public void aCylinderGoesInAndPlaysAndAnEmptyHandStopsAndStartsItAndASneakEjectsIt(GameTestHelper helper) {
        PhonographBlockEntity phonograph = place(helper);
        MockPlayer player = join(helper, "listener");
        use(helper, player, cylinder(Recording.OTHELLO), false);
        expect(helper, phonograph.playing().equals(Optional.of(Recording.OTHELLO)), "it plays Othello");
        expect(helper, Recording.of(phonograph.cylinder()) == Recording.OTHELLO, "it holds the Othello cylinder");
        expect(helper, loaded(helper), "the cylinder shows on the spindle");
        expect(helper, player.player().getMainHandItem().isEmpty(), "the cylinder left the player's hand");

        use(helper, player, ItemStack.EMPTY, false);
        expect(helper, !phonograph.isPlaying(), "an empty hand stops it");
        expect(helper, !phonograph.cylinder().isEmpty() && loaded(helper), "stopping keeps the cylinder in");

        use(helper, player, ItemStack.EMPTY, false);
        expect(helper, phonograph.playing().equals(Optional.of(Recording.OTHELLO)), "it plays again");

        use(helper, player, ItemStack.EMPTY, true);
        expect(helper, !phonograph.isPlaying(), "ejecting stops it");
        expect(helper, phonograph.cylinder().isEmpty() && !loaded(helper), "the cylinder is out");
        helper.runAfterDelay(DRAIN_TICKS, () -> {
            List<ItemEntity> popped = dropped(helper);
            expect(
                    helper,
                    popped.size() == 1 && Recording.of(popped.getFirst().getItem()) == Recording.OTHELLO,
                    "the Othello cylinder lies above the block, found %s",
                    popped);
            expect(
                    helper,
                    startsAtMine(helper, player)
                            .equals(List.of(id(DeepSound.PHONOGRAPH_OTHELLO), id(DeepSound.PHONOGRAPH_OTHELLO))),
                    "the player heard the start twice at this block: %s",
                    startsAtMine(helper, player));
            expect(
                    helper,
                    player.soundsStopped().contains(id(DeepSound.PHONOGRAPH_OTHELLO)),
                    "the stop was sent: %s",
                    player.soundsStopped());
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void anEmptyHandStoppingItSendsTheStopToThePlayer(GameTestHelper helper) {
        PhonographBlockEntity phonograph = place(helper);
        MockPlayer player = join(helper, "stopper");
        use(helper, player, cylinder(Recording.JEUNE_JOUVENCELLE), false);
        use(helper, player, ItemStack.EMPTY, false);
        expect(helper, !phonograph.isPlaying(), "an empty hand stops it");
        helper.runAfterDelay(DRAIN_TICKS, () -> {
            expect(
                    helper,
                    player.soundsStopped().contains(id(DeepSound.PHONOGRAPH_JEUNE_JOUVENCELLE)),
                    "the player was not told to stop the recording: %s",
                    player.soundsStopped());
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void anEmptyPhonographPlaysNothingAndEjectsNothing(GameTestHelper helper) {
        PhonographBlockEntity phonograph = place(helper);
        MockPlayer player = join(helper, "listener");
        use(helper, player, ItemStack.EMPTY, false);
        use(helper, player, ItemStack.EMPTY, true);
        expect(helper, !phonograph.isPlaying() && phonograph.cylinder().isEmpty(), "nothing plays");
        helper.runAfterDelay(DRAIN_TICKS, () -> {
            expect(helper, dropped(helper).isEmpty(), "nothing was ejected");
            expect(helper, startsAtMine(helper, player).isEmpty(), "something played at the empty phonograph");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void aSecondCylinderSwapsTheFirstBackIntoTheInventoryAndPlaysInstead(GameTestHelper helper) {
        PhonographBlockEntity phonograph = place(helper);
        MockPlayer player = join(helper, "swapper");
        use(helper, player, cylinder(Recording.GAMME_DE_LA_VOIX), false);
        use(helper, player, cylinder(Recording.JEUNE_JOUVENCELLE), false);
        expect(helper, phonograph.playing().equals(Optional.of(Recording.JEUNE_JOUVENCELLE)), "it plays the second");
        List<Recording> back = new ArrayList<>();
        for (ItemStack stack : player.player().getInventory().getNonEquipmentItems()) {
            if (stack.is(PhonographRegistry.WAX_CYLINDER)) {
                back.add(Recording.of(stack));
            }
        }
        expect(helper, back.equals(List.of(Recording.GAMME_DE_LA_VOIX)), "the first came back: %s", back);
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void breakingThePhonographDropsTheCylinderAndStopsTheSound(GameTestHelper helper) {
        PhonographBlockEntity phonograph = place(helper);
        MockPlayer player = join(helper, "breaker");
        use(helper, player, cylinder(Recording.GAMME_DE_LA_VOIX), false);
        expect(helper, phonograph.isPlaying(), "it plays before it is broken");
        helper.getLevel().destroyBlock(helper.absolutePos(PLACE), true);
        helper.runAfterDelay(DRAIN_TICKS, () -> {
            List<Recording> drops = new ArrayList<>();
            boolean block = false;
            for (ItemEntity entity : dropped(helper)) {
                if (entity.getItem().is(PhonographRegistry.WAX_CYLINDER)) {
                    drops.add(Recording.of(entity.getItem()));
                }
                block |= entity.getItem().is(PhonographRegistry.PHONOGRAPH.asItem());
            }
            expect(helper, drops.equals(List.of(Recording.GAMME_DE_LA_VOIX)), "the cylinder dropped: %s", drops);
            expect(helper, block, "the phonograph dropped itself");
            expect(
                    helper,
                    player.soundsStopped().contains(id(DeepSound.PHONOGRAPH_GAMME_DE_LA_VOIX)),
                    "the sound was stopped: %s",
                    player.soundsStopped());
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 700)
    public void aPlaybackEndsByItselfWhenTheRecordingDoes(GameTestHelper helper) {
        PhonographBlockEntity phonograph = place(helper);
        MockPlayer player = join(helper, "patient");
        use(helper, player, cylinder(Recording.GAMME_DE_LA_VOIX), false);
        int length = Recording.GAMME_DE_LA_VOIX.lengthTicks();
        helper.runAfterDelay(
                length - 5, () -> expect(helper, phonograph.isPlaying(), "it is still playing near the end"));
        helper.runAfterDelay(length + 5, () -> {
            expect(helper, !phonograph.isPlaying(), "it stopped when the recording ended");
            expect(helper, !phonograph.cylinder().isEmpty() && loaded(helper), "the cylinder stays in");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void aPlayerBeyondTheRangeIsNeverSentTheStartNorTheStop(GameTestHelper helper) {
        PhonographBlockEntity phonograph = place(helper);
        MockPlayer near = join(helper, "near");
        MockPlayer far = join(helper, "far");
        ServerLevel level = helper.getLevel();
        Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(PLACE));
        double range = SoundTuning.DEFAULT.phonographRange();
        far.teleportTo(level, at.add(range + 6, 1, 0), 0f, 0f);
        use(helper, near, cylinder(Recording.OTHELLO), false);
        use(helper, near, ItemStack.EMPTY, false);
        expect(helper, !phonograph.isPlaying(), "stopped");
        helper.runAfterDelay(DRAIN_TICKS, () -> {
            expect(helper, startsAtMine(helper, far).isEmpty(), "a player past %s blocks heard the start", range);
            expect(
                    helper,
                    !far.soundsStopped().contains(id(DeepSound.PHONOGRAPH_OTHELLO)),
                    "a player who never heard the start was sent the stop: %s",
                    far.soundsStopped());
            expect(helper, startsAtMine(helper, near).size() == 1, "the near player heard the start once");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void aPlayerWhoComesIntoRangeWhileItPlaysIsSentTheStartAndTheStopLater(GameTestHelper helper) {
        PhonographBlockEntity phonograph = place(helper);
        MockPlayer first = join(helper, "first");
        MockPlayer late = join(helper, "late");
        ServerLevel level = helper.getLevel();
        Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(PLACE));
        double range = SoundTuning.DEFAULT.phonographRange();
        late.teleportTo(level, at.add(range + 6, 1, 0), 0f, 0f);
        use(helper, first, cylinder(Recording.GAMME_DE_LA_VOIX), false);
        expect(helper, phonograph.listenerCount() >= 1, "the first player is a listener");
        int listeners = phonograph.listenerCount();
        helper.runAfterDelay(DRAIN_TICKS, () -> {
            expect(helper, startsAtMine(helper, late).isEmpty(), "the late player heard the start from afar");
            late.teleportTo(level, at.add(3, 1, 0), 0f, 0f);
        });
        helper.runAfterDelay(DRAIN_TICKS + SoundTuning.DEFAULT.phonographListenTicks() + DRAIN_TICKS, () -> {
            expect(helper, phonograph.isPlaying() && phonograph.listenerCount() > 0, "still playing");
            expect(
                    helper,
                    startsAtMine(helper, late).equals(List.of(id(DeepSound.PHONOGRAPH_GAMME_DE_LA_VOIX))),
                    "the late player was sent the start once: %s",
                    startsAtMine(helper, late));
            expect(helper, startsAtMine(helper, first).size() == 1, "the first player was sent it again");
            expect(helper, phonograph.listenerCount() > listeners, "the late player became a listener");
            use(helper, first, ItemStack.EMPTY, false);
        });
        helper.runAfterDelay(DRAIN_TICKS * 3 + SoundTuning.DEFAULT.phonographListenTicks() + 2, () -> {
            expect(
                    helper,
                    late.soundsStopped().contains(id(DeepSound.PHONOGRAPH_GAMME_DE_LA_VOIX)),
                    "the late listener was not sent the stop: %s",
                    late.soundsStopped());
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void stoppingOnePhonographLeavesAnotherPlayingTheSameRecordingAlone(GameTestHelper helper) {
        PhonographBlockEntity mine = place(helper);
        ServerLevel level = helper.getLevel();
        BlockPos otherPos = helper.absolutePos(PLACE).offset(60, 0, 0);
        level.getChunk(otherPos.getX() >> 4, otherPos.getZ() >> 4);
        level.setBlock(otherPos, PhonographRegistry.PHONOGRAPH.defaultBlockState(), 3);
        MockPlayer nearMine = join(helper, "near-mine");
        MockPlayer nearOther = join(helper, "near-other");
        nearOther.teleportTo(level, Vec3.atBottomCenterOf(otherPos).add(2, 0, 0), 0f, 0f);
        if (!(level.getBlockEntity(otherPos) instanceof PhonographBlockEntity other)) {
            throw failure(helper, "the second phonograph has no block entity");
        }
        use(helper, nearMine, cylinder(Recording.OTHELLO), false);
        nearOther.player().setItemInHand(InteractionHand.MAIN_HAND, cylinder(Recording.OTHELLO));
        other.insert(nearOther.player(), InteractionHand.MAIN_HAND);
        helper.runAfterDelay(DRAIN_TICKS, () -> {
            expect(helper, other.isPlaying() && mine.isPlaying(), "both play the same recording");
            mine.stop();
        });
        helper.runAfterDelay(DRAIN_TICKS * 2 + 2, () -> {
            expect(
                    helper,
                    nearMine.soundsStopped().contains(id(DeepSound.PHONOGRAPH_OTHELLO)),
                    "the stopped phonograph's listener was not told to stop");
            expect(
                    helper,
                    !nearOther.soundsStopped().contains(id(DeepSound.PHONOGRAPH_OTHELLO)),
                    "the other phonograph's listener was told to stop it: %s",
                    nearOther.soundsStopped());
            expect(helper, other.isPlaying(), "the other phonograph stopped");
            other.stop();
            level.setBlock(otherPos, Blocks.AIR.defaultBlockState(), 3);
            helper.succeed();
        });
    }

    /** The saved names of the recordings are permanent: a cylinder in a world and a loot table name them. */
    @GameTest
    public void theRecordingsNamesArePermanent(GameTestHelper helper) {
        expect(
                helper,
                List.of(Recording.values()).stream()
                        .map(Recording::getSerializedName)
                        .toList()
                        .equals(List.of(
                                "gamme_de_la_voix",
                                "jeune_jouvencelle",
                                "othello",
                                "volta_counting",
                                "volta_hamlet",
                                "sullivan_toast",
                                "edison_around_the_world",
                                "lost_chord",
                                "edison_doll",
                                "volta_nursery_rhyme")),
                "the recordings are named %s",
                List.of(Recording.values()));
        expect(
                helper,
                Recording.GAMME_DE_LA_VOIX.year() == 1860
                        && Recording.JEUNE_JOUVENCELLE.year() == 1857
                        && Recording.VOLTA_COUNTING.year() == 1881
                        && Recording.VOLTA_HAMLET.year() == 1884
                        && Recording.SULLIVAN_TOAST.year() == 1888
                        && Recording.EDISON_AROUND_THE_WORLD.year() == 1888
                        && Recording.LOST_CHORD.year() == 1888
                        && Recording.EDISON_DOLL.year() == 1888
                        && Recording.VOLTA_NURSERY_RHYME.year() == 1885,
                "the years moved");
        helper.succeed();
    }

    /** The tooltip of a cylinder is its title and its year and nothing else, for the later recordings as for the first three (#946). */
    @GameTest
    public void aCylindersLabelIsItsTitleAndYearAndNothingElse(GameTestHelper helper) {
        for (Recording recording : Recording.values()) {
            expect(
                    helper,
                    recording.label().getContents() instanceof TranslatableContents label
                            && label.getKey().equals("deepcharter.phonograph.label")
                            && label.getArgs().length == 2
                            && label.getArgs()[0] instanceof Component title
                            && title.getContents() instanceof TranslatableContents name
                            && name.getKey().equals("deepcharter.recording." + recording.getSerializedName())
                            && label.getArgs()[1].equals(recording.year()),
                    "the label of %s is not its title and year: %s",
                    recording,
                    recording.label());
        }
        helper.succeed();
    }

    @GameTest
    public void theCylinderSurvivesASave(GameTestHelper helper) {
        PhonographBlockEntity phonograph = place(helper);
        MockPlayer player = join(helper, "saver");
        use(helper, player, cylinder(Recording.JEUNE_JOUVENCELLE), false);
        BlockState state = helper.getBlockState(PLACE);
        CompoundTag saved = phonograph.saveWithFullMetadata(helper.getLevel().registryAccess());
        BlockEntity loaded = BlockEntity.loadStatic(
                helper.absolutePos(PLACE), state, saved, helper.getLevel().registryAccess());
        expect(
                helper,
                loaded instanceof PhonographBlockEntity read
                        && Recording.of(read.cylinder()) == Recording.JEUNE_JOUVENCELLE
                        && !read.isPlaying(),
                "the loaded block entity holds the cylinder and is not playing: %s",
                String.valueOf(loaded));
        helper.succeed();
    }

    @GameTest
    public void thePhonographIsCraftedFromCopperIronAndPlanks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<ItemStack> grid = List.of(
                ItemStack.EMPTY,
                new ItemStack(Items.COPPER_INGOT),
                ItemStack.EMPTY,
                new ItemStack(Items.OAK_PLANKS),
                new ItemStack(Items.IRON_INGOT),
                new ItemStack(Items.SPRUCE_PLANKS),
                new ItemStack(Items.OAK_PLANKS),
                new ItemStack(Items.OAK_PLANKS),
                new ItemStack(Items.OAK_PLANKS));
        CraftingInput input = CraftingInput.of(3, 3, grid);
        ItemStack result = level.recipeAccess()
                .getRecipeFor(RecipeType.CRAFTING, input, level)
                .map(holder -> holder.value().assemble(input))
                .orElseThrow(() -> failure(helper, "no recipe takes copper, iron and planks in that shape"));
        expect(helper, result.is(PhonographRegistry.PHONOGRAPH.asItem()), "it crafts a phonograph, got %s", result);
        List<ItemStack> noIron = new ArrayList<>(grid);
        noIron.set(4, new ItemStack(Items.COPPER_INGOT));
        CraftingInput wrong = CraftingInput.of(3, 3, noIron);
        expect(
                helper,
                level.recipeAccess()
                        .getRecipeFor(RecipeType.CRAFTING, wrong, level)
                        .isEmpty(),
                "two coppers and no iron craft nothing");
        helper.succeed();
    }

    @GameTest
    public void everyRecordingHasItsOwnSoundAndAuClairDeLaLuneIsNone(GameTestHelper helper) {
        List<DeepSound> sounds = new ArrayList<>();
        for (Recording recording : Recording.values()) {
            sounds.add(recording.sound());
            String name =
                    recording.getSerializedName() + recording.sound().event().location();
            expect(helper, !name.contains("clair") && !name.contains("lune"), "%s is Au clair de la lune", recording);
            expect(
                    helper,
                    cylinder(recording).get(PhonographRegistry.RECORDING) == recording,
                    "a cylinder carries %s",
                    recording);
        }
        expect(helper, sounds.stream().distinct().count() == sounds.size(), "each recording has its own sound");
        expect(
                helper,
                Recording.COLONY_RACK.equals(List.of(
                        Recording.GAMME_DE_LA_VOIX,
                        Recording.JEUNE_JOUVENCELLE,
                        Recording.OTHELLO,
                        Recording.EDISON_DOLL,
                        Recording.VOLTA_NURSERY_RHYME)),
                "the colony's rack holds its three recordings and then the doll and the rhyme, in order, and none of the found ones: %s",
                Recording.COLONY_RACK);
        helper.succeed();
    }

    private static List<ItemEntity> dropped(GameTestHelper helper) {
        // entity-query: the test's own block, in the chunk the test is running in
        BlockPos pos = helper.absolutePos(PLACE);
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(3));
    }

    private static Identifier id(DeepSound sound) {
        return sound.event().location();
    }
}
