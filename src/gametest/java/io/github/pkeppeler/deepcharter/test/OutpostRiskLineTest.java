package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.ore.SlagBrick;
import io.github.pkeppeler.deepcharter.outpost.OutpostData;
import io.github.pkeppeler.deepcharter.outpost.OutpostData.Outpost;
import io.github.pkeppeler.deepcharter.outpost.OutpostHabitability;
import io.github.pkeppeler.deepcharter.outpost.OutpostHabitability.Status;
import io.github.pkeppeler.deepcharter.outpost.RatingAxis;
import io.github.pkeppeler.deepcharter.outpost.Ratings;
import io.github.pkeppeler.deepcharter.suit.OutpostRisks;
import io.github.pkeppeler.deepcharter.suit.SuitTuning;
import io.github.pkeppeler.deepcharter.terminal.TerminalNotices;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;
import io.github.pkeppeler.deepcharter.terminal.TerminalViewPayload;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.SuitScenes;
import io.github.pkeppeler.deepcharter.test.support.SuitScenes.Hall;
import io.github.pkeppeler.deepcharter.test.support.SuitScenes.Pocket;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.WorldData;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;

/**
 * Server GameTests for the risk line of the outpost terminals (#993, ADR 0083): one line under the header, in words, that says whether the
 * shell holds, and what it costs if it does not. It is hidden where the depth asks nothing, and a guest sees what the owner sees.
 */
public class OutpostRiskLineTest {
    private static final String SAMPLE = "deepcharter:campaign_sample";
    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 400;
    private static final Ratings EXAMPLE_LAYER = new Ratings(2, 1, 1, 2);
    /** A row that asks quake alone, which the suit does not hold against. */
    private static final Ratings QUAKE_ONLY = new Ratings(0, 0, 0, 3);

    private static final String HOLDS = "notice.deepcharter.outpost.holds";
    private static final String LEAKS = "notice.deepcharter.outpost.leaks";
    private static final String UNRATED = "notice.deepcharter.outpost.unrated";

    private static String key(Component component) {
        return component.getContents() instanceof TranslatableContents translatable ? translatable.getKey() : "";
    }

    private static List<String> keys(List<Component> lines) {
        return lines.stream().map(OutpostRiskLineTest::key).toList();
    }

    /** Holding, leaking and nothing-rated each have their own line, with the axes and the count in the leak; a depth that asks nothing of a suit has none. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void theLineSaysHoldingLeakingOrNothingRatedAndIsHiddenWhereTheDepthAsksNothing(GameTestHelper helper) {
        Hall hall = SuitScenes.stageHall(helper, 6700 + 64, "risk-words");
        SuitScenes.whenHallReady(helper, hall, () -> {
            Outpost outpost = hall.build();
            Status leaking = OutpostHabitability.of(hall.level(), outpost, Optional.of(EXAMPLE_LAYER));
            List<Component> leaks = OutpostRisks.lines(Optional.of(EXAMPLE_LAYER), leaking);
            expect(helper, keys(leaks).equals(List.of(LEAKS)), "a hall short on the row says it leaks, said %s", leaks);
            Object[] args = ((TranslatableContents) leaks.getFirst().getContents()).getArgs();
            expect(
                    helper,
                    args.length == 3
                            && args[1].equals(leaking.unrated())
                            && args[0] instanceof Component
                            && args[2].equals(Math.round(SuitTuning.DEFAULT.leakShare() * 100)),
                    "the leak names the axes, counts the blocks and gives the suit's share in per cent, got %s",
                    List.of(args));

            for (BlockPos pos : BlockPos.betweenClosed(
                    hall.floor().offset(-1, 0, -1), hall.floor().offset(1, 0, 1))) {
                hall.level().setBlock(pos, SlagBrick.BLOCK.defaultBlockState(), 3);
            }
            for (BlockPos pos : BlockPos.betweenClosed(
                    hall.floor().offset(-2, 6, -2), hall.floor().offset(2, 6, 2))) {
                if (!hall.level().getBlockState(pos).isAir()) {
                    hall.level().setBlock(pos, SlagBrick.BLOCK.defaultBlockState(), 3);
                }
            }
            Status holding = OutpostHabitability.of(hall.level(), outpost, Optional.of(EXAMPLE_LAYER));
            List<Component> holds = OutpostRisks.lines(Optional.of(EXAMPLE_LAYER), holding);
            expect(helper, keys(holds).equals(List.of(HOLDS)), "a shell that holds says so");
            expect(
                    helper,
                    TerminalNotices.isGood(holds.getFirst())
                            && !TerminalNotices.isGood(leaks.getFirst())
                            && !TerminalNotices.isGood(OutpostRisks.lines(Optional.empty(), holding)
                                    .getFirst()),
                    "only the holding line is good news, drawn in phosphor; a leak and an unrated depth are amber");
            expect(
                    helper,
                    keys(OutpostRisks.lines(Optional.empty(), holding)).equals(List.of(UNRATED)),
                    "a depth with no row says nothing is rated");
            expect(
                    helper,
                    OutpostRisks.lines(Optional.of(Ratings.NONE), holding).isEmpty(),
                    "a depth that asks nothing shows no line");
            Status quakeOnly = OutpostHabitability.of(hall.level(), outpost, Optional.of(QUAKE_ONLY));
            expect(
                    helper,
                    quakeOnly.habitable()
                            && OutpostRisks.lines(Optional.of(QUAKE_ONLY), quakeOnly)
                                    .isEmpty(),
                    "a depth that asks only quake asks nothing of the suit and shows no line, the shell reads %s",
                    quakeOnly);
        });
    }

    /** A guest at a shared outpost sees the owner's line, and a terminal that is in no hall has none. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aGuestSeesTheSameLineAndNothingShowsOutsideAHall(GameTestHelper helper) {
        ServerLevel level = TestLayers.level(helper, 1);
        Pocket pocket = SuitScenes.unbuilt(level, 6828);
        Outpost outpost = new Outpost(new CharterId(UUID.randomUUID()), 1, level.dimension(), pocket.floor());
        MockPlayer owner = MockPlayers.join(helper, "risk-owner");
        MockPlayer guest = MockPlayers.join(helper, "risk-guest");
        FarChunks.awaitEntityTicking(helper, level, pocket.floor(), () -> {
            WorldData.with(level.getServer(), OutpostData.TYPE, SuitScenes.dataWith(outpost), () -> {
                BlockPos inside = pocket.floor().offset(0, 1, -4);
                for (MockPlayer who : List.of(owner, guest)) {
                    who.teleportTo(level, pocket.stand(), 0f, 0f);
                    List<Component> lines = TerminalNotices.collect(
                            level.getServer(),
                            who.player(),
                            Charters.readableCharterOf(
                                    level.getServer(), who.player().getUUID()),
                            inside);
                    expect(
                            helper,
                            keys(lines).equals(List.of(UNRATED)),
                            "%s should be told nothing is rated here, got %s",
                            who.player().getName().getString(),
                            lines);
                    List<Component> outside = TerminalNotices.collect(
                            level.getServer(),
                            who.player(),
                            Optional.empty(),
                            pocket.floor().offset(40, 1, 0));
                    expect(helper, outside.isEmpty(), "a terminal outside any hall has no line, got %s", outside);
                }
            });
            helper.succeed();
        });
    }

    /** The lines go with the view the server sends a player who opens a terminal, and survive the wire. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void theLinesTravelWithTheTerminalView(GameTestHelper helper) {
        Hall hall = SuitScenes.stageHall(helper, 6892, "risk-wire");
        BlockPos pump = hall.floor().offset(-2, 1, -4);
        // A source that speaks for this test's pump only: a Fabric event cannot be unregistered.
        TerminalNotices.register((server, player, charter, pos) ->
                pos.equals(pump) ? List.of(Component.translatable(UNRATED)) : List.of());
        // The sequence owns the success: whenHallReady would succeed on the tick it opens the pump, and a finished test
        // drops its sequences unrun.
        SuitScenes.hallSequence(helper, hall)
                .thenExecute(() -> SuitScenes.withFreshOutposts(hall, () -> {
                    Outpost outpost = hall.build();
                    hall.mock().announceChannel(TerminalViewPayload.TYPE);
                    expect(helper, outpost.centre().equals(hall.floor()), "the hall is built on the floor block");
                    expect(
                            helper,
                            hall.level().getBlockState(pump).is(TerminalTypes.FUEL_PUMP.block()),
                            "the pump stands against the north wall");
                    hall.standIn();
                    expect(helper, Terminals.open(hall.player(), pump).isEmpty(), "the owner opens the pump");
                    hall.player().setGameMode(GameType.SPECTATOR);
                }))
                .thenWaitUntil(() -> {
                    if (hall.mock().payloadsOf(TerminalViewPayload.class).isEmpty()) {
                        throw helper.assertionException("waiting for the view the server sends");
                    }
                })
                .thenExecute(() -> {
                    TerminalView view = hall.mock()
                            .payloadsOf(TerminalViewPayload.class)
                            .getLast()
                            .view();
                    expect(
                            helper,
                            keys(view.notices()).equals(List.of(UNRATED)),
                            "the line a source gave the pump should travel with its view, got %s",
                            view.notices());
                    // The real lines: a leak with its axes and count, and a holding line marked good.
                    List<Component> real = Stream.of(
                                    new Status(false, 40, 14, Set.of(RatingAxis.HEAT, RatingAxis.PRESSURE)),
                                    new Status(true, 40, 0, Set.of()))
                            .flatMap(status -> OutpostRisks.lines(Optional.of(EXAMPLE_LAYER), status).stream())
                            .toList();
                    TerminalView withLines = new TerminalView(
                            view.pos(),
                            view.type(),
                            view.repaired(),
                            view.unlocked(),
                            view.parts(),
                            view.feature(),
                            real);
                    ByteBuf wire = Unpooled.buffer();
                    TerminalView.STREAM_CODEC.encode(wire, withLines);
                    TerminalView decoded = TerminalView.STREAM_CODEC.decode(wire);
                    expect(
                            helper,
                            decoded.equals(withLines)
                                    && keys(decoded.notices()).equals(List.of(LEAKS, HOLDS))
                                    && !TerminalNotices.isGood(decoded.notices().getFirst())
                                    && TerminalNotices.isGood(decoded.notices().getLast())
                                    && wire.readableBytes() == 0,
                            "a view's lines go over the wire whole, with their args and their good mark, got %s from %s",
                            decoded.notices(),
                            withLines.notices());
                })
                .thenSucceed();
    }
}
