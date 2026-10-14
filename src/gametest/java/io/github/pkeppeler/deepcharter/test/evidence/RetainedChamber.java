package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The room of the Retained's evidence scenarios (#449): a long, closed chamber of layer 2's rock, dark but for what a scenario puts in it
 * (the world's mobs are off: {@code TestPacks} pins that for every world). A scenario holds one and delegates to it (a scenario extends {@code EvidenceScenario} directly). Layer 2 is where the rail line is and where the figures are met; its fog ends at 32 blocks. A chamber runs
 * along x from its floor block, {@code z} across it.
 */
final class RetainedChamber {
    /** The layer the chamber is cut into: the terrain there is noise, so the chamber is carved and sealed in the rock. */
    static final int LAYER = 2;

    static final BlockPos FLOOR = new BlockPos(2000, 100, 2000);
    static final double EYE = 1.62;
    private static final int SETTLE_POLLS = 5;

    private final ClientGameTestContext ctx;
    private final TestSingleplayerContext world;

    RetainedChamber(ClientGameTestContext ctx, TestSingleplayerContext world) {
        this.ctx = ctx;
        this.world = world;
    }

    /** Takes the player into the chamber's layer, with the mobs of the world off. Call once the world is up. */
    void enterLayer(double x, double y, double z, float yaw, float pitch) {
        ctx.waitTicks(40); // tick-wait: the world's spawn settles before the player is moved
        serverDo(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            player.teleportTo(layer(server), x, y, z, Set.of(), yaw, pitch, true);
        });
        ClientWait.until(
                ctx,
                "the player in layer " + LAYER,
                client -> client.level != null
                        && client.level.dimension().equals(LayerChain.dimension(LAYER))
                        && client.gui.screen() == null);
    }

    /** A stone chamber with its floor block at {@link #FLOOR}: {@code length} blocks along x, {@code 2 * halfWidth + 1} across, {@code height} high, sealed. */
    static void buildChamber(ServerLevel level, int length, int halfWidth, int height) {
        BlockPos west = FLOOR.offset(-1, 0, -halfWidth - 1);
        BlockPos east = FLOOR.offset(length, height + 1, halfWidth + 1);
        RoomCarver.carve(level, west, east, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        RoomCarver.carve(
                level, west.offset(1, 1, 1), east.offset(-1, -1, -1), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    /** A point in the chamber as a world position. */
    static Vec3 at(double x, double y, double z) {
        return new Vec3(FLOOR.getX() + x, FLOOR.getY() + y, FLOOR.getZ() + z);
    }

    void nightVision(boolean on) {
        serverDo(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            if (on) {
                player.addEffect(new MobEffectInstance(
                        MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false));
            } else {
                player.removeEffect(MobEffects.NIGHT_VISION);
            }
        });
    }

    /** Puts the camera (a creative flier that nothing can hurt) at {@code eye}, looking at {@code target}. */
    void look(Vec3 eye, Vec3 target) {
        Vec3 d = target.subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
        serverDo(server -> server.getPlayerList()
                .getPlayers()
                .getFirst()
                .teleportTo(layer(server), eye.x, eye.y - EYE, eye.z, Set.of(), yaw, pitch, true));
    }

    /**
     * Waits until the light has settled and every section in view has rendered, and both have held for {@value #SETTLE_POLLS} polls in a
     * row: block changes reach the client a little after the server makes them.
     */
    void settle() {
        int[] stable = {0};
        ClientWait.until(
                ctx,
                "the view settled",
                () -> {
                    boolean ready =
                            !serverGet(server -> layer(server).getLightEngine().hasLightWork())
                                    && ctx.computeOnClient(client -> client.levelRenderer.hasRenderedAllSections());
                    stable[0] = ready ? stable[0] + 1 : 0;
                    return stable[0] >= SETTLE_POLLS;
                },
                () -> ctx.computeOnClient(ClientWait::describe));
    }

    static ServerLevel layer(MinecraftServer server) {
        return server.getLevel(LayerChain.dimension(LAYER));
    }

    void serverDo(Consumer<MinecraftServer> action) {
        world.getServer().runOnServer(action::accept);
    }

    <T> T serverGet(Function<MinecraftServer, T> query) {
        return world.getServer().computeOnServer(query::apply);
    }
}
