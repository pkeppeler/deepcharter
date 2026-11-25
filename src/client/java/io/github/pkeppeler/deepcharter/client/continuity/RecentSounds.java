package io.github.pkeppeler.deepcharter.client.continuity;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The last few seconds of what the client played, so the record can skip on the person's own last sound (#1337): the sound events the
 * engine started, with where and how they played and the very file the engine picked, never the mixed audio. Fed by the sound manager as
 * the subtitles are, on every sound it starts, on the client thread (the subtitles' own list is not thread-safe either); the event's own
 * sounds, music, records and the ambience beds are left out: the skip is on something that happened to the person.
 */
final class RecentSounds implements SoundEventListener {
    /** The most sounds kept, whatever their age: a lava lake plays many. */
    private static final int CAPACITY = 64;

    static final RecentSounds INSTANCE = new RecentSounds();

    /** Kinds of sound the record never skips on: the score and the beds under everything, not an event. */
    private static final Set<SoundSource> BACKGROUND = EnumSet.of(SoundSource.MUSIC, SoundSource.RECORDS, SoundSource.AMBIENT);

    /**
     * One sound as it was started.
     *
     * @param file   the variant the engine picked for it, so a replay plays that same sample
     * @param pitch  the pitch it played at, the file's own pitch included
     * @param volume the volume it played at, the file's own volume included
     * @param millis the client's clock ({@code Util.getMillis()}) when it started
     */
    record Heard(
            Identifier event,
            Sound file,
            double x,
            double y,
            double z,
            boolean relative,
            SoundInstance.Attenuation attenuation,
            float pitch,
            float volume,
            long millis) {}

    private final Deque<Heard> heard = new ArrayDeque<>();

    private RecentSounds() {}

    @Override
    public void onPlaySound(SoundInstance sound, WeighedSoundEvents events, float range) {
        Sound file = sound.getSound();
        if (file == null
                || sound instanceof SequenceSound
                || BACKGROUND.contains(sound.getSource())
                || sound.getVolume() <= 0f) {
            return;
        }
        Heard entry = new Heard(
                sound.getIdentifier(),
                file,
                sound.getX(),
                sound.getY(),
                sound.getZ(),
                sound.isRelative(),
                sound.getAttenuation(),
                sound.getPitch(),
                sound.getVolume(),
                Util.getMillis());
        heard.addLast(entry);
        while (heard.size() > CAPACITY) {
            heard.removeFirst();
        }
    }

    /**
     * What the record skips on, at {@code now}: the latest sound of the last {@code windowMillis} played at {@code loudVolume} or more,
     * or, if none was that loud, the loudest of them. Empty when nothing played in the window.
     */
    Optional<Heard> last(long now, long windowMillis, float loudVolume) {
        @Nullable Heard loudest = null;
        for (Iterator<Heard> it = heard.descendingIterator(); it.hasNext(); ) {
            Heard entry = it.next();
            if (now - entry.millis() > windowMillis) {
                break;
            }
            if (entry.volume() >= loudVolume) {
                return Optional.of(entry);
            }
            if (loudest == null || entry.volume() > loudest.volume()) {
                loudest = entry;
            }
        }
        return Optional.ofNullable(loudest);
    }

    /** Forgets everything: a new world has no last sound. */
    void clear() {
        heard.clear();
    }
}
