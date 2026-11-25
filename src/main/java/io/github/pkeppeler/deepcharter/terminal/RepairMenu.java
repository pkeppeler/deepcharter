package io.github.pkeppeler.deepcharter.terminal;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.menu.SlotRule;
import io.github.pkeppeler.deepcharter.menu.SlottedMenu;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * An unrepaired terminal's screen: one locked slot per part of its type, over the player's inventory. A part dragged or
 * shift-clicked into its slot leaves the player and goes into the world's {@link RepairState} at once, so closing the
 * screen keeps it; when the last goes in, {@link TerminalEvents#REPAIRED} fires, the menu closes and the terminal opens
 * online.
 *
 * <p>The slots show the repair state and are never a store of their own: the server refreshes them from it on every
 * broadcast, and a part in one cannot be taken out. The server checks every placement with the rules of {@link
 * Terminals#insert} (distance, charter, readable data, the repair order); the client predicts with what it was sent.
 */
public final class RepairMenu extends SlottedMenu {
    /** Where the first part slot sits in the screen's image: below the title and two lines of the terminal's text. */
    public static final int PARTS_Y = 50;
    /** Pixels from one column of part slots to the next, room for the part's name beside its slot. */
    public static final int PART_COLUMN = 140;

    public static final int PART_COLUMNS = 2;
    /** Pixels from one row of part slots to the next. */
    public static final int PART_ROW = 20;
    /** Pixels between the part slots and the inventory, which hold the refusal line. */
    public static final int REFUSAL_GAP = 14;

    public static final ExtendedMenuType<RepairMenu, Opening> TYPE = Registry.register(
            BuiltInRegistries.MENU,
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "terminal_repair"),
            new ExtendedMenuType<>(RepairMenu::new, Opening.STREAM_CODEC));

    /**
     * What the client needs to build the menu.
     *
     * @param unlocked the type's prerequisite, if any, is repaired, so its parts can go in
     */
    public record Opening(BlockPos pos, Identifier type, boolean unlocked) {
        public static final StreamCodec<ByteBuf, Opening> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC,
                Opening::pos,
                Identifier.STREAM_CODEC,
                Opening::type,
                ByteBufCodecs.BOOL,
                Opening::unlocked,
                Opening::new);
    }

    private final Opening opening;
    private final TerminalType type;
    /** The player the server's menu acts for; null on the client, which only predicts. */
    private final @Nullable ServerPlayer player;

    private final SimpleContainer view;
    /** This menu put the last part in: once it closes (it is no longer valid), the terminal opens online. */
    private boolean repairedHere;

    /** The client's menu. */
    public RepairMenu(int containerId, Inventory inventory, Opening opening) {
        this(containerId, inventory, opening, null);
    }

    private RepairMenu(int containerId, Inventory inventory, Opening opening, @Nullable ServerPlayer player) {
        super(TYPE, containerId);
        this.opening = opening;
        this.type = TerminalTypes.get(opening.type())
                .orElseThrow(() -> new IllegalStateException("unknown terminal type " + opening.type()));
        this.player = player;
        List<Item> parts = type.parts();
        this.view = new SimpleContainer(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            Item part = parts.get(i);
            int slot = i;
            SlotRule rule = SlotRule.of(stack -> stack.is(part), this::unfit)
                    .checked(stack -> refusal(slot, part).map(TerminalRefusal::message))
                    .ghost(new ItemStack(part), new ItemStack(part).getHoverName())
                    .locking()
                    .holding(1);
            addRuleSlot(view, i, FRAME + i % PART_COLUMNS * PART_COLUMN, PARTS_Y + i / PART_COLUMNS * PART_ROW, rule);
        }
        addPlayerInventory(inventory, FRAME, inventoryY(parts.size()));
        refresh();
    }

    /** Registers the menu type. */
    public static void register() {}

    /** Server only: opens the repair menu of the terminal of {@code type} at {@code pos} for {@code player}. */
    static void open(ServerPlayer player, BlockPos pos, TerminalType type, boolean unlocked) {
        Opening opening = new Opening(pos.immutable(), type.id(), unlocked);
        player.openMenu(new ExtendedMenuProvider<Opening>() {
            @Override
            public Opening getScreenOpeningData(ServerPlayer opener) {
                return opening;
            }

            @Override
            public Component getDisplayName() {
                return Component.translatable(type.block().getDescriptionId());
            }

            @Override
            public AbstractContainerMenu createMenu(int id, Inventory inventory, Player opener) {
                return new RepairMenu(id, inventory, opening, (ServerPlayer) opener);
            }
        });
    }

    /** The y of the player's inventory below {@code parts} part slots. */
    public static int inventoryY(int parts) {
        int rows = (parts + PART_COLUMNS - 1) / PART_COLUMNS;
        return PARTS_Y + rows * PART_ROW + REFUSAL_GAP;
    }

    public Opening opening() {
        return opening;
    }

    public TerminalType type() {
        return type;
    }

    private Component unfit(ItemStack stack) {
        TerminalRefusal why =
                type.parts().contains(stack.getItem()) ? TerminalRefusal.WRONG_SLOT : TerminalRefusal.NOT_A_PART;
        return why.message();
    }

    /** Why {@code part} cannot go into slot {@code slot} now: the server's rules, or on the client what it was sent. */
    private Optional<TerminalRefusal> refusal(int slot, Item part) {
        if (player != null) {
            return Terminals.insertRefusal(player, opening.pos(), part);
        }
        if (!view.getItem(slot).isEmpty()) {
            return Optional.of(TerminalRefusal.ALREADY_INSERTED);
        }
        return opening.unlocked() ? Optional.empty() : Optional.of(TerminalRefusal.PREREQUISITE_UNREPAIRED);
    }

    @Override
    protected void placed(RuleSlot slot, ItemStack stack) {
        if (player != null) {
            Terminals.commit(player, opening.pos(), stack.getItem());
            repairedHere = RepairState.get(player.level().getServer()).repaired(type);
        }
    }

    @Override
    public void broadcastChanges() {
        refresh();
        super.broadcastChanges();
    }

    /** Fills each slot whose part is in, from the repair state. Server only. */
    private void refresh() {
        if (player == null) {
            return;
        }
        RepairState state = RepairState.get(player.level().getServer());
        if (!state.isReadable()) {
            return;
        }
        List<Item> inserted = state.inserted(type);
        List<Item> parts = type.parts();
        for (int i = 0; i < parts.size(); i++) {
            ItemStack shown = inserted.contains(parts.get(i)) ? new ItemStack(parts.get(i)) : ItemStack.EMPTY;
            if (!ItemStack.matches(view.getItem(i), shown)) {
                view.setItem(i, shown);
            }
        }
    }

    @Override
    public boolean stillValid(Player opener) {
        return player == null || Terminals.stillOpen(player, opening.pos(), type);
    }

    /** A menu closed by the repair it finished opens the terminal again, now online. */
    @Override
    public void removed(Player opener) {
        super.removed(opener);
        if (player != null && repairedHere) {
            repairedHere = false;
            Terminals.open(player, opening.pos());
        }
    }
}
