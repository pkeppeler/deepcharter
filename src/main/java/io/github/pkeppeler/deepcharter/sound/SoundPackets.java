package io.github.pkeppeler.deepcharter.sound;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/** Plays a sound to one player and to no one else. */
final class SoundPackets {
    private SoundPackets() {}

    static void playTo(ServerPlayer player, DeepSound sound) {
        if (player.connection == null) {
            return;
        }
        Holder<SoundEvent> event = BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound.event());
        player.connection.send(new ClientboundSoundEntityPacket(
                event, SoundSource.PLAYERS, player, 1f, 1f, player.getRandom().nextLong()));
    }

    /** Stops a sound that {@link #playTo} started, wherever it has got to. */
    static void stopFor(ServerPlayer player, DeepSound sound) {
        if (player.connection == null) {
            return;
        }
        player.connection.send(new ClientboundStopSoundPacket(sound.event().location(), SoundSource.PLAYERS));
    }
}
