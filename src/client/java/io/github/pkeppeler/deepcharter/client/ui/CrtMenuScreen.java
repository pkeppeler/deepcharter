package io.github.pkeppeler.deepcharter.client.ui;

import io.github.pkeppeler.deepcharter.client.theme.PanelLook;
import io.github.pkeppeler.deepcharter.client.theme.PanelScreen;
import io.github.pkeppeler.deepcharter.menu.SlotRule;
import io.github.pkeppeler.deepcharter.menu.SlottedMenu;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * The CRT screen of a {@link SlottedMenu}: the terminal's backdrop or machine panel, its title, a typed intro, a frame
 * round every slot, and under the scanlines the items. An empty typed slot shows its rule's ghost, the item that goes
 * there under a veil, with its name beside it; the menu's last refusal shows under the typed slots. The menu's slot
 * positions are measured from the top left of the content area ({@link #contentLeft}, {@link #contentTop}).
 *
 * <p>Colours come from {@link CrtTuning}, so a resource pack restyles the screen as it does every terminal.
 */
public abstract class CrtMenuScreen<M extends SlottedMenu> extends AbstractContainerScreen<M> implements PanelScreen {
    private static final int MARGIN = 24;
    private static final double SECONDS_PER_TICK = 1.0 / 20.0;
    private static final int SLOT = 18;
    /** Pixels from a typed slot's left edge to the name beside it. */
    private static final int LABEL_X = SLOT + 4;
    /** Pixels from the top of the content to the first line of the intro: the title and its rule. */
    private static final int INTRO_Y = 23;

    private final Typewriter intro;

    protected CrtMenuScreen(M menu, Inventory inventory, Component title, Component intro, Typewriter.LetterHook hook) {
        super(menu, inventory, title, SlottedMenu.INVENTORY_WIDTH, SlottedMenu.INVENTORY_HEIGHT);
        this.intro = new Typewriter(intro.getString(), hook);
    }

    /** The intro's typewriter, which types on each tick. */
    // test-seam: a client test waits for the intro to type out before it reads the screen, as with TerminalViewScreen
    public final Typewriter typewriter() {
        return intro;
    }

    @Override
    protected void init() {
        super.init();
        leftPos = contentLeft();
        topPos = contentTop();
    }

    /** The x where content starts: the panel's left inset, or the margin without the panel. */
    protected final int contentLeft() {
        return PanelLook.drawn() ? PanelLook.current().content().left() : MARGIN;
    }

    /** The y where content starts: the panel's top inset, or the margin without the panel. */
    protected final int contentTop() {
        return PanelLook.drawn() ? PanelLook.current().content().top() : MARGIN;
    }

    private int contentRight() {
        return width - (PanelLook.drawn() ? PanelLook.current().content().right() : MARGIN);
    }

    private int contentBottom() {
        return height - (PanelLook.drawn() ? PanelLook.current().content().bottom() : MARGIN);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        intro.advance(SECONDS_PER_TICK);
    }

    /** A click anywhere off the content area, not only off the inventory, drops what the cursor holds. */
    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top) {
        return mouseX < contentLeft() || mouseX >= contentRight() || mouseY < contentTop() || mouseY >= contentBottom();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (PanelLook.drawn()) {
            CrtDraw.panelBackground(graphics, PanelLook.current(), width, height);
        } else {
            CrtDraw.backdrop(graphics, 0, 0, width, height);
        }
        CrtTuning tuning = CrtTuning.current();
        for (Slot slot : menu.slots) {
            int x = leftPos + slot.x;
            int y = topPos + slot.y;
            CrtDraw.border(graphics, x - 1, y - 1, x + SLOT - 1, y + SLOT - 1, tuning.dimColor());
            if (slot instanceof SlottedMenu.RuleSlot typed && !slot.hasItem()) {
                typed.rule().ghost().ifPresent(ghost -> {
                    graphics.fakeItem(ghost.icon(), x, y);
                    graphics.fill(x, y, x + SLOT - 2, y + SLOT - 2, tuning.ghostVeilColor());
                });
            }
        }
        extractText(graphics);
    }

    /** The title, the intro, each typed slot's name and the refusal, measured from the content's top left. */
    private void extractText(GuiGraphicsExtractor graphics) {
        CrtTuning tuning = CrtTuning.current();
        CrtDraw.header(graphics, font, title.getString().toUpperCase(Locale.ROOT), leftPos, topPos, contentRight());
        int lineY = topPos + INTRO_Y;
        for (String line : CrtText.wrap(font, intro.visible(), contentRight() - leftPos)) {
            CrtDraw.glowText(graphics, font, line, leftPos, lineY, tuning.phosphorColor());
            lineY += font.lineHeight + tuning.lineSpacing();
        }
        int below = 0;
        for (SlottedMenu.RuleSlot slot : menu.ruleSlots()) {
            below = Math.max(below, slot.y + SLOT);
            SlotRule rule = slot.rule();
            if (rule.ghost().isPresent()) {
                String name = rule.ghost().get().label().getString().toUpperCase(Locale.ROOT);
                int color = slot.hasItem() ? tuning.phosphorColor() : tuning.dimColor();
                int y = topPos + slot.y + (SLOT - font.lineHeight) / 2;
                CrtDraw.glowText(graphics, font, name, leftPos + slot.x + LABEL_X, y, color);
            }
        }
        int refusalY = topPos + below + 1;
        menu.refusal()
                .ifPresent(reason -> CrtDraw.glowText(
                        graphics,
                        font,
                        reason.getString().toUpperCase(Locale.ROOT),
                        leftPos,
                        refusalY,
                        tuning.refusalColor()));
    }

    /** The panel draws its own title and labels, so nothing is left for the default ones. */
    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {}

    /**
     * The glass and the scanlines go over the slots and their items; the carried item and the tooltip stay clear of
     * them.
     */
    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractContents(graphics, mouseX, mouseY, partialTick);
        if (PanelLook.drawn()) {
            CrtDraw.panelGlass(graphics, PanelLook.current(), width, height);
        } else {
            CrtDraw.scanlines(graphics, width, height);
        }
    }

    /** The narrator reads the intro in full, not the part typed so far. */
    @Override
    public Component getNarrationMessage() {
        return Component.literal(super.getNarrationMessage().getString() + ". " + intro.text());
    }
}
