package io.github.pkeppeler.deepcharter.pod;

import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;

/**
 * The heat shield (#482, #712), rung 3 of the lava ladder: while lava touches a shielded pod, each tick's lava damage is soaked by burning a slag brick,
 * {@link PodHeatShieldTuning.Tier#hullPerBrick} of hull at a time, in place of the hull. The bricks are the liner's, rack first, then the seated pilot's pack if
 * {@link PodLining#mayUseStores} allows; the top tier ({@link PodHeatShieldTuning.Tier#packFirst}) takes the pack first and leaves the rack for lining. The rack
 * burns for any pilot or none; only the pack is gated (decision A, #482). With no brick left the lava takes the hull at the full rate. It soaks lava only.
 */
public final class PodHeatShield {
    /** Hull the brick now burning can still soak, for each shielded pod, server side. A pod with no entry is on a fresh brick. */
    private static final Map<PodEntity, Float> SOAK = new WeakHashMap<>();

    private PodHeatShield() {}

    public static void init() {
        PodEvents.AFTER_TICK.register(PodHeatShield::afterTick);
    }

    /** The tier of the pod's heat shield: 0 for none, a void part or an unreadable one. */
    public static int tier(PodEntity pod) {
        return PodComponents.effectiveTier(pod, ComponentTrack.HEAT_SHIELD);
    }

    /** The lava hull damage of one tick that gets past the shield, from the {@code damage} the lava deals. Burns the bricks that soak the rest. Server only. */
    public static float absorb(PodEntity pod, float damage) {
        int tier = tier(pod);
        if (tier == 0) {
            return damage;
        }
        PodHeatShieldTuning.Tier shield = PodHeatShieldTuning.DEFAULT.tier(tier);
        float soak = SOAK.getOrDefault(pod, 0f);
        while (soak < damage && burnBrick(pod, shield.packFirst())) {
            soak += shield.hullPerBrick();
        }
        float absorbed = Math.min(soak, damage);
        SOAK.put(pod, soak - absorbed);
        return damage - absorbed;
    }

    /** Burns one brick and says so; false when there is none to burn. The pack is tried first when {@code packFirst}, else last. */
    private static boolean burnBrick(PodEntity pod, boolean packFirst) {
        boolean burned = packFirst ? burnFromPack(pod) || burnFromRack(pod) : burnFromRack(pod) || burnFromPack(pod);
        if (burned) {
            pod.level()
                    .playSound(
                            null,
                            pod.getX(),
                            pod.getY(),
                            pod.getZ(),
                            DeepSound.POD_GLAZE_BURN.event(),
                            SoundSource.NEUTRAL);
        }
        return burned;
    }

    private static boolean burnFromRack(PodEntity pod) {
        if (PodLining.readable(pod).filter(state -> state.bricks() > 0).isEmpty()) {
            return false;
        }
        PodLining.modify(
                pod,
                state -> new PodLining.State(
                        state.spoil(), state.bricks() - 1, state.used(), state.working(), state.dry()));
        return true;
    }

    private static boolean burnFromPack(PodEntity pod) {
        Optional<ServerPlayer> pack = pilotWithPack(pod);
        if (pack.isEmpty()) {
            return false;
        }
        PodLining.takeCarried(pack.get().getInventory());
        return true;
    }

    /** The pilot, if the pod has a seated one who may use its stores and carries a brick. */
    private static Optional<ServerPlayer> pilotWithPack(PodEntity pod) {
        return Optional.ofNullable(pod.getControllingPassenger())
                .filter(ServerPlayer.class::isInstance)
                .map(ServerPlayer.class::cast)
                .filter(pilot -> PodLining.mayUseStores(pod, pilot) && PodLining.carried(pilot.getInventory()) > 0);
    }

    /** Keeps the count the HUD reads: the bricks the shield can burn now, 0 for a pod with none. */
    private static void afterTick(PodEntity pod) {
        if (tier(pod) == 0) {
            SOAK.remove(pod);
            pod.setGlazeBricks(0);
            return;
        }
        int pack = pod.getControllingPassenger() instanceof ServerPlayer pilot && PodLining.mayUseStores(pod, pilot)
                ? PodLining.carried(pilot.getInventory())
                : 0;
        pod.setGlazeBricks(PodLining.of(pod).bricks() + pack);
    }
}
