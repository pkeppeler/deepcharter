package io.github.pkeppeler.deepcharter.repair;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.CharterRefusal;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.fuel.EngineSoot;
import io.github.pkeppeler.deepcharter.fuel.FuelTuning;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.terminal.ParkedPodView;
import io.github.pkeppeler.deepcharter.terminal.TerminalAction;
import io.github.pkeppeler.deepcharter.terminal.TerminalActions;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.TerminalWares;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * The repair station terminal's actions: hull repair at {@link RepairTuning#repairCostPerHp} dollars a point, and the shop. Both
 * charge the player's charter account, and a refused action charges nothing. The terminal framework has already checked that the
 * station is repaired and the player is in reach; the arguments are the client's and are checked here.
 */
public final class RepairStation {
    /** Repair {@code args.hp} hull points (a whole number above 0), or what is missing if that is less. */
    public static final Identifier REPAIR = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "repair");
    /** Repair all that is missing. */
    public static final Identifier REPAIR_TOTAL = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "repair_total");
    /**
     * Clear the soot of the engine of the pod parked at the station: all of it at {@link EngineSoot#serviceCost}, or
     * the whole litres the account pays for ({@link EngineSoot#quote}).
     */
    public static final Identifier SERVICE_ENGINE =
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "service_engine");
    /** Start the free, slow flue scrape of a pod in limp mode at the colony ({@link FlueScrape}). */
    public static final Identifier SCRAPE_FLUE = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "scrape_flue");
    /** Buy one of the item whose id is {@code args.item}. */
    public static final Identifier BUY = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "buy");

    public static final String HP_KEY = "hp";
    public static final String ITEM_KEY = "item";
    private static final double HULL_PRECISION = 1000.0;

    private RepairStation() {}

    static void register() {
        TerminalActions.register(
                TerminalTypes.REPAIR_STATION,
                REPAIR,
                context -> repair(context, context.args().getInt(HP_KEY)));
        TerminalActions.register(
                TerminalTypes.REPAIR_STATION, REPAIR_TOTAL, context -> repair(context, Optional.of(Integer.MAX_VALUE)));
        TerminalActions.register(TerminalTypes.REPAIR_STATION, SERVICE_ENGINE, RepairStation::serviceEngine);
        TerminalActions.register(TerminalTypes.REPAIR_STATION, SCRAPE_FLUE, RepairStation::scrapeFlue);
        TerminalActions.register(TerminalTypes.REPAIR_STATION, BUY, RepairStation::buy);
        ParkedPodView.register(TerminalTypes.REPAIR_STATION);
        declareWares();
    }

    /**
     * What the station sells, each with the line that says what it does before the buy (#1645): the hull repairs,
     * the engine's soot and the suit tracks (see {@code SuitShop}) have a line of their own, an item has its spec
     * sheet's.
     */
    private static void declareWares() {
        String about = "screen.deepcharter.repair.about.";
        for (String ware : List.of("repair", "repair_total", "service_engine", "scrape_flue")) {
            TerminalWares.register(TerminalTypes.REPAIR_STATION, ware, about + ware);
        }
        for (Consumable consumable : Consumable.values()) {
            String path = consumable.itemId().getPath();
            TerminalWares.register(TerminalTypes.REPAIR_STATION, "item:" + path, "deepcharter.spec.item." + path);
        }
    }

    private static Optional<Component> repair(TerminalAction.Context context, Optional<Integer> requested) {
        if (requested.isEmpty() || requested.get() <= 0) {
            return Consumables.refusal("bad_amount");
        }
        Optional<PodEntity> parked = parkedPod(context);
        if (parked.isEmpty()) {
            return Consumables.refusal("no_pod");
        }
        PodEntity pod = parked.get();
        // Only the hangar restores a wreck (#77): a pod at hull 0 is refused, however much is paid.
        if (pod.hull() <= 0f || Wrecks.isWreck(pod)) {
            return Consumables.refusal("wreck");
        }
        // Hull is a float: the gap is rounded to a thousandth of a point, so float noise cannot add a dollar.
        double missing = Math.round(((double) pod.maxHull() - pod.hull()) * HULL_PRECISION) / HULL_PRECISION;
        if (missing <= 0.0) {
            return Consumables.refusal("hull_full");
        }
        double repaired = Math.min(requested.get(), missing);
        // A part of a hull point is charged as a whole dollar, rounded up.
        long cost = (long) Math.ceil(repaired * RepairTuning.DEFAULT.repairCostPerHp());
        Optional<CharterRefusal> unpaid =
                Charters.spend(context.server(), context.charter().orElseThrow().id(), cost);
        if (unpaid.isPresent()) {
            return Optional.of(unpaid.get().message());
        }
        pod.setHull(pod.hull() + (float) repaired);
        pod.level()
                .playSound(
                        null,
                        pod.getX(),
                        pod.getY(),
                        pod.getZ(),
                        DeepSound.REPAIR_NANOBOTS.event(),
                        SoundSource.PLAYERS);
        return Optional.empty();
    }

    /**
     * Clears the soot of the parked pod's engine: all of it when the account pays for that, or else the whole litres
     * it pays for. It never refuses only because the full bill is more than the account; it refuses a clean engine,
     * a wreck, unreadable soot, and an account short of one litre.
     */
    private static Optional<Component> serviceEngine(TerminalAction.Context context) {
        Optional<PodEntity> parked = parkedPod(context);
        Optional<Component> refused = engineRefusal(parked);
        if (refused.isPresent()) {
            return refused;
        }
        PodEntity pod = parked.orElseThrow();
        if (EngineSoot.soot(pod) <= 0f) {
            return Consumables.refusal("engine_clean");
        }
        CharterId charter = context.charter().orElseThrow().id();
        long account =
                Charters.findOrThrow(context.server(), charter).orElseThrow().account();
        Optional<EngineSoot.ServiceQuote> quote = EngineSoot.quote(pod, account);
        if (quote.isEmpty()) {
            return Optional.of(Component.translatable(
                    "message.deepcharter.repair.service_cannot_pay",
                    FuelTuning.DEFAULT.soot().servicePerLitre()));
        }
        Optional<CharterRefusal> unpaid =
                Charters.spend(context.server(), charter, quote.get().cost());
        if (unpaid.isPresent()) {
            return Optional.of(unpaid.get().message());
        }
        if (quote.get().all()) {
            EngineSoot.service(pod);
        } else {
            EngineSoot.service(pod, quote.get().litres());
        }
        Consumables.play(pod, DeepSound.REPAIR_NANOBOTS);
        return Optional.empty();
    }

    /**
     * Starts the free flue scrape ({@link FlueScrape}) of the parked pod, which must be in limp mode at the colony
     * (the only place limp mode holds a pod), with no scrape already running.
     */
    private static Optional<Component> scrapeFlue(TerminalAction.Context context) {
        Optional<PodEntity> parked = parkedPod(context);
        Optional<Component> refused = engineRefusal(parked);
        if (refused.isPresent()) {
            return refused;
        }
        PodEntity pod = parked.orElseThrow();
        if (!EngineSoot.limpAtColony(pod)) {
            return Consumables.refusal("scrape_not_limp");
        }
        if (FlueScrape.running(pod)) {
            return Consumables.refusal("scrape_running");
        }
        FlueScrape.start(pod, context.player(), context.pos());
        return Optional.empty();
    }

    /** Why the station cannot work on the parked pod's engine: no pod, a wreck, or soot this build cannot read. */
    private static Optional<Component> engineRefusal(Optional<PodEntity> parked) {
        if (parked.isEmpty()) {
            return Consumables.refusal("no_pod");
        }
        if (parked.get().hull() <= 0f || Wrecks.isWreck(parked.get())) {
            return Consumables.refusal("wreck");
        }
        if (!EngineSoot.isReadable(parked.get())) {
            return Consumables.refusal("engine_unreadable");
        }
        return Optional.empty();
    }

    private static Optional<Component> buy(TerminalAction.Context context) {
        Optional<Consumable> item =
                context.args().getString(ITEM_KEY).map(Identifier::tryParse).flatMap(Consumable::byItemId);
        if (item.isEmpty()) {
            return Consumables.refusal("unknown_item");
        }
        ServerPlayer player = context.player();
        ItemStack stack =
                new ItemStack(RepairRegistry.item(item.get()), item.get().perPurchase());
        Inventory inventory = player.getInventory();
        // Checked first: add() keeps only what fits, and the money is not given back. A pack must fit whole.
        if (roomFor(inventory, stack) < stack.getCount()) {
            return Consumables.refusal("no_room");
        }
        Optional<CharterRefusal> unpaid = Charters.spend(
                context.server(),
                context.charter().orElseThrow().id(),
                item.get().price());
        if (unpaid.isPresent()) {
            return Optional.of(unpaid.get().message());
        }
        inventory.add(stack);
        RepairEvents.BOUGHT.invoker().onBought(context.server(), player, item.get());
        return Optional.empty();
    }

    /**
     * How many of {@code stack}'s item {@code inventory.add} can take, as vanilla does: space in same-item stacks in the
     * 36 main slots, the offhand only if it already holds that item, and empty main slots (never an empty offhand).
     */
    private static int roomFor(Inventory inventory, ItemStack stack) {
        int room = 0;
        for (ItemStack held : inventory.getNonEquipmentItems()) {
            if (held.isEmpty()) {
                room += stack.getMaxStackSize();
            } else if (ItemStack.isSameItemSameComponents(held, stack)) {
                room += Math.max(0, held.getMaxStackSize() - held.getCount());
            }
        }
        ItemStack offhand = inventory.getItem(Inventory.SLOT_OFFHAND);
        if (!offhand.isEmpty() && ItemStack.isSameItemSameComponents(offhand, stack)) {
            room += Math.max(0, offhand.getMaxStackSize() - offhand.getCount());
        }
        return room;
    }

    /** The nearest pod parked at the station ({@link Terminals#parkedPods}) that the player's charter may use. */
    private static Optional<PodEntity> parkedPod(TerminalAction.Context context) {
        return Terminals.parkedPods(context.player().level(), context.pos(), context.charter()).stream()
                .findFirst();
    }
}
