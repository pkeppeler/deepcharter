package io.github.pkeppeler.deepcharter.sound.phonograph;

import io.github.pkeppeler.deepcharter.sound.SoundTuning;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * Plays a recording at a block and stops it, with the game's own sound packets, so no client code is needed and nothing names the
 * recording on screen (a vanilla jukebox song shows "Now playing" and would need a client mixin to hide it).
 *
 * <p>A playback keeps a set of its listeners, the players it has sent the start to. {@link #start} sends it to the players within
 * {@link SoundTuning#phonographRange()} blocks, and {@link #recruit} sends it again, from the top, to a player who has come into range
 * since. {@link #stop} goes to the listeners only, wherever they are now (the server's player list reaches one who left the level), so a
 * recording that two phonographs play stops at one and plays on at the other. The set is in memory and not saved: after a restart the
 * sound is gone as well.
 */
final class PhonographSound {
    private PhonographSound() {}

    /** The players of {@code level} within earshot of {@code pos}. */
    static Set<UUID> audience(ServerLevel level, BlockPos pos) {
        Vec3 at = Vec3.atCenterOf(pos);
        double range = SoundTuning.DEFAULT.phonographRange();
        Set<UUID> inRange = new HashSet<>();
        for (ServerPlayer player : level.players()) {
            if (player.connection != null && player.distanceToSqr(at) <= range * range) {
                inRange.add(player.getUUID());
            }
        }
        return inRange;
    }

    /** Sends the start to every player in range and returns them: the playback's first listeners. */
    static Set<UUID> start(ServerLevel level, BlockPos pos, Recording recording) {
        Set<UUID> listeners = new HashSet<>();
        recruit(level, pos, recording, listeners);
        return listeners;
    }

    /** Sends the start to each player in range who is not a listener yet, and makes them one. */
    static void recruit(ServerLevel level, BlockPos pos, Recording recording, Set<UUID> listeners) {
        Holder<SoundEvent> event =
                BuiltInRegistries.SOUND_EVENT.wrapAsHolder(recording.sound().event());
        Vec3 at = Vec3.atCenterOf(pos);
        for (UUID id : audience(level, pos)) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
            if (player != null && listeners.add(id)) {
                player.connection.send(new ClientboundSoundPacket(
                        event,
                        SoundSource.RECORDS,
                        at.x,
                        at.y,
                        at.z,
                        1f,
                        1f,
                        level.getRandom().nextLong()));
            }
        }
    }

    /** Tells each listener's client to stop the recording. A player who has left the server is gone with their sound. */
    static void stop(MinecraftServer server, Recording recording, Set<UUID> listeners) {
        for (UUID id : listeners) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null && player.connection != null) {
                player.connection.send(
                        new ClientboundStopSoundPacket(recording.sound().event().location(), SoundSource.RECORDS));
            }
        }
    }
}
