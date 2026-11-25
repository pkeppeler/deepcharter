package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.handbook.Directives;
import io.github.pkeppeler.deepcharter.handbook.HandbookEvents;
import io.github.pkeppeler.deepcharter.handbook.HandbookProgress;
import io.github.pkeppeler.deepcharter.handbook.HandbookProgressData;
import io.github.pkeppeler.deepcharter.handbook.HandbookTuning;
import io.github.pkeppeler.deepcharter.layer.BreachEvents;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.sound.HymnMomentsProbe;
import io.github.pkeppeler.deepcharter.sound.SoundTuning;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalBlock;
import io.github.pkeppeler.deepcharter.terminal.TerminalRefusal;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TerminalParts;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.WorldData;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for #1267: a terminal that comes online plays the power-on and its chord once, in the world where its repairer
 * stands, and a handbook chapter whose last directive is done plays the Lamp Hymn's phrase once to each online member, and never for
 * progress a charter already had.
 *
 * <p>The chapter tests use the test mod's sample chapter ({@code data/deepcharter/deepcharter/handbook_chapter/sample.json}): its
 * {@code sleep} directive is completed by the vanilla "slept in bed" trigger and the poll, its {@code custom} one by {@link
 * Directives#fire}. Each test founds a charter of its own, so it starts with no progress.
 */
public class MachineAndChapterStingsTest {
    private static final int DRAIN_TICKS = 3;
    private static final int POLL_BUDGET_TICKS = 400;
    private static final Identifier POWER_ON = id("terminal.power_on");
    private static final Identifier ONLINE = id("terminal.online");
    private static final Identifier PHRASE = id("hymn.chapter");
    private static final Identifier SAMPLE_CHAPTER = id("sample");
    private static final Identifier BACK_ONLINE = id("back_online");
    private static final Identifier COLONY_HYMN = id("hymn.colony");
    private static final Identifier LAYER_1_HYMN = id("hymn.layer_1");
    private static final Identifier SLEEP = id("handbook/sample/sleep");
    private static final Identifier CUSTOM = id("handbook/sample/custom");

    /** One firing of {@link HandbookEvents#CHAPTER_COMPLETED}: plain values, so the listener keeps no player alive. */
    private record Completion(UUID player, Identifier chapter) {}

    private static final List<Completion> COMPLETED = new ArrayList<>();

    static {
        HandbookEvents.CHAPTER_COMPLETED.register(
                (server, player, chapter) -> COMPLETED.add(new Completion(player.getUUID(), chapter)));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path);
    }

    private static List<Identifier> completedFor(MockPlayer mock) {
        return COMPLETED.stream()
                .filter(completion -> completion.player().equals(mock.player().getUUID()))
                .map(Completion::chapter)
                .toList();
    }

    private static long heard(MockPlayer mock, Identifier sound) {
        return mock.soundsHeard().stream().filter(sound::equals).count();
    }

    /** The sounds the server played to {@code mock} at {@code at} (a packet's place is rounded to an eighth of a block). */
    private static List<Identifier> heardAt(MockPlayer mock, Vec3 at) {
        return mock.soundsHeardAtPlaces().stream()
                .filter(sound -> sound.at().distanceToSqr(at) < 1)
                .map(MockPlayer.PlacedSound::id)
                .toList();
    }

    private static String name(MockPlayer mock) {
        return mock.player().getGameProfile().name();
    }

    private static MockPlayer survivor(GameTestHelper helper, String name) {
        MockPlayer mock = MockPlayers.join(helper, name);
        mock.player().setGameMode(GameType.SURVIVAL);
        return mock;
    }

    private static CharterId found(GameTestHelper helper, MockPlayer director, MockPlayer... crew) {
        MinecraftServer server = helper.getLevel().getServer();
        UUID founder = director.player().getUUID();
        expect(
                helper,
                Charters.found(
                                server,
                                founder,
                                "Stings " + UUID.randomUUID().toString().substring(0, 8))
                        .isEmpty(),
                "founding should succeed");
        CharterId charter =
                Charters.charterOfOrThrow(server, founder).orElseThrow().id();
        for (MockPlayer member : crew) {
            join(helper, charter, director, member);
        }
        return charter;
    }

    private static void join(GameTestHelper helper, CharterId charter, MockPlayer director, MockPlayer member) {
        MinecraftServer server = helper.getLevel().getServer();
        UUID joiner = member.player().getUUID();
        expect(
                helper,
                Charters.apply(server, joiner, charter).isEmpty()
                        && Charters.approve(server, director.player().getUUID(), joiner)
                                .isEmpty(),
                "%s should join the charter",
                name(member));
    }

    private static void expectSleepRecorded(GameTestHelper helper, MockPlayer mock) {
        expect(
                helper,
                HandbookProgress.completedFor(
                                helper.getLevel().getServer(), mock.player().getUUID())
                        .contains(SLEEP),
                "the poll should record the sleep directive for %s",
                name(mock));
    }

    /** Each of {@code mocks} has heard the phrase {@code phrases} times and completed exactly {@code chapters}. */
    private static void expectPhrases(
            GameTestHelper helper, int phrases, List<Identifier> chapters, String what, MockPlayer... mocks) {
        for (MockPlayer mock : mocks) {
            expect(
                    helper,
                    heard(mock, PHRASE) == phrases && completedFor(mock).equals(chapters),
                    "%s, %s heard %s and completed %s",
                    what,
                    name(mock),
                    mock.soundsHeard(),
                    completedFor(mock));
        }
    }

    /** Puts {@code mock} {@code distance} blocks east of the pump at {@code pump}, eyes level with it, and returns where they stand. */
    private static Vec3 standBy(MockPlayer mock, GameTestHelper helper, BlockPos pump, double distance) {
        Vec3 centre = Vec3.atCenterOf(pump);
        Vec3 feet = new Vec3(centre.x + distance, centre.y - mock.player().getEyeHeight(), centre.z);
        mock.teleportTo(helper.getLevel(), feet, 0, 0);
        return mock.player().position();
    }

    /** Gives {@code fitter} each of {@code parts}, then has them insert every one into the terminal at {@code pos}. */
    private static void fit(GameTestHelper helper, MockPlayer fitter, BlockPos pos, List<Item> parts) {
        ServerPlayer player = fitter.player();
        parts.forEach(part -> player.getInventory().add(new ItemStack(part)));
        for (Item part : parts) {
            Optional<TerminalRefusal> refusal = TerminalParts.insert(player, pos, part);
            if (refusal.isPresent()) {
                throw failure(helper, "inserting %s should succeed, was refused: %s", part, refusal.get());
            }
        }
    }

    /** Places a whole terminal of {@code type}, both halves, in the test's structure. */
    private static BlockPos place(GameTestHelper helper, TerminalType type) {
        BlockPos pos = helper.absolutePos(new BlockPos(0, 1, 0));
        TerminalBlock.place(helper.getLevel(), pos, type.block().defaultBlockState(), 3);
        return pos;
    }

    @GameTest
    public void theLastPartPlaysThePowerOnAndTheChordOnceToEveryoneNear(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer fitter = survivor(helper, "Fitter");
        MockPlayer onlooker = survivor(helper, "Onlooker");
        found(helper, fitter);
        BlockPos pump = place(helper, TerminalTypes.FUEL_PUMP);
        Vec3 at = standBy(fitter, helper, pump, 2);
        standBy(onlooker, helper, pump, 3.5);
        WorldData.with(server, RepairState.TYPE, new RepairState(), () -> {
            fit(helper, fitter, pump, TerminalTypes.FUEL_PUMP.parts());
            expect(helper, RepairState.get(server).repaired(TerminalTypes.FUEL_PUMP), "the pump should be repaired");
        });
        helper.runAfterDelay(DRAIN_TICKS, () -> {
            for (MockPlayer mock : List.of(fitter, onlooker)) {
                expect(
                        helper,
                        heardAt(mock, at).equals(List.of(POWER_ON, ONLINE)),
                        "%s should hear the power-on, then the chord, once each, where the fitter stands, heard %s",
                        name(mock),
                        heardAt(mock, at));
            }
            helper.succeed();
        });
    }

    @GameTest
    public void aPartThatDoesNotFinishTheRepairPlaysNeither(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer fitter = survivor(helper, "HalfFitter");
        found(helper, fitter);
        BlockPos pump = place(helper, TerminalTypes.FUEL_PUMP);
        Vec3 at = standBy(fitter, helper, pump, 2);
        WorldData.with(server, RepairState.TYPE, new RepairState(), () -> {
            fit(helper, fitter, pump, List.of(TerminalTypes.FUEL_PUMP.parts().getFirst()));
            expect(
                    helper,
                    !RepairState.get(server).repaired(TerminalTypes.FUEL_PUMP),
                    "one part of the pump's should leave it offline");
        });
        helper.runAfterDelay(DRAIN_TICKS, () -> {
            expect(
                    helper,
                    heardAt(fitter, at).isEmpty(),
                    "a part that finishes nothing should be silent, heard %s",
                    heardAt(fitter, at));
            helper.succeed();
        });
    }

    @GameTest(maxTicks = POLL_BUDGET_TICKS)
    public void theLastDirectiveOfAChapterPlaysThePhraseOnceToEachOnlineMember(GameTestHelper helper) {
        MockPlayer director = MockPlayers.join(helper, "ChapterDirector");
        MockPlayer crew = MockPlayers.join(helper, "ChapterCrew");
        found(helper, director, crew);
        // Founding and joining play the colony hymn, which the phrase gives way to: start from silence.
        HymnMomentsProbe.forget(director.player());
        HymnMomentsProbe.forget(crew.player());
        helper.startSequence()
                .thenExecute(() -> CriteriaTriggers.SLEPT_IN_BED.trigger(crew.player()))
                .thenWaitUntil(() -> expectSleepRecorded(helper, director))
                .thenIdle(DRAIN_TICKS)
                .thenExecute(() -> {
                    expectPhrases(
                            helper,
                            0,
                            List.of(),
                            "a directive that leaves the chapter open plays nothing",
                            director,
                            crew);
                    Directives.fire(director.player(), CUSTOM);
                })
                // The phrase is queued for the next tick at the earliest, and longer while another test's chord rings.
                .thenWaitUntil(() -> expectPhrases(
                        helper,
                        1,
                        List.of(SAMPLE_CHAPTER),
                        "the last directive plays the phrase once to each member",
                        director,
                        crew))
                .thenExecute(() -> {
                    // Done again, by anyone and however, it is no news: the hymn rule cannot be what keeps it quiet.
                    HymnMomentsProbe.forget(director.player());
                    HymnMomentsProbe.forget(crew.player());
                    Directives.fire(crew.player(), CUSTOM);
                    CriteriaTriggers.SLEPT_IN_BED.trigger(director.player());
                })
                .thenIdle(HandbookTuning.DEFAULT.progressPollTicks() * 2)
                .thenExecute(() ->
                        expectPhrases(helper, 1, List.of(SAMPLE_CHAPTER), "a chapter completes once", director, crew))
                .thenSucceed();
    }

    @GameTest(maxTicks = POLL_BUDGET_TICKS)
    public void aPlayerOnNoCharterHearsThePhraseForTheirOwnChapter(GameTestHelper helper) {
        MockPlayer loner = MockPlayers.join(helper, "ChapterLoner");
        helper.startSequence()
                .thenExecute(() -> CriteriaTriggers.SLEPT_IN_BED.trigger(loner.player()))
                .thenWaitUntil(() -> expectSleepRecorded(helper, loner))
                .thenExecute(() -> Directives.fire(loner.player(), CUSTOM))
                .thenWaitUntil(() -> expectPhrases(
                        helper, 1, List.of(SAMPLE_CHAPTER), "the loner's own chapter plays the phrase once", loner))
                .thenSucceed();
    }

    @GameTest(maxTicks = POLL_BUDGET_TICKS)
    public void joiningACharterOrTheWorldWithTheChapterAlreadyDonePlaysNoPhrase(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer director = MockPlayers.join(helper, "DoneDirector");
        CharterId charter = found(helper, director);
        UUID returnerId = UUID.randomUUID();
        MockPlayer[] returner = {MockPlayers.join(helper, "DoneReturner", returnerId)};
        MockPlayer[] newcomer = new MockPlayer[1];
        helper.startSequence()
                .thenExecute(() -> {
                    join(helper, charter, director, returner[0]);
                    CriteriaTriggers.SLEPT_IN_BED.trigger(director.player());
                })
                .thenWaitUntil(() -> expectSleepRecorded(helper, director))
                .thenExecute(() -> {
                    Directives.fire(director.player(), CUSTOM);
                    expect(
                            helper,
                            completedFor(returner[0]).equals(List.of(SAMPLE_CHAPTER)),
                            "the chapter should complete for the online crew, completed %s",
                            completedFor(returner[0]));
                    returner[0].leave();
                    newcomer[0] = MockPlayers.join(helper, "DoneNewcomer");
                    join(helper, charter, director, newcomer[0]);
                    returner[0] = MockPlayers.join(helper, "DoneReturner", returnerId);
                })
                .thenIdle(HandbookTuning.DEFAULT.progressPollTicks() * 2)
                .thenExecute(() -> {
                    expect(
                            helper,
                            HandbookProgress.completedFor(
                                            server, newcomer[0].player().getUUID())
                                    .containsAll(List.of(SLEEP, CUSTOM)),
                            "the newcomer should inherit the finished chapter");
                    for (MockPlayer mock : List.of(newcomer[0], returner[0])) {
                        expect(
                                helper,
                                heard(mock, PHRASE) == 0,
                                "%s joined with the chapter done and should hear no phrase, heard %s",
                                name(mock),
                                mock.soundsHeard());
                    }
                    expect(
                            helper,
                            completedFor(newcomer[0]).isEmpty(),
                            "joining a charter completes no chapter, the newcomer completed %s",
                            completedFor(newcomer[0]));
                    expect(
                            helper,
                            completedFor(returner[0]).equals(List.of(SAMPLE_CHAPTER)),
                            "coming back to the world completes no chapter again, the returner completed %s",
                            completedFor(returner[0]));
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = POLL_BUDGET_TICKS)
    public void aRepairThatFinishesAChapterPlaysThePhraseOnlyOnceTheChordIsOver(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer fitter = survivor(helper, "ChapterFitter");
        CharterId charter = found(helper, fitter);
        HymnMomentsProbe.forget(fitter.player());
        // Chapter 2 asks for three repairs; the first two are done, so the upgrade terminal's repair finishes it.
        HandbookProgressData.get(server).complete(charter, id("handbook/back_online/repair_fuel_pump"));
        HandbookProgressData.get(server).complete(charter, id("handbook/back_online/repair_ore_processor"));
        BlockPos terminal = place(helper, TerminalTypes.UPGRADE_TERMINAL);
        Vec3[] at = new Vec3[1];
        int chord = SoundTuning.DEFAULT.terminalOnlineTicks();
        helper.startSequence()
                .thenExecute(() -> WorldData.with(server, RepairState.TYPE, new RepairState(), () -> {
                    RepairState state = RepairState.get(server);
                    for (TerminalType before : List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR)) {
                        for (Item part : before.parts()) {
                            expect(
                                    helper,
                                    state.insert(before, part).isEmpty(),
                                    "%s should take %s",
                                    before.id(),
                                    part);
                        }
                    }
                    at[0] = standBy(fitter, helper, terminal, 2);
                    fit(helper, fitter, terminal, TerminalTypes.UPGRADE_TERMINAL.parts());
                }))
                .thenIdle(DRAIN_TICKS)
                .thenExecute(() -> expect(
                        helper,
                        heardAt(fitter, at[0]).equals(List.of(POWER_ON, ONLINE))
                                && completedFor(fitter).equals(List.of(BACK_ONLINE))
                                && heard(fitter, PHRASE) == 0,
                        "the repair plays power-on and chord and ends chapter 2, heard %s here and %s, completed %s",
                        heardAt(fitter, at[0]),
                        fitter.soundsHeard(),
                        completedFor(fitter)))
                .thenIdle(chord - DRAIN_TICKS - 2)
                .thenExecute(() -> expect(
                        helper,
                        heard(fitter, PHRASE) == 0,
                        "the phrase waits for the chord's %s ticks, heard %s",
                        chord,
                        fitter.soundsHeard()))
                .thenWaitUntil(() -> expect(
                        helper,
                        heard(fitter, PHRASE) == 1,
                        "the phrase plays once the chord is over, heard %s",
                        fitter.soundsHeard()))
                .thenIdle(DRAIN_TICKS)
                .thenExecute(() -> expect(
                        helper, heard(fitter, PHRASE) == 1, "the phrase plays once, heard %s", fitter.soundsHeard()))
                .thenSucceed();
    }

    @GameTest(maxTicks = POLL_BUDGET_TICKS)
    public void aChapterFinishedWhileTheFoundingHymnPlaysGetsNoPhrase(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer founder = MockPlayers.join(helper, "HymnFounder");
        int hymnTicks = SoundTuning.DEFAULT.hymnTicks().getFirst();
        helper.startSequence()
                // Founded after the batch's first tick, when HymnMomentsTest resets all hymn state.
                .thenExecute(() -> {
                    found(helper, founder);
                    CriteriaTriggers.SLEPT_IN_BED.trigger(founder.player());
                })
                .thenWaitUntil(() -> expectSleepRecorded(helper, founder))
                .thenExecute(() -> {
                    Directives.fire(founder.player(), CUSTOM);
                    expect(
                            helper,
                            completedFor(founder).equals(List.of(SAMPLE_CHAPTER)),
                            "the chapter should complete for the founder, completed %s",
                            completedFor(founder));
                })
                .thenIdle(DRAIN_TICKS)
                .thenExecute(() -> expect(
                        helper,
                        heard(founder, COLONY_HYMN) == 1 && heard(founder, PHRASE) == 0,
                        "the founding hymn plays and the phrase gives way to it, heard %s",
                        founder.soundsHeard()))
                // Past the founding hymn's end: a phrase that had only been put off would have played by now.
                .thenIdle(hymnTicks)
                .thenExecute(() -> {
                    expect(
                            helper,
                            heard(founder, PHRASE) == 0,
                            "the dropped phrase must not come back, heard %s",
                            founder.soundsHeard());
                    HymnMomentsProbe.forget(founder.player());
                    BreachEvents.CROSSED
                            .invoker()
                            .onCrossed(
                                    founder.player(),
                                    server.overworld(),
                                    TestLayers.level(server, 1),
                                    LayerChain.SURFACE,
                                    1);
                })
                .thenWaitUntil(() -> expect(
                        helper,
                        heard(founder, LAYER_1_HYMN) == 1,
                        "a later hymn moment still plays, heard %s",
                        founder.soundsHeard()))
                .thenSucceed();
    }
}
