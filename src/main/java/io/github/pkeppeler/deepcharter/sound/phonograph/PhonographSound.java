package io.github.pkeppeler.deepcharter.sound.phonograph;

import io.github.pkeppeler.deepcharter.sound.DeepSound;
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
 * Plays a sound at a place and stops it, with the game's own sound packets, so no client code is needed and nothing names the
 * recording on screen (a vanilla jukebox song shows "Now playing" and would need a client mixin to hide it).
 *
 * <p>A playback keeps a set of its listeners, the players it has sent the start to. {@link #start} sends it to the players within
 * the range it is given, and {@link #recruit} sends it again, from the top, to a player who has come into range
 * since. {@link #stop} goes to the listeners only, wherever they are now (the server's player list reaches one who left the level), so a
 * sound that two phonographs play stops at one and plays on at the other. The set is in memory and not saved: after a restart the
 * sound is gone as well.
 */
final class PhonographSound {
    private PhonographSound() {}

    /** The players of {@code level} within {@code range} blocks of {@code pos}. */
    static Set<UUID> audience(ServerLevel level, BlockPos pos, double range) {
        Vec3 at = Vec3.atCenterOf(pos);
        Set<UUID> inRange = new HashSet<>();
        for (ServerPlayer player : level.players()) {
            if (player.connection != null && player.distanceToSqr(at) <= range * range) {
                inRange.add(player.getUUID());
            }
        }
        return inRange;
    }

    // Takes a DeepSound, a place and a range, not a Recording: #861's wreck phonograph plays its own sound through
    // this call.
    /** Sends the start to every player in range and returns them: the playback's first listeners. */
    static Set<UUID> start(ServerLevel level, BlockPos pos, DeepSound sound, double range) {
        Set<UUID> listeners = new HashSet<>();
        recruit(level, pos, sound, range, listeners);
        return listeners;
    }

    /** Sends the start to each player in range who is not a listener yet, and makes them one. */
    static void recruit(ServerLevel level, BlockPos pos, DeepSound sound, double range, Set<UUID> listeners) {
        Holder<SoundEvent> event = BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound.event());
        Vec3 at = Vec3.atCenterOf(pos);
        for (UUID id : audience(level, pos, range)) {
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

    /** Tells each listener's client to stop the sound. A player who has left the server is gone with their sound. */
    static void stop(MinecraftServer server, DeepSound sound, Set<UUID> listeners) {
        for (UUID id : listeners) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null && player.connection != null) {
                player.connection.send(
                        new ClientboundStopSoundPacket(sound.event().location(), SoundSource.RECORDS));
            }
        }
    }
}
