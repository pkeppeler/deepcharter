package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
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
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;

/**
 * The cave-in (#987, ADR 0081): the roof of natural rock over a person on foot comes down {@link CaveInTuning#warnTicks()} after a creak,
 * except where a roof bolt ({@link RoofBoltBlock}) holds it. Only natural rock ({@link HazardBlocks#NATURAL_ROCK}) falls, as rock that lies
 * where it lands, so a cave-in blocks a stretch of tunnel and does not erase it.
 *
 * <p>A blast ({@link #blasted}) starts one over each person it may shake; {@link #shake} is the call an earthquake or a creature would
 * make. A cave-in that has creaked and not fallen is held in memory, not saved, and forgotten when the server stops.
 */
public final class CaveIns {
    /** A cave-in that has creaked and not yet fallen: the place it was started, as a person's feet, and the tick it falls. */
    private record Pending(BlockPos feet, long fallsAt) {}

    /** What the roof over a stretch is: the blocks that will fall, and how many roof blocks the bolts hold. */
    private record Roof(List<BlockPos> loose, int bolted) {}

    private static final Map<ResourceKey<Level>, List<Pending>> PENDING = new HashMap<>();
    /** The rock of a cave-in that is still falling, watched until it lands. */
    private static final Map<ResourceKey<Level>, List<FallingBlockEntity>> FALLING = new HashMap<>();
    /** A cave-in started within this many blocks of one that is already coming is the same cave-in. */
    private static final int SAME_PLACE_BLOCKS = 2;
    /** A rock's next step down is at most this far: gravity twice over, so a rock is caught before it reaches a person, never after. */
    private static final double STEP_DOWN = 0.08;

    private CaveIns() {}

    public static void init() {
        ServerTickEvents.END_LEVEL_TICK.register(CaveIns::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> stopped());
    }

    /** Forgets every cave-in: the maps are static, so an integrated server restarted in one JVM would inherit the last one's. */
    static void stopped() {
        PENDING.clear();
        FALLING.clear();
    }

    /**
     * A blast went off over {@code blast} (for a charge, the box round the cells it cleared; for dynamite and plastic, their cube): the roof over each person on foot within {@link CaveInTuning#shakeReach()} of that
     * volume's edge is shaken, so a bigger blast shakes farther (#1458). Only if the server allows PvP or the person is on the charter that set
     * it off ({@code blaster}), so no charter is griefed by another's blast. A person in a pod is not shaken: the pod's own hull is what the
     * roof falls on.
     */
    public static void blasted(ServerLevel level, AABB blast, Optional<CharterId> blaster) {
        double reach = CaveInTuning.DEFAULT.shakeReach();
        boolean pvp = level.getGameRules().get(GameRules.PVP);
        for (ServerPlayer player : level.players()) {
            if (onFoot(player)
                    && blast.distanceToSqr(player.position()) <= reach * reach
                    && (pvp || onCharter(level, player, blaster))) {
                shake(level, player.blockPosition());
            }
        }
    }

    private static boolean onCharter(ServerLevel level, ServerPlayer player, Optional<CharterId> charter) {
        return charter.isPresent()
                && Charters.readableCharterOf(level.getServer(), player.getUUID())
                        .map(Charter::id)
                        .equals(charter);
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
     * within reach.
     */
    public static List<BlockPos> looseRoof(ServerLevel level, BlockPos feet) {
        return read(level, feet).loose();
    }

    /** Air, or the moss film (#974): a film of moss on a face is no roof, and does not fill the cell under one. */
    private static boolean open(BlockState state) {
        return state.isAir() || state.is(LayerBlocks.OVERGROWTH_VENEER);
    }

    /** Never loads a chunk: a block in one that is not loaded is not read. */
    private static Roof read(ServerLevel level, BlockPos feet) {
        CaveInTuning tuning = CaveInTuning.DEFAULT;
        int radius = tuning.stretchRadius();
        List<BlockPos> bolts = boltsAround(level, feet);
        List<BlockPos> loose = new ArrayList<>();
        int bolted = 0;
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
                    if (open(state) || !state.getFluidState().isEmpty()) {
                        continue;
                    }
                    if (state.is(HazardBlocks.NATURAL_ROCK) && open(level.getBlockState(at.below()))) {
                        if (holds(bolts, at)) {
                            bolted++;
                        } else {
                            loose.add(at);
                        }
                    }
                    break;
                }
            }
        }
        return new Roof(loose, bolted);
    }

    /** The roof bolts that can hold any roof block of the stretch round {@code feet}: one scan, instead of one per block. */
    private static List<BlockPos> boltsAround(ServerLevel level, BlockPos feet) {
        CaveInTuning tuning = CaveInTuning.DEFAULT;
        int span = (int) Math.ceil(tuning.boltReach());
        int flat = tuning.stretchRadius() + span;
        return boltsIn(
                level,
                BlockPos.betweenClosed(
                        feet.offset(-flat, 2 - span, -flat), feet.offset(flat, tuning.ceilingReach() + span, flat)));
    }

    private static List<BlockPos> boltsIn(ServerLevel level, Iterable<BlockPos> cells) {
        List<BlockPos> bolts = new ArrayList<>();
        for (BlockPos at : cells) {
            if (loaded(level, at) && level.getBlockState(at).is(LayerBlocks.ROOF_BOLT)) {
                bolts.add(at.immutable());
            }
        }
        return bolts;
    }

    private static boolean holds(List<BlockPos> bolts, BlockPos roof) {
        double reach = CaveInTuning.DEFAULT.boltReach();
        for (BlockPos bolt : bolts) {
            if (bolt.distSqr(roof) <= reach * reach) {
                return true;
            }
        }
        return false;
    }

    private static boolean loaded(ServerLevel level, BlockPos at) {
        return level.getChunkSource().hasChunk(at.getX() >> 4, at.getZ() >> 4);
    }

    /** Whether another roof bolt hangs within {@code spacing} blocks of the cell {@code at}. */
    public static boolean boltNear(ServerLevel level, BlockPos at, double spacing) {
        int span = (int) Math.ceil(spacing);
        for (BlockPos other :
                boltsIn(level, BlockPos.betweenClosed(at.offset(-span, -span, -span), at.offset(span, span, span)))) {
            if (other.distSqr(at) <= spacing * spacing) {
                return true;
            }
        }
        return false;
    }

    /** One tick of every cave-in in {@code level}: a creak every {@link CaveInTuning#cueTicks()}, the fall (or the all-clear) at its time, and rock still falling. */
    private static void tick(ServerLevel level) {
        List<Pending> pending = PENDING.get(level.dimension());
        if (pending != null && !pending.isEmpty()) {
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
        List<FallingBlockEntity> rocks = FALLING.get(level.dimension());
        if (rocks != null && !rocks.isEmpty()) {
            rocks.removeIf(rock -> rock.isRemoved() || shatterOnPerson(level, rock));
        }
    }

    /** Tells each person on foot within the stretch of {@code feet} the line under {@code key}. */
    private static void tellNear(ServerLevel level, BlockPos feet, String key) {
        for (ServerPlayer player : underRoof(level, feet)) {
            player.sendOverlayMessage(Component.translatable(key));
        }
    }

    private static List<ServerPlayer> underRoof(ServerLevel level, BlockPos feet) {
        int radius = CaveInTuning.DEFAULT.stretchRadius();
        return level.players().stream()
                .filter(player -> onFoot(player) && player.blockPosition().distSqr(feet) <= radius * radius)
                .toList();
    }

    /** The roof creaks and sheds dust over the stretch. */
    private static void creak(ServerLevel level, BlockPos feet) {
        for (BlockPos at : looseRoof(level, feet)) {
            level.sendParticles(
                    ParticleTypes.ASH, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 3, 0.3, 0.05, 0.3, 0.01);
        }
        // PLACEHOLDER sound until the sound pass: the crust's creak, played at each person under the roof, as the
        // crust's is, and where it was started when no one is.
        List<ServerPlayer> under = underRoof(level, feet);
        for (ServerPlayer player : under) {
            level.playSound(null, player, SoundEvents.CREAKING_TWITCH, SoundSource.BLOCKS, 1.0F, 0.6F);
        }
        if (under.isEmpty()) {
            level.playSound(null, feet, SoundEvents.CREAKING_TWITCH, SoundSource.BLOCKS, 1.0F, 0.6F);
        }
    }

    /** The roof still loose falls, as rock. A roof the bolts held says so, but only when they were what held it. */
    private static void fall(ServerLevel level, BlockPos feet) {
        Roof roof = read(level, feet);
        if (roof.loose().isEmpty()) {
            if (roof.bolted() > 0) {
                tellNear(level, feet, "deepcharter.layer.cave_in_holds");
            }
            return;
        }
        List<FallingBlockEntity> rocks = FALLING.computeIfAbsent(level.dimension(), key -> new ArrayList<>());
        for (BlockPos at : roof.loose()) {
            FallingBlockEntity falling = FallingBlockEntity.fall(level, at, level.getBlockState(at));
            // Rock that cannot land (the cell is taken) is lost, not dropped as an item; disableDrop() would skip the
            // landing too.
            falling.dropItem = false;
            falling.setHurtsEntities(CaveInTuning.DEFAULT.fallDamage(), CaveInTuning.DEFAULT.fallDamageMax());
            rocks.add(falling);
        }
        level.playSound(null, feet, DeepSound.CAVE_IN_COLLAPSE.event(), SoundSource.BLOCKS, 1.0F, 1.0F);
    }

    /**
     * A falling block settles in the cell of any entity under it, so rock that landed on a person would leave them
     * inside it. Rock about to reach a person on foot shatters on them instead: it hurts by the blocks it fell to their
     * feet, up to {@link CaveInTuning#fallDamageMax()}, never to their last health, and leaves no block. Returns
     * whether it shattered.
     */
    private static boolean shatterOnPerson(ServerLevel level, FallingBlockEntity rock) {
        AABB reach = rock.getBoundingBox().expandTowards(0, rock.getDeltaMovement().y - STEP_DOWN, 0);
        for (ServerPlayer person : level.players()) {
            if (person.isSpectator() || !person.getBoundingBox().intersects(reach)) {
                continue;
            }
            CaveInTuning tuning = CaveInTuning.DEFAULT;
            float fallen = rock.getStartPos().getY() - person.getBlockY();
            float damage =
                    Math.min(Math.min(tuning.fallDamage() * fallen, tuning.fallDamageMax()), person.getHealth() - 1);
            if (damage > 0) {
                person.hurtServer(level, level.damageSources().fallingBlock(rock), damage);
            }
            level.sendParticles(
                    new BlockParticleOption(ParticleTypes.BLOCK, rock.getBlockState()),
                    rock.getX(),
                    rock.getY() + 0.5,
                    rock.getZ(),
                    12,
                    0.3,
                    0.3,
                    0.3,
                    0.05);
            level.playSound(null, rock.blockPosition(), SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 1.0F, 0.7F);
            rock.discard();
            return true;
        }
        return false;
    }

    /**
     * Why a roof bolt cannot hang under {@code roof}, as a lang key suffix under {@code message.deepcharter.repair.}: the roof must be bare
     * natural rock with an empty cell under it and no other bolt within {@code spacing}. Empty when it can.
     */
    public static Optional<String> boltRefusal(ServerLevel level, BlockPos roof, double spacing) {
        BlockPos cell = roof.below();
        if (!level.getBlockState(roof).is(HazardBlocks.NATURAL_ROCK)) {
            return Optional.of("bolt_not_rock");
        }
        if (!open(level.getBlockState(cell))) {
            return Optional.of("bolt_no_room");
        }
        if (boltNear(level, cell, spacing)) {
            return Optional.of("bolt_already");
        }
        return Optional.empty();
    }

    /** Hangs a roof bolt in the cell under {@code roof}, which {@link #boltRefusal} has passed. */
    public static void hangBolt(ServerLevel level, BlockPos roof) {
        BlockPos cell = roof.below();
        level.setBlock(cell, LayerBlocks.ROOF_BOLT.defaultBlockState(), Block.UPDATE_ALL);
        // PLACEHOLDER sound until the sound pass.
        level.playSound(null, cell, SoundEvents.CHAIN_PLACE, SoundSource.BLOCKS, 1.0F, 0.8F);
    }
}
