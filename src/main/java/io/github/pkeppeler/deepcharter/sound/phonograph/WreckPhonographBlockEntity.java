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
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * What a wreck's phonograph is doing: playing its song or not, and when it was last spent ({@link WreckSongArming}). Only the two times are saved;
 * a playback is not, since a restart cuts the sound off. A playback keeps its listeners, as the other phonographs' do, so a player who walks
 * into range hears it from the top, and its stop reaches only those it was sent to.
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

    /** Server only. A player's hand: stops the song if it plays, and starts it if it does not. Either way it is spent. */
    public void toggle() {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        arming.byHand(server.getGameTime());
        setChanged();
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
        PhonographSound.stop(server.getServer(), DeepSound.SONG_WRECK, listeners);
        playing = false;
        ticksLeft = 0;
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
        long spentBefore = phonograph.arming.spentAt();
        long nearBefore = phonograph.arming.lastNearAt();
        boolean startNow = phonograph.arming.look(near, server.getGameTime());
        if (phonograph.arming.spentAt() != spentBefore || phonograph.arming.lastNearAt() != nearBefore) {
            phonograph.setChanged();
        }
        if (startNow && !phonograph.playing) {
            phonograph.start(server);
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putLong("spent_at", arming.spentAt());
        output.putLong("last_near_at", arming.lastNearAt());
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        arming.restore(
                input.getLongOr("spent_at", WreckSongArming.NEVER),
                input.getLongOr("last_near_at", WreckSongArming.NEVER));
    }
}
