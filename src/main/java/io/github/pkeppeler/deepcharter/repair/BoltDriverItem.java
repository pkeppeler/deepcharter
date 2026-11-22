package io.github.pkeppeler.deepcharter.repair;

import io.github.pkeppeler.deepcharter.layer.CaveIns;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The bolt driver (#987, CREATURES section 8), used on foot and kept: a click on the underside of a roof sets a roof bolt, a click anywhere
 * else fires a bolt at range, and either spends one bolt. A shot is a ray, not an entity; another player takes it only where PvP is allowed.
 */
public final class BoltDriverItem extends Item {
    // A figure is narrow: a creature within this many blocks of the bolt's line is still hit.
    private static final float HIT_MARGIN = 0.3F;

    public BoltDriverItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getClickedFace() != Direction.DOWN) {
            // Not an underside: the click goes on to use(), which fires.
            return InteractionResult.PASS;
        }
        if (!(context.getPlayer() instanceof ServerPlayer player)
                || !(context.getLevel() instanceof ServerLevel level)) {
            return InteractionResult.SUCCESS;
        }
        BlockPos roof = context.getClickedPos();
        Optional<String> refusal = ready(player);
        if (refusal.isEmpty() && !mayHang(level, player, roof, context.getItemInHand())) {
            refusal = Optional.of("bolt_forbidden");
        }
        if (refusal.isEmpty()) {
            refusal = CaveIns.boltRefusal(level, roof, BoltDriverTuning.DEFAULT.boltSpacing());
        }
        if (refusal.isPresent()) {
            return refuse(player, refusal.get());
        }
        // The bolt is spent before the roof bolt hangs: nothing is placed that was not paid for.
        spend(player);
        CaveIns.hangBolt(level, roof);
        player.getCooldowns().addCooldown(context.getItemInHand(), BoltDriverTuning.DEFAULT.setCooldownTicks());
        player.sendOverlayMessage(Component.translatable("message.deepcharter.repair.bolt_set"));
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!(player instanceof ServerPlayer shooter) || !(level instanceof ServerLevel server)) {
            return InteractionResult.PASS;
        }
        Optional<String> refusal = ready(shooter);
        if (refusal.isPresent()) {
            return refuse(shooter, refusal.get());
        }
        spend(shooter);
        shoot(server, shooter);
        shooter.getCooldowns().addCooldown(shooter.getItemInHand(hand), BoltDriverTuning.DEFAULT.fireCooldownTicks());
        return InteractionResult.SUCCESS;
    }

    private static InteractionResult refuse(ServerPlayer player, String reason) {
        player.sendOverlayMessage(Component.translatable("message.deepcharter.repair." + reason));
        return InteractionResult.FAIL;
    }

    /** Whether the player may place a block in the cell under {@code roof}: the checks vanilla block placement makes. */
    private static boolean mayHang(ServerLevel level, ServerPlayer player, BlockPos roof, ItemStack driver) {
        BlockPos cell = roof.below();
        return level.mayInteract(player, cell) && player.mayUseItemAt(cell, Direction.DOWN, driver);
    }

    /** Why the player cannot use the driver now: a pilot in the seat, or an empty pack. Empty when they can. */
    private static Optional<String> ready(ServerPlayer player) {
        if (player.getVehicle() instanceof PodEntity) {
            return Optional.of("bolt_on_foot");
        }
        if (!player.hasInfiniteMaterials() && packBolts(player).isEmpty()) {
            return Optional.of("bolt_none");
        }
        return Optional.empty();
    }

    /** The stack of bolts the driver draws from: the pack, then the offhand, never what is worn. Empty when there is none. */
    private static ItemStack packBolts(ServerPlayer player) {
        Item bolt = RepairRegistry.item(Consumable.BOLT);
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (stack.is(bolt)) {
                return stack;
            }
        }
        return player.getOffhandItem().is(bolt) ? player.getOffhandItem() : ItemStack.EMPTY;
    }

    /** Takes one bolt from the pack that {@link #ready} found; a creative player's pack is not touched. */
    private static void spend(ServerPlayer player) {
        if (!player.hasInfiniteMaterials()) {
            packBolts(player).shrink(1);
        }
    }

    private static void shoot(ServerLevel level, ServerPlayer player) {
        BoltDriverTuning tuning = BoltDriverTuning.DEFAULT;
        Vec3 from = player.getEyePosition();
        Vec3 to = from.add(player.getLookAngle().scale(tuning.range()));
        HitResult block =
                level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        Vec3 end = block.getType() == HitResult.Type.MISS ? to : block.getLocation();
        AABB sweep = player.getBoundingBox().expandTowards(end.subtract(from)).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                level, player, from, end, sweep, target -> canHit(player, target), HIT_MARGIN);
        if (hit != null) {
            end = hit.getLocation();
            hit.getEntity().hurtServer(level, level.damageSources().playerAttack(player), tuning.damage());
        }
        trail(level, from, end);
        // PLACEHOLDER sound until the sound pass: a crossbow's snap.
        level.playSound(null, player, SoundEvents.CROSSBOW_SHOOT, SoundSource.PLAYERS, 0.8F, 1.6F);
    }

    /** A living thing that is not the shooter; another player only where PvP is allowed. */
    private static boolean canHit(ServerPlayer shooter, Entity target) {
        if (!(target instanceof LivingEntity living)
                || !living.isAlive()
                || target.isSpectator()
                || target == shooter) {
            return false;
        }
        return !(target instanceof Player other) || shooter.canHarmPlayer(other);
    }

    private static void trail(ServerLevel level, Vec3 from, Vec3 to) {
        Vec3 line = to.subtract(from);
        int steps = Math.max(1, (int) line.length());
        for (int i = 1; i <= steps; i++) {
            Vec3 at = from.add(line.scale(i / (double) steps));
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }
}
