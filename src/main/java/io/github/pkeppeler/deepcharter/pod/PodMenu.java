package io.github.pkeppeler.deepcharter.pod;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.menu.MirrorContainer;
import io.github.pkeppeler.deepcharter.menu.SlotRule;
import io.github.pkeppeler.deepcharter.menu.SlottedMenu;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.PartFitting;
import io.github.pkeppeler.deepcharter.upgrade.PartLabel;
import io.github.pkeppeler.deepcharter.upgrade.PartUnits;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTuning;
import io.netty.buffer.ByteBuf;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * The pod screen (ADR 0102): one slot for each part track, the fuel slot, and a page of the hold, over the player's
 * inventory. It opens for the pod's own charter only: by sneak and use from outside, or by the pod screen key from a
 * seat ({@link PodScreenPayload}).
 *
 * <ul>
 *   <li><b>Parts.</b> Each track's slot shows the part fitted on it, bought or crafted, or a faded stock or empty ghost.
 *       A crafted unit dragged or shift-clicked into its track's slot is fitted by {@link PartFitting}'s rules, and a
 *       fitted part stays: the slots mirror the pod's parts and lock.</li>
 *   <li><b>Fuel.</b> The fuel slot holds one stack of pod fuel ({@link PodFuelSlot}), which feeds the tank as it has room.
 *       The gauge reads the tank.</li>
 *   <li><b>Hold.</b> {@link PodCargo}'s slots, a page at a time; anything moves in and out as with a chest, as far as
 *       the cargo bay's size. The slots are the hold itself, not a copy, so two open screens share them.</li>
 * </ul>
 *
 * <p>The server's menu decides every click; the client's predicts from the data slots the server keeps in step. Walking
 * away, leaving the seat the screen was opened from, losing the pod or losing the right to it closes the screen.
 */
public final class PodMenu extends SlottedMenu {
    /** How many hold slots one page shows: four rows of nine, beside the inventory. */
    public static final int HOLD_WINDOW = 36;

    public static final int HOLD_COLUMNS = 9;
    /** Part slots in each row. */
    public static final int PART_COLUMNS = 7;
    /** Where the first part slot sits in the screen's image: under the title and two lines of intro. */
    public static final int PARTS_Y = 46;

    public static final int FUEL_X = 145;
    /** Where the inventory and the hold start, at the bottom of the screen. */
    public static final int INVENTORY_Y = 116;

    public static final int HOLD_X = 172;
    /** Blocks from the pod within which a player outside can keep the screen open. */
    private static final double REACH = 8;

    /** The menu button ids: the hold's previous and next page. */
    public static final int PREVIOUS_PAGE = 0;

    public static final int NEXT_PAGE = 1;

    private static final int CAPACITY = 0;
    private static final int REACH_SLOTS = 1;
    private static final int OCCUPIED = 2;
    private static final int FUEL_TENTHS = 3;
    private static final int TANK_TENTHS = 4;
    private static final int MASS = 5;
    private static final int SPOIL = 6;
    private static final int BRICKS = 7;
    private static final int HOPPER = 8;
    private static final int DATA = 9;

    public static final ExtendedMenuType<PodMenu, Opening> TYPE = Registry.register(
            BuiltInRegistries.MENU,
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pod"),
            new ExtendedMenuType<>(PodMenu::new, Opening.STREAM_CODEC));

    /**
     * What the client needs to build the menu.
     *
     * @param pod    the pod's entity id, so the client can read the parts' condition from the pod it tracks
     * @param seated the screen was opened from a seat of the pod, so leaving the seat closes it
     */
    public record Opening(int pod, boolean seated) {
        public static final StreamCodec<ByteBuf, Opening> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Opening::pod, ByteBufCodecs.BOOL, Opening::seated, Opening::new);
    }

    private final Opening opening;
    /** The pod: the server's always, the client's when it tracks the entity. */
    private final @Nullable PodEntity pod;
    /** The player the server's menu acts for; null on the client, which only predicts. */
    private final @Nullable ServerPlayer player;

    private final MirrorContainer parts;
    private final Container fuel;
    private final Container hold;
    private final ContainerData data;
    private final DataSlot page = DataSlot.standalone();
    private final Map<ComponentTrack, RuleSlot> partSlots = new EnumMap<>(ComponentTrack.class);
    private final RuleSlot fuelSlot;
    private final RuleSlot[] holdSlots = new RuleSlot[HOLD_WINDOW];

    /** The client's menu. */
    public PodMenu(int containerId, Inventory inventory, Opening opening) {
        this(
                containerId,
                inventory,
                opening,
                // entity-query: the server just opened this pod for this player, so the client tracks it; a miss only
                // leaves the parts' condition unshown, and the server decides every click.
                inventory.player.level().getEntity(opening.pod()) instanceof PodEntity seen ? seen : null,
                null);
    }

    private PodMenu(
            int containerId,
            Inventory inventory,
            Opening opening,
            @Nullable PodEntity pod,
            @Nullable ServerPlayer player) {
        super(TYPE, containerId);
        this.opening = opening;
        this.pod = pod;
        this.player = player;
        ComponentTrack[] tracks = ComponentTrack.values();
        this.parts = new MirrorContainer(tracks.length);
        if (player != null && pod != null) {
            this.fuel = PodFuelSlot.container(pod);
            this.hold = new HoldView(pod);
            this.data = new ServerData(pod);
        } else {
            this.fuel = new SimpleContainer(1);
            this.hold = new SimpleContainer(HOLD_WINDOW);
            this.data = new SimpleContainerData(DATA);
        }
        addDataSlots(data);
        addDataSlot(page);
        for (int i = 0; i < tracks.length; i++) {
            ComponentTrack track = tracks[i];
            int x = FRAME + i % PART_COLUMNS * SLOT_SIZE;
            int y = PARTS_Y + i / PART_COLUMNS * SLOT_SIZE;
            partSlots.put(track, addRuleSlot(parts, i, x, y, partRule(track)));
        }
        this.fuelSlot = addRuleSlot(fuel, 0, FUEL_X, PARTS_Y, fuelRule());
        for (int i = 0; i < HOLD_WINDOW; i++) {
            int x = HOLD_X + i % HOLD_COLUMNS * SLOT_SIZE;
            int y = INVENTORY_Y + i / HOLD_COLUMNS * SLOT_SIZE;
            holdSlots[i] = addRuleSlot(hold, i, x, y, holdRule(i));
        }
        addPlayerInventory(inventory, FRAME, INVENTORY_Y);
        refresh();
    }

    /** Registers the menu type and the ways to open it: sneak and use on the pod, and the key's payload. */
    public static void init() {
        PodScreenPayload.register();
        UseEntityCallback.EVENT.register((user, level, hand, entity, hit) -> {
            if (!(entity instanceof PodEntity target) || !user.isSecondaryUseActive() || user.isSpectator()) {
                return InteractionResult.PASS;
            }
            if (user instanceof ServerPlayer server) {
                open(server, target).ifPresent(server::sendOverlayMessage);
            }
            return InteractionResult.SUCCESS;
        });
    }

    /**
     * Server only: opens the pod screen of {@code pod} for {@code player}, or says why not. Only the pod's own charter may
     * open it ({@link PodComponents#mayAccess}); while the saved charters cannot be read, a readable pod opens, as it lets
     * anyone board.
     */
    public static Optional<Component> open(ServerPlayer player, PodEntity pod) {
        if (player.isSpectator() || !pod.isAlive()) {
            return Optional.of(Component.translatable("deepcharter.pod.screen.refusal.gone"));
        }
        if (!mayOpen(player, pod)) {
            return Optional.of(Component.translatable("deepcharter.pod.screen.refusal.not_yours"));
        }
        Opening opening = new Opening(pod.getId(), player.getVehicle() == pod);
        Component title = PodComponents.registration(pod)
                .<Component>map(registration -> Component.literal(registration.serial()))
                .orElseGet(() -> Component.translatable("container.deepcharter.pod"));
        player.openMenu(new ExtendedMenuProvider<Opening>() {
            @Override
            public Opening getScreenOpeningData(ServerPlayer opener) {
                return opening;
            }

            @Override
            public Component getDisplayName() {
                return title;
            }

            @Override
            public AbstractContainerMenu createMenu(int id, Inventory inventory, Player opener) {
                return new PodMenu(id, inventory, opening, pod, (ServerPlayer) opener);
            }
        });
        return Optional.empty();
    }

    private static boolean mayOpen(ServerPlayer player, PodEntity pod) {
        MinecraftServer server = player.level().getServer();
        if (!Charters.isReadable(server)) {
            return Versioned.readable(pod, PodComponents.STATE).isPresent();
        }
        Optional<Charter> charter = Charters.readableCharterOf(server, player.getUUID());
        return PodComponents.mayAccess(pod, charter);
    }

    // ---- what the screen reads ----

    /** The pod, on the client only while it tracks the entity. */
    public Optional<PodEntity> pod() {
        return Optional.ofNullable(pod);
    }

    /** The slot of {@code track}'s part. */
    public RuleSlot partSlot(ComponentTrack track) {
        return Objects.requireNonNull(partSlots.get(track), "every track has a slot");
    }

    public RuleSlot fuelSlot() {
        return fuelSlot;
    }

    /** Hold slot {@code index} of the page shown, counted from the top left. */
    // test-seam: a test clicks a hold slot by its place on the page, as the player sees it; the screen draws all slots
    public RuleSlot holdSlot(int index) {
        return holdSlots[index];
    }

    /** The hold's size: the cargo bay's slots. */
    public int capacity() {
        return data.get(CAPACITY);
    }

    /** How many hold slots hold something. */
    public int occupied() {
        return data.get(OCCUPIED);
    }

    /** How many hold slots the screen shows: the bay's size, or more while a shrunk bay still holds what it held. */
    public int shown() {
        return Math.max(data.get(CAPACITY), data.get(REACH_SLOTS));
    }

    /** The hold page shown, from 0. */
    public int page() {
        return page.get();
    }

    /** How many pages the hold takes, at least one. */
    public int pages() {
        return Math.max(1, (shown() + HOLD_WINDOW - 1) / HOLD_WINDOW);
    }

    /** The litres in the tank, in tenths. */
    public int fuelTenths() {
        return data.get(FUEL_TENTHS);
    }

    /** The tank's size in litres, in tenths. */
    public int tankTenths() {
        return data.get(TANK_TENTHS);
    }

    /** The hold's mass, whole units. */
    public int mass() {
        return data.get(MASS);
    }

    /** The spoil in the hopper, or -1 for a pod with no hopper fitted. */
    public int spoil() {
        return data.get(HOPPER) != 0 ? data.get(SPOIL) : -1;
    }

    /** The slag bricks in the rack. */
    public int bricks() {
        return data.get(BRICKS);
    }

    /** How a track's fitted part works now: as fitted, held down to the chassis' cap, or not at all. */
    public enum Condition {
        WORKING,
        CAPPED,
        VOID
    }

    /** How the part on {@code track} works, read from the pod; empty for no part, or a pod the client does not track. */
    public Optional<Condition> condition(ComponentTrack track) {
        if (pod == null) {
            return Optional.empty();
        }
        Optional<PartLabel> label = PodComponents.partOf(pod, track);
        if (label.isEmpty()) {
            return Optional.empty();
        }
        int working = PodComponents.effectiveTier(pod, track);
        if (working == 0) {
            return Optional.of(Condition.VOID);
        }
        return Optional.of(working < label.get().tier() ? Condition.CAPPED : Condition.WORKING);
    }

    // ---- rules ----

    private SlotRule partRule(ComponentTrack track) {
        ItemStack unit = new ItemStack(PartUnits.unit(track));
        Component name = Component.translatable("item.deepcharter.part_" + track.id());
        Component ghost = UpgradeTuning.DEFAULT.value(track, 0) > 0f
                ? Component.translatable("screen.deepcharter.pod.stock", name)
                : Component.translatable("screen.deepcharter.pod.none", name);
        return SlotRule.of(
                        stack -> PartUnits.trackOf(stack).equals(Optional.of(track)), stack -> wrongPart(stack, track))
                .checked(stack -> partRefusal(track))
                .ghost(unit, ghost)
                .locking()
                .holding(1);
    }

    private static Component wrongPart(ItemStack stack, ComponentTrack track) {
        Component unit = Component.translatable(PartUnits.unit(track).getDescriptionId());
        return PartUnits.trackOf(stack).isPresent()
                ? Component.translatable("deepcharter.pod.screen.refusal.other_track", stack.getHoverName(), unit)
                : Component.translatable("deepcharter.pod.screen.refusal.not_a_unit", unit);
    }

    /** Why a unit of {@code track} cannot go in now: the server's fitting rules, or on the client what it can see. */
    private Optional<Component> partRefusal(ComponentTrack track) {
        if (player != null && pod != null) {
            if (pod.isRemoved()) {
                return Optional.of(Component.translatable("deepcharter.pod.screen.refusal.gone"));
            }
            return PartFitting.check(player, pod, track).map(refusal -> refusal.message(track));
        }
        // The client sees the pod's parts and owner: a working part of the track refuses a unit, the rest the server
        // decides and says.
        if (pod != null
                && PodComponents.registration(pod)
                        .flatMap(registration -> PodComponents.partOf(pod, track, registration.owner()))
                        .isPresent()) {
            return Optional.of(PartFitting.Refusal.ALREADY_FITTED.message(track));
        }
        return Optional.empty();
    }

    private SlotRule fuelRule() {
        return SlotRule.of(
                        stack -> stack.is(PodFuelItems.TAG),
                        stack -> Component.translatable("deepcharter.pod.screen.refusal.not_fuel"))
                .checked(stack -> fuelRefusal(stack))
                .ghost(new ItemStack(Items.COAL), Component.translatable("screen.deepcharter.pod.fuel"));
    }

    private Optional<Component> fuelRefusal(ItemStack stack) {
        if (player == null || pod == null) {
            return Optional.empty();
        }
        if (pod.isRemoved()) {
            return Optional.of(Component.translatable("deepcharter.pod.screen.refusal.gone"));
        }
        if (!PodFuelSlot.isReadable(pod)) {
            return Optional.of(Component.translatable("deepcharter.pod.screen.refusal.fuel_unreadable"));
        }
        // The client goes by the synced tag; only the server has the litres table.
        return PodFuelItems.litresOf(stack).isPresent()
                ? Optional.empty()
                : Optional.of(Component.translatable("deepcharter.pod.screen.refusal.not_fuel"));
    }

    private SlotRule holdRule(int window) {
        return SlotRule.of(stack -> true, stack -> Component.empty()).checked(stack -> holdRefusal(window));
    }

    /** Why hold slot {@code window} of the page takes nothing now: past the bay's size, a full bay, or no hold to put it in. */
    private Optional<Component> holdRefusal(int window) {
        if (player != null && pod != null) {
            if (pod.isRemoved()) {
                return Optional.of(Component.translatable("deepcharter.pod.screen.refusal.gone"));
            }
            if (!pod.cargo().isReadable()) {
                return Optional.of(Component.translatable("deepcharter.pod.screen.refusal.hold_unreadable"));
            }
        }
        int index = page() * HOLD_WINDOW + window;
        if (index >= capacity()) {
            return Optional.of(Component.translatable("deepcharter.pod.screen.refusal.past_the_bay", capacity()));
        }
        if (hold.getItem(window).isEmpty() && occupied() >= capacity()) {
            return Optional.of(Component.translatable("deepcharter.pod.screen.refusal.hold_full", capacity()));
        }
        return Optional.empty();
    }

    private boolean isHold(RuleSlot slot) {
        return slot.container == hold;
    }

    /** A unit shift-clicked from the inventory goes to its track's slot or is refused there, never into the hold. */
    @Override
    protected boolean routes(RuleSlot slot, ItemStack stack) {
        if (isHold(slot) && PartUnits.trackOf(stack).isPresent()) {
            return false;
        }
        return super.routes(slot, stack);
    }

    /**
     * A fitted part stays for good, and ore stays in the hold until a terminal takes it: in the pack it would ride
     * weightless and past the bay's size, and skip the spill of a Company recovery.
     */
    @Override
    protected Optional<Component> keeps(RuleSlot slot) {
        if (slot.container == parts) {
            return Optional.of(Component.translatable("deepcharter.pod.screen.refusal.fitted_for_good"));
        }
        if (isHold(slot) && OreRegistry.typeOf(slot.getItem()).isPresent()) {
            return Optional.of(Component.translatable("deepcharter.pod.screen.refusal.ore_stays"));
        }
        return Optional.empty();
    }

    /** A hold slot past what the hold shows is not there. */
    @Override
    protected boolean active(RuleSlot slot) {
        return !isHold(slot) || page() * HOLD_WINDOW + slot.getContainerSlot() < shown();
    }

    // ---- changes ----

    @Override
    protected void placed(RuleSlot slot, ItemStack stack) {
        if (player == null || pod == null || slot.container != parts) {
            return;
        }
        ComponentTrack track = ComponentTrack.values()[slot.getContainerSlot()];
        // The unit is spent in the fit: the refresh below shows the part in its place.
        PartFitting.fit(player, pod, track);
        refresh();
    }

    @Override
    public boolean clickMenuButton(Player clicker, int id) {
        int next = switch (id) {
            case PREVIOUS_PAGE -> page() - 1;
            case NEXT_PAGE -> page() + 1;
            default -> -1;
        };
        if (next < 0 || next >= pages()) {
            return false;
        }
        page.set(next);
        return true;
    }

    @Override
    public void broadcastChanges() {
        refresh();
        super.broadcastChanges();
    }

    /** Fills the part slots from the pod's parts, and keeps the page within the hold. Server only. */
    private void refresh() {
        if (player == null || pod == null) {
            return;
        }
        for (ComponentTrack track : ComponentTrack.values()) {
            ItemStack shown = PodComponents.partOf(pod, track)
                    .map(label -> ComponentItems.stackOf(track, label))
                    .orElse(ItemStack.EMPTY);
            int index = track.ordinal();
            if (!ItemStack.matches(parts.getItem(index), shown)) {
                parts.setItem(index, shown);
            }
        }
        if (page() >= pages()) {
            page.set(pages() - 1);
        }
    }

    @Override
    public boolean stillValid(Player viewer) {
        if (player == null || pod == null) {
            return true;
        }
        if (!pod.isAlive() || pod.isRemoved()) {
            return false;
        }
        boolean near = opening.seated() ? viewer.getVehicle() == pod : viewer.distanceToSqr(pod) <= REACH * REACH;
        return near && mayOpen(player, pod);
    }

    /** The server's numbers for the client, read from the pod on each broadcast. */
    private record ServerData(PodEntity pod) implements ContainerData {
        @Override
        public int get(int index) {
            return switch (index) {
                case CAPACITY -> PodStats.of(pod).cargoSlots();
                case REACH_SLOTS -> pod.cargo().reach();
                case OCCUPIED -> pod.cargo().occupied();
                case FUEL_TENTHS ->
                    Math.round(pod.fuel()
                            / PodTuning.DEFAULT.shell().fullFuel()
                            * PodStats.of(pod).tankLitres()
                            * 10f);
                case TANK_TENTHS -> Math.round(PodStats.of(pod).tankLitres() * 10f);
                case MASS -> Math.round(pod.cargoMass());
                case SPOIL ->
                    PodLining.readable(pod).map(PodLining.State::spoil).orElse(0);
                case BRICKS ->
                    PodLining.readable(pod).map(PodLining.State::bricks).orElse(0);
                case HOPPER -> PodLining.hasHopper(pod) ? 1 : 0;
                default -> throw new IndexOutOfBoundsException("no pod screen number " + index);
            };
        }

        @Override
        public void set(int index, int value) {
            throw new UnsupportedOperationException("the pod screen's numbers are read from the pod");
        }

        @Override
        public int getCount() {
            return DATA;
        }
    }

    /**
     * The page of the hold the server's menu shows: hold slot {@code page * HOLD_WINDOW + i} as container slot {@code i}.
     * It hands out the hold's own stacks, so a click changes the hold and nothing else; a pod that is gone shows nothing.
     */
    private final class HoldView implements Container {
        private final PodEntity owner;

        HoldView(PodEntity owner) {
            this.owner = owner;
        }

        private int index(int window) {
            return page() * HOLD_WINDOW + window;
        }

        private boolean gone() {
            return owner.isRemoved();
        }

        @Override
        public int getContainerSize() {
            return HOLD_WINDOW;
        }

        @Override
        public boolean isEmpty() {
            for (int i = 0; i < HOLD_WINDOW; i++) {
                if (!getItem(i).isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public ItemStack getItem(int window) {
            return gone() ? ItemStack.EMPTY : owner.cargo().stackAt(index(window));
        }

        @Override
        public ItemStack removeItem(int window, int count) {
            return gone() ? ItemStack.EMPTY : owner.cargo().removeAt(owner, index(window), count);
        }

        @Override
        public ItemStack removeItemNoUpdate(int window) {
            ItemStack there = getItem(window);
            if (!there.isEmpty()) {
                owner.cargo().setAt(owner, index(window), ItemStack.EMPTY);
            }
            return there;
        }

        @Override
        public void setItem(int window, ItemStack stack) {
            if (!gone() && owner.cargo().isReadable()) {
                owner.cargo().setAt(owner, index(window), stack);
            }
        }

        @Override
        public void setChanged() {
            if (!gone()) {
                owner.cargo().changed(owner);
            }
        }

        @Override
        public boolean stillValid(Player viewer) {
            return !gone();
        }

        @Override
        public void clearContent() {
            throw new UnsupportedOperationException("the pod screen never empties the hold wholesale");
        }
    }
}
