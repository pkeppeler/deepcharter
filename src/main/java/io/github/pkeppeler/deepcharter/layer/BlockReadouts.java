package io.github.pkeppeler.deepcharter.layer;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * What the readout above the hotbar says about the block looked at (#859): the block's name and, for some blocks, a
 * short hint on how to deal with them. The client draws it ({@code BlockReadoutHud}); this class only decides the
 * words, from the block state and its place in the level, so a server GameTest can check them.
 *
 * <p>Two rules keep it from telling more than the eye sees. It reads only the block whose face the hit landed on, never anything behind it;
 * and a block that is drawn as another ({@link #disguise}) reads as that one, hint and all, so the readout never gives away what the world
 * hides.
 *
 * <p>A feature adds its words from its own init, on {@link #SHARED}: {@link #addHint} for a hint that depends on the state or the place (the
 * depth of a rock, a block state's property), {@link #disguise} for a block that must read as another. The first provider that answers wins,
 * in the order they were added.
 */
public final class BlockReadouts {
    /** The readouts of the game: every feature's providers register here from its init. */
    public static final BlockReadouts SHARED = new BlockReadouts();

    /**
     * What the readout shows.
     *
     * @param name the block's name
     * @param hint a second, short line on how to deal with it, if there is one
     */
    public record Readout(Component name, Optional<Component> hint) {}

    /** Answers a hint for a block, or empty when it has none for it. */
    @FunctionalInterface
    public interface HintProvider {
        /**
         * The hint for {@code state} at {@code pos}, if this provider has one.
         *
         * @param state the block as it reads (the one it is disguised as, if any)
         * @param level the level the block is in, for a hint that depends on depth or surroundings
         * @param pos where the block is
         * @param viewer who looks, for a hint that depends on their game mode or what they hold
         */
        Optional<Component> hint(BlockState state, Level level, BlockPos pos, Player viewer);
    }

    private final List<HintProvider> hints = new CopyOnWriteArrayList<>();
    private final Map<Block, Block> disguises = new ConcurrentHashMap<>();

    /** Adds a hint provider, behind every provider added before it. */
    public void addHint(HintProvider provider) {
        hints.add(provider);
    }

    /** Makes {@code real} read as {@code shownAs}: its name and its hint are those of the block it is drawn as. */
    public void disguise(Block real, Block shownAs) {
        if (disguises.putIfAbsent(real, shownAs) != null) {
            throw new IllegalStateException(real + " is already disguised");
        }
    }

    /**
     * The readout for what a crosshair hit: empty for a miss or an entity, and otherwise the readout of the one block hit. The hit's
     * position is the only one read.
     */
    public Optional<Readout> resolve(Level level, HitResult hit, Player viewer) {
        if (!(hit instanceof BlockHitResult blockHit) || blockHit.getType() != HitResult.Type.BLOCK) {
            return Optional.empty();
        }
        BlockPos pos = blockHit.getBlockPos();
        return readout(level.getBlockState(pos), level, pos, viewer);
    }

    /** The readout of the block {@code state} at {@code pos} in {@code level} as {@code viewer} sees it; empty for air and fluid. */
    public Optional<Readout> readout(BlockState state, Level level, BlockPos pos, Player viewer) {
        if (state.isAir() || state.getBlock() instanceof LiquidBlock) {
            return Optional.empty();
        }
        Block shown = disguises.getOrDefault(state.getBlock(), state.getBlock());
        BlockState shownState = shown == state.getBlock() ? state : shown.defaultBlockState();
        Optional<Component> hint = hints.stream()
                .flatMap(provider -> provider.hint(shownState, level, pos, viewer).stream())
                .findFirst();
        return Optional.of(new Readout(shown.getName(), hint));
    }
}
