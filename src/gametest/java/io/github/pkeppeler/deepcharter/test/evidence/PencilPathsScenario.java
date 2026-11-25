package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.handbook.ClientNotes;
import io.github.pkeppeler.deepcharter.client.handbook.ClientPencil;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookPage;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreen;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreenTuning;
import io.github.pkeppeler.deepcharter.handbook.Notes;
import io.github.pkeppeler.deepcharter.handbook.PencilPaths;
import io.github.pkeppeler.deepcharter.handbook.PencilPathsData;
import io.github.pkeppeler.deepcharter.handbook.PencilSyncPayload;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/**
 * Evidence scenario "m2-pencil-paths" for #849: a charter that has found the prayer card (N07) and Ines's log (N10) has two pencil
 * paths on the sheet after the last chapter, in the green of a margin note. Then the charter reaches the Prospector's wreck, and the
 * first path is inked in: a solid line, the x circled, the place named. The detection itself, in the layer, is the GameTest's
 * ({@code PencilPathsTest}); here the sync from the server is real.
 */
public class PencilPathsScenario extends EvidenceScenario {
    private static final int HOLD_FRAMES = 4;

    @Override
    protected String name() {
        return "m2-pencil-paths";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            awaitWorld(context);
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                if (Charters.found(server, player.getUUID(), "Pencil Charter").isPresent()) {
                    throw new AssertionError("founding the charter should succeed");
                }
                Notes.find(player, Notes.id(7));
                Notes.find(player, Notes.id(10));
            });
            ClientWait.until(
                    context,
                    "the client to know both Notes and their paths",
                    client -> ClientNotes.entries().size() == 2
                            && ClientPencil.entries().size() == 2);
            openSheet(context);
            screenshot(context, "pencil-sheet-open");

            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                Charter charter =
                        Charters.readableCharterOf(server, player.getUUID()).orElseThrow();
                PencilPathsData.get(server).addToCharter(charter.id(), List.of(PencilPaths.ONES_BELOW.id()));
                ServerPlayNetworking.send(player, PencilSyncPayload.of(server, player.getUUID()));
            });
            ClientWait.until(
                    context,
                    "the client to know the first path is complete",
                    client -> ClientPencil.entries().stream().anyMatch(HandbookPage.PencilEntry::done));
            openSheet(context);
            screenshot(context, "pencil-sheet-reached");
            context.setScreen(() -> null);
        }
    }

    /** Opens the handbook on its pencil sheet, as the synced Notes and paths make it. */
    private void openSheet(ClientGameTestContext context) {
        context.runOnClient(HandbookScreen::open);
        ClientWait.screen(context, HandbookScreen.class);
        HandbookScreen screen = context.computeOnClient(client -> ClientSession.screen(client, HandbookScreen.class));
        context.runOnClient(client -> {
            List<HandbookPage> pages = HandbookScreen.currentPages(client);
            for (int index = 0; index < pages.size(); index++) {
                if (pages.get(index) instanceof HandbookPage.Pencil) {
                    screen.goTo(index);
                    return;
                }
            }
            throw new AssertionError("the handbook has no pencil sheet: " + pages);
        });
        context.waitTicks(HandbookScreenTuning.current().flipTicks() + 2);
        for (int frame = 0; frame < HOLD_FRAMES; frame++) {
            frame(context);
        }
    }
}
