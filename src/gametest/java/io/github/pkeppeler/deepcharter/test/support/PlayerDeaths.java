package io.github.pkeppeler.deepcharter.test.support;

import java.util.function.Function;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;

/** Kills the singleplayer player on the server, in a client GameTest. */
public final class PlayerDeaths {
    private PlayerDeaths() {}

    /**
     * Strikes the player with {@code damage} until they are dead. The server does not let a player be hurt until their client says it
     * has loaded the world or 60 server ticks pass, after the join and again after each return to work; a server running behind
     * stretches that, so one blow can miss (#1462).
     */
    public static void strikeUntilDead(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            Function<DamageSources, DamageSource> damage) {
        ClientWait.until(
                context,
                "the player dead",
                () -> singleplayer.getServer().computeOnServer(server -> {
                    ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                    if (!player.isDeadOrDying()) {
                        player.hurtServer(
                                player.level(), damage.apply(player.level().damageSources()), 1000f);
                    }
                    return player.isDeadOrDying();
                }),
                () -> singleplayer.getServer().computeOnServer(server -> {
                    ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                    return "a player who could not be hurt: health " + player.getHealth() + ", " + player.gameMode()
                            + ", client loaded " + player.connection.hasClientLoaded() + ", changing dimension "
                            + player.isChangingDimension();
                }));
    }
}
