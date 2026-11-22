package io.github.pkeppeler.deepcharter.repair;

import io.github.pkeppeler.deepcharter.creature.DrawSources;
import io.github.pkeppeler.deepcharter.pod.PodCharge;
import io.github.pkeppeler.deepcharter.scanner.LoadedBlocks;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A thrown flare, decoy or charge (#985), in the air and, for a decoy and a charge, where it lies. It flies as a snowball does and lands on the top face
 * of a block; any other face or a creature turns it back, and it drops to the floor. What it does then depends on what it is:
 *
 * <ul>
 *   <li>a <b>flare</b> stands a {@link FlareLightBlock} where it landed, schedules the block's burn-out and registers with {@link DrawSources}, then is gone;
 *   <li>a <b>decoy</b> lies there and sounds every {@code pulseTicks} for {@code noiseTicks}, a source in {@link DrawSources}, then is gone;
 *   <li>a <b>charge</b> lies there with a fuse, sparking, and blasts as the pod's blasting charge does ({@link PodCharge}): the 3 x 3 column under it,
 *       {@code chargeDepth} rows deep, clears Company rock and natural rock (only where the volume holds Company rock, the pod's own rule), and hurts
 *       a pod in reach by distance. A person in reach is hurt too, the thrower's own risk, but never killed.
 * </ul>
 *
 * <p>Not saved: a throw that its chunk unloads on is spent, as the pod's fuse is. A flare's block is saved and burns out on its own.
 */
public final class ThrownKitEntity extends ThrowableItemProjectile {
    private static final int SPARK_EVERY_TICKS = 4;
    private static final int TICKS_PER_SECOND = 20;
    /** How much of its speed a thrown item keeps when it turns back from a wall or a creature. */
    private static final double TURN_BACK_KEEPS = 0.3;
    /** How far off the face it struck a turned back item is put, so that it does not strike it again at once. */
    private static final double OFF_THE_FACE = 0.1;
    /** The decoy's note: a bell high enough to be a lure and loud enough to carry. */
    private static final float DECOY_VOLUME = 3f;

    private static final float DECOY_PITCH = 1.6f;

    private Consumable kind = Consumable.THROWN_FLARE;
    private ThrownKitTuning tuning = ThrownKitTuning.DEFAULT;
    private boolean landed;
    private int ticksLanded;
    /** The cell above the floor it landed on, where its blast is centred. */
    private BlockPos cell = BlockPos.ZERO;

    public ThrownKitEntity(EntityType<? extends ThrownKitEntity> type, Level level) {
        super(type, level);
    }

    ThrownKitEntity(ServerLevel level, LivingEntity owner, ItemStack stack, Consumable kind, ThrownKitTuning tuning) {
        super(RepairRegistry.THROWN_KIT, owner, level, stack);
        this.kind = kind;
        this.tuning = tuning;
    }

    @Override
    protected Item getDefaultItem() {
        return RepairRegistry.item(Consumable.THROWN_FLARE);
    }

    /** Whether it has come to rest. A flare never shows as landed: it becomes a block and is gone. */
    public boolean landed() {
        return landed;
    }

    @Override
    public void tick() {
        if (level().isClientSide() || !landed()) {
            super.tick();
            return;
        }
        ServerLevel level = (ServerLevel) level();
        switch (kind) {
            case THROWN_DECOY -> sound(level);
            case THROWN_CHARGE -> burn(level);
            default -> discard();
        }
        ticksLanded++;
    }

    @Override
    protected void onHitBlock(BlockHitResult hit) {
        super.onHitBlock(hit);
        if (level().isClientSide()) {
            return;
        }
        if (hit.getDirection() == Direction.UP) {
            land((ServerLevel) level(), hit);
        } else {
            turnBack(hit.getDirection());
        }
    }

    @Override
    protected void onHitEntity(EntityHitResult hit) {
        super.onHitEntity(hit);
        if (!level().isClientSide()) {
            setDeltaMovement(getDeltaMovement().multiply(-TURN_BACK_KEEPS, 0, -TURN_BACK_KEEPS));
        }
    }

    /** Off a wall or a ceiling, it keeps a little of its speed and falls. */
    private void turnBack(Direction face) {
        Vec3 normal = Vec3.atLowerCornerOf(face.getUnitVec3i());
        Vec3 speed = getDeltaMovement();
        setDeltaMovement(speed.subtract(normal.scale(2 * speed.dot(normal))).scale(TURN_BACK_KEEPS));
        setPos(position().add(normal.scale(OFF_THE_FACE)));
    }

    private void land(ServerLevel level, BlockHitResult hit) {
        landed = true;
        cell = hit.getBlockPos().above();
        setDeltaMovement(Vec3.ZERO);
        setNoGravity(true);
        setPos(hit.getLocation());
        switch (kind) {
            case THROWN_FLARE -> lightFlare(level);
            case THROWN_DECOY ->
                DrawSources.addDecoy(
                        level,
                        position(),
                        tuning.decoy().pullBlocks(),
                        level.getGameTime() + tuning.decoy().noiseTicks());
            case THROWN_CHARGE -> {
                if (getOwner() instanceof ServerPlayer thrower) {
                    thrower.sendOverlayMessage(Component.translatable(
                            "message.deepcharter.repair.thrown_charge_down",
                            Mth.ceil(tuning.charge().fuseTicks() / (double) TICKS_PER_SECOND)));
                }
            }
            default -> throw new IllegalStateException(kind + " is not thrown");
        }
    }

    /** Stands the flare in the floor where it landed, or a cell above if the first is taken. A flare that finds no room is spent. */
    private void lightFlare(ServerLevel level) {
        LoadedBlocks blocks = new LoadedBlocks(level);
        for (BlockPos spot : new BlockPos[] {cell, cell.above()}) {
            BlockState there = blocks.getBlockState(spot);
            if (blocks.canChange(spot)
                    && there.canBeReplaced()
                    && there.getFluidState().isEmpty()) {
                level.setBlock(spot, RepairRegistry.FLARE_LIGHT.defaultBlockState(), Block.UPDATE_ALL);
                level.scheduleTick(
                        spot, RepairRegistry.FLARE_LIGHT, tuning.flare().burnTicks());
                DrawSources.addFlare(
                        level,
                        spot,
                        tuning.flare().keepOffBlocks(),
                        level.getGameTime() + tuning.flare().burnTicks());
                level.playSound(null, spot, SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 1f, 0.8f);
                break;
            }
        }
        discard();
    }

    /** The decoy rings out, then falls silent. */
    private void sound(ServerLevel level) {
        ThrownKitTuning.Decoy decoy = tuning.decoy();
        if (ticksLanded >= decoy.noiseTicks()) {
            level.sendParticles(ParticleTypes.SMOKE, getX(), getY() + 0.2, getZ(), 4, 0.1, 0.1, 0.1, 0.01);
            discard();
            return;
        }
        if (ticksLanded % decoy.pulseTicks() == 0) {
            level.playSound(
                    null,
                    getX(),
                    getY(),
                    getZ(),
                    SoundEvents.BELL_BLOCK,
                    SoundSource.NEUTRAL,
                    DECOY_VOLUME,
                    DECOY_PITCH);
            level.sendParticles(ParticleTypes.NOTE, getX(), getY() + 0.5, getZ(), 1, 0.2, 0.1, 0.2, 1.0);
        }
    }

    /** The charge sparks and counts down, then blasts. */
    private void burn(ServerLevel level) {
        if (ticksLanded >= tuning.charge().fuseTicks()) {
            blast(level);
            discard();
            return;
        }
        if (ticksLanded % SPARK_EVERY_TICKS == 0) {
            level.sendParticles(ParticleTypes.FLAME, getX(), getY() + 0.3, getZ(), 2, 0.1, 0.1, 0.1, 0.01);
            level.sendParticles(ParticleTypes.SMOKE, getX(), getY() + 0.5, getZ(), 1, 0.1, 0.1, 0.1, 0.01);
        }
    }

    /** The pod's blast, round where it lies, and the same share of a person's health as of a pod's hull. */
    private void blast(ServerLevel level) {
        RepairTuning repair = RepairTuning.DEFAULT;
        int radius = tuning.charge().blastRadius();
        BlockPos low = cell.offset(-radius, -repair.chargeDepth(), -radius);
        BlockPos high = cell.offset(radius, -1, radius);
        PodCharge.detonate(
                level,
                low,
                high,
                repair.chargeDamage(),
                repair.chargeReach(),
                PodCharge.hasCompanyRock(level, low, high));
        AABB volume = new AABB(low.getX(), low.getY(), low.getZ(), high.getX() + 1, high.getY() + 1, high.getZ() + 1);
        // entity-query: round a charge that a ticking projectile landed, so its chunks are entity-ticking; a person
        // in an unloaded chunk is out of the blast's reach anyway
        List<LivingEntity> hurt =
                level.getEntitiesOfClass(LivingEntity.class, volume.inflate(repair.chargeReach()), this::isPerson);
        for (LivingEntity person : hurt) {
            double share = PodCharge.reachShare(volume, person.getBoundingBox(), repair.chargeReach());
            if (share > 0) {
                float damage = (float) (tuning.charge().personDamage() * share);
                // Never lethal: it leaves half a heart at worst (the pod's blast wrecks nobody's run, and a thrown one
                // kills nobody).
                person.hurtServer(
                        level,
                        level.damageSources().explosion(this, getOwner()),
                        Math.min(damage, Math.max(0f, person.getHealth() - 1f)));
            }
        }
    }

    private boolean isPerson(Entity entity) {
        return entity instanceof Player player && !player.isSpectator();
    }
}
