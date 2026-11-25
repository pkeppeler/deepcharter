package io.github.pkeppeler.deepcharter.sound.phonograph;

import io.github.pkeppeler.deepcharter.sound.SoundTuning;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * The state of a phonograph: the cylinder in it, and what it is playing. A playback is not saved: a restart cuts the sound off, so a
 * block that came back "playing" would be silent. A chunk that unloads mid-playback does not stop the sound for a client that already
 * has it: the client plays out the tail, and the block starts silent when it loads. The colony's phonograph holds no cylinder; it plays the next
 * recording of {@link Recording#COLONY_RACK} each time, and remembers where it is in the rack and the night it last started by itself
 * ({@code ColonyPhonograph}). The block entity is saved as plain values of vanilla codecs, not through {@code Versioned}: it is neither a
 * SavedData nor an attachment. The cylinder is an {@code ItemStack}, saved with the item codec.
 */
public final class PhonographBlockEntity extends BlockEntity {
    private static final long NEVER = Long.MIN_VALUE;

    private ItemStack cylinder = ItemStack.EMPTY;
    private @Nullable Recording playing;
    private int ticksLeft;
    /** The players this playback has sent its start to, and will send its stop to. In memory only. */
    private Set<UUID> listeners = new HashSet<>();
    /** The colony's rack: the index of the recording the next playback starts. */
    private int rack;

    private long lastAutoNight = NEVER;

    public PhonographBlockEntity(BlockPos pos, BlockState state) {
        super(PhonographRegistry.BLOCK_ENTITY, pos, state);
    }

    public boolean isColony() {
        return getBlockState().getBlock() instanceof PhonographBlock block && block.isColony();
    }

    public Optional<Recording> playing() {
        return Optional.ofNullable(playing);
    }

    public boolean isPlaying() {
        return playing().isPresent();
    }

    /** The cylinder in it (a copy), empty when none. The colony's holds none. */
    public ItemStack cylinder() {
        return cylinder.copy();
    }

    /** The night, by the sky timeline's period count, on which the colony's phonograph last started by itself; empty if it never has. */
    public Optional<Long> lastAutoNight() {
        return lastAutoNight == NEVER ? Optional.empty() : Optional.of(lastAutoNight);
    }

    public void markAutoNight(long night) {
        lastAutoNight = night;
        setChanged();
    }

    /**
     * Server only. Puts the cylinder {@code player} holds in {@code hand} in, gives back the one that was in, and plays it. The colony's
     * rack is shut: it takes nothing.
     */
    public void insert(Player player, InteractionHand hand) {
        if (isColony()) {
            player.sendOverlayMessage(Component.translatable("deepcharter.phonograph.rack_shut"));
            return;
        }
        ItemStack held = player.getItemInHand(hand);
        ItemStack inserted = held.copyWithCount(1);
        held.consume(1, player);
        ItemStack old = cylinder;
        cylinder = inserted;
        if (!old.isEmpty() && !player.getInventory().add(old) && level instanceof ServerLevel server) {
            player.spawnAtLocation(server, old);
        }
        play();
    }

    /** Server only. Stops the playback and pops the cylinder out above the block. The colony's rack gives nothing. */
    public void eject(Player player) {
        if (isColony()) {
            player.sendOverlayMessage(Component.translatable("deepcharter.phonograph.rack_shut"));
            return;
        }
        if (cylinder.isEmpty() || !(level instanceof ServerLevel server)) {
            return;
        }
        silence(server);
        ItemStack out = cylinder();
        cylinder = ItemStack.EMPTY;
        setChanged();
        showLoaded();
        Block.popResource(server, getBlockPos().above(), out);
    }

    /** Server only. Stops the playback if there is one, and starts one if there is not. */
    public void toggle() {
        if (playing != null) {
            stop();
        } else {
            play();
        }
    }

    /**
     * Server only. Starts a playback, over any that is still going: the cylinder's recording, or for the colony's the next of its rack.
     * Returns false when there is nothing to play (no cylinder).
     */
    public boolean play() {
        if (!(level instanceof ServerLevel server)) {
            return false;
        }
        Recording next;
        if (isColony()) {
            next = Recording.COLONY_RACK.get(rack);
            rack = (rack + 1) % Recording.COLONY_RACK.size();
        } else if (cylinder.isEmpty()) {
            return false;
        } else {
            next = Recording.of(cylinder);
        }
        silence(server);
        playing = next;
        ticksLeft = next.lengthTicks();
        setChanged();
        showLoaded();
        listeners = PhonographSound.start(server, getBlockPos(), next.sound(), SoundTuning.DEFAULT.phonographRange());
        return true;
    }

    /** Server only. Stops the playback. */
    public void stop() {
        if (level instanceof ServerLevel server && playing != null) {
            silence(server);
            showLoaded();
        }
    }

    /** How many players the playback has sent its start to: none when nobody was within earshot and nobody has come since. */
    public int listenerCount() {
        return listeners.size();
    }

    /** Whether any player is within earshot of this phonograph now. */
    public boolean hasAudience() {
        return level instanceof ServerLevel server
                && !PhonographSound.audience(server, getBlockPos(), SoundTuning.DEFAULT.phonographRange())
                        .isEmpty();
    }

    /** Ends a playback and cuts its sound off for its listeners, leaving the block state alone. */
    private void silence(ServerLevel server) {
        if (playing != null) {
            PhonographSound.stop(server.getServer(), playing.sound(), listeners);
            playing = null;
            ticksLeft = 0;
        }
        listeners = new HashSet<>();
    }

    /** Draws the cylinder on the spindle when a player's phonograph holds one, and when the colony's rack plays. */
    private void showLoaded() {
        BlockState state = getBlockState();
        boolean loaded = isColony() ? playing != null : !cylinder.isEmpty();
        if (level != null && state.getValue(PhonographBlock.LOADED) != loaded) {
            level.setBlock(getBlockPos(), state.setValue(PhonographBlock.LOADED, loaded), Block.UPDATE_ALL);
        }
    }

    static void tick(Level level, BlockPos pos, BlockState state, PhonographBlockEntity phonograph) {
        Recording recording = phonograph.playing;
        if (recording == null || !(level instanceof ServerLevel server)) {
            return;
        }
        if (--phonograph.ticksLeft <= 0) {
            // The sound ends by itself, so nothing is sent.
            phonograph.playing = null;
            phonograph.listeners = new HashSet<>();
            phonograph.showLoaded();
        } else if (server.getGameTime() % SoundTuning.DEFAULT.phonographListenTicks() == 0) {
            PhonographSound.recruit(
                    server, pos, recording.sound(), SoundTuning.DEFAULT.phonographRange(), phonograph.listeners);
        }
    }

    /** The block is going: its sound stops and its cylinder drops. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level instanceof ServerLevel server) {
            silence(server);
            if (!cylinder.isEmpty()) {
                Block.popResource(server, pos, cylinder());
                cylinder = ItemStack.EMPTY;
            }
        }
        super.preRemoveSideEffects(pos, state);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (!cylinder.isEmpty()) {
            output.store("cylinder", ItemStack.CODEC, cylinder);
        }
        output.putInt("rack", rack);
        if (lastAutoNight != NEVER) {
            output.putLong("last_auto_night", lastAutoNight);
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        cylinder = input.read("cylinder", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        rack = Math.floorMod(input.getIntOr("rack", 0), Recording.COLONY_RACK.size());
        lastAutoNight = input.getLongOr("last_auto_night", NEVER);
    }
}
