package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.handbook.ClientHandbook;
import io.github.pkeppeler.deepcharter.client.handbook.ClientReadMarks;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookMemory;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookPage;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreen;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreenTuning;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapter;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapters;
import io.github.pkeppeler.deepcharter.handbook.HandbookProgressData;
import io.github.pkeppeler.deepcharter.handbook.HandbookSyncPayload;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.ShippedHandbook;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Evidence scenario "m2-quest-map" for #847: the handbook's quest map, in the real game with the shipped chapters. A player on a
 * charter has done five of chapter 1's six steps, the last of them just now (the step popup), and holds some of what the sixth needs
 * (the index card). Then the first two chapters are done, and chapter 3's map, with chapter 4 ahead and the rest redacted, is shown
 * whole and zoomed out.
 */
public class QuestMapScenario extends EvidenceScenario {
    private static final int HOLD_FRAMES = 4;
    private static final int TOAST_SETTLE_TICKS = 12;
    private static final int SCANNER_IRON = 2;
    private static final int SCANNER_COPPER = 2;
    private static final int ZOOM_OUT_NOTCHES = 3;

    @Override
    protected String name() {
        return "m2-quest-map";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            awaitWorld(context);
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                if (Charters.found(server, player.getUUID(), "Quest Map Charter")
                        .isPresent()) {
                    throw new AssertionError("founding the charter should succeed");
                }
                player.getInventory().add(new ItemStack(Items.IRON_INGOT, SCANNER_IRON));
                player.getInventory().add(new ItemStack(Items.COPPER_INGOT, SCANNER_COPPER));
            });

            // four steps at once are a handover, not news; the fifth alone is the popup
            complete(context, singleplayer, 1, 4);
            complete(context, singleplayer, 1, 5);
            context.waitTicks(TOAST_SETTLE_TICKS);
            frame(context);
            screenshot(context, "step-popup");
            context.runOnClient(client -> client.gui.toastManager().clear());

            open(context, 1);
            hold(context);
            screenshot(context, "quest-map-chapter-1");

            complete(context, singleplayer, 1, Integer.MAX_VALUE);
            complete(context, singleplayer, 2, Integer.MAX_VALUE);
            open(context, 3);
            // the two steps finished just above popped up; the stills are of the map
            context.runOnClient(client -> client.gui.toastManager().clear());
            hold(context);
            screenshot(context, "quest-map-chapter-3");
            HandbookScreen screen =
                    context.computeOnClient(client -> ClientSession.screen(client, HandbookScreen.class));
            context.runOnClient(client -> {
                for (int notch = 0; notch < ZOOM_OUT_NOTCHES; notch++) {
                    screen.mouseScrolled(
                            client.getWindow().getGuiScaledWidth() / 2.0,
                            client.getWindow().getGuiScaledHeight() / 2.0,
                            0,
                            -1);
                }
            });
            hold(context);
            screenshot(context, "quest-map-zoomed-out");
            context.setScreen(() -> null);
        }
    }

    /** Completes the first {@code count} directives of chapter {@code chapter} for the player's charter, and waits for the client to know. */
    private void complete(ClientGameTestContext context, TestSingleplayerContext singleplayer, int chapter, int count) {
        List<Identifier> done = singleplayer.getServer().computeOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            Charter charter =
                    Charters.readableCharterOf(server, player.getUUID()).orElseThrow();
            Holder.Reference<HandbookChapter> holder = HandbookChapters.all(server).stream()
                    .filter(entry -> entry.value().order() == chapter)
                    .findFirst()
                    .orElseThrow();
            List<Identifier> ids = new ArrayList<>();
            for (HandbookChapter.Entry entry : holder.value()
                    .directives()
                    .subList(0, Math.min(count, holder.value().directives().size()))) {
                HandbookProgressData.get(server).complete(charter.id(), entry.id());
                ids.add(entry.id());
            }
            HandbookSyncPayload.send(server, player);
            return ids;
        });
        ClientWait.until(
                context,
                "the client to know directives " + done,
                client -> ClientHandbook.completed().containsAll(done));
    }

    /** Opens the handbook, as the shipped chapters and the synced progress make it, on the map of {@code chapter}. */
    private void open(ClientGameTestContext context, int chapter) {
        context.setScreen(() -> {
            var client = net.minecraft.client.Minecraft.getInstance();
            return new HandbookScreen(
                    ShippedHandbook.pages(client), ClientReadMarks::isRead, id -> {}, List.of(), new HandbookMemory());
        });
        ClientWait.screen(context, HandbookScreen.class);
        HandbookScreen screen = context.computeOnClient(client -> ClientSession.screen(client, HandbookScreen.class));
        context.runOnClient(client -> {
            List<HandbookPage> pages = ShippedHandbook.pages(client);
            for (int index = 0; index < pages.size(); index++) {
                if (pages.get(index) instanceof HandbookPage.Chapter map && map.number() == chapter) {
                    screen.goTo(index);
                }
            }
        });
        context.waitTicks(HandbookScreenTuning.current().flipTicks() + 2);
    }

    private void hold(ClientGameTestContext context) {
        for (int frame = 0; frame < HOLD_FRAMES; frame++) {
            frame(context);
        }
    }
}
