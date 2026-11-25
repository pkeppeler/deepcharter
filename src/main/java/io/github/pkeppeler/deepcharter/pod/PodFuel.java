package io.github.pkeppeler.deepcharter.pod;

import io.github.pkeppeler.deepcharter.fuel.EngineSoot;
import java.util.OptionalDouble;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Fuel drain, refuelling and stranding; FUEL is a percentage, so 100 is full whatever the tank size. */
public final class PodFuel {
    private static final float TICKS_PER_SECOND = 20f;

    /** What the pod is doing, which sets how fast it burns fuel. */
    public enum Activity {
        IDLE,
        MOVING,
        DRILLING
    }

    private PodFuel() {}

    /** Refuel by using a fuel item ({@link PodFuelItems}) on the pod. An event, so that PodEntity.interact stays the mount's. */
    public static void init() {
        PodFuelItems.init();
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                entity instanceof PodEntity pod ? refuel(player, hand, pod) : InteractionResult.PASS);
    }

    /** Fuel burned per tick, in percent of the tank, by a pod with these stats. */
    public static float drainPercentPerTick(PodStats stats, Activity activity) {
        float litresPerSecond = switch (activity) {
            case IDLE -> stats.idleLitresPerSecond();
            case MOVING -> stats.movingLitresPerSecond();
            case DRILLING -> stats.drillingLitresPerSecond();
        };
        return litresToPercent(stats, litresPerSecond / TICKS_PER_SECOND);
    }

    /** One tick of the extra fuel a Company rock bite burns (#526): the drilling rate times {@code multiplier - 1}, on top of the tick's own burn. */
    static void burnForBite(PodEntity pod, PodStats stats, float multiplier) {
        pod.setFuel(Math.max(0f, pod.fuel() - drainPercentPerTick(stats, Activity.DRILLING) * (multiplier - 1f)));
    }

    /** True at the beep threshold and below. */
    public static boolean isLow(float fuelPercent) {
        return fuelPercent <= PodTuning.DEFAULT.fuel().lowFuelPercent();
    }

    /** Called every pod tick, on the server only; a pod without power burns no fuel. */
    static void tick(PodEntity pod, PodStats stats) {
        if (!PodEvents.isPowered(pod)) {
            return;
        }
        // A fouled engine burns more; what the engine burns is what leaves soot (#1280).
        float before = pod.fuel();
        float fuel = Math.max(0f, before - drainPercentPerTick(stats, activity(pod)) * EngineSoot.burnMultiplier(pod));
        pod.setFuel(fuel);
        EngineSoot.burned(pod, (before - fuel) / PodTuning.DEFAULT.shell().fullFuel() * stats.tankLitres());
        if (fuel <= 0f) {
            pod.setStranded(true);
        }
    }

    /**
     * Takes up to {@code litres} from the pod's tank, never below {@code floorPercent}, and answers the litres it took. The pod's own
     * burn is {@link #tick}; this is what something else takes from it.
     */
    public static float drain(PodEntity pod, float litres, float floorPercent) {
        PodStats stats = PodStats.of(pod);
        float before = pod.fuel();
        float after = Math.max(Math.min(before, floorPercent), before - litresToPercent(stats, litres));
        pod.setFuel(after);
        return (before - after) / PodTuning.DEFAULT.shell().fullFuel() * stats.tankLitres();
    }

    private static Activity activity(PodEntity pod) {
        if (pod.drilling()) {
            return Activity.DRILLING;
        }
        boolean driving = pod.getDeltaMovement().horizontalDistanceSqr() > 0 || pod.flying();
        return driving ? Activity.MOVING : Activity.IDLE;
    }

    private static InteractionResult refuel(Player player, InteractionHand hand, PodEntity pod) {
        ItemStack stack = player.getItemInHand(hand);
        float full = PodTuning.DEFAULT.shell().fullFuel();
        // The client decides from the synced tag: the litres table is the server's alone.
        if (!stack.is(PodFuelItems.TAG) || pod.fuel() >= full) {
            return InteractionResult.PASS;
        }
        if (pod.level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        OptionalDouble litres = PodFuelItems.litresOf(stack);
        if (litres.isEmpty()) {
            // Tagged but with no litres entry: logged when the data loaded, and not fuel.
            return InteractionResult.PASS;
        }
        pour(pod, PodStats.of(pod), stack, (float) litres.getAsDouble());
        stack.consume(1, player);
        return InteractionResult.SUCCESS;
    }

    /**
     * Server only: pours one of {@code stack} into the tank and takes it from the stack, if the tank has room for all of
     * its litres; true if it did. The pod's fuel slot feeds the tank this way ({@link PodFuelSlot}), so none is wasted.
     */
    static boolean feedOne(PodEntity pod, ItemStack stack) {
        OptionalDouble litres = stack.isEmpty() ? OptionalDouble.empty() : PodFuelItems.litresOf(stack);
        if (litres.isEmpty()) {
            return false;
        }
        PodStats stats = PodStats.of(pod);
        float full = PodTuning.DEFAULT.shell().fullFuel();
        float roomLitres = (full - pod.fuel()) / full * stats.tankLitres();
        if (roomLitres < litres.getAsDouble()) {
            return false;
        }
        pour(pod, stats, stack, (float) litres.getAsDouble());
        stack.shrink(1);
        return true;
    }

    /** Adds one of {@code stack}'s {@code litres} to the tank, up to full, with its soot, and lifts a stranding. */
    private static void pour(PodEntity pod, PodStats stats, ItemStack stack, float litres) {
        float full = PodTuning.DEFAULT.shell().fullFuel();
        float added = litresToPercent(stats, litres);
        float before = pod.fuel();
        pod.setFuel(Math.min(full, before + added));
        // Only the litres that fit in the tank count towards its soot.
        EngineSoot.refuelled(pod, litres * (pod.fuel() - before) / added, PodFuelItems.sootOf(stack));
        pod.setStranded(false);
    }

    private static float litresToPercent(PodStats stats, float litres) {
        return litres / stats.tankLitres() * PodTuning.DEFAULT.shell().fullFuel();
    }
}
