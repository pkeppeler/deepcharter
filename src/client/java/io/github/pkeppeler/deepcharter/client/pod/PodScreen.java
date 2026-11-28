package io.github.pkeppeler.deepcharter.client.pod;

import io.github.pkeppeler.deepcharter.client.sound.TypewriterSound;
import io.github.pkeppeler.deepcharter.client.ui.CrtButton;
import io.github.pkeppeler.deepcharter.client.ui.CrtDraw;
import io.github.pkeppeler.deepcharter.client.ui.CrtMenuScreen;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.menu.SlottedMenu;
import io.github.pkeppeler.deepcharter.ore.LooseOre;
import io.github.pkeppeler.deepcharter.ore.OreEncumbrance;
import io.github.pkeppeler.deepcharter.pod.LooseOreTuning;
import io.github.pkeppeler.deepcharter.pod.PodLiningTuning;
import io.github.pkeppeler.deepcharter.pod.PodMenu;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The pod screen ({@link PodMenu}): the part slots in two rows with each fitted part's tier on it and its condition in
 * the frame's colour, the fuel slot and its gauge, the hold's mass, the hopper's spoil and the slag rack against their
 * sizes, and a page of the hold beside the inventory and its offhand slot, with buttons to turn the page when the hold
 * takes more than one. An empty part slot names the stock part or the empty track when the cursor is on it, and a hold
 * stack's tooltip names the key that dumps it.
 */
public final class PodScreen extends CrtMenuScreen<PodMenu> {
    private static final int SLOT = PodMenu.SLOT_SIZE;
    /** Where the gauge sits, from the content's top left: right of the fuel slot, as tall as a slot's inside. */
    private static final int GAUGE_X = PodMenu.FUEL_X + SLOT + 2;

    private static final int GAUGE_WIDTH = 72;
    private static final int GAUGE_HEIGHT = 12;
    /** The column of readouts right of the gauge. */
    private static final int READOUT_X = GAUGE_X + GAUGE_WIDTH + 12;

    private static final int LINE = 10;
    /** The refusal line, under the part slots. */
    private static final int REFUSAL_Y = PodMenu.PARTS_Y + 2 * SLOT + 2;
    /** The line over the hold and the inventory that names them. */
    private static final int LABEL_Y = PodMenu.INVENTORY_Y - 12;

    /** The line over the pack's label that says what loose ore costs. */
    private static final int LOOSE_Y = LABEL_Y - 12;

    private static final int PAGE_BUTTON = 14;

    private @Nullable CrtButton previous;
    private @Nullable CrtButton next;

    public PodScreen(PodMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, Component.translatable("screen.deepcharter.pod.intro"), new TypewriterSound());
    }

    @Override
    protected void init() {
        super.init();
        int right = leftPos + PodMenu.HOLD_X + PodMenu.HOLD_COLUMNS * SLOT - 1;
        int y = topPos + LABEL_Y - 2;
        previous = addRenderableWidget(new CrtButton(
                right - 2 * PAGE_BUTTON - 2,
                y,
                PAGE_BUTTON,
                PAGE_BUTTON - 2,
                Component.literal("<"),
                button -> turn(PodMenu.PREVIOUS_PAGE)));
        next = addRenderableWidget(new CrtButton(
                right - PAGE_BUTTON,
                y,
                PAGE_BUTTON,
                PAGE_BUTTON - 2,
                Component.literal(">"),
                button -> turn(PodMenu.NEXT_PAGE)));
        showPageButtons();
    }

    private void turn(int button) {
        if (minecraft.player != null && minecraft.gameMode != null && menu.clickMenuButton(minecraft.player, button)) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, button);
        }
    }

    private void showPageButtons() {
        boolean paged = menu.pages() > 1;
        CrtButton back = Objects.requireNonNull(previous, "<built by init()>");
        CrtButton forward = Objects.requireNonNull(next, "<built by init()>");
        back.visible = paged;
        forward.visible = paged;
        back.active = menu.page() > 0;
        forward.active = menu.page() + 1 < menu.pages();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        showPageButtons();
    }

    @Override
    protected boolean labelsBeside() {
        return false;
    }

    @Override
    protected int refusalTop(int below) {
        return REFUSAL_Y;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        CrtTuning tuning = CrtTuning.current();
        for (ComponentTrack track : ComponentTrack.values()) {
            Optional<PodMenu.Condition> condition = menu.condition(track);
            if (condition.isPresent() && condition.get() != PodMenu.Condition.WORKING) {
                Slot slot = menu.partSlot(track);
                int x = leftPos + slot.x;
                int y = topPos + slot.y;
                CrtDraw.border(graphics, x - 1, y - 1, x + SLOT - 1, y + SLOT - 1, tuning.refusalColor());
            }
        }
        // The offhand's frame is lit, so it does not read as a tenth hotbar slot.
        Slot offhand = menu.offhandSlot();
        int offhandX = leftPos + offhand.x;
        int offhandY = topPos + offhand.y;
        CrtDraw.border(
                graphics, offhandX - 1, offhandY - 1, offhandX + SLOT - 1, offhandY + SLOT - 1, tuning.phosphorColor());
        gauge(graphics, tuning);
        readouts(graphics, tuning);
    }

    /** The tank's gauge: a frame, a bar as full as the tank, and the litres under it. */
    private void gauge(GuiGraphicsExtractor graphics, CrtTuning tuning) {
        int x = leftPos + GAUGE_X;
        int y = topPos + PodMenu.PARTS_Y + 1;
        CrtDraw.border(graphics, x, y, x + GAUGE_WIDTH, y + GAUGE_HEIGHT, tuning.dimColor());
        int tank = Math.max(1, menu.tankTenths());
        int filled = Math.round((GAUGE_WIDTH - 2) * Math.min(1f, (float) menu.fuelTenths() / tank));
        if (filled > 0) {
            graphics.fill(x + 1, y + 1, x + 1 + filled, y + GAUGE_HEIGHT - 1, tuning.phosphorColor());
        }
        String litres = Component.translatable(
                        "screen.deepcharter.pod.gauge", tenths(menu.fuelTenths()), tenths(menu.tankTenths()))
                .getString()
                .toUpperCase(Locale.ROOT);
        CrtDraw.glowText(graphics, font, litres, x, y + GAUGE_HEIGHT + 3, tuning.phosphorColor());
    }

    private static String tenths(int tenths) {
        return String.format(Locale.ROOT, "%d.%d", tenths / 10, tenths % 10);
    }

    /**
     * The readouts right of the gauge: the hold's mass, the hopper's spoil and the rack's slag brick, each store
     * against its size, so a fused brick has a place on screen (#1649).
     */
    public List<String> readouts() {
        PodLiningTuning lining = PodLiningTuning.DEFAULT;
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("screen.deepcharter.pod.mass", OreEncumbrance.shown(menu.massTenths())));
        lines.add(
                menu.spoil() >= 0
                        ? Component.translatable("screen.deepcharter.pod.spoil", menu.spoil(), lining.spoilCapacity())
                        : Component.translatable("screen.deepcharter.pod.no_hopper"));
        lines.add(Component.translatable("screen.deepcharter.pod.rack", menu.bricks(), lining.brickCapacity()));
        return lines.stream()
                .map(line -> line.getString().toUpperCase(Locale.ROOT))
                .toList();
    }

    /** The readouts, and the names over the hold and the pack. */
    private void readouts(GuiGraphicsExtractor graphics, CrtTuning tuning) {
        int y = topPos + PodMenu.PARTS_Y + 1;
        for (String line : readouts()) {
            CrtDraw.glowText(graphics, font, line, leftPos + READOUT_X, y, tuning.phosphorColor());
            y += LINE;
        }
        Component hold = menu.pages() > 1
                ? Component.translatable(
                        "screen.deepcharter.pod.hold_page",
                        menu.occupied(),
                        menu.capacity(),
                        menu.page() + 1,
                        menu.pages())
                : Component.translatable("screen.deepcharter.pod.hold", menu.occupied(), menu.capacity());
        CrtDraw.glowText(
                graphics,
                font,
                hold.getString().toUpperCase(Locale.ROOT),
                leftPos + PodMenu.HOLD_X,
                topPos + LABEL_Y,
                tuning.phosphorColor());
        if (menu.seated() && packHoldsOre()) {
            CrtDraw.glowText(
                    graphics,
                    font,
                    Component.translatable(
                                    "screen.deepcharter.pod.loose", Math.round(LooseOreTuning.DEFAULT.weightFactor()))
                            .getString()
                            .toUpperCase(Locale.ROOT),
                    leftPos + 1,
                    topPos + LOOSE_Y,
                    tuning.refusalColor());
        }
        CrtDraw.glowText(
                graphics,
                font,
                Component.translatable("screen.deepcharter.pod.pack")
                        .getString()
                        .toUpperCase(Locale.ROOT),
                leftPos + 1,
                topPos + LABEL_Y,
                tuning.dimColor());
    }

    /** A fitted part's slot carries its tier, as a stack carries its count. */
    @Override
    protected void extractSlot(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY) {
        super.extractSlot(graphics, slot, mouseX, mouseY);
        if (!isPart(slot) || !slot.hasItem()) {
            return;
        }
        ComponentItems.labelOf(slot.getItem()).ifPresent(label -> {
            String tier = String.valueOf(label.tier());
            int color = menu.condition(ComponentItems.trackOf(slot.getItem()).orElseThrow())
                            .filter(condition -> condition == PodMenu.Condition.WORKING)
                            .isPresent()
                    ? CrtTuning.current().phosphorColor()
                    : CrtTuning.current().refusalColor();
            graphics.text(font, tier, slot.x + SLOT - 1 - font.width(tier), slot.y + SLOT - 1 - font.lineHeight, color);
        });
    }

    /** Whether the player's pack holds ore, loose or in a container: what the loose-ore line is about. */
    private boolean packHoldsOre() {
        return minecraft.player != null && LooseOre.inPack(minecraft.player).any();
    }

    private boolean isPart(Slot slot) {
        return slot instanceof SlottedMenu.RuleSlot typed && typed.rule().locks();
    }

    /** A fitted part's tooltip says how it works on this pod; a hold stack's names the key that dumps it. */
    @Override
    protected List<Component> getTooltipFromContainerItem(ItemStack stack) {
        List<Component> lines = new ArrayList<>(super.getTooltipFromContainerItem(stack));
        Slot hovered = hoveredSlot;
        if (hovered != null && menu.isHold(hovered)) {
            Component drop = minecraft.options.keyDrop.getTranslatedKeyMessage();
            lines.add(Component.translatable("screen.deepcharter.pod.dump_tip", drop, drop));
        }
        if (hovered != null && isPart(hovered)) {
            ComponentItems.trackOf(stack)
                    .flatMap(menu::condition)
                    .ifPresent(condition -> lines.add(Component.translatable("screen.deepcharter.pod.condition."
                            + condition.name().toLowerCase(Locale.ROOT))));
        }
        return lines;
    }

    /**
     * An empty part slot names what is there: the stock part, or nothing yet. The empty offhand names itself and the
     * key that fills it.
     */
    @Override
    protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractTooltip(graphics, mouseX, mouseY);
        Slot hovered = hoveredSlot;
        if (hovered == menu.offhandSlot()
                && !hovered.hasItem()
                && menu.getCarried().isEmpty()) {
            Component swap = minecraft.options.keySwapOffhand.getTranslatedKeyMessage();
            graphics.setTooltipForNextFrame(
                    font, Component.translatable("screen.deepcharter.pod.offhand", swap), mouseX, mouseY);
        }
        if (hovered instanceof SlottedMenu.RuleSlot typed
                && !hovered.hasItem()
                && menu.getCarried().isEmpty()
                && typed.rule().ghost().isPresent()) {
            graphics.setTooltipForNextFrame(font, typed.rule().ghost().get().label(), mouseX, mouseY);
        }
    }
}
