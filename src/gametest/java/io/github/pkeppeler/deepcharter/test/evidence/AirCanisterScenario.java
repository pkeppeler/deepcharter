package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CataloguePanel;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.SpecSheetOpener;
import io.github.pkeppeler.deepcharter.client.suit.SuitGaugeHud;
import io.github.pkeppeler.deepcharter.client.suit.SuitGaugeHud.Tone;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.suit.SuitTracks;
import io.github.pkeppeler.deepcharter.test.RepairStationClientTest;
import io.github.pkeppeler.deepcharter.test.RepairStationClientTest.Scene;
import io.github.pkeppeler.deepcharter.test.support.CatalogueScene;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.SuitScenes;
import io.github.pkeppeler.deepcharter.test.support.SuitScenes.Pocket;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.transmission.TransmissionCatalog;
import io.github.pkeppeler.deepcharter.transmission.TransmissionData;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "m2-air-canister" for #1194: a person on foot in the unbuilt depth, where a stock suit drains 5.5 suit-seconds a
 * second, with the gauge amber (80 s left, 14 s at that rate). They use an air canister from the hotbar: the bar jumps by a minute, and
 * the gauge is still amber, because a minute is all a canister is. The spec sheet shows what a canister adds and costs.
 */
public class AirCanisterScenario extends EvidenceScenario {
    private static final int TICKS_PER_FRAME = 3;
    private static final int HOLD_FRAMES = 8;
    private static final int BEFORE_FRAMES = 5;
    private static final Identifier FIRST_BONUS = Identifier.fromNamespaceAndPath("deepcharter", "t05");
    private static final int OUTSIDE_X = 8000;
    private static final float FACE_NORTH = 180f;
    private static final float LOOK_SLIGHTLY_DOWN = 8f;
    private static final int SETTLE_RADIUS = 16;
    /** Where the suit starts: amber, with a minute of room before a canister could be wasted. */
    private static final float START_RESERVE = 80f;
    /** The bar's share of a stock suit's capacity: under it before the canister, over it after. */
    private static final float BAR_SPLIT = 0.6f;

    @Override
    protected String name() {
        return "m2-air-canister";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            Scene scene = singleplayer.getServer().computeOnServer(RepairStationClientTest::setUp);
            // The layer 2 breach pays the Company's first bonus when the scenario drops into the unbuilt depth. It is
            // marked fired now,
            // so the balance the stills show stays the one the scenario leaves.
            singleplayer
                    .getServer()
                    .runOnServer(server -> TransmissionData.get(server)
                            .fire(scene.charter().id(), TransmissionCatalog.require(FIRST_BONUS)));
            ClientWait.until(
                    context,
                    "the charter's balance",
                    client -> client.player != null && ClientCharter.view().isPresent());
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = first(server);
                player.setPermanentlyInvulnerable(true);
                SuitScenes.setTracks(player, SuitTracks.STOCK);
                player.getInventory()
                        .setItem(
                                player.getInventory().getSelectedSlot(),
                                new ItemStack(RepairRegistry.item(Consumable.AIR_CANISTER), 3));
                stand(server);
            });
            stillAmber(context, singleplayer, "air-canister-1-before-amber");

            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = first(server);
                player.gameMode.useItem(
                        player,
                        player.level(),
                        player.getItemInHand(InteractionHand.MAIN_HAND),
                        InteractionHand.MAIN_HAND);
            });
            ClientWait.until(
                    context,
                    "the bar over " + BAR_SPLIT,
                    client -> SuitGaugeHud.gauge(client)
                            .filter(gauge -> gauge.fraction() > BAR_SPLIT)
                            .isPresent());
            // The still comes straight after the jump, before the suit drains it away; the frames that follow show it
            // drain.
            screenshot(context, "air-canister-2-after-the-bar-jumps");
            hold(context);

            Item canister = RepairRegistry.item(Consumable.AIR_CANISTER);
            CatalogueScene.openInventory(context);
            CataloguePanel panel = CatalogueScene.panel(context);
            context.runOnClient(client -> SpecSheetOpener.show(canister));
            ClientWait.until(context, "the canister's sheet", client -> panel.sheetItem() == canister);
            hold(context);
            screenshot(context, "air-canister-3-spec-sheet");
            context.setScreen(() -> null);
        }
    }

    private static ServerPlayer first(MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }

    private static void stand(MinecraftServer server) {
        ServerLevel level = TestLayers.level(server, 1);
        Pocket pocket = SuitScenes.unbuilt(level, OUTSIDE_X);
        Vec3 stand = pocket.stand();
        first(server).teleportTo(level, stand.x, stand.y, stand.z, Set.of(), FACE_NORTH, LOOK_SLIGHTLY_DOWN, true);
    }

    /**
     * Takes {@code still} with the gauge amber and the bar under {@value #BAR_SPLIT}. The reserve is set twice: the world settles
     * between, and the suit drains while it does, so the second setting is what the frames and the still show.
     */
    private void stillAmber(ClientGameTestContext context, TestSingleplayerContext singleplayer, String still) {
        for (int pass = 0; pass < 2; pass++) {
            singleplayer.getServer().runOnServer(server -> SuitScenes.setReserve(first(server), START_RESERVE, true));
            ClientWait.until(
                    context,
                    "an amber gauge under " + BAR_SPLIT,
                    client -> SuitGaugeHud.gauge(client)
                            .filter(gauge -> gauge.tone() == Tone.AMBER && gauge.fraction() < BAR_SPLIT)
                            .isPresent());
            if (pass == 0) {
                settle(context);
            }
        }
        for (int i = 0; i < BEFORE_FRAMES; i++) {
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
        }
        screenshot(context, still);
    }

    private static void settle(ClientGameTestContext context) {
        context.runOnClient(client -> {
            TransmissionOverlay.reset();
            client.gui.toastManager().clear();
        });
        var at = context.computeOnClient(client -> ClientSession.player(client).blockPosition());
        ClientWait.chunksAround(context, at.getX(), at.getZ(), SETTLE_RADIUS);
        ClientWait.until(
                context, "every section in view rendered", client -> client.levelRenderer.hasRenderedAllSections());
    }

    private void hold(ClientGameTestContext context) {
        for (int i = 0; i < HOLD_FRAMES; i++) {
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
        }
    }
}
