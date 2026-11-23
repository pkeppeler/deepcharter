package io.github.pkeppeler.deepcharter.suit;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Whether a player is on foot for the suit timer (#993): alive, not a spectator or in creative mode, and not riding a pod. The suit has
 * its own rule because the cave-in's and the foot grips' are private to them.
 */
public final class OnFoot {
    private OnFoot() {}

    public static boolean is(Player player) {
        return player.isAlive()
                && !player.isSpectator()
                && !player.isCreative()
                && !(player.getVehicle() instanceof PodEntity);
    }
}
