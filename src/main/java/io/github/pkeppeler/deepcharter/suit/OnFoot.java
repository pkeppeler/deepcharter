package io.github.pkeppeler.deepcharter.suit;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Whether a player is on foot for the suit timer (#993): alive, not a spectator or in creative mode, and not riding a pod; or, with the same
 * first three, seated in one. The suit has its own rule because the cave-in's and the foot grips' are private to them.
 */
public final class OnFoot {
    private OnFoot() {}

    public static boolean is(Player player) {
        return inPlay(player) && !(player.getVehicle() instanceof PodEntity);
    }

    /** Whether {@code player} is in play and seated in a pod: the suit rests. */
    static boolean seated(Player player) {
        return inPlay(player) && player.getVehicle() instanceof PodEntity;
    }

    private static boolean inPlay(Player player) {
        return player.isAlive() && !player.isSpectator() && !player.isCreative();
    }
}
