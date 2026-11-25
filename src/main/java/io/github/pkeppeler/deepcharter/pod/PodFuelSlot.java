package io.github.pkeppeler.deepcharter.pod;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * The pod's fuel slot (ADR 0102): one stack of pod fuel, like a furnace's, that feeds the tank one item at a time
 * whenever the tank has room for all of that item's litres, so nothing is wasted. It is filled through the pod screen
 * ({@link PodMenu}), weighs what its fuel weighs, and is saved with the pod.
 *
 * <p>The stack is a versioned attachment, {@code deepcharter:pod_fuel_slot}, saved and not synced (the screen's slot
 * syncs it). The live stack the screen and the tick change sits in one {@link Container} for each pod entity, which
 * writes a copy back to the attachment on every change, so two players with the screen open share one stack and nothing
 * is held twice. Unreadable data reads as an empty slot that takes nothing, and is never written over.
 */
public final class PodFuelSlot {
    public static final int VERSION = 1;

    /** The attachment's value: the stack waiting in the slot, empty for none. */
    public record State(ItemStack stack) {
        public static final State EMPTY = new State(ItemStack.EMPTY);
        public static final MapCodec<State> BODY = RecordCodecBuilder.mapCodec(instance -> instance.group(
                        ItemStack.OPTIONAL_CODEC.fieldOf("stack").forGetter(State::stack))
                .apply(instance, State::new));

        public State {
            stack = stack.copy();
        }

        /** A copy: a stack is mutable, and the attachment's value must not change under it. */
        @Override
        public ItemStack stack() {
            return stack.copy();
        }
    }

    public static final AttachmentType<Versioned<State>> STATE = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pod_fuel_slot"),
            builder -> builder.persistent(Versioned.codec(VERSION, State.BODY))
                    .initializer(() -> Versioned.of(State.EMPTY)));

    /** The live slot of each server-side pod entity. Server thread only. */
    private static final Map<PodEntity, Slot> SLOTS = Collections.synchronizedMap(new WeakHashMap<>());

    private PodFuelSlot() {}

    static void init() {
        PodEvents.AFTER_TICK.register(PodFuelSlot::feed);
        PodEvents.EXTRA_MASS.register(pod ->
                pod.level().isClientSide() ? 0f : PodCargo.massOf(container(pod).getItem(0)));
    }

    /** Server only: the pod's fuel slot as a one-slot container, the one every open screen of the pod shares. */
    public static Container container(PodEntity pod) {
        if (pod.level().isClientSide()) {
            throw new IllegalStateException("the fuel slot is the server's; the client's screen has its own copy");
        }
        return SLOTS.computeIfAbsent(pod, Slot::new);
    }

    /** Whether the pod's saved fuel slot can be read; when it cannot, the slot shows nothing and takes nothing. */
    public static boolean isReadable(PodEntity pod) {
        return Versioned.readable(pod, STATE).isPresent();
    }

    /** One item at a time into the tank, while it has room for all of that item's litres. */
    private static void feed(PodEntity pod) {
        Slot slot = (Slot) container(pod);
        ItemStack stack = slot.getItem(0);
        if (!stack.isEmpty() && PodFuel.feedOne(pod, stack)) {
            slot.setChanged();
        }
    }

    /** The live stack of one pod's fuel slot, read from the attachment once and written back after every change. */
    private static final class Slot implements Container {
        private final PodEntity pod;
        private final boolean readable;
        private ItemStack stack;

        Slot(PodEntity pod) {
            this.pod = pod;
            Optional<State> saved = Versioned.readable(pod, STATE);
            this.readable = saved.isPresent();
            this.stack = saved.map(State::stack).orElse(ItemStack.EMPTY);
        }

        /**
         * Nothing is there to take from a pod that is gone (saved and unloaded, or removed): its stack went with it.
         */
        private boolean present() {
            return readable && !pod.isRemoved();
        }

        @Override
        public int getContainerSize() {
            return 1;
        }

        @Override
        public boolean isEmpty() {
            return getItem(0).isEmpty();
        }

        @Override
        public ItemStack getItem(int index) {
            return present() && index == 0 ? stack : ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItem(int index, int count) {
            if (!present() || index != 0 || stack.isEmpty() || count <= 0) {
                return ItemStack.EMPTY;
            }
            ItemStack split = stack.split(count);
            setChanged();
            return split;
        }

        @Override
        public ItemStack removeItemNoUpdate(int index) {
            if (!present() || index != 0) {
                return ItemStack.EMPTY;
            }
            ItemStack taken = stack;
            stack = ItemStack.EMPTY;
            setChanged();
            return taken;
        }

        @Override
        public void setItem(int index, ItemStack newStack) {
            if (!present() || index != 0) {
                return;
            }
            stack = newStack;
            setChanged();
        }

        /** Writes a copy of the stack to the attachment. The menu calls this after it changes the stack in place. */
        @Override
        public void setChanged() {
            if (!present()) {
                return;
            }
            if (stack.isEmpty()) {
                stack = ItemStack.EMPTY;
            }
            pod.setAttached(STATE, Versioned.of(new State(stack)));
        }

        @Override
        public boolean stillValid(Player player) {
            return present();
        }

        @Override
        public void clearContent() {
            setItem(0, ItemStack.EMPTY);
        }
    }
}
