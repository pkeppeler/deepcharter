package io.github.pkeppeler.deepcharter.pod;

import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerChain.Band;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Names the tow cable as the rescue when a pod needs one (#1600): a pod stranded in a layer, or left in one by its
 * pilot low on fuel, tells the online members of its owner charter, once, that someone with a tow cable can tow it
 * home. Nobody else comes for it ({@code docs/adr/0104}), so the line is the only pointer the game gives at the
 * moment it matters. A pod on the surface is a walk, a pod with a pilot and fuel needs no rescue, and a pod already
 * on a cable is being rescued: each says nothing. A wreck says it in its own report ({@code Wrecks}). Server only.
 */
public final class PodRescueHint {
    /** What the last tick saw of a pod: a player aboard, and the charter told for this stay in a layer. */
    private record Seen(boolean occupied, boolean told) {}

    // An ephemeral throttle, not saved state (m2-rules: pod state is an attachment): a reload may repeat one line.
    /** The last look at each pod, in a map that holds a pod only weakly, so a pod that goes is dropped with it. */
    private static final Map<PodEntity, Seen> SEEN = Collections.synchronizedMap(new WeakHashMap<>());

    private PodRescueHint() {}

    public static void init() {
        PodEvents.AFTER_TICK.register(PodEvents.OBSERVE, PodRescueHint::onTick);
    }

    private static void onTick(PodEntity pod) {
        if (!(pod.level() instanceof ServerLevel level) || Wrecks.isWreck(pod)) {
            SEEN.remove(pod);
            return;
        }
        boolean occupied = pod.hasPassenger(Player.class::isInstance);
        Seen before = SEEN.get(pod);
        boolean wasOccupied = before == null ? occupied : before.occupied();
        boolean told = before != null && before.told();
        OptionalInt layer = LayerChain.layerAt(level, pod.getBlockY()).stream()
                .mapToInt(Band::layer)
                .findFirst();
        if (layer.isEmpty() || PodTowing.isTowed(pod)) {
            told = false;
        } else if (!told) {
            // A pilot who steps out of a healthy pod is no emergency: only a pod low on fuel is left in need.
            boolean left = wasOccupied && !occupied && PodFuel.isLow(pod.fuel());
            if (pod.stranded() || left) {
                tell(
                        level,
                        pod,
                        layer.getAsInt(),
                        pod.stranded() ? "deepcharter.pod.rescue.stranded" : "deepcharter.pod.rescue.left");
                told = true;
            }
        }
        SEEN.put(pod, new Seen(occupied, told));
    }

    /** Tells the online members of the pod's owner charter which pod, where, and that a tow cable brings it home. */
    private static void tell(ServerLevel level, PodEntity pod, int layer, String key) {
        BlockPos at = pod.blockPosition();
        Component name = pod.hasCustomName()
                ? Objects.requireNonNull(pod.getCustomName(), "a pod with a custom name has one")
                : Component.translatable("deepcharter.pod.rescue.unnamed");
        Component line = Component.translatable(key, name, layer, at.getX(), at.getY(), at.getZ());
        PodComponents.ownerCharter(pod).ifPresent(charter -> {
            var players = level.getServer().getPlayerList();
            for (var member : charter.roster()) {
                ServerPlayer player = players.getPlayer(member);
                if (player != null) {
                    player.sendSystemMessage(line);
                }
            }
        });
    }
}
