package io.github.pkeppeler.deepcharter.charter;

import io.github.pkeppeler.deepcharter.layer.Depth;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/**
 * Raises each charter's deepest point from play: a member standing deeper than the charter has ever been, on foot or in a pod,
 * sets a new record. The depth is the one the HUD altimeter shows ({@link Depth#of}), so a teleport or a fall counts as well as a bore.
 * Only the surface and the layers count, and only a player in survival or adventure: a spectator or a creative player moves through rock
 * for free, and the Nether has a depth of its own that means nothing here.
 */
public final class DeepestPoint {
    private DeepestPoint() {}

    private static boolean counts(ServerPlayer player) {
        GameType mode = player.gameMode.getGameModeForPlayer();
        if (mode != GameType.SURVIVAL && mode != GameType.ADVENTURE) {
            return false;
        }
        return LayerChain.indexOf(player.level()
                        .dimensionTypeRegistration()
                        .unwrapKey()
                        .orElseThrow()
                        .identifier())
                .isPresent();
    }

    /** Checks every online member against their charter's record. A player on no charter, or unreadable saved charters, change nothing. */
    public static void poll(MinecraftServer server) {
        if (!Charters.isReadable(server)) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!counts(player)) {
                continue;
            }
            Charters.readableCharterOf(server, player.getUUID()).ifPresent(charter -> {
                int depth = Depth.of(player.level(), player.getBlockY());
                if (depth > charter.deepestPoint()) {
                    Charters.recordDeepestPoint(server, charter.id(), depth);
                }
            });
        }
    }
}
