package io.github.pkeppeler.deepcharter.test.evidence;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.handbook.ClientHandbook;
import io.github.pkeppeler.deepcharter.client.handbook.ClientReadMarks;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookControls;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookKeys;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookMemory;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookPage;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreen;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreenTuning;
import io.github.pkeppeler.deepcharter.client.handbook.StepToast;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapter;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapters;
import io.github.pkeppeler.deepcharter.handbook.HandbookProgressData;
import io.github.pkeppeler.deepcharter.handbook.HandbookSyncPayload;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.ShippedHandbook;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Evidence scenario "m2-quest-map" for #847: the handbook's quest map, in the real game with the shipped chapters. A
 * player on a charter has done five of chapter 1's steps, the last of them, the charcoal (#1423), just now (the step
 * popup); the card of the sixth shows the iron it asks for. Then the first two chapters are done, and chapter 3's map, with the
 * chapters after it redacted, is shown whole. Every page carries the tabs: the stills show them on the Founder's letter
 * (the intro), on a chapter text page and on the maps, and a step with a very long line shows its popup grown to fit it
 * (#962). Last, the controls pages, and the first of them again with the handbook key rebound (#1260).
 */
public class QuestMapScenario extends EvidenceScenario {
    private static final int HOLD_FRAMES = 4;
    private static final int TOAST_SETTLE_TICKS = 40;
    private static final String LONG_STEP =
            "Carry the whole of a very long instruction through the hatch, past the second sump pump and "
                    + "the old tally board, down three ladders to the Company's first fuel depot, and sign for every barrel.";

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
            });

            // two steps done, the current step is the stone tools: one any-of slot, its icon cycling through the tools
            complete(context, singleplayer, 1, 2);
            open(context, 1);
            context.runOnClient(client -> client.gui.toastManager().clear());
            hold(context);
            screenshot(context, "quest-card-stone-tools");

            // four steps at once are a handover, not news; the fifth alone is the popup
            complete(context, singleplayer, 1, 4);
            complete(context, singleplayer, 1, 5);
            context.waitTicks(TOAST_SETTLE_TICKS);
            frame(context);
            screenshot(context, "step-popup");
            context.runOnClient(client -> client.gui.toastManager().clear());
            context.runOnClient(
                    client -> client.gui.toastManager().addToast(StepToast.of(Component.literal(LONG_STEP))));
            context.waitTicks(TOAST_SETTLE_TICKS);
            frame(context);
            screenshot(context, "step-popup-long");
            context.runOnClient(client -> client.gui.toastManager().clear());

            openAt(context, page -> page instanceof HandbookPage.Letter);
            hold(context);
            screenshot(context, "tabs-intro-page");
            openAt(context, page -> page instanceof HandbookPage.ChapterText text && text.number() == 1);
            hold(context);
            screenshot(context, "tabs-chapter-text-page");

            open(context, 1);
            hold(context);
            screenshot(context, "quest-map-chapter-1");

            // the scanner step done, the current step is the light: a torch or a lantern, two items to click
            complete(context, singleplayer, 1, 7);
            open(context, 1);
            context.runOnClient(client -> client.gui.toastManager().clear());
            hold(context);
            screenshot(context, "quest-card-light");

            complete(context, singleplayer, 1, Integer.MAX_VALUE);
            complete(context, singleplayer, 2, Integer.MAX_VALUE);
            open(context, 3);
            // the two steps finished just above popped up; the stills are of the map
            context.runOnClient(client -> client.gui.toastManager().clear());
            hold(context);
            screenshot(context, "quest-map-chapter-3");
            // chapter 4 has the most steps ahead of it of any current chapter: the tightest fit
            complete(context, singleplayer, 3, Integer.MAX_VALUE);
            open(context, 4);
            context.runOnClient(client -> client.gui.toastManager().clear());
            hold(context);
            screenshot(context, "quest-map-chapter-4");

            // the controls pages name each key as it is bound now: the handbook key is H, then J once rebound (#1260)
            for (int part = 1; part <= HandbookControls.sheets(); part++) {
                int sheet = part;
                openAt(context, page -> page instanceof HandbookPage.Controls controls && controls.part() == sheet);
                hold(context);
                screenshot(context, "controls-" + part);
            }
            context.runOnClient(client -> rebind(HandbookKeys.OPEN, InputConstants.KEY_J));
            try {
                openAt(context, page -> page instanceof HandbookPage.Controls controls && controls.part() == 1);
                hold(context);
                screenshot(context, "controls-1-rebound");
            } finally {
                context.runOnClient(client -> rebind(
                        HandbookKeys.OPEN, HandbookKeys.OPEN.getDefaultKey().getValue()));
            }
            context.setScreen(() -> null);
        }
    }

    private static void rebind(KeyMapping mapping, int key) {
        mapping.setKey(InputConstants.Type.KEYBOARD.getOrCreate(key));
        KeyMapping.resetMapping();
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
        openAt(context, page -> page instanceof HandbookPage.Chapter map && map.number() == chapter);
    }

    /** Opens the handbook, as the shipped chapters and the synced progress make it, on the first page that {@code target} accepts. */
    private void openAt(ClientGameTestContext context, Predicate<HandbookPage> target) {
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
                if (target.test(pages.get(index))) {
                    screen.goTo(index);
                    break;
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
