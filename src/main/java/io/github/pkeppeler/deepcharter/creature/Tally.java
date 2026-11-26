package io.github.pkeppeler.deepcharter.creature;

import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.pod.PodCargo;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodLights;
import io.github.pkeppeler.deepcharter.scanner.LoadedBlocks;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A Tally (#917, CREATURES.md): a brass Company counting drone that rides the Conduit's casing and clicks over ore. Moloch's family: it takes
 * and never breaks, never hurts a person or a hull, and never touches a block. A Tally that finds a pod holding ore, or a person carrying
 * some, trails it; one that has trailed it for {@link TallyTuning#trailTicks} (about 30 s) clamps to the cargo hatch, or to the person's pack,
 * and after a short hold takes one ore, the cheapest it finds, as a "quality sample" and flies off. The pod's cutter bites it on the hatch while the pod drills; on
 * foot, one blow breaks it. It drops nothing. Not saved: a Tally whose chunk unloads is gone.
 */
public final class Tally extends PathfinderMob {
    /** Whether the Tally holds a hatch or a pack. Synced, for the client's pose. */
    private static final EntityDataAccessor<Boolean> CLAMPED =
            SynchedEntityData.defineId(Tally.class, EntityDataSerializers.BOOLEAN);

    /** Ticks a dead Tally takes to fall apart: the game's own length of a death. */
    private static final int DISSOLVE_TICKS = 20;

    private static final int WANDER_TICKS = 40;
    private static final int WANDER_BLOCKS = 6;
    private static final int SPARKS = 10;
    /** How far off the hull the Tally sits, beyond the half width of the pod, and how high on it. */
    private static final double HATCH_GAP = 0.3;

    private static final double HATCH_HEIGHT_SHARE = 0.4;
    /** How far behind a person, and how high, the Tally sits on a pack. */
    private static final double PACK_GAP = 0.5;

    private static final double PACK_HEIGHT = 1.1;
    /** Blocks a second-guessed leaving Tally climbs. */
    private static final double LEAVE_CLIMB = 4;

    private enum Phase {
        DRIFT,
        TRAIL,
        CLAMP,
        LEAVE
    }

    private Phase phase = Phase.DRIFT;
    /** What the Tally trails or holds: a pod or a person. Server only. */
    private @Nullable Entity mark;
    /** Ticks the Tally has trailed its mark within sense. */
    private int trailed;
    /** Ticks the mark has been out of sense. */
    private int lost;

    private int idle;
    private int dwell;
    private int leaving;
    private int biteIn;
    private boolean cued;
    private boolean bitten;

    public Tally(EntityType<? extends Tally> type, Level level) {
        super(type, level);
        moveControl = new FlyingMoveControl<>(this, 20, true);
        setNoGravity(true);
    }

    public static AttributeSupplier.Builder attributes() {
        TallyTuning tuning = TallyTuning.DEFAULT;
        return createMobAttributes()
                .add(Attributes.MOVEMENT_SPEED, tuning.flySpeed())
                .add(Attributes.FLYING_SPEED, tuning.flySpeed())
                .add(Attributes.MAX_HEALTH, tuning.maxHealth())
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0);
    }

    /** Loads the class so that the data id is assigned at startup, the same way on both sides. */
    static void init() {}

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(CLAMPED, false);
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        FlyingPathNavigation navigation = new FlyingPathNavigation(this, level);
        navigation.setCanOpenDoors(false);
        navigation.setCanFloat(true);
        return navigation;
    }

    /**
     * Whether a pod draws a Tally: a survival pilot aboard, power, no lights, and ore in the bay. A parked pod is left alone, and so is a wreck, a lit
     * pod, a pod flown by a creative player and a pod whose bay holds nothing to sample. A pod within the reach of a burning flare
     * ({@link DrawSources}) counts as lit, though it frees no Tally already clamped.
     */
    public static boolean draws(PodEntity pod) {
        return pod.getFirstPassenger() instanceof Player pilot
                && !pilot.isCreative()
                && !pilot.isSpectator()
                && PodEvents.isPowered(pod)
                && !PodLights.isLit(pod)
                && !(pod.level() instanceof ServerLevel level && DrawSources.litByFlare(level, pod.position()))
                && pod.cargo().isReadable()
                && !pod.cargo().entries().isEmpty();
    }

    /** Whether a person on foot draws a Tally: carrying ore, in the dark (block light under the level at which a place counts as lit). */
    public static boolean draws(Player person) {
        return !person.isSpectator()
                && !person.isCreative()
                && person.getVehicle() == null
                && firstOre(person) >= 0
                && person.level().getBrightness(LightLayer.BLOCK, person.blockPosition())
                        < CreatureTuning.DEFAULT.litBlockLight();
    }

    private static int firstOre(Player person) {
        for (int slot = 0; slot < person.getInventory().getContainerSize(); slot++) {
            if (OreRegistry.typeOf(person.getInventory().getItem(slot)).isPresent()) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean draws(Entity candidate) {
        return candidate instanceof PodEntity pod ? draws(pod) : candidate instanceof Player person && draws(person);
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        switch (phase) {
            case DRIFT -> drift(level);
            case TRAIL -> trail();
            case CLAMP -> clamp();
            case LEAVE -> leave();
        }
    }

    private void drift(ServerLevel level) {
        TallyTuning tuning = TallyTuning.DEFAULT;
        if (tickCount % CreatureTuning.DEFAULT.senseCheckTicks() == 0) {
            Entity found = Tallies.nearestMark(level, this, tuning.senseBlocks());
            if (found != null) {
                begin(found);
                return;
            }
        }
        if (++idle > tuning.idleTicks()) {
            vanish(level);
            return;
        }
        if (tickCount % WANDER_TICKS == 0) {
            BlockPos at = blockPosition()
                    .offset(
                            random.nextInt(-WANDER_BLOCKS, WANDER_BLOCKS + 1),
                            random.nextInt(-2, 3),
                            random.nextInt(-WANDER_BLOCKS, WANDER_BLOCKS + 1));
            if (LoadedBlocks.isLoaded(level, at)
                    && level.getBlockState(at).getCollisionShape(level, at).isEmpty()) {
                getNavigation().moveTo(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 1.0);
            }
        }
    }

    private void begin(Entity found) {
        mark = found;
        phase = Phase.TRAIL;
        trailed = 0;
        lost = 0;
        idle = 0;
        if (!cued) {
            cued = true;
            tell(found, Component.translatable("creature.deepcharter.tally_approach"));
            level().playSound(
                            null, getX(), getY(), getZ(), DeepSound.CREATURE_TALLY_CLICK.event(), SoundSource.HOSTILE);
        }
    }

    /** Follows its mark at a few blocks; clamps on once it has trailed it long enough, forgets it when it is lost for long. */
    private void trail() {
        TallyTuning tuning = TallyTuning.DEFAULT;
        Entity followed = mark;
        if (followed == null || followed.isRemoved() || !draws(followed)) {
            forget();
            return;
        }
        double distance = distanceTo(followed);
        if (distance <= tuning.senseBlocks()) {
            lost = 0;
            trailed++;
        } else if (++lost > tuning.forgetTicks()) {
            forget();
            return;
        }
        if (distance > tuning.trailGapBlocks() && tickCount % CreatureTuning.DEFAULT.senseCheckTicks() == 0) {
            getNavigation().moveTo(followed.getX(), followed.getY() + 1.0, followed.getZ(), 1.0);
        } else if (distance <= tuning.trailGapBlocks()) {
            getNavigation().stop();
        }
        face(followed);
        // It latches only from beside the mark: a Tally that cannot get there (walled off, or held back by rock) goes
        // on trailing and never clamps.
        if (trailed >= tuning.trailTicks() && distance <= tuning.trailGapBlocks() + 1) {
            latch(followed);
        }
    }

    private void forget() {
        mark = null;
        phase = Phase.DRIFT;
        trailed = 0;
        lost = 0;
        cued = false;
        getNavigation().stop();
    }

    private void latch(Entity held) {
        phase = Phase.CLAMP;
        dwell = 0;
        biteIn = 0;
        bitten = false;
        getNavigation().stop();
        noPhysics = true;
        entityData.set(CLAMPED, true);
        level().playSound(null, getX(), getY(), getZ(), DeepSound.CREATURE_TALLY_CLAMP.event(), SoundSource.HOSTILE);
        tell(
                held,
                Component.translatable(
                        held instanceof PodEntity
                                ? "creature.deepcharter.tally_clamp_pod"
                                : "creature.deepcharter.tally_clamp_foot"));
    }

    /** Holds the hatch or the pack. The cutter bites it while its pod drills; at the end of the hold it takes its sample. */
    private void clamp() {
        TallyTuning tuning = TallyTuning.DEFAULT;
        Entity held = mark;
        if (held == null || held.isRemoved() || !held.isAlive()) {
            unclamp();
            forget();
            return;
        }
        if (held instanceof PodEntity pod) {
            if (pod.drilling()) {
                cut(pod, tuning);
            } else {
                biteIn = 0;
            }
        }
        holdTo(held, tuning);
        int hold = held instanceof PodEntity ? tuning.podSampleTicks() : tuning.footSampleTicks();
        if (++dwell >= hold) {
            sample(held);
        }
    }

    /** The pod's cutter bites the Tally on its hatch: the first bite as the drill starts, then one each {@code biteTicks}. */
    private void cut(PodEntity pod, TallyTuning tuning) {
        if (biteIn-- > 0) {
            return;
        }
        biteIn = tuning.biteTicks() - 1;
        if (hurtServer(
                        (ServerLevel) level(),
                        new DamageSource(level().damageSources().generic().typeHolder(), pod),
                        tuning.biteDamage())
                && !bitten) {
            bitten = true;
            tell(pod, Component.translatable("creature.deepcharter.tally_bitten"));
        }
    }

    private void holdTo(Entity held, TallyTuning tuning) {
        Vec3 place = placeOn(held);
        Vec3 gap = place.subtract(position());
        double distance = gap.length();
        setPos(distance <= tuning.latchSpeed() ? place : position().add(gap.scale(tuning.latchSpeed() / distance)));
        setDeltaMovement(Vec3.ZERO);
        face(held);
    }

    /** The cargo hatch is at the back of the pod, low on the hull; a person's pack is at their back. */
    private static Vec3 placeOn(Entity held) {
        if (held instanceof PodEntity pod) {
            var back = pod.getDirection().getOpposite();
            double reach = pod.getBbWidth() / 2 + HATCH_GAP;
            return new Vec3(
                    pod.getX() + back.getStepX() * reach,
                    pod.getY() + pod.getBbHeight() * HATCH_HEIGHT_SHARE,
                    pod.getZ() + back.getStepZ() * reach);
        }
        Vec3 look = Vec3.directionFromRotation(0, held.getYRot());
        return new Vec3(held.getX() - look.x * PACK_GAP, held.getY() + PACK_HEIGHT, held.getZ() - look.z * PACK_GAP);
    }

    private void face(Entity toward) {
        // Yaw 0 looks toward +z and yaw 90 toward -x, so the direction to the mark (dx, dz) is atan2(-dx, dz).
        float yaw = (float) (Mth.atan2(getX() - toward.getX(), toward.getZ() - getZ()) * Mth.RAD_TO_DEG);
        setYRot(yaw);
        setYHeadRot(yaw);
        setYBodyRot(yaw);
    }

    /**
     * Takes one ore, the cheapest in the bay or the pack, tells the owner, and flies off. Takes nothing from an empty bay. The take has
     * its own cue (#1450), played where the Tally is, so the person robbed hears it at their hatch or their back, and anyone near hears
     * what a Tally does: the counter ticks over and a receipt is stamped.
     */
    private void sample(Entity held) {
        Component name = held instanceof PodEntity pod ? takeFromBay(pod) : takeFromPack((Player) held);
        if (name != null) {
            level().playSound(null, getX(), getY(), getZ(), DeepSound.CREATURE_TALLY_TAKE.event(), SoundSource.HOSTILE);
            tell(held, Component.translatable("creature.deepcharter.tally_sample", name));
        }
        unclamp();
        mark = null;
        phase = Phase.LEAVE;
        leaving = 0;
    }

    /** The cheapest ore in the bay, the oldest of equals: a sample is a pinch, and the Company counts, it does not pick the best. */
    private static @Nullable Component takeFromBay(PodEntity pod) {
        PodCargo cargo = pod.cargo();
        if (!cargo.isReadable() || cargo.entries().isEmpty()) {
            return null;
        }
        ItemStack cheapest = cargo.entries().getFirst().stack();
        for (PodCargo.Entry entry : cargo.entries()) {
            if (valueOf(entry.stack()) < valueOf(cheapest)) {
                cheapest = entry.stack();
            }
        }
        Component name = cheapest.getHoverName();
        cargo.take(pod, OreRegistry.typeOf(cheapest).orElseThrow(), 1);
        return name;
    }

    /** The cheapest ore in the pack, the first of equals. */
    private static @Nullable Component takeFromPack(Player person) {
        int slot = firstOre(person);
        if (slot < 0) {
            return null;
        }
        for (int other = slot + 1; other < person.getInventory().getContainerSize(); other++) {
            ItemStack stack = person.getInventory().getItem(other);
            if (OreRegistry.typeOf(stack).isPresent()
                    && valueOf(stack) < valueOf(person.getInventory().getItem(slot))) {
                slot = other;
            }
        }
        ItemStack stack = person.getInventory().getItem(slot);
        Component name = stack.getHoverName();
        stack.shrink(1);
        person.getInventory().setChanged();
        person.containerMenu.broadcastChanges();
        return name;
    }

    private static int valueOf(ItemStack ore) {
        return OreRegistry.typeOf(ore).orElseThrow().value();
    }

    private void unclamp() {
        noPhysics = false;
        entityData.set(CLAMPED, false);
    }

    private void leave() {
        if (leaving++ == 0) {
            getNavigation().moveTo(getX(), getY() + LEAVE_CLIMB, getZ(), 1.0);
        }
        if (leaving >= TallyTuning.DEFAULT.leaveTicks() && level() instanceof ServerLevel level) {
            vanish(level);
        }
    }

    /** Goes without a trace: it is not killed, so no one is told it broke. */
    private void vanish(ServerLevel level) {
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, getX(), getY() + 0.2, getZ(), SPARKS, 0.2, 0.2, 0.2, 0.05);
        discard();
    }

    /** Tells everyone aboard the pod, or the person on foot. */
    private void tell(Entity owner, Component line) {
        if (owner instanceof PodEntity pod) {
            for (Entity rider : pod.getPassengers()) {
                if (rider instanceof ServerPlayer player) {
                    player.sendOverlayMessage(line);
                }
            }
        } else if (owner instanceof ServerPlayer person) {
            person.sendOverlayMessage(line);
        }
    }

    /** Only a person's hand (a blow, an arrow) and the cutter of a pod hurt it; what the world does to it does not. */
    @Override
    public boolean isInvulnerableTo(ServerLevel level, DamageSource source) {
        return !(source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)
                || source.getEntity() instanceof Player
                || source.getDirectEntity() instanceof PodEntity);
    }

    /** One blow breaks it: a person's hit takes all its health, however light. The cutter takes it a bite at a time. */
    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
        float taken = source.getEntity() instanceof Player ? getMaxHealth() : damage;
        boolean hurt = super.hurtServer(level, source, taken);
        if (hurt) {
            level.sendParticles(
                    ParticleTypes.ELECTRIC_SPARK, getX(), getY() + 0.2, getZ(), SPARKS, 0.2, 0.2, 0.2, 0.05);
        }
        return hurt;
    }

    /** It lets go, its owner is told, and it comes apart. It drops nothing. */
    @Override
    public void die(DamageSource source) {
        boolean alreadyDead = dead;
        Entity held = mark;
        super.die(source);
        if (alreadyDead || !(level() instanceof ServerLevel level)) {
            return;
        }
        if (held != null && phase == Phase.CLAMP) {
            tell(held, Component.translatable("creature.deepcharter.tally_broken"));
        }
        unclamp();
        mark = null;
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, getX(), getY() + 0.2, getZ(), SPARKS * 3, 0.3, 0.3, 0.3, 0.1);
        level.sendParticles(ParticleTypes.SMOKE, getX(), getY() + 0.2, getZ(), SPARKS, 0.2, 0.2, 0.2, 0.01);
    }

    @Override
    protected boolean shouldDropLoot(ServerLevel level) {
        return false;
    }

    @Override
    public boolean shouldDropExperience() {
        return false;
    }

    @Override
    protected @Nullable SoundEvent getAmbientSound() {
        return DeepSound.CREATURE_TALLY_CLICK.event();
    }

    @Override
    public int getAmbientSoundInterval() {
        return WANDER_TICKS;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return DeepSound.CREATURE_TALLY_BREAK.event();
    }

    @Override
    protected SoundEvent getDeathSound() {
        return DeepSound.CREATURE_TALLY_BREAK.event();
    }

    @Override
    public boolean canBeLeashed() {
        return false;
    }

    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return true;
    }

    /** 0 while it lives, up to 1 as a dead Tally comes apart. */
    public float fadeFraction() {
        return Math.min(1f, deathTime / (float) DISSOLVE_TICKS);
    }

    /** Whether the Tally holds a hatch or a pack. */
    public boolean isClamped() {
        return entityData.get(CLAMPED);
    }
}
