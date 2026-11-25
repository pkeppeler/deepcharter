package io.github.pkeppeler.deepcharter.client.continuity;

import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import org.jspecify.annotations.Nullable;

/**
 * A sound of the Continuity Event: the laugh, a pass of the skipping record, the needle. Only these play while {@link Silence} holds; the
 * engine refuses everything else ({@code SoundEngineMixin}). They play in the master slider, never the one the original sound had, so the
 * player's own volume for blocks or hostile mobs does not turn the laugh down.
 */
final class SequenceSound extends AbstractSoundInstance {
    /** For a replay, the very file the heard sound played, at its own volume and pitch: those are in the heard values already. */
    private @Nullable Sound file;

    private SequenceSound(Identifier event, float volume, float pitch) {
        super(event, SoundSource.MASTER, RandomSource.create());
        this.volume = volume;
        this.pitch = pitch;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.relative = true;
    }

    /** {@code event} at the listener, as a UI sound plays. */
    static SequenceSound atListener(Identifier event, float volume, float pitch) {
        return new SequenceSound(event, volume, pitch);
    }

    /**
     * A replay of {@code heard}: the same file it played (not a new pick among the event's variants), where it was heard (or at the
     * listener, if it was played there), at the volume it was heard at and {@code pitch}.
     */
    static SequenceSound replay(RecentSounds.Heard heard, float pitch) {
        SequenceSound sound = new SequenceSound(heard.event(), heard.volume(), pitch);
        Sound played = heard.file();
        sound.file = new Sound(
                played.getLocation(),
                ConstantFloat.of(1f),
                ConstantFloat.of(1f),
                played.getWeight(),
                played.getType(),
                played.shouldStream(),
                played.shouldPreload(),
                played.getAttenuationDistance());
        if (!heard.relative()) {
            sound.relative = false;
            sound.attenuation = heard.attenuation();
            sound.x = heard.x();
            sound.y = heard.y();
            sound.z = heard.z();
        }
        return sound;
    }

    @Override
    public @Nullable WeighedSoundEvents getOrResolve(SoundManager manager) {
        WeighedSoundEvents events = super.getOrResolve(manager);
        if (file != null && sound != SoundManager.EMPTY_SOUND) {
            sound = file;
        }
        return events;
    }
}
