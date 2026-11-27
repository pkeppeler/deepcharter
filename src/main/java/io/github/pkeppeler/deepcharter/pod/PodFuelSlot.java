package io.github.pkeppeler.deepcharter.pod;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.ore.OreEncumbrance;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
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
 * is held twice. The container goes when the pod leaves the level, and it holds its pod weakly, so it never pins a pod
 * or its level. Unreadable data reads as an empty slot that takes nothing, and is never written over.
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

    /** The live slot of each server-side pod entity, dropped when the pod leaves the level. Server thread only. */
    private static final Map<PodEntity, Slot> SLOTS = new WeakHashMap<>();

    private PodFuelSlot() {}

    static void init() {
        PodEvents.AFTER_TICK.register(PodFuelSlot::feed);
        PodEvents.EXTRA_MASS.register(pod -> pod.level().isClientSide()
                ? 0f
                : OreEncumbrance.massOf(container(pod).getItem(0)));
        // Discard, unload and a change of dimension all end the entity's tracking.
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (entity instanceof PodEntity pod) {
                SLOTS.remove(pod);
            }
        });
    }

    /** Server only: the pod's fuel slot as a one-slot container, the one every open screen of the pod shares. */
    public static Container container(PodEntity pod) {
        if (pod.level().isClientSide()) {
            throw new IllegalStateException("the fuel slot is the server's; the client's screen has its own copy");
        }
        return SLOTS.computeIfAbsent(pod, Slot::new);
    }

    /** Whether a live slot is held for {@code pod}. */
    // test-seam: a test checks that a pod which left the level no longer has a live slot
    public static boolean hasLiveSlot(PodEntity pod) {
        return SLOTS.containsKey(pod);
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

    /**
     * The live stack of one pod's fuel slot, read from the attachment once and written back after every change. It
     * holds its pod weakly: the map's value must not keep its own key alive.
     */
    private static final class Slot implements Container {
        private final WeakReference<PodEntity> pod;
        private final boolean readable;
        private ItemStack stack;

        Slot(PodEntity pod) {
            this.pod = new WeakReference<>(pod);
            Optional<State> saved = Versioned.readable(pod, STATE);
            this.readable = saved.isPresent();
            this.stack = saved.map(State::stack).orElse(ItemStack.EMPTY);
        }

        /**
         * The pod, while it is there: nothing is there to take from a pod that is gone (saved and unloaded, removed or
         * collected), as its stack went with it.
         */
        private Optional<PodEntity> present() {
            PodEntity owner = pod.get();
            return readable && owner != null && !owner.isRemoved() ? Optional.of(owner) : Optional.empty();
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
            return present().isPresent() && index == 0 ? stack : ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItem(int index, int count) {
            if (present().isEmpty() || index != 0 || stack.isEmpty() || count <= 0) {
                return ItemStack.EMPTY;
            }
            ItemStack split = stack.split(count);
            setChanged();
            return split;
        }

        @Override
        public ItemStack removeItemNoUpdate(int index) {
            if (present().isEmpty() || index != 0) {
                return ItemStack.EMPTY;
            }
            ItemStack taken = stack;
            stack = ItemStack.EMPTY;
            setChanged();
            return taken;
        }

        @Override
        public void setItem(int index, ItemStack newStack) {
            if (present().isEmpty() || index != 0) {
                return;
            }
            stack = newStack;
            setChanged();
        }

        /** Writes a copy of the stack to the attachment. The menu calls this after it changes the stack in place. */
        @Override
        public void setChanged() {
            Optional<PodEntity> owner = present();
            if (owner.isEmpty()) {
                return;
            }
            if (stack.isEmpty()) {
                stack = ItemStack.EMPTY;
            }
            owner.get().setAttached(STATE, Versioned.of(new State(stack)));
        }

        @Override
        public boolean stillValid(Player player) {
            return present().isPresent();
        }

        @Override
        public void clearContent() {
            setItem(0, ItemStack.EMPTY);
        }
    }
}
