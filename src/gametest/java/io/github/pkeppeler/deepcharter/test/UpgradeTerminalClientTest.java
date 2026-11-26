package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.RecipeConsoleScreen;
import io.github.pkeppeler.deepcharter.client.ui.CrtButton;
import io.github.pkeppeler.deepcharter.client.upgrade.UpgradeScreen;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.scanner.ScannerRegistry;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalBlock;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.DrawnText;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.UpgradeShown;
import io.github.pkeppeler.deepcharter.test.support.UseEntity;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.TrackOffer;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * Client GameTest for #73 and #1440: the upgrade screen says what a track does, what is fitted and what the next tier
 * gains, lists the tiers for sale with their prices (never the crafted tier 1), marks the tiers above the Mole's cap
 * and shows the cap, buys through the server, shows the fitted part (and the cap on it), leaves a part the charter
 * cannot pay for dead, and locks a track that waits for its crafted unit, with a line that opens the unit's recipe.
 */
public class UpgradeTerminalClientTest implements FabricClientGameTest {
    private static final long ACCOUNT = 30_000;

    /** The terminal, and the id of the pod parked beside it. */
    public record Scene(BlockPos terminal, UUID pod) {}

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            Scene scene = singleplayer.getServer().computeOnServer(UpgradeTerminalClientTest::setUp);
            ClientWait.until(
                    context,
                    "the account at its starting balance",
                    client -> ClientCharter.view()
                            .map(charter -> charter.balance() == ACCOUNT)
                            .orElse(false),
                    client -> "charter " + ClientCharter.view());

            context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(scene.terminal())));
            ClientWait.screen(context, UpgradeScreen.class);
            UpgradeScreen screen = context.computeOnClient(client -> ClientSession.screen(client, UpgradeScreen.class));
            require(
                    screen.upgrade().orElseThrow().pod().orElseThrow().cap() == 2,
                    "the screen shows the Mole's cap of 2");
            ClientWait.until(
                    context,
                    "the upgrade screen finished typing",
                    client -> screen.typewriter().done(),
                    client -> "typewriter text '" + screen.typewriter().text() + "'");
            require(
                    labels(context, screen).contains("DRILL  T0")
                            && labels(context, screen).contains("FUEL TANK  T0"),
                    "every track is listed with its installed tier, got " + labels(context, screen));

            context.clickScreenButton("HULL  T0");
            ClientWait.until(
                    context, "the hull track lit", client -> UpgradeShown.isChosen(screen, ComponentTrack.HULL));
            List<String> hull = labels(context, screen);
            require(
                    hull.contains("BUY TIER 2  $500")
                            && hull.stream()
                                    .noneMatch(label -> label.startsWith("TIER 1") || label.contains("CRAFTED")),
                    "the hull offers tier 2 at its price, and its crafted tier 1 is no row, got " + hull);
            require(
                    hull.contains("BUY TIER 3  $1250  (WORKS AS TIER 2)")
                            && hull.contains("BUY TIER 4  $5000  (WORKS AS TIER 2)"),
                    "tiers above the cap say so, got " + hull);
            // #1440: what the hull does, what is fitted, and the next tier's gain from the pod's stats.
            requireDrawn(
                    context,
                    screen,
                    "SURVIVES HARDER HITS AND LANDINGS",
                    "FITTED  STOCK PART",
                    nextLine(context, screen, ComponentTrack.HULL));

            // A Mole has a stock hull, so tier 2 is bought over it with no tier 1 fitted (ADR 0053).
            context.clickScreenButton("BUY TIER 2  $500");
            ClientWait.until(
                    context,
                    "the fitted tier 2 hull",
                    client -> drawn(screen).contains("FITTED  TIER 2"),
                    client -> "drawn " + drawn(screen));
            require(
                    singleplayer
                            .getServer()
                            .computeOnServer(server2 -> PodComponents.partOf(pod(server2, scene), ComponentTrack.HULL)
                                    .map(label -> label.tier() == 2)
                                    .orElse(false)),
                    "the server installed the tier 2 hull");
            require(
                    labels(context, screen).stream().noneMatch(label -> label.startsWith("BUY TIER 2")),
                    "the fitted tier is no longer for sale, got " + labels(context, screen));

            context.clickScreenButton("BUY TIER 4  $5000  (WORKS AS TIER 2)");
            ClientWait.until(
                    context,
                    "the fitted tier 4 hull, capped",
                    client -> drawn(screen).contains("FITTED  TIER 4  (WORKS AS TIER 2)"),
                    client -> "drawn " + drawn(screen));
            boolean cappedAndDropped = singleplayer.getServer().computeOnServer(server2 -> {
                PodEntity pod = pod(server2, scene);
                boolean capped = PodComponents.effectiveTier(pod, ComponentTrack.HULL) == 2;
                boolean dropped =
                        pod
                                .level()
                                .getEntitiesOfClass(ItemEntity.class, new AABB(scene.terminal()).inflate(20))
                                .stream()
                                .anyMatch(item -> ComponentItems.labelOf(item.getItem())
                                        .map(label -> label.tier() == 2)
                                        .orElse(false));
                long account = Charters.charterOfOrThrow(
                                server2,
                                server2.getPlayerList().getPlayers().getFirst().getUUID())
                        .orElseThrow()
                        .account();
                return capped && dropped && account == ACCOUNT - 500 - 5_000;
            });
            require(
                    cappedAndDropped,
                    "the tier 4 hull works as tier 2, the tier 2 hull dropped, and the account paid 5500 in all");
            requireDrawn(context, screen, "TIER 5 WORKS AS TIER 2 IN THIS POD");

            CrtButton unaffordable = context.computeOnClient(client -> screen.children().stream()
                    .filter(CrtButton.class::isInstance)
                    .map(CrtButton.class::cast)
                    .filter(button -> button.getMessage().getString().startsWith("BUY TIER 6"))
                    .findFirst()
                    .orElseThrow());
            require(!unaffordable.active, "a part the account cannot pay for is dead");

            // #483, #1440: the scanner's tier 1 is crafted, so its next purchase is locked until the unit is fitted,
            // and the line
            // naming the unit opens its recipe.
            context.clickScreenButton("SCANNER  T0");
            ClientWait.until(
                    context,
                    "the scanner track selected",
                    client -> UpgradeShown.isChosen(screen, ComponentTrack.SCANNER));
            List<String> bare = labels(context, screen);
            require(
                    bare.contains("BUY TIER 2  $500  LOCKED")
                            && bare.contains("FIT A CRAFTED SCANNER UNIT FIRST")
                            && bare.stream().noneMatch(label -> label.startsWith("BUY TIER 3")),
                    "the scanner shows tier 2 locked and what unlocks it, and nothing else for sale, got " + bare);
            require(!button(context, screen, "BUY TIER 2  $500  LOCKED").active, "the locked tier cannot be bought");
            requireDrawn(context, screen, "FITTED  NOTHING");
            context.clickScreenButton("FIT A CRAFTED SCANNER UNIT FIRST");
            ClientWait.screen(context, RecipeConsoleScreen.class);
            context.runOnClient(client ->
                    ClientSession.screen(client, RecipeConsoleScreen.class).onClose());
            ClientWait.until(
                    context,
                    "the upgrade screen back from the recipe",
                    client -> client.gui.screen() == screen,
                    client -> "screen " + client.gui.screen());

            // The spoil hopper's one tier is crafted: nothing is for sale, and the line says how to get it.
            context.runOnClient(client -> UpgradeShown.reveal(screen, ComponentTrack.SPOIL_HOPPER));
            context.clickScreenButton("SPOIL HOPPER  T0");
            ClientWait.until(
                    context,
                    "the spoil hopper track selected",
                    client -> UpgradeShown.isChosen(screen, ComponentTrack.SPOIL_HOPPER));
            List<String> hopper = labels(context, screen);
            require(
                    hopper.contains("CRAFT A SPOIL HOPPER UNIT AND FIT IT")
                            && hopper.stream().noneMatch(label -> label.startsWith("BUY")),
                    "the spoil hopper sells nothing and says how to get one, got " + hopper);

            singleplayer.getServer().runOnServer(server2 -> {
                ServerPlayer player = server2.getPlayerList().getPlayers().getFirst();
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ScannerRegistry.SCANNER_UNIT));
                require(
                        UseEntity.on(player, player.level(), InteractionHand.MAIN_HAND, pod(server2, scene))
                                .consumesAction(),
                        "using the crafted unit on the pod should fit it");
            });
            context.setScreen(() -> null);
            context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(scene.terminal())));
            ClientWait.screen(context, UpgradeScreen.class);
            UpgradeScreen fitted = context.computeOnClient(client -> ClientSession.screen(client, UpgradeScreen.class));
            ClientWait.until(
                    context,
                    "the upgrade screen finished typing",
                    client -> fitted.typewriter().done(),
                    client -> "typewriter text '" + fitted.typewriter().text() + "'");
            context.clickScreenButton("SCANNER  T1");
            ClientWait.until(
                    context,
                    "the scanner track selected again",
                    client -> UpgradeShown.isChosen(fitted, ComponentTrack.SCANNER));
            List<String> after = labels(context, fitted);
            require(
                    after.contains("BUY TIER 2  $500") && after.stream().noneMatch(label -> label.contains("LOCKED")),
                    "a fitted scanner unlocks tier 2, got " + after);
            requireDrawn(context, fitted, "FITTED  TIER 1", nextLine(context, fitted, ComponentTrack.SCANNER));
            context.clickScreenButton("BUY TIER 2  $500");
            ClientWait.until(
                    context,
                    "the fitted tier 2 scanner",
                    client -> drawn(fitted).contains("FITTED  TIER 2"),
                    client -> "drawn " + drawn(fitted));
            require(
                    singleplayer
                            .getServer()
                            .computeOnServer(server2 ->
                                    PodComponents.effectiveTier(pod(server2, scene), ComponentTrack.SCANNER) == 2),
                    "the server installed the tier 2 scanner over the crafted one");

            // #712: the heat shield sells tiers 2 and 3; a Mole works a tier 3 as tier 2.
            context.runOnClient(client -> UpgradeShown.reveal(fitted, ComponentTrack.HEAT_SHIELD));
            context.clickScreenButton("HEAT SHIELD  T0");
            ClientWait.until(
                    context,
                    "the heat shield track selected",
                    client -> UpgradeShown.isChosen(fitted, ComponentTrack.HEAT_SHIELD));
            List<String> shield = labels(context, fitted);
            require(
                    shield.contains("BUY TIER 2  $1250")
                            && shield.contains("BUY TIER 3  $2500  (WORKS AS TIER 2)")
                            && shield.stream().noneMatch(label -> label.startsWith("TIER 1")),
                    "the heat shield offers tier 2 at $1250 and tier 3 at $2500, and no crafted row, got " + shield);
            require(
                    context.computeOnClient(client -> DrawnText.of(fitted).stream()
                            .anyMatch(drawn -> drawn.text().contains("BURNS THE PACK'S BRICKS FIRST"))),
                    "the heat shield's tier 3 note says it burns the pack first");
            context.clickScreenButton("BUY TIER 2  $1250");
            ClientWait.until(
                    context,
                    "the fitted tier 2 heat shield",
                    client -> drawn(fitted).contains("FITTED  TIER 2"),
                    client -> "drawn " + drawn(fitted));
            require(
                    singleplayer
                            .getServer()
                            .computeOnServer(server2 ->
                                    PodComponents.effectiveTier(pod(server2, scene), ComponentTrack.HEAT_SHIELD) == 2),
                    "the server installed the tier 2 heat shield");
            context.setScreen(() -> null);
        }
    }

    /** The player founds a charter with money, and has a repaired upgrade terminal and a pod of the charter beside them. */
    public static Scene setUp(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        require(Charters.found(server, player.getUUID(), "Upgrade Test Charter").isEmpty(), "founding should succeed");
        CharterId charter = Charters.charterOfOrThrow(server, player.getUUID())
                .orElseThrow()
                .id();
        require(Charters.deposit(server, charter, ACCOUNT).isEmpty(), "funding should succeed");
        RepairState state = RepairState.get(server);
        for (TerminalType type :
                List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR, TerminalTypes.UPGRADE_TERMINAL)) {
            type.parts()
                    .forEach(part -> state.insert(type, part).ifPresent(refusal -> {
                        throw new AssertionError("repairing " + type.id() + ": " + refusal);
                    }));
        }
        BlockPos here = player.blockPosition();
        BlockPos terminal = here.relative(Direction.EAST, 2);
        TerminalBlock.place(
                server.overworld(),
                terminal,
                TerminalTypes.UPGRADE_TERMINAL.block().defaultBlockState(),
                3);
        PodEntity pod = TestEntities.create(PodRegistry.POD, server.overworld());
        BlockPos parked = here.relative(Direction.WEST, 3);
        pod.setPos(parked.getX() + 0.5, parked.getY(), parked.getZ() + 0.5);
        server.overworld().addFreshEntity(pod);
        PodComponents.register(pod, charter);
        return new Scene(terminal, pod.getUUID());
    }

    private static PodEntity pod(MinecraftServer server, Scene scene) {
        return TestEntities.require(server.overworld(), scene.pod(), PodEntity.class);
    }

    private static CrtButton button(ClientGameTestContext context, UpgradeScreen screen, String label) {
        return context.computeOnClient(client -> screen.children().stream()
                .filter(CrtButton.class::isInstance)
                .map(CrtButton.class::cast)
                .filter(button ->
                        button.getMessage().getString().replaceFirst("^> ", "").equals(label))
                .findFirst()
                .orElseThrow());
    }

    /**
     * The lines the screen draws itself: the chosen track's description, fitted part, next gain and note. Client
     * thread.
     */
    private static List<String> drawn(UpgradeScreen screen) {
        return DrawnText.of(screen).stream().map(DrawnText.Drawn::text).toList();
    }

    private static void requireDrawn(ClientGameTestContext context, UpgradeScreen screen, String... lines) {
        List<String> drawn = context.computeOnClient(client -> drawn(screen));
        for (String line : lines) {
            require(drawn.contains(line), "the screen should draw '" + line + "', drew " + drawn);
        }
    }

    /** The next tier's gain for the pod the screen shows, as {@link TrackOffer} words it from the pod's stats. */
    private static String nextLine(ClientGameTestContext context, UpgradeScreen screen, ComponentTrack track) {
        return context.computeOnClient(
                client -> TrackOffer.of(screen.upgrade().orElseThrow().pod().orElseThrow(), track)
                        .nextLine()
                        .orElseThrow()
                        .getString());
    }

    private static List<String> labels(ClientGameTestContext context, UpgradeScreen screen) {
        return context.computeOnClient(client -> labels(screen));
    }

    private static List<String> labels(UpgradeScreen screen) {
        return screen.children().stream()
                .filter(CrtButton.class::isInstance)
                .map(button -> ((CrtButton) button).getMessage().getString())
                .map(label -> label.startsWith("> ") ? label.substring(2) : label)
                .toList();
    }
}
