package io.github.pkeppeler.deepcharter.repair;

import io.github.pkeppeler.deepcharter.layer.CaveIns;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import java.util.Optional;
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
import net.minecraft.world.entity.player.Inventory;
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
 * The bolt driver (#987, CREATURES section 8): a mining roof-bolter, used on foot, kept and not used up. A right click on the underside of
 * a roof sets a roof bolt there, which holds the roof round it against a cave-in ({@code CaveIns}); a right click anywhere else fires a bolt
 * at range. Either spends one bolt from the pack. Only the server decides what a click does.
 *
 * <p>A shot is a ray, not an entity: a bolt this fast needs no flight to draw. It stops at the first block, which it never changes, and
 * at the first creature, which takes {@link BoltDriverTuning#damage()}. Another player takes it only where the server allows PvP.
 */
public final class BoltDriverItem extends Item {
    /** How far from its line a creature is still hit, in blocks: a figure is narrow. */
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
        Optional<String> refusal = ready(player);
        if (refusal.isEmpty()) {
            refusal = CaveIns.setBolt(level, context.getClickedPos(), BoltDriverTuning.DEFAULT.boltSpacing());
            if (refusal.isEmpty() && !spend(player)) {
                throw new IllegalStateException("a bolt was checked in the pack and is gone: " + player);
            }
        }
        if (refusal.isPresent()) {
            player.sendOverlayMessage(Component.translatable("message.deepcharter.repair." + refusal.get()));
            return InteractionResult.FAIL;
        }
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
            shooter.sendOverlayMessage(Component.translatable("message.deepcharter.repair." + refusal.get()));
            return InteractionResult.FAIL;
        }
        if (!spend(shooter)) {
            throw new IllegalStateException("a bolt was checked in the pack and is gone: " + shooter);
        }
        shoot(server, shooter);
        shooter.getCooldowns().addCooldown(shooter.getItemInHand(hand), BoltDriverTuning.DEFAULT.fireCooldownTicks());
        return InteractionResult.SUCCESS;
    }

    /** Why the player cannot use the driver now: a pilot in the seat, or an empty pack. Empty when they can. */
    private static Optional<String> ready(ServerPlayer player) {
        if (player.getVehicle() instanceof PodEntity) {
            return Optional.of("bolt_on_foot");
        }
        if (!player.hasInfiniteMaterials()
                && player.getInventory().countItem(RepairRegistry.item(Consumable.BOLT)) == 0) {
            return Optional.of("bolt_none");
        }
        return Optional.empty();
    }

    /** Takes one bolt from the pack; a creative player's pack is not touched. */
    private static boolean spend(ServerPlayer player) {
        if (player.hasInfiniteMaterials()) {
            return true;
        }
        Inventory pack = player.getInventory();
        for (int slot = 0; slot < pack.getContainerSize(); slot++) {
            ItemStack stack = pack.getItem(slot);
            if (stack.is(RepairRegistry.item(Consumable.BOLT))) {
                stack.shrink(1);
                return true;
            }
        }
        return false;
    }

    private static void shoot(ServerLevel level, ServerPlayer player) {
        BoltDriverTuning tuning = BoltDriverTuning.DEFAULT;
        Vec3 from = player.getEyePosition();
        Vec3 to = from.add(player.getLookAngle().scale(tuning.range()));
        HitResult block =
                level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        Vec3 end = block.getType() == HitResult.Type.MISS ? to : block.getLocation();
        AABB sweep = player.getBoundingBox().expandTowards(end.subtract(from)).inflate(1.0);
        // The bolt's ray reaches only what is loaded round the shooter; a creature in an unloaded chunk is out of reach
        // anyway.
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
