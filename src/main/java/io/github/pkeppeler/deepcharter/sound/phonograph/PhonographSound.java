package io.github.pkeppeler.deepcharter.sound.phonograph;

import io.github.pkeppeler.deepcharter.sound.SoundTuning;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * Plays a recording at a block and stops it, with the game's own sound packets, so no client code is needed and nothing names the
 * recording on screen (a vanilla jukebox song shows "Now playing" and would need a client mixin to hide it). The start goes to the players
 * within {@link SoundTuning#phonographRange()} blocks; the stop goes to every player of the level, since one who walked away still
 * hears the tail. A stop silences that recording at every phonograph the player hears: two phonographs playing the same recording
 * stop together.
 */
final class PhonographSound {
    private PhonographSound() {}

    static void start(ServerLevel level, BlockPos pos, Recording recording) {
        Holder<SoundEvent> event =
                BuiltInRegistries.SOUND_EVENT.wrapAsHolder(recording.sound().event());
        Vec3 at = Vec3.atCenterOf(pos);
        double range = SoundTuning.DEFAULT.phonographRange();
        for (ServerPlayer player : level.players()) {
            if (player.connection != null && player.distanceToSqr(at) <= range * range) {
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

    static void stop(ServerLevel level, Recording recording) {
        for (ServerPlayer player : level.players()) {
            if (player.connection != null) {
                player.connection.send(
                        new ClientboundStopSoundPacket(recording.sound().event().location(), SoundSource.RECORDS));
            }
        }
    }
}
