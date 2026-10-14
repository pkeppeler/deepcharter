package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.charter.CharterTuning;
import io.github.pkeppeler.deepcharter.charter.terminal.ContractState;
import io.github.pkeppeler.deepcharter.charter.terminal.ContractTerminal;
import io.github.pkeppeler.deepcharter.client.charter.terminal.ContractScreen;
import io.github.pkeppeler.deepcharter.client.handbook.ClientHandbook;
import io.github.pkeppeler.deepcharter.client.handbook.ClientNotes;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookNote;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookPages;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreen;
import io.github.pkeppeler.deepcharter.client.terminal.TerminalScreens;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapter;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapters;
import io.github.pkeppeler.deepcharter.handbook.Notes;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.ShippedNotes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

/**
 * Evidence scenario "m2-layout-gate" for #411: the screens the drawn-text layout gate found overflowing, at the default window. A
 * Director of a charter with the longest name (its status line ran off the right edge), and the longest Note of the handbook, Ines's log
 * (its text ran under the back button and off the bottom of the sheet; it now runs on to a second sheet). The ore processor's own stills
 * are in "m2-ore-processor".
 */
public class LayoutGateScenario extends EvidenceScenario {
    private static final int TICKS_PER_FRAME = 3;
    private static final int HOLD_FRAMES = 4;

    @Override
    protected String name() {
        return "m2-layout-gate";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            String name = "W".repeat(CharterTuning.DEFAULT.maxNameLength());
            TerminalView view = new TerminalView(
                    new BlockPos(0, 64, 0), ContractTerminal.TYPE.id(), true, true, List.of(), Optional.empty());
            context.setScreen(() -> TerminalScreens.create(view));
            ClientWait.screen(context, ContractScreen.class);
            context.runOnClient(client -> ((ContractScreen) client.gui.screen())
                    .show(new ContractState(
                            ContractState.Role.DIRECTOR,
                            name,
                            List.of(),
                            0,
                            List.of(),
                            0,
                            List.of(),
                            7,
                            Optional.empty())));
            hold(context);
            screenshot(context, "contract-director-long-name");

            List<HandbookNote> notes =
                    ShippedNotes.ids().stream().map(ClientNotes::entry).toList();
            context.setScreen(() -> {
                Minecraft client = Minecraft.getInstance();
                Map<Identifier, HandbookChapter> chapters = new LinkedHashMap<>();
                HandbookChapters.all(client.getConnection().registryAccess())
                        .forEach(chapter -> chapters.put(chapter.key().identifier(), chapter.value()));
                return new HandbookScreen(
                        HandbookPages.of(chapters, ClientHandbook.completed()), id -> true, id -> {}, notes);
            });
            ClientWait.screen(context, HandbookScreen.class);
            context.runOnClient(client -> ((HandbookScreen) client.gui.screen()).openNote(Notes.id(10)));
            hold(context);
            screenshot(context, "handbook-note-10-first-sheet");
            context.runOnClient(client -> ((HandbookScreen) client.gui.screen()).turnNote(1));
            hold(context);
            screenshot(context, "handbook-note-10-second-sheet");
            context.setScreen(() -> null);
        }
    }

    private void hold(ClientGameTestContext context) {
        for (int i = 0; i < HOLD_FRAMES; i++) {
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
        }
    }
}
