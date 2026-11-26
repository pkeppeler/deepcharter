package io.github.pkeppeler.deepcharter.pod;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.command.FeatureCommands;
import io.github.pkeppeler.deepcharter.ore.DrilledMass;
import io.github.pkeppeler.deepcharter.ore.OreEncumbrance;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * A pod's hold (ADR 0102): a row of slots, as many as the pod's {@link PodStats#cargoSlots}, each holding one stack of
 * anything. Drilled ore goes in one ore to a free slot ({@link #tryAdd}); the pod screen ({@link PodMenu}) moves stacks
 * in and out of any slot, as a chest does. What the terminals and the brace read and take is the ore ({@link
 * #entries}, {@link #count}, {@link #take}, {@link #dump}); a teleport or a salvage takes everything ({@link
 * #takeAll}). The synced {@link PodData#CARGO_USED} (slots holding something) and {@link PodData#CARGO_MASS} mirror
 * the slots; only this class writes them.
 *
 * <p>A stack weighs what {@link OreEncumbrance#massOf} says, worked out from the stack itself each time the hold
 * changes, so a stack changed in place (a bundle filled in its slot) weighs what it holds now. An ore drilled at a
 * mass other than its own carries that mass on the stack ({@link DrilledMass}).
 *
 * <p>The saved hold has a version ({@value #VERSION}). Version 1 was the ore bay: ore only, one entry after another
 * with its mass, read into the first slots. A save with no version is from before ore items: its entries were vanilla
 * ores, which are not cargo, so they are dropped and logged. A save of another version, or one with an entry that does
 * not decode or two entries in one slot, is kept as it was read and written back unchanged, the hold shows nothing and
 * takes nothing, and {@link #tryAdd}, {@link #dump} and {@link #takeAll} throw until a build can read it. Nothing is
 * dropped silently.
 */
public final class PodCargo {
    /** The version of the saved hold: 1 was the ore bay, 2 the hold of any item, each in its own slot. */
    public static final int VERSION = 2;

    private static final int ORE_BAY_VERSION = 1;
    /** More slots than any pod has, so a corrupt slot number cannot make the load build a huge list. */
    private static final int MOST_SLOTS = 4096;

    private static final String CARGO_KEY = "cargo";
    private static final String VERSION_KEY = "cargo_version";

    /** A version 1 entry as saved: an ore and its mass, its slot the next one. */
    private record OreBayEntry(ItemStack stack, float mass) {
        static final Codec<OreBayEntry> CODEC = RecordCodecBuilder.<OreBayEntry>create(instance -> instance.group(
                                ItemStack.CODEC.fieldOf("stack").forGetter(OreBayEntry::stack),
                                Codec.FLOAT.fieldOf("mass").forGetter(OreBayEntry::mass))
                        .apply(instance, OreBayEntry::new))
                .flatXmap(
                        saved -> OreRegistry.typeOf(saved.stack()).isEmpty()
                                ? DataResult.error(() -> "only ore is cargo, not " + saved.stack())
                                : saved.mass() >= 0f
                                        ? DataResult.success(saved)
                                        : DataResult.error(
                                                () -> "cargo mass must be a number, not negative: " + saved.mass()),
                        DataResult::success);
    }

    /** A version 2 entry as saved: the slot and the stack in it, which carries its own mass. */
    private record SlotEntry(int slot, ItemStack stack) {
        static final Codec<SlotEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        Codec.intRange(0, MOST_SLOTS - 1).fieldOf("slot").forGetter(SlotEntry::slot),
                        ItemStack.CODEC.fieldOf("stack").forGetter(SlotEntry::stack))
                .apply(instance, SlotEntry::new));
    }

    /** Saved cargo this build cannot read, held as it was read: its version if it had one, and its entries. */
    private record Unreadable(Optional<Integer> version, List<Tag> raw) {}

    /** One ore in the hold. The stack is the ore item; the mass is what it adds to the pod's cargo mass. */
    public record Entry(ItemStack stack, float mass) {
        public Entry {
            if (OreRegistry.typeOf(stack).isEmpty()) {
                throw new IllegalArgumentException("only ore is cargo, not " + stack);
            }
            if (!(mass >= 0f)) {
                throw new IllegalArgumentException("ore mass must be a number, not negative: " + mass);
            }
            stack = stack.copy();
        }

        /** The stack is mutable and has no value equality of its own, so compare what it holds. */
        @Override
        public boolean equals(Object other) {
            return other instanceof Entry entry && mass == entry.mass && ItemStack.matches(stack, entry.stack);
        }

        @Override
        public int hashCode() {
            return 31 * ItemStack.hashItemAndComponents(stack) + Float.hashCode(mass);
        }
    }

    /**
     * The slots in order, each the stack itself, which the pod screen changes in place as vanilla's menus do; an empty
     * stack is an empty slot. Never ends in an empty slot.
     */
    private final List<ItemStack> slots = new ArrayList<>();
    /** The saved cargo this build cannot read, or null when the cargo is readable. */
    private @Nullable Unreadable unreadable;

    private boolean discardLogged;

    /**
     * Server only: puts one ore at its real mass in a free slot, if the hold has one, and returns whether it did.
     * Anything but ore throws.
     */
    public boolean tryAdd(PodEntity pod, ItemStack ore) {
        return tryAdd(
                pod,
                ore,
                OreRegistry.typeOf(ore)
                        .map(type -> type.mass() * ore.getCount())
                        .orElseThrow(() -> new IllegalArgumentException("only ore is cargo, not " + ore)));
    }

    /**
     * Server only: puts one ore at the given mass (not negative) in a free slot, if the hold has one, and returns
     * whether it did. A mass other than the ore's own goes on the stack ({@link DrilledMass}).
     */
    public boolean tryAdd(PodEntity pod, ItemStack ore, float mass) {
        requireOwnServerPod(pod);
        requireReadable(pod);
        Entry entry = new Entry(ore, mass);
        if (occupied() >= PodStats.of(pod).cargoSlots()) {
            return false;
        }
        put(firstFree(), DrilledMass.at(entry.stack(), mass));
        sync(pod);
        return true;
    }

    /** How many of {@code ore} the hold has; 0 while the saved cargo is unreadable. */
    public int count(OreType ore) {
        return held().filter(stack -> isOre(stack, ore))
                .mapToInt(ItemStack::getCount)
                .sum();
    }

    /**
     * Server only: removes up to {@code amount} (at least 1) of {@code ore}, in slot order, and returns how many it
     * removed: fewer than asked when the hold has fewer.
     */
    public int take(PodEntity pod, OreType ore, int amount) {
        requireOwnServerPod(pod);
        requireReadable(pod);
        if (amount < 1) {
            throw new IllegalArgumentException("cannot take " + amount + " ore from the cargo");
        }
        int left = amount;
        for (int i = 0; i < slots.size() && left > 0; i++) {
            ItemStack stack = slots.get(i);
            if (!isOre(stack, ore)) {
                continue;
            }
            int taken = Math.min(left, stack.getCount());
            stack.shrink(taken);
            left -= taken;
        }
        if (left < amount) {
            changed(pod);
        }
        return amount - left;
    }

    /**
     * Server only: takes every ore out of the hold, leaving what else it holds, and returns how many slots of ore it
     * emptied.
     */
    public int dump(PodEntity pod) {
        requireOwnServerPod(pod);
        requireReadable(pod);
        int dumped = 0;
        for (int i = 0; i < slots.size(); i++) {
            if (OreRegistry.typeOf(slots.get(i)).isPresent()) {
                slots.set(i, ItemStack.EMPTY);
                dumped++;
            }
        }
        trim();
        sync(pod);
        return dumped;
    }

    /** Server only: empties every slot of the hold and returns what they held, in slot order. */
    public List<ItemStack> takeAll(PodEntity pod) {
        requireOwnServerPod(pod);
        requireReadable(pod);
        List<ItemStack> taken = held().toList();
        slots.clear();
        sync(pod);
        return taken;
    }

    /**
     * False while the saved cargo is unreadable: the hold then takes nothing and shows nothing, and {@link #tryAdd},
     * {@link #dump} and {@link #takeAll} throw.
     */
    public boolean isReadable() {
        return unreadable == null;
    }

    /** Logs, once for each load of the cargo, that a drilled ore was lost because the cargo is unreadable. */
    public void logDiscardedOre(PodEntity pod) {
        if (!discardLogged) {
            discardLogged = true;
            DeepCharter.LOGGER.error("Pod {}: cargo unreadable, drilled ore discarded", pod.getUUID());
        }
    }

    /** The ore in the hold, in slot order, each slot's stack a copy; empty while the saved cargo is unreadable. */
    public List<Entry> entries() {
        return held().filter(stack -> OreRegistry.typeOf(stack).isPresent())
                .map(stack -> new Entry(stack, OreEncumbrance.massOf(stack)))
                .toList();
    }

    /** How many slots, from the first, the hold's contents reach: the last one that holds something, plus one. */
    public int reach() {
        return slots.size();
    }

    /** How many slots hold something. */
    public int occupied() {
        return (int) held().count();
    }

    /**
     * The stack in slot {@code index} itself, not a copy, for the pod screen's container; empty for an empty slot, one
     * past the hold's reach, or while the saved cargo is unreadable. A change to it is the caller's to report with
     * {@link #changed}.
     */
    public ItemStack stackAt(int index) {
        if (unreadable != null || index < 0 || index >= slots.size()) {
            return ItemStack.EMPTY;
        }
        return slots.get(index);
    }

    /**
     * Server only: puts {@code stack} itself in slot {@code index}, over what was there, or empties it. Whoever calls
     * this has checked the slot may take it.
     */
    public void setAt(PodEntity pod, int index, ItemStack stack) {
        requireOwnServerPod(pod);
        requireReadable(pod);
        if (index < 0 || index >= MOST_SLOTS) {
            throw new IndexOutOfBoundsException("no hold slot " + index);
        }
        put(index, stack);
        trim();
        sync(pod);
    }

    /**
     * Server only: splits up to {@code amount} off the stack in slot {@code index} and returns it; empty for an empty
     * slot.
     */
    public ItemStack removeAt(PodEntity pod, int index, int amount) {
        ItemStack there = stackAt(index);
        if (there.isEmpty() || amount <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack split = there.split(amount);
        changed(pod);
        return split;
    }

    /**
     * Server only: a stack the hold holds was changed in place (grown, shrunk, emptied or filled): empties the slots
     * left with nothing, and updates the synced counts and mass from the stacks.
     */
    public void changed(PodEntity pod) {
        requireOwnServerPod(pod);
        if (unreadable != null) {
            return;
        }
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).isEmpty()) {
                slots.set(i, ItemStack.EMPTY);
            }
        }
        trim();
        sync(pod);
    }

    private Stream<ItemStack> held() {
        return slots.stream().filter(stack -> !stack.isEmpty());
    }

    private static boolean isOre(ItemStack stack, OreType ore) {
        return OreRegistry.typeOf(stack).map(type -> type == ore).orElse(false);
    }

    private int firstFree() {
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).isEmpty()) {
                return i;
            }
        }
        return slots.size();
    }

    private void put(int index, ItemStack stack) {
        while (slots.size() <= index) {
            slots.add(ItemStack.EMPTY);
        }
        slots.set(index, stack);
    }

    private void trim() {
        while (!slots.isEmpty() && slots.getLast().isEmpty()) {
            slots.removeLast();
        }
    }

    private void sync(PodEntity pod) {
        float mass = 0f;
        int used = 0;
        for (ItemStack stack : slots) {
            if (!stack.isEmpty()) {
                mass += OreEncumbrance.massOf(stack);
                used++;
            }
        }
        pod.setCargoUsed(used);
        pod.setCargoMass(mass);
    }

    private void requireReadable(PodEntity pod) {
        if (unreadable != null) {
            throw new IllegalStateException("the cargo of pod " + pod.getUUID() + " was saved with version "
                    + unreadable.version().map(String::valueOf).orElse("none")
                    + ", which this build cannot read; it is kept unchanged");
        }
    }

    private void requireOwnServerPod(PodEntity pod) {
        if (pod.cargo() != this) {
            throw new IllegalArgumentException("this cargo bay belongs to another pod");
        }
        if (pod.level().isClientSide()) {
            throw new IllegalStateException("cargo changes only on the server");
        }
    }

    /** Saves the hold with the pod, with its version; unreadable cargo is written back as it was read. */
    public void save(ValueOutput output) {
        if (unreadable != null) {
            unreadable.version().ifPresent(version -> output.putInt(VERSION_KEY, version));
            ValueOutput.TypedOutputList<Dynamic<?>> raw = output.list(CARGO_KEY, Codec.PASSTHROUGH);
            unreadable.raw().forEach(tag -> raw.add(new Dynamic<>(NbtOps.INSTANCE, tag)));
            return;
        }
        output.putInt(VERSION_KEY, VERSION);
        ValueOutput.TypedOutputList<SlotEntry> list = output.list(CARGO_KEY, SlotEntry.CODEC);
        for (int i = 0; i < slots.size(); i++) {
            if (!slots.get(i).isEmpty()) {
                list.add(new SlotEntry(i, slots.get(i)));
            }
        }
    }

    /**
     * Restores the hold saved by {@link #save}, or the ore bay of version 1, and derives the pod's synced counts from
     * it.
     */
    public void load(ValueInput input, PodEntity pod) {
        slots.clear();
        unreadable = null;
        discardLogged = false;
        List<Tag> raw = new ArrayList<>();
        input.list(CARGO_KEY, Codec.PASSTHROUGH)
                .orElseThrow(() -> new IllegalStateException("saved pod has no '" + CARGO_KEY + "'"))
                .forEach(entry -> raw.add(entry.convert(NbtOps.INSTANCE).getValue()));
        Optional<Integer> version = input.getInt(VERSION_KEY);
        if (version.isEmpty()) {
            // Saved before ore items: every entry was a vanilla ore block, which is no longer cargo.
            if (!raw.isEmpty()) {
                DeepCharter.LOGGER.error(
                        "Pod {} was saved before ore items: dropped {} cargo entries, which were vanilla ores",
                        pod.getUUID(),
                        raw.size());
            }
            sync(pod);
            return;
        }
        // Not checked against the slots: the hold's size is a stat that other features change, and a pod whose hold
        // shrank since it was saved must still load. A hold over its size takes nothing until it is under it again.
        boolean read = switch (version.get()) {
            case ORE_BAY_VERSION -> readOreBay(input, raw.size());
            case VERSION -> readHold(input, raw.size());
            default -> false;
        };
        if (!read) {
            slots.clear();
            unreadable = new Unreadable(version, raw);
            DeepCharter.LOGGER.error(
                    "Pod {} has cargo this build cannot read (version {}, {} entries): kept unchanged, and the cargo refuses changes",
                    pod.getUUID(),
                    version.get(),
                    raw.size());
        }
        sync(pod);
    }

    /** Reads the ore bay of version 1 into the first slots; false if an entry does not decode. */
    private boolean readOreBay(ValueInput input, int saved) {
        List<OreBayEntry> decoded = new ArrayList<>();
        input.list(CARGO_KEY, OreBayEntry.CODEC).orElseThrow().forEach(decoded::add);
        if (decoded.size() < saved) {
            return false;
        }
        decoded.forEach(entry -> slots.add(DrilledMass.at(entry.stack(), entry.mass())));
        trim();
        return true;
    }

    /** Reads the hold of version 2; false if an entry does not decode or two share a slot. */
    private boolean readHold(ValueInput input, int saved) {
        List<SlotEntry> decoded = new ArrayList<>();
        input.list(CARGO_KEY, SlotEntry.CODEC).orElseThrow().forEach(decoded::add);
        if (decoded.size() < saved) {
            return false;
        }
        for (SlotEntry entry : decoded) {
            if (!stackAt(entry.slot()).isEmpty()) {
                return false;
            }
            put(entry.slot(), entry.stack());
        }
        trim();
        return true;
    }

    public static void init() {
        FeatureCommands.register(
                "pod",
                root -> root.then(Commands.literal("dump")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(PodCargo::dumpRiddenPod)));
    }

    private static int dumpRiddenPod(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() != null && source.getEntity().getVehicle() instanceof PodEntity pod)) {
            source.sendFailure(Component.translatable("deepcharter.pod.dump.no_pod"));
            return 0;
        }
        int dumped = pod.cargo().dump(pod);
        source.sendSuccess(() -> Component.translatable("deepcharter.pod.dump.success", dumped), true);
        return dumped;
    }
}
