package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.repair.RepairStationScreen;
import io.github.pkeppeler.deepcharter.fuel.EngineSoot;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.FlueScrape;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.suit.SuitRegistry;
import io.github.pkeppeler.deepcharter.suit.SuitTracks;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RepairRows;
import io.github.pkeppeler.deepcharter.test.support.ScreenScroll;
import io.github.pkeppeler.deepcharter.test.support.SuitScenes;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Client GameTest for #70: the repair station screen opens, and its repair and buy buttons send the actions the server
 * acts on (the server's answer is checked, not the screen's own state). The soot rows (#1280) show only where they
 * apply.
 */
public class RepairStationClientTest implements FabricClientGameTest {
    private static final long START_BALANCE = 100_000;
    private static final float DAMAGE = 40f;

    /** What {@link #setUp} made: the station, the parked pod, and the charter. */
    public record Scene(BlockPos station, PodEntity pod, Charter charter) {}

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            Scene scene = singleplayer.getServer().computeOnServer(RepairStationClientTest::setUp);

            context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(scene.station())));
            ClientWait.screen(context, RepairStationScreen.class);

            clickRow(context, "REPAIR 10 HP ($10)");
            ClientWait.until(
                    context,
                    "the pod repaired by 10 HP",
                    () -> hull(singleplayer, scene) == scene.pod().maxHull() - DAMAGE + 10f,
                    () -> "hull " + hull(singleplayer, scene) + ", account $" + account(singleplayer, scene));
            require(
                    account(singleplayer, scene) == START_BALANCE - 10,
                    "10 HP cost $10, the account is $" + account(singleplayer, scene));

            clickRow(context, "BUY DYNAMITE $100");
            ClientWait.until(
                    context,
                    "one dynamite carried",
                    () -> carried(singleplayer, Consumable.DYNAMITE) == 1,
                    () -> carried(singleplayer, Consumable.DYNAMITE) + " dynamite, account $"
                            + account(singleplayer, scene));
            require(
                    account(singleplayer, scene) == START_BALANCE - 10 - 100,
                    "the dynamite cost $100, the account is $" + account(singleplayer, scene));

            clickRow(context, "REPAIR ALL");
            ClientWait.until(
                    context,
                    "the pod fully repaired",
                    () -> hull(singleplayer, scene) == scene.pod().maxHull(),
                    () -> "hull " + hull(singleplayer, scene) + " of "
                            + scene.pod().maxHull() + ", account $" + account(singleplayer, scene));
            require(
                    account(singleplayer, scene) == START_BALANCE - 10 - 100 - 30 * 1,
                    "the rest of the hull cost $30, the account is $" + account(singleplayer, scene));
            expectTheSootRowsOnlyWhereTheyApply(context, singleplayer, scene);
            expectTheSuitRowsMoveOnAsTiersAreBought(context, singleplayer, scene);
            context.setScreen(() -> null);
        }
    }

    /**
     * The soot rows (#1280) follow the synced soot with no press: a limp pod at the colony gets the paid row and the
     * free scrape, a pod under the limit only the paid row, a clean engine neither. A running scrape counts down on an
     * inactive row, and ends when the engine is no longer limp.
     */
    private static void expectTheSootRowsOnlyWhereTheyApply(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, Scene scene) {
        String scrape = "SCRAPE FLUE (FREE, SLOW)";
        setSoot(singleplayer, scene, 13f);
        ClientWait.until(
                context,
                "the paid row and the scrape row of a limp pod",
                () -> rowActive(context, "SERVICE ALL 13 L ($130)").equals(Optional.of(true))
                        && rowActive(context, scrape).equals(Optional.of(true)),
                () -> "rows " + labels(context));
        clickRow(context, scrape);
        ClientWait.until(
                context,
                "the scrape counting down on an inactive row",
                () -> labels(context).stream().anyMatch(label -> label.startsWith("SCRAPING FLUE: "))
                        && rowActive(context, scrape).isEmpty(),
                () -> "rows " + labels(context));
        require(
                singleplayer.getServer().computeOnServer(server -> FlueScrape.running(scene.pod())),
                "the press should start the scrape on the server");
        setSoot(singleplayer, scene, 11f);
        ClientWait.until(
                context,
                "only the paid row under the limit",
                () -> rowActive(context, "SERVICE ALL 11 L ($110)").equals(Optional.of(true))
                        && labels(context).stream().noneMatch(label -> label.contains("FLUE")),
                () -> "rows " + labels(context));
        require(
                !singleplayer.getServer().computeOnServer(server -> FlueScrape.running(scene.pod())),
                "a pod out of limp mode ends its scrape");
        setSoot(singleplayer, scene, 0f);
        ClientWait.until(
                context,
                "no soot rows for a clean engine",
                () -> labels(context).stream().noneMatch(label -> label.contains("SERVICE") || label.contains("FLUE")),
                () -> "rows " + labels(context));
    }

    private static void setSoot(TestSingleplayerContext singleplayer, Scene scene, float litres) {
        singleplayer
                .getServer()
                .runOnServer(server ->
                        scene.pod().setAttached(EngineSoot.STATE, Versioned.of(new EngineSoot.State(litres, 0f, 0f))));
    }

    /** Every row's label, scrolling the list through. */
    private static List<String> labels(ClientGameTestContext context) {
        RepairStationScreen screen =
                context.computeOnClient(client -> ClientSession.screen(client, RepairStationScreen.class));
        return context.computeOnClient(client -> {
            Set<String> seen = new LinkedHashSet<>();
            for (int first = 0; first < screen.rowCount(); first++) {
                ScreenScroll.scrollTo(screen, first);
                ClientChecks.buttons(screen)
                        .forEach(button -> seen.add(button.getMessage().getString()));
            }
            return List.copyOf(seen);
        });
    }

    /**
     * The suit track rows (#1064) read the tier the server last sent: buying heat tier 1 takes its price from the account and the row
     * moves on to tier 2 at its price, and at the top tier the row says so and cannot be pressed. The other tracks keep their rows.
     */
    private static void expectTheSuitRowsMoveOnAsTiersAreBought(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, Scene scene) {
        long before = account(singleplayer, scene);
        require(
                rowActive(context, "SUIT HEAT T1 $450").orElse(false)
                        && rowActive(context, "SUIT PRESSURE T1 $450").orElse(false)
                        && rowActive(context, "SUIT AIR T1 $550").orElse(false),
                "a stock suit is offered tier 1 of each track at its price");
        clickRow(context, "SUIT HEAT T1 $450");
        ClientWait.until(
                context,
                "the row at tier 2",
                () -> rowActive(context, "SUIT HEAT T2 $1,100").isPresent(),
                () -> "heat " + tracks(singleplayer).heat() + ", account $" + account(singleplayer, scene));
        require(
                account(singleplayer, scene) == before - 450
                        && tracks(singleplayer).heat() == 1,
                "heat tier 1 cost $450 and was fitted, the account is $" + account(singleplayer, scene));
        require(
                rowActive(context, "SUIT HEAT T1 $450").isEmpty()
                        && rowActive(context, "SUIT PRESSURE T1 $450").isPresent(),
                "only the heat row moved on");
        clickRow(context, "SUIT HEAT T2 $1,100");
        ClientWait.until(
                context,
                "the row at tier 3",
                () -> rowActive(context, "SUIT HEAT T3 $2,500").isPresent(),
                () -> "heat " + tracks(singleplayer).heat());
        clickRow(context, "SUIT HEAT T3 $2,500");
        ClientWait.until(
                context,
                "the top row",
                () -> rowActive(context, "SUIT HEAT (TOP)").isPresent(),
                () -> "heat " + tracks(singleplayer).heat());
        require(
                rowActive(context, "SUIT HEAT (TOP)").equals(Optional.of(false)),
                "a track at its top tier shows (TOP) and cannot be pressed");
        require(
                account(singleplayer, scene) == before - 450 - 1_100 - 2_500,
                "the three heat tiers cost $4,050, the account is $" + account(singleplayer, scene));

        // Tracks this build cannot read leave one inactive row that says so, and sell nothing.
        singleplayer
                .getServer()
                .runOnServer(server -> UnreadableChecks.makeUnreadable(
                        server.getPlayerList().getPlayers().getFirst(), SuitRegistry.TRACKS));
        ClientWait.until(
                context,
                "the unreadable tracks on the client",
                client -> client.player != null
                        && client.player.getAttached(SuitRegistry.TRACKS) instanceof Versioned.Unreadable<SuitTracks>);
        // The list is rebuilt by the server's next answer: a purchase elsewhere on the list asks for one.
        clickRow(context, "BUY DYNAMITE $100");
        ClientWait.until(
                context,
                "the unreadable row",
                () -> rowActive(context, "SUIT LOG UNREADABLE").isPresent(),
                () -> "rows not rebuilt yet");
        require(
                rowActive(context, "SUIT LOG UNREADABLE").equals(Optional.of(false)),
                "the unreadable row cannot be pressed");
        require(
                rowActive(context, "SUIT HEAT (TOP)").isEmpty(),
                "no track row is offered while the tracks are unreadable");
    }

    private static SuitTracks tracks(TestSingleplayerContext singleplayer) {
        return singleplayer
                .getServer()
                .computeOnServer(server ->
                        SuitScenes.tracks(server.getPlayerList().getPlayers().getFirst()));
    }

    /** Whether the button labelled {@code label} can be pressed, or empty when no row has that label. Scrolls the list to find it. */
    private static Optional<Boolean> rowActive(ClientGameTestContext context, String label) {
        RepairStationScreen screen =
                context.computeOnClient(client -> ClientSession.screen(client, RepairStationScreen.class));
        return context.computeOnClient(client -> {
            for (int first = 0; first < screen.rowCount(); first++) {
                ScreenScroll.scrollTo(screen, first);
                for (Button button : ClientChecks.buttons(screen)) {
                    if (button.getMessage().getString().equals(label)) {
                        return Optional.of(button.active);
                    }
                }
            }
            return Optional.empty();
        });
    }

    /**
     * Scrolls the open repair station until the button labelled {@code label} is shown, then clicks it: a small screen shows only
     * some rows. Fails when no row has the label, or when two do (the click would take the first).
     */
    public static void clickRow(ClientGameTestContext context, String label) {
        RepairStationScreen screen =
                context.computeOnClient(client -> ClientSession.screen(client, RepairStationScreen.class));
        int matches = context.computeOnClient(client -> {
            Set<Integer> rows = new HashSet<>();
            int shownAt = 0;
            for (int first = 0; first < screen.rowCount(); first++) {
                ScreenScroll.scrollTo(screen, first);
                List<Button> buttons = screen.children().stream()
                        .filter(Button.class::isInstance)
                        .map(Button.class::cast)
                        .toList();
                for (int i = 0; i < buttons.size(); i++) {
                    if (buttons.get(i).getMessage().getString().equals(label)
                            && rows.add(RepairRows.firstRow(screen) + i)) {
                        shownAt = RepairRows.firstRow(screen);
                    }
                }
            }
            // Leave the list where the row is shown, so that the click finds it.
            ScreenScroll.scrollTo(screen, shownAt);
            return rows.size();
        });
        require(matches == 1, matches + " buttons in the repair station are labelled '" + label + "', not 1");
        context.clickScreenButton(label);
    }

    /** The player founds a charter, a repaired station stands beside them, and the charter's damaged pod is parked at it. */
    public static Scene setUp(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        require(
                Charters.found(server, player.getUUID(), "Emendation Test Charter")
                        .isEmpty(),
                "founding should succeed");
        Charter charter = Charters.charterOfOrThrow(server, player.getUUID()).orElseThrow();
        RepairState state = RepairState.get(server);
        for (TerminalType type : List.of(
                TerminalTypes.FUEL_PUMP,
                TerminalTypes.ORE_PROCESSOR,
                TerminalTypes.UPGRADE_TERMINAL,
                TerminalTypes.REPAIR_STATION)) {
            type.parts()
                    .forEach(part -> state.insert(type, part).ifPresent(refusal -> {
                        throw new AssertionError("repairing " + type.id() + ": " + refusal);
                    }));
        }
        BlockPos here = player.blockPosition();
        BlockPos station = here.east(2);
        server.overworld()
                .setBlock(station, TerminalTypes.REPAIR_STATION.block().defaultBlockState(), 3);
        PodEntity pod = TestEntities.create(PodRegistry.POD, server.overworld());
        pod.setPos(here.getX() + 0.5, here.getY(), here.getZ() + 4.5);
        server.overworld().addFreshEntity(pod);
        PodComponents.register(pod, charter.id());
        pod.setHull(pod.maxHull() - DAMAGE);
        require(Charters.deposit(server, charter.id(), START_BALANCE).isEmpty(), "funding should succeed");
        return new Scene(station, pod, charter);
    }

    private static float hull(TestSingleplayerContext singleplayer, Scene scene) {
        return singleplayer.getServer().computeOnServer(server -> scene.pod().hull());
    }

    private static long account(TestSingleplayerContext singleplayer, Scene scene) {
        return singleplayer
                .getServer()
                .computeOnServer(
                        server -> Charters.findOrThrow(server, scene.charter().id())
                                .orElseThrow()
                                .account());
    }

    private static int carried(TestSingleplayerContext singleplayer, Consumable consumable) {
        return singleplayer
                .getServer()
                .computeOnServer(server -> server.getPlayerList()
                        .getPlayers()
                        .getFirst()
                        .getInventory()
                        .countItem(RepairRegistry.item(consumable)));
    }
}
