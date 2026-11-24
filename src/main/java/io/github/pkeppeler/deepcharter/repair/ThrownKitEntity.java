package io.github.pkeppeler.deepcharter.repair;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.creature.DrawSources;
import io.github.pkeppeler.deepcharter.pod.PodCharge;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.scanner.LoadedBlocks;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
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
 *   <li>a <b>flare</b> stands a {@link FlareLightBlock} where it landed, schedules the block's burn-out and registers with {@link DrawSources}, then is
 *       gone; one that finds no room to stand (water, a solid block, an unloaded neighbour) drowns and is given back to the thrower;
 *   <li>a <b>decoy</b> lies there and sounds every {@code pulseTicks} for {@code noiseTicks}, a source in {@link DrawSources}, then is gone;
 *   <li>a <b>charge</b> lies there with a fuse, sparking, and blasts as the pod's blasting charge does ({@link PodCharge}): the 3 x 3 column under it,
 *       {@code chargeDepth} rows deep, clears Company rock and natural rock (only where the volume holds Company rock, the pod's own rule), and hurts
 *       a pod in reach by distance. A person in reach is hurt too, but never killed. Friendly fire is deliberate: the thrower and their own charter's
 *       crew are hurt (the fuse and the GET CLEAR bar warn them), anyone else only where the server allows PvP; a pod of another charter is never hurt.
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

    /** Whether it has come to rest. */
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
            case THROWN_FLARE -> discard();
            // enum-default: the three thrown kinds have arms; a kind that lands without one has no landed behaviour,
            // and the throw is the refusal
            default -> throw new IllegalStateException(kind + " has no landed behaviour");
        }
        ticksLanded++;
    }

    /** A decoy that is gone before its time (its chunk unloaded, it was killed) falls silent with it. */
    @Override
    public void onRemoval(RemovalReason reason) {
        if (kind == Consumable.THROWN_DECOY && landed && level() instanceof ServerLevel level) {
            DrawSources.removeDecoy(level, position());
        }
        super.onRemoval(reason);
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
            // enum-default: the three thrown kinds have arms; any other member is a kit that is not thrown, and the
            // throw is the refusal
            default -> throw new IllegalStateException(kind + " is not thrown");
        }
    }

    /**
     * Stands the flare in the floor where it landed, or a cell above if the first is taken. A flare that finds no room drowns: the thrower is told and
     * gets it back, so a throw into a flooded bore is a lesson, not a loss.
     */
    private void lightFlare(ServerLevel level) {
        LoadedBlocks blocks = new LoadedBlocks(level);
        boolean stood = false;
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
                        RepairRegistry.FLARE_LIGHT,
                        tuning.flare().keepOffBlocks(),
                        level.getGameTime() + tuning.flare().burnTicks());
                level.playSound(null, spot, SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 1f, 0.8f);
                stood = true;
                break;
            }
        }
        if (!stood && getOwner() instanceof ServerPlayer thrower) {
            level.playSound(null, cell, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.4f, 1.4f);
            thrower.sendOverlayMessage(Component.translatable("message.deepcharter.repair.thrown_flare_drowned"));
            thrower.addItem(new ItemStack(RepairRegistry.item(Consumable.THROWN_FLARE)));
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

    /** The pod's blast, round where it lies; a person takes the same share of their health as a pod does of its hull. Who is hurt: the class Javadoc. */
    private void blast(ServerLevel level) {
        RepairTuning repair = RepairTuning.DEFAULT;
        int radius = tuning.charge().blastRadius();
        BlockPos low = cell.offset(-radius, -repair.chargeDepth(), -radius);
        BlockPos high = cell.offset(radius, -1, radius);
        Optional<Charter> charter = throwerCharter(level);
        PodCharge.detonate(
                level,
                low,
                high,
                repair.chargeDamage(),
                repair.chargeReach(),
                pod -> PodComponents.mayAccess(pod, charter),
                charter.map(Charter::id));
        AABB volume = new AABB(low.getX(), low.getY(), low.getZ(), high.getX() + 1, high.getY() + 1, high.getZ() + 1);
        // entity-query: round a charge that a ticking projectile landed, so its chunks are entity-ticking; a person
        // in an unloaded chunk is out of the blast's reach anyway
        List<Player> hurt = level.getEntitiesOfClass(
                Player.class, volume.inflate(repair.chargeReach()), person -> isHurt(level, person, charter));
        for (Player person : hurt) {
            double share = PodCharge.reachShare(volume, person.getBoundingBox(), repair.chargeReach());
            if (share > 0) {
                float damage = (float) (tuning.charge().personDamage() * share);
                // Never lethal. No attacker is named: the vanilla PvP rule would spare the thrower from their own
                // charge, and isHurt has already decided who is hurt.
                person.hurtServer(
                        level,
                        level.damageSources().explosion(this, null),
                        Math.min(damage, Math.max(0f, person.getHealth() - 1f)));
            }
        }
    }

    private Optional<Charter> throwerCharter(ServerLevel level) {
        return getOwner() instanceof Player thrower
                ? Charters.readableCharterOf(level.getServer(), thrower.getUUID())
                : Optional.empty();
    }

    /** Whether the blast hurts {@code person}: the thrower, a crewmate, or anyone at all when the server allows PvP. Not a spectator. */
    private boolean isHurt(ServerLevel level, Player person, Optional<Charter> thrower) {
        if (person.isSpectator()) {
            return false;
        }
        boolean crew = person == getOwner()
                || (thrower.isPresent()
                        && Charters.readableCharterOf(level.getServer(), person.getUUID())
                                .map(charter ->
                                        charter.id().equals(thrower.get().id()))
                                .orElse(false));
        return crew || level.isPvpAllowed();
    }
}
