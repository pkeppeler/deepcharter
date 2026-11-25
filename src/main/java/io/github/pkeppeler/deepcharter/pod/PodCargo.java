package io.github.pkeppeler.deepcharter.pod;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.command.FeatureCommands;
import io.github.pkeppeler.deepcharter.ore.OreEncumbrance;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
 * anything, with a mass. Drilled ore goes in one ore to a free slot ({@link #tryAdd}); the pod screen ({@link PodMenu})
 * moves stacks in and out of any slot, as a chest does. What the terminals and the brace read and take is the ore
 * ({@link #entries}, {@link #count}, {@link #take}, {@link #dump}); what else the crew stows rides along. The synced
 * {@link PodData#CARGO_USED} (slots holding something) and {@link PodData#CARGO_MASS} mirror the slots; only this class
 * writes them.
 *
 * <p>An ore weighs its {@link OreType#mass} unless it was drilled at another; anything else weighs what {@link
 * OreEncumbrance#massOf} says, its {@code cargo_weight} and whatever it holds.
 *
 * <p>The saved hold has a version ({@value #VERSION}). Version 1 was the ore bay: ore only, one entry after another,
 * read into the first slots. A save with no version is from before ore items: its entries were vanilla ores, which are
 * not cargo, so they are dropped and logged. A save of another version, or one with an entry that does not decode, is
 * kept as it was read and written back unchanged, the hold shows nothing and takes nothing, and {@link #tryAdd} and
 * {@link #dump} throw until a build can read it. Nothing is dropped silently.
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
                                : checkedMass(saved.mass(), saved),
                        DataResult::success);
    }

    /** A version 2 entry as saved: the slot, the stack in it and its mass. */
    private record SlotEntry(int slot, ItemStack stack, float mass) {
        static final Codec<SlotEntry> CODEC = RecordCodecBuilder.<SlotEntry>create(instance -> instance.group(
                                Codec.intRange(0, MOST_SLOTS - 1)
                                        .fieldOf("slot")
                                        .forGetter(SlotEntry::slot),
                                ItemStack.CODEC.fieldOf("stack").forGetter(SlotEntry::stack),
                                Codec.FLOAT.fieldOf("mass").forGetter(SlotEntry::mass))
                        .apply(instance, SlotEntry::new))
                .flatXmap(saved -> checkedMass(saved.mass(), saved), DataResult::success);
    }

    /** A bad mass is a decode error, never a throw: loading a pod must not crash. */
    private static <T> DataResult<T> checkedMass(float mass, T saved) {
        return mass >= 0f
                ? DataResult.success(saved)
                : DataResult.error(() -> "cargo mass must be a number, not negative: " + mass);
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
     * What one slot holds: the stack itself, which the pod screen changes in place as vanilla's menus do, and the mass
     * it had at {@code count} items. A stack that grew or shrank keeps its mass in proportion ({@link #changed}).
     */
    private record Held(ItemStack stack, float mass, int count) {
        Held rescaled() {
            return stack.getCount() == count
                    ? this
                    : new Held(stack, mass * stack.getCount() / count, stack.getCount());
        }
    }

    /** The slots in order; null is an empty slot. Never ends in null. */
    private final List<@Nullable Held> slots = new ArrayList<>();
    /** The saved cargo this build cannot read, or null when the cargo is readable. */
    private @Nullable Unreadable unreadable;

    private boolean discardLogged;

    /**
     * The mass {@code stack} adds to a pod when stowed by hand: an ore's own mass, or {@link OreEncumbrance#massOf}.
     */
    public static float massOf(ItemStack stack) {
        return OreEncumbrance.massOf(stack);
    }

    /**
     * Server only: puts one ore at its real mass in a free slot, if the hold has one, and returns whether it did.
     * Anything but ore throws.
     */
    public boolean tryAdd(PodEntity pod, ItemStack ore) {
        return tryAdd(
                pod,
                ore,
                OreRegistry.typeOf(ore)
                        .map(OreType::mass)
                        .orElseThrow(() -> new IllegalArgumentException("only ore is cargo, not " + ore)));
    }

    /**
     * Server only: puts one ore at the given mass (not negative) in a free slot, if the hold has one, and returns
     * whether it did.
     */
    public boolean tryAdd(PodEntity pod, ItemStack ore, float mass) {
        requireOwnServerPod(pod);
        requireReadable(pod);
        Entry entry = new Entry(ore, mass);
        if (occupied() >= PodStats.of(pod).cargoSlots()) {
            return false;
        }
        put(firstFree(), new Held(entry.stack(), mass, entry.stack().getCount()));
        sync(pod);
        return true;
    }

    /** How many of {@code ore} the hold has; 0 while the saved cargo is unreadable. */
    public int count(OreType ore) {
        return held().filter(held -> isOre(held, ore))
                .mapToInt(held -> held.stack().getCount())
                .sum();
    }

    /**
     * Server only: removes up to {@code amount} (at least 1) of {@code ore}, in slot order, and returns how many it
     * removed: fewer than asked when the hold has fewer. A slot that holds more than is asked is shrunk and keeps its
     * mass in proportion.
     */
    public int take(PodEntity pod, OreType ore, int amount) {
        requireOwnServerPod(pod);
        requireReadable(pod);
        if (amount < 1) {
            throw new IllegalArgumentException("cannot take " + amount + " ore from the cargo");
        }
        int left = amount;
        for (int i = 0; i < slots.size() && left > 0; i++) {
            Held held = slots.get(i);
            if (held == null || !isOre(held, ore)) {
                continue;
            }
            int count = held.stack().getCount();
            if (count <= left) {
                slots.set(i, null);
                left -= count;
            } else {
                held.stack().shrink(left);
                slots.set(i, held.rescaled());
                left = 0;
            }
        }
        if (left < amount) {
            trim();
            sync(pod);
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
            Held held = slots.get(i);
            if (held != null && OreRegistry.typeOf(held.stack()).isPresent()) {
                slots.set(i, null);
                dumped++;
            }
        }
        trim();
        sync(pod);
        return dumped;
    }

    /**
     * False while the saved cargo is unreadable: the hold then takes nothing and shows nothing, and {@link #tryAdd} and
     * {@link #dump} throw.
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
        return held().filter(held -> OreRegistry.typeOf(held.stack()).isPresent())
                .map(held -> new Entry(held.stack(), held.mass()))
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
        Held held = slots.get(index);
        return held == null ? ItemStack.EMPTY : held.stack();
    }

    /**
     * Server only: puts {@code stack} itself in slot {@code index}, over what was there, or empties it. The stack that
     * is already there, put back after a change in place, keeps its mass in proportion; any other weighs {@link
     * #massOf}. Whoever calls this has checked the slot may take it.
     */
    public void setAt(PodEntity pod, int index, ItemStack stack) {
        requireOwnServerPod(pod);
        requireReadable(pod);
        if (index < 0 || index >= MOST_SLOTS) {
            throw new IndexOutOfBoundsException("no hold slot " + index);
        }
        Held there = index < slots.size() ? slots.get(index) : null;
        if (stack.isEmpty()) {
            put(index, null);
        } else if (there != null && there.stack() == stack) {
            put(index, there.rescaled());
        } else {
            put(index, new Held(stack, massOf(stack), stack.getCount()));
        }
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
     * Server only: a stack the pod screen holds was changed in place (grown, shrunk or emptied): empties the slots left
     * with nothing, keeps each changed stack's mass in proportion, and updates the synced counts.
     */
    public void changed(PodEntity pod) {
        requireOwnServerPod(pod);
        if (unreadable != null) {
            return;
        }
        for (int i = 0; i < slots.size(); i++) {
            Held held = slots.get(i);
            if (held != null) {
                slots.set(i, held.stack().isEmpty() ? null : held.rescaled());
            }
        }
        trim();
        sync(pod);
    }

    private Stream<Held> held() {
        return slots.stream().filter(Objects::nonNull).map(held -> Objects.requireNonNull(held));
    }

    private static boolean isOre(Held held, OreType ore) {
        return OreRegistry.typeOf(held.stack()).map(type -> type == ore).orElse(false);
    }

    private int firstFree() {
        int free = slots.indexOf(null);
        return free < 0 ? slots.size() : free;
    }

    private void put(int index, @Nullable Held held) {
        while (slots.size() <= index) {
            slots.add(null);
        }
        slots.set(index, held);
    }

    private void trim() {
        while (!slots.isEmpty() && slots.getLast() == null) {
            slots.removeLast();
        }
    }

    private void sync(PodEntity pod) {
        float mass = 0f;
        int used = 0;
        for (Held held : slots) {
            if (held != null) {
                mass += held.mass();
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
            Held held = slots.get(i);
            if (held != null) {
                list.add(new SlotEntry(i, held.stack(), held.mass()));
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
        decoded.forEach(entry ->
                slots.add(new Held(entry.stack(), entry.mass(), entry.stack().getCount())));
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
            if (entry.slot() < slots.size() && slots.get(entry.slot()) != null) {
                return false;
            }
            put(
                    entry.slot(),
                    new Held(entry.stack(), entry.mass(), entry.stack().getCount()));
        }
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
