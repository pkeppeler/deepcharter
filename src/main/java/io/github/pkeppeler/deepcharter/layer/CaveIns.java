package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The cave-in (#987): the roof of natural rock over a person on foot comes down. It starts with a creak, {@link CaveInTuning#warnTicks()}
 * before the fall, and every roof block within {@link CaveInTuning#boltReach()} of a roof bolt ({@link RoofBoltBlock}) holds. The roof is
 * read again at the fall, so a bolt set while the roof creaks saves it. Nothing a person built is a roof: only natural rock ({@link
 * HazardBlocks#NATURAL_ROCK}) falls, and it falls as rock that hurts what it lands on and lies where it lands, so a cave-in blocks a
 * stretch of tunnel and does not erase it.
 *
 * <p>What starts one: a blast ({@link #blasted}), which shakes the roof over each person on foot near it. Nothing starts one by itself yet,
 * and {@link #shake} is the call an earthquake or a creature would make. A cave-in started and not yet fallen is held in memory, not saved:
 * a server that stops in those few seconds forgets it.
 */
public final class CaveIns {
    /** A cave-in that has creaked and not yet fallen: the place it was started, as a person's feet, and the tick it falls. */
    private record Pending(BlockPos feet, long fallsAt) {}

    private static final Map<ResourceKey<Level>, List<Pending>> PENDING = new HashMap<>();
    /** A cave-in started within this many blocks of one that is already coming is the same cave-in. */
    private static final int SAME_PLACE_BLOCKS = 2;

    private CaveIns() {}

    public static void init() {
        ServerTickEvents.END_LEVEL_TICK.register(CaveIns::tick);
    }

    /**
     * A blast went off at {@code centre}: the roof over each person on foot within {@link CaveInTuning#shakeReach()} of it is shaken. A
     * person in a pod is not: the pod's own hull is what the roof falls on.
     */
    public static void blasted(ServerLevel level, Vec3 centre) {
        double reach = CaveInTuning.DEFAULT.shakeReach();
        for (ServerPlayer player : level.players()) {
            if (onFoot(player) && player.position().distanceToSqr(centre) <= reach * reach) {
                shake(level, player.blockPosition());
            }
        }
    }

    private static boolean onFoot(ServerPlayer player) {
        return !player.isSpectator() && !(player.getVehicle() instanceof PodEntity);
    }

    /**
     * Starts a cave-in over {@code feet}, the stretch of roof round a person standing there. Returns whether there was any loose roof: false
     * when the roof is bolted, built, or not there, and also when a cave-in is already coming over the same place.
     */
    public static boolean shake(ServerLevel level, BlockPos feet) {
        List<Pending> pending = PENDING.computeIfAbsent(level.dimension(), key -> new ArrayList<>());
        for (Pending other : pending) {
            if (other.feet().distSqr(feet) <= SAME_PLACE_BLOCKS * SAME_PLACE_BLOCKS) {
                return false;
            }
        }
        if (looseRoof(level, feet).isEmpty()) {
            return false;
        }
        pending.add(new Pending(feet.immutable(), level.getGameTime() + CaveInTuning.DEFAULT.warnTicks()));
        creak(level, feet);
        tellNear(level, feet, "deepcharter.layer.cave_in_warn");
        return true;
    }

    /**
     * The roof that is loose in the stretch round {@code feet}: for each column within {@link CaveInTuning#stretchRadius()}, the first solid
     * block over the person's head within {@link CaveInTuning#ceilingReach()}, when it is natural rock with air under it and no roof bolt
     * within reach. Blocks in a chunk that is not loaded are not read.
     */
    public static List<BlockPos> looseRoof(ServerLevel level, BlockPos feet) {
        CaveInTuning tuning = CaveInTuning.DEFAULT;
        int radius = tuning.stretchRadius();
        List<BlockPos> roof = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                for (int up = 2; up <= tuning.ceilingReach(); up++) {
                    BlockPos at = feet.offset(dx, up, dz);
                    if (!loaded(level, at)) {
                        break;
                    }
                    BlockState state = level.getBlockState(at);
                    if (state.isAir() || !state.getFluidState().isEmpty()) {
                        continue;
                    }
                    if (state.is(HazardBlocks.NATURAL_ROCK)
                            && level.getBlockState(at.below()).isAir()
                            && !isBolted(level, at)) {
                        roof.add(at);
                    }
                    break;
                }
            }
        }
        return roof;
    }

    /** Whether the chunk of {@code at} is loaded: a cave-in never loads one. */
    private static boolean loaded(ServerLevel level, BlockPos at) {
        return level.getChunkSource().hasChunk(at.getX() >> 4, at.getZ() >> 4);
    }

    /** Whether a roof bolt holds {@code roof}: one within {@link CaveInTuning#boltReach()} of it. */
    public static boolean isBolted(ServerLevel level, BlockPos roof) {
        double reach = CaveInTuning.DEFAULT.boltReach();
        int span = (int) Math.ceil(reach);
        for (BlockPos at : BlockPos.betweenClosed(roof.offset(-span, -span, -span), roof.offset(span, span, span))) {
            if (at.distSqr(roof) <= reach * reach
                    && loaded(level, at)
                    && level.getBlockState(at).is(LayerBlocks.ROOF_BOLT)) {
                return true;
            }
        }
        return false;
    }

    /** Whether another roof bolt hangs within {@code spacing} blocks of the cell {@code at}. */
    public static boolean boltNear(ServerLevel level, BlockPos at, double spacing) {
        int span = (int) Math.ceil(spacing);
        for (BlockPos other : BlockPos.betweenClosed(at.offset(-span, -span, -span), at.offset(span, span, span))) {
            if (other.distSqr(at) <= spacing * spacing
                    && loaded(level, other)
                    && level.getBlockState(other).is(LayerBlocks.ROOF_BOLT)) {
                return true;
            }
        }
        return false;
    }

    /** One tick of every cave-in in {@code level}: a creak every {@link CaveInTuning#cueTicks()}, and the fall (or the all-clear) at its time. */
    private static void tick(ServerLevel level) {
        List<Pending> pending = PENDING.get(level.dimension());
        if (pending == null || pending.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        Iterator<Pending> each = pending.iterator();
        while (each.hasNext()) {
            Pending cave = each.next();
            if (now >= cave.fallsAt()) {
                each.remove();
                fall(level, cave.feet());
            } else if ((cave.fallsAt() - now) % CaveInTuning.DEFAULT.cueTicks() == 0) {
                creak(level, cave.feet());
            }
        }
    }

    /** Tells each person on foot within the stretch of {@code feet} the line under {@code key}. */
    private static void tellNear(ServerLevel level, BlockPos feet, String key) {
        int radius = CaveInTuning.DEFAULT.stretchRadius();
        for (ServerPlayer player : level.players()) {
            if (onFoot(player) && player.blockPosition().distSqr(feet) <= radius * radius) {
                player.sendOverlayMessage(Component.translatable(key));
            }
        }
    }

    /** The roof creaks and sheds dust over the stretch. */
    private static void creak(ServerLevel level, BlockPos feet) {
        for (BlockPos at : looseRoof(level, feet)) {
            level.sendParticles(
                    ParticleTypes.ASH, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 3, 0.3, 0.05, 0.3, 0.01);
        }
        // PLACEHOLDER sound until the sound pass: the crust's creak, since the roof speaks the way the grain does. It
        // is played at each
        // person under the roof, as the crust's is, and where it was started when no one is.
        boolean heard = false;
        int radius = CaveInTuning.DEFAULT.stretchRadius();
        for (ServerPlayer player : level.players()) {
            if (onFoot(player) && player.blockPosition().distSqr(feet) <= radius * radius) {
                level.playSound(null, player, SoundEvents.CREAKING_TWITCH, SoundSource.BLOCKS, 1.0F, 0.6F);
                heard = true;
            }
        }
        if (!heard) {
            level.playSound(null, feet, SoundEvents.CREAKING_TWITCH, SoundSource.BLOCKS, 1.0F, 0.6F);
        }
    }

    /**
     * The roof still loose falls, as rock, and the roof bolts have held the rest. A cave-in whose roof is all bolted by now says the bolts
     * held.
     */
    private static void fall(ServerLevel level, BlockPos feet) {
        List<BlockPos> loose = looseRoof(level, feet);
        if (loose.isEmpty()) {
            tellNear(level, feet, "deepcharter.layer.cave_in_holds");
            return;
        }
        for (BlockPos at : loose) {
            BlockState rock = level.getBlockState(at);
            FallingBlockEntity falling = FallingBlockEntity.fall(level, at, rock);
            // Rock that cannot land (the cell is taken) is lost, not dropped as an item; disableDrop() would skip the
            // landing too.
            falling.dropItem = false;
            falling.setHurtsEntities(CaveInTuning.DEFAULT.fallDamage(), CaveInTuning.DEFAULT.fallDamageMax());
        }
        // PLACEHOLDER sound until the sound pass.
        level.playSound(null, feet, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 0.8F, 0.5F);
    }

    /**
     * Sets a roof bolt in the cell under {@code roof}, which must be bare natural rock with an empty cell under it. Returns the lang key
     * suffix, under {@code message.deepcharter.repair.}, of why it cannot be set, or empty when it was.
     */
    public static Optional<String> setBolt(ServerLevel level, BlockPos roof, double spacing) {
        BlockPos cell = roof.below();
        if (!level.getBlockState(roof).is(HazardBlocks.NATURAL_ROCK)) {
            return Optional.of("bolt_not_rock");
        }
        if (!level.getBlockState(cell).isAir()) {
            return Optional.of("bolt_no_room");
        }
        if (boltNear(level, cell, spacing)) {
            return Optional.of("bolt_already");
        }
        level.setBlock(cell, LayerBlocks.ROOF_BOLT.defaultBlockState(), Block.UPDATE_ALL);
        // PLACEHOLDER sound until the sound pass.
        level.playSound(null, cell, SoundEvents.CHAIN_PLACE, SoundSource.BLOCKS, 1.0F, 0.8F);
        return Optional.empty();
    }
}
