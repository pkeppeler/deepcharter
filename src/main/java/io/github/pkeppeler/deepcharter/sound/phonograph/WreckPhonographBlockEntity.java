package io.github.pkeppeler.deepcharter.sound.phonograph;

import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.sound.SoundTuning;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a wreck's phonograph is doing: playing its song or not, and whether the next player who comes close will start it. It looks for
 * players every {@link SoundTuning#phonographListenTicks()} ticks. {@link WreckSongArming} decides when it starts; a player's right click
 * stops it, or starts it again, and either way spends it until the long absence. Nothing here is saved: a restart cuts the sound off, and
 * the block comes back armed. A playback keeps its listeners, as the other phonographs' do, so a player who walks into range while it
 * plays hears it from the top, and its stop reaches only those it was sent to.
 */
public final class WreckPhonographBlockEntity extends BlockEntity {
    private final WreckSongArming arming = new WreckSongArming(SoundTuning.DEFAULT.wreckSongRearmTicks());
    private boolean playing;
    private int ticksLeft;
    private Set<UUID> listeners = new HashSet<>();

    public WreckPhonographBlockEntity(BlockPos pos, BlockState state) {
        super(PhonographRegistry.WRECK_BLOCK_ENTITY, pos, state);
    }

    public boolean isPlaying() {
        return playing;
    }

    /** Whether the next player to come close starts the song. */
    // test-seam: the off rule (a switch-off or an ended song is spent until the long absence) is observable only here
    public boolean isArmed() {
        return arming.isArmed();
    }

    /** How many players the song has been sent to. */
    public int listenerCount() {
        return listeners.size();
    }

    /** Server only. A player's hand: stops the song if it plays, and starts it if it does not. Either way it is spent. */
    public void toggle() {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        arming.byHand();
        if (isPlaying()) {
            silence(server);
        } else {
            start(server);
        }
    }

    private void start(ServerLevel server) {
        playing = true;
        ticksLeft = SoundTuning.DEFAULT.wreckSongTicks();
        listeners = PhonographSound.start(
                server, getBlockPos(), DeepSound.SONG_WRECK, SoundTuning.DEFAULT.wreckSongRange());
    }

    private void silence(ServerLevel server) {
        if (playing) {
            PhonographSound.stop(server.getServer(), DeepSound.SONG_WRECK, listeners);
            playing = false;
            ticksLeft = 0;
        }
        listeners = new HashSet<>();
    }

    static void tick(Level level, BlockPos pos, BlockState state, WreckPhonographBlockEntity phonograph) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        if (phonograph.playing && --phonograph.ticksLeft <= 0) {
            // The sound ends by itself, so nothing is sent.
            phonograph.playing = false;
            phonograph.listeners = new HashSet<>();
        }
        SoundTuning tuning = SoundTuning.DEFAULT;
        if (server.getGameTime() % tuning.phonographListenTicks() != 0) {
            return;
        }
        if (phonograph.isPlaying()) {
            PhonographSound.recruit(server, pos, DeepSound.SONG_WRECK, tuning.wreckSongRange(), phonograph.listeners);
        }
        boolean near = !PhonographSound.audience(server, pos, tuning.wreckSongTriggerRange())
                .isEmpty();
        if (phonograph.arming.look(near, tuning.phonographListenTicks()) && !phonograph.playing) {
            phonograph.start(server);
        }
    }
}
