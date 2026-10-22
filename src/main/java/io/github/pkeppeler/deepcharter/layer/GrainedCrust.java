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
import java.util.Optional;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
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
    /** How far past a pod's or a player's box a grained block still counts as touching it, as {@link LavaHazard#TOUCH_REACH} says for lava. */
    private static final double TOUCH_REACH = LavaHazard.TOUCH_REACH;
    /** A pod that moved less than this in the tick (blocks) is not moving. */
    private static final double MOVING_BLOCKS = 0.01;

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
                .getBlockStates(box.inflate(TOUCH_REACH))
                .anyMatch(state -> state.is(LayerBlocks.GRAINED_CRUST));
    }

    private static boolean moving(PodEntity pod) {
        return pod.flying() || pod.position().distanceToSqr(pod.xo, pod.yo, pod.zo) > MOVING_BLOCKS * MOVING_BLOCKS;
    }

    /** A pod that is neither moving nor drilling, and touches the grain, loses hull on the pulse (a wreck takes none). */
    private static void squeezePod(PodEntity pod) {
        Level level = pod.level();
        if (!isPulse(level)
                || Wrecks.isWreck(pod)
                || pod.drilling()
                || moving(pod)
                || !touches(level, pod.getBoundingBox())) {
            return;
        }
        pod.damageHull(LayerTuning.DEFAULT.grainSqueezeHull());
        // PLACEHOLDER sound until the sound pass: a creak of the grain, played to the crew.
        level.playSound(null, pod, SoundEvents.CREAKING_SWAY, SoundSource.NEUTRAL, 1.0F, 0.6F);
        if (pod.getControllingPassenger() instanceof ServerPlayer pilot) {
            pilot.sendOverlayMessage(Component.translatable("deepcharter.layer.grain_squeeze"));
        }
    }

    /**
     * A player on foot who touches the grain takes squeeze damage on the pulse. A player in a pod is the pod's to protect (its hull takes
     * the squeeze). Spectators and creative players are skipped, as every depth feature does.
     */
    private static void squeezePlayers(ServerLevel level) {
        if (!isPulse(level)) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()
                    || player.isCreative()
                    || player.getVehicle() instanceof PodEntity
                    || !touches(level, player.getBoundingBox())) {
                continue;
            }
            player.hurtServer(level, level.damageSources().cramming(), LayerTuning.DEFAULT.grainSqueezeDamage());
            level.playSound(null, player, SoundEvents.CREAKING_SWAY, SoundSource.PLAYERS, 1.0F, 0.6F);
            player.sendOverlayMessage(Component.translatable("deepcharter.layer.grain_squeeze"));
        }
    }

    /**
     * The first time a player tries to place a block against the grain, a message says why it will crumble (#746): once for each player,
     * and kept across a respawn. A message and not a handbook entry, because it reaches a player on any charter at the moment they try
     * it, and a handbook entry needs a charter and its chapter text is the lore session's. Saved data of another version is left alone and
     * the player is not told.
     */
    private static InteractionResult explainPlacement(
            Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide() || !(player instanceof ServerPlayer told) || !(stack.getItem() instanceof BlockItem)) {
            return InteractionResult.PASS;
        }
        BlockPos at = new BlockPlaceContext(player, hand, stack, hit).getClickedPos();
        boolean againstGrain = false;
        for (Direction side : Direction.values()) {
            againstGrain |= level.getBlockState(at.relative(side)).is(LayerBlocks.GRAINED_CRUST);
        }
        if (!againstGrain) {
            return InteractionResult.PASS;
        }
        Optional<Explained> state = Versioned.readable(told, EXPLAINED);
        if (state.isPresent() && !state.get().told()) {
            told.setAttached(EXPLAINED, Versioned.of(new Explained(true)));
            told.sendSystemMessage(Component.translatable("deepcharter.layer.grain_no_place"));
        }
        return InteractionResult.PASS;
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

    /** Ticks until the next pulse, 1 to a whole period: on a pulse tick, the next pulse is a whole period away. */
    public static int ticksToPulse(Level level) {
        int period = LayerTuning.DEFAULT.grainPulseTicks();
        return period - (int) (level.getGameTime() % period);
    }
}
