package io.github.pkeppeler.deepcharter.layer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.scanner.LoadedBlocks;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The grained crust's pulse and the rules it enforces (#746, ADR 0060): the drill skates sideways across the grain, a block placed
 * against it crumbles, a fluid is absorbed, and whatever stops in it is squeezed. Every name and message here is a placeholder until the
 * lore session (#12) names them.
 */
public final class GrainedCrust {
    private static final int VERSION = 1;
    /** A pod that moved less than this in the tick (blocks) is not moving. */
    private static final double MOVING_BLOCKS = 0.01;

    /** How long before a pulse the grain creaks, for what it will squeeze. */
    private static final int WARN_TICKS = 10;

    /** A placement a player has just tried against the grain: where the block would stand. Read and dropped at the end of the tick. */
    private record Tried(ResourceKey<Level> level, BlockPos at) {}

    private static final Map<UUID, Tried> TRIED = new HashMap<>();

    private static final int SPARK_TICKS = 2;
    private static final int SQUEAL_TICKS = 10;
    private static final int TOLD_TICKS = 40;

    /** Whether the player was told why a block placed against the grain crumbles: once for each player (it is kept across a respawn). */
    public record Explained(boolean told) {
        public static final Explained NO = new Explained(false);
        public static final MapCodec<Explained> BODY = RecordCodecBuilder.mapCodec(
                instance -> instance.group(Codec.BOOL.fieldOf("told").forGetter(Explained::told))
                        .apply(instance, Explained::new));
    }

    public static final AttachmentType<Versioned<Explained>> EXPLAINED = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "grain_explained"),
            builder -> builder.persistent(Versioned.codec(VERSION, Explained.BODY))
                    .initializer(() -> Versioned.of(Explained.NO))
                    .copyOnDeath());

    private GrainedCrust() {}

    public static void init() {
        PodEvents.AFTER_TICK.register(GrainedCrust::squeezePod);
        ServerTickEvents.END_LEVEL_TICK.register(GrainedCrust::squeezePlayers);
        ServerTickEvents.END_LEVEL_TICK.register(GrainedCrust::explainPlacements);
        UseBlockCallback.EVENT.register(GrainedCrust::explainPlacement);
    }

    /**
     * True for what the grained crust sheds when it changes next to it: a fluid, or any block that can be broken. Another grained block, the
     * breach crust, Company rock and unbreakable blocks are left alone, and so is air.
     */
    static boolean sheds(BlockState state) {
        return !state.isAir()
                && !state.is(LayerBlocks.GRAINED_CRUST)
                && !state.is(LayerBlocks.BREACH_CRUST)
                && !state.is(HazardBlocks.UNDIGGABLE)
                && state.getBlock().defaultDestroyTime() >= 0;
    }

    /** True when a block of the grained crust is in or touches {@code box}. Blocks in a chunk that is not loaded are not seen. */
    private static boolean touches(Level level, AABB box) {
        return new LoadedBlocks(level)
                .getBlockStates(box.inflate(LavaHazard.TOUCH_REACH))
                .anyMatch(state -> state.is(LayerBlocks.GRAINED_CRUST));
    }

    private static boolean moving(PodEntity pod) {
        return pod.flying() || pod.position().distanceToSqr(pod.xo, pod.yo, pod.zo) > MOVING_BLOCKS * MOVING_BLOCKS;
    }

    /** A pod that is neither moving nor drilling, and touches the grain, is squeezed (a wreck takes none). */
    private static boolean squeezes(PodEntity pod) {
        return !Wrecks.isWreck(pod) && !pod.drilling() && !moving(pod) && touches(pod.level(), pod.getBoundingBox());
    }

    /**
     * A player on foot who touches the grain is squeezed. A player in a pod is the pod's to protect (its hull takes the squeeze). Spectators and
     * creative players are skipped, as every depth feature does.
     */
    private static boolean squeezes(ServerPlayer player) {
        return !player.isSpectator()
                && !player.isCreative()
                && !(player.getVehicle() instanceof PodEntity)
                && touches(player.level(), player.getBoundingBox());
    }

    /** A pod that is squeezed loses hull on the pulse; its crew is told to keep moving. */
    private static void squeezePod(PodEntity pod) {
        Level level = pod.level();
        if (isWarning(level) && squeezes(pod)) {
            warn(level, pod);
        }
        if (!isPulse(level) || !squeezes(pod)) {
            return;
        }
        pod.damageHull(LayerTuning.DEFAULT.grainSqueezeHull());
        // PLACEHOLDER sound until the sound pass: a creak of the grain, played to the crew.
        level.playSound(null, pod, SoundEvents.CREAKING_SWAY, SoundSource.NEUTRAL, 1.0F, 0.6F);
        if (pod.getControllingPassenger() instanceof ServerPlayer pilot) {
            pilot.sendOverlayMessage(Component.translatable("deepcharter.layer.grain_squeeze_pod"));
        }
    }

    /** A player who is squeezed takes damage on the pulse, with no exemption for one who moves: the way out is off the crust. */
    private static void squeezePlayers(ServerLevel level) {
        boolean pulse = isPulse(level);
        boolean warning = isWarning(level);
        if (!pulse && !warning) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            if (!squeezes(player)) {
                continue;
            }
            if (warning) {
                warn(level, player);
            }
            if (pulse) {
                player.hurtServer(level, level.damageSources().cramming(), LayerTuning.DEFAULT.grainSqueezeDamage());
                level.playSound(null, player, SoundEvents.CREAKING_SWAY, SoundSource.PLAYERS, 1.0F, 0.6F);
                player.sendOverlayMessage(Component.translatable("deepcharter.layer.grain_squeeze_foot"));
            }
        }
    }

    /**
     * The grain creaks and sheds dust {@link #WARN_TICKS} before a pulse, for whatever the pulse will squeeze, so that the danger can be heard
     * before it hurts. The animated texture is flavour only: it is not tied to the world clock.
     */
    private static void warn(Level level, Entity squeezed) {
        // PLACEHOLDER sound until the sound pass: the entity's own sound packet, so that the crew or the player hears
        // it.
        level.playSound(null, squeezed, SoundEvents.CREAKING_TWITCH, SoundSource.NEUTRAL, 0.8F, 0.7F);
        if (level instanceof ServerLevel server) {
            server.sendParticles(
                    ParticleTypes.ASH,
                    squeezed.getX(),
                    squeezed.getY() + 0.2,
                    squeezed.getZ(),
                    10,
                    0.4,
                    0.2,
                    0.4,
                    0.02);
        }
    }

    /** True on the one tick, {@link #WARN_TICKS} before each pulse (half the period at most), when a squeeze is announced. */
    private static boolean isWarning(Level level) {
        int period = LayerTuning.DEFAULT.grainPulseTicks();
        return period - level.getGameTime() % period == Math.min(WARN_TICKS, period / 2);
    }

    /**
     * The first time a player places a block against the grain, a message says why it will crumble (#746): once for each player, and kept across
     * a respawn. A message and not a handbook entry, because it reaches a player on any charter at the moment they try it, and a handbook entry
     * needs a charter and its chapter text is the lore session's. Saved data of another version is left alone and the player is not told.
     * The use of the item is only noted here, before the game has decided what it does (it may open a chest): the tick's end checks that a
     * block is there.
     */
    private static InteractionResult explainPlacement(
            Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide() || !(player instanceof ServerPlayer) || !(stack.getItem() instanceof BlockItem)) {
            return InteractionResult.PASS;
        }
        BlockPos at = new BlockPlaceContext(player, hand, stack, hit).getClickedPos();
        for (Direction side : Direction.values()) {
            if (level.getBlockState(at.relative(side)).is(LayerBlocks.GRAINED_CRUST)) {
                TRIED.put(player.getUUID(), new Tried(level.dimension(), at));
                break;
            }
        }
        return InteractionResult.PASS;
    }

    /** Tells each player who tried a placement against the grain, and has a block there now, why it will crumble: once. */
    private static void explainPlacements(ServerLevel level) {
        Iterator<Map.Entry<UUID, Tried>> tries = TRIED.entrySet().iterator();
        while (tries.hasNext()) {
            Map.Entry<UUID, Tried> entry = tries.next();
            Tried tried = entry.getValue();
            if (!tried.level().equals(level.dimension())) {
                continue;
            }
            tries.remove();
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null || !sheds(level.getBlockState(tried.at()))) {
                continue;
            }
            Optional<Explained> state = Versioned.readable(player, EXPLAINED);
            if (state.isPresent() && !state.get().told()) {
                player.setAttached(EXPLAINED, Versioned.of(new Explained(true)));
                player.sendSystemMessage(Component.translatable("deepcharter.layer.grain_no_place"));
            }
        }
    }

    /**
     * One tick of a drill that is pushed across the grain: heat in the hull (less by a radiator), sparks at the face, a squeal, and now and
     * then the line that says what is wrong. Called while the pilot pushes, so it stops with the push.
     */
    public static void skate(PodEntity pod, Direction direction) {
        if (!(pod.level() instanceof ServerLevel level)) {
            return;
        }
        pod.damageHull(LayerTuning.DEFAULT.grainSkateHeatPerSecond() / 20f * PodComponents.radiatorRatio(pod));
        if (pod.tickCount % SPARK_TICKS == 0) {
            level.sendParticles(
                    ParticleTypes.ELECTRIC_SPARK,
                    pod.getX() + direction.getStepX(),
                    pod.getY() + 0.9,
                    pod.getZ() + direction.getStepZ(),
                    8,
                    0.3,
                    0.4,
                    0.3,
                    0.2);
        }
        if (pod.tickCount % SQUEAL_TICKS == 0) {
            // PLACEHOLDER sound until the sound pass.
            level.playSound(null, pod, SoundEvents.GRINDSTONE_USE, SoundSource.NEUTRAL, 0.8F, 1.4F);
        }
        if (pod.tickCount % TOLD_TICKS == 0 && pod.getControllingPassenger() instanceof ServerPlayer pilot) {
            pilot.sendOverlayMessage(Component.translatable("deepcharter.layer.grain_skates"));
        }
    }

    /** True on the one tick of each {@link LayerTuning#grainPulseTicks()} when the grain flexes. Every level pulses together, on the world clock. */
    public static boolean isPulse(Level level) {
        return level.getGameTime() % LayerTuning.DEFAULT.grainPulseTicks() == 0;
    }
}
