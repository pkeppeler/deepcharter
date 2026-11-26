package io.github.pkeppeler.deepcharter.creature;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodFuel;
import io.github.pkeppeler.deepcharter.pod.PodLights;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import java.util.Comparator;
import net.minecraft.core.Direction;
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
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A Retained: a veteran of the Workings that the Company rebuilt too often, and that stopped using its lamp. A normal mob of the deep
 * layers (never a boss, ADR 0046): it never attacks anyone, and it does not fade for a light or for a person near it. It walks on
 * whatever happens and comes to a dark pod, one with a pilot, power and no lights. It grabs the hull and takes fuel, and does not let go
 * until it is dead (ADR 0046, amended for #485): the pod's cutter bites it while the pod drills, and a person's blow hurts it too.
 * A person on foot in the dark with no light in either hand draws one too, which clings, weighs on them like ore and drains their
 * hunger to a floor until it is dead ({@link FootGrips}, ADR 0046 amended for #916).
 * A lit lamp in the helmet, tiny and dim, shows them from afar. It is not saved: a figure whose chunk unloads is gone, and the level makes another.
 */
public class LamplessFigure extends PathfinderMob {
    /** Whether the figure holds a pod's hull. Synced, for the client's pose. */
    private static final EntityDataAccessor<Boolean> CLINGING =
            SynchedEntityData.defineId(LamplessFigure.class, EntityDataSerializers.BOOLEAN);
    /** Whether the lamp in the figure's helmet is lit. Synced, for the client's glow. */
    private static final EntityDataAccessor<Boolean> LAMP =
            SynchedEntityData.defineId(LamplessFigure.class, EntityDataSerializers.BOOLEAN);
    /** How far ahead along its heading the figure aims, so that it never reaches the point it walks to. */
    private static final double LOOK_AHEAD = 4;
    /** Ticks a dead figure takes to dissolve: the game's own length of a death. */
    private static final int DISSOLVE_TICKS = 20;
    /** Flecks of ash a hit shakes off, and what a death gives: smoke and ash. */
    private static final int HURT_ASH = 8;

    private static final int DEATH_SMOKE = 14;
    private static final int DEATH_ASH = 30;
    /** How far off the hull the figure stands, beyond the half width of the pod. */
    private static final double HULL_GAP = 0.3;
    /** The game's own exhaustion for one point of hunger. */
    private static final float EXHAUSTION_PER_POINT = 4f;

    private static final float TICKS_PER_SECOND = 20f;

    private Direction heading = Direction.NORTH;
    /** The pod this figure holds, null when it holds none. Server only. */
    private @Nullable PodEntity grip;
    /** The pod this figure walks to, null when none draws it. Server only. */
    private @Nullable PodEntity target;
    /** The person on foot this figure holds, null when it holds none. Server only. */
    private @Nullable ServerPlayer held;
    /** The person on foot this figure walks to, null when none draws it. Server only. */
    private @Nullable ServerPlayer quarry;
    /** Whether the game's spawner made this figure: only those go when no player is near, so that the level's cap is not held by figures nobody will meet. Naturals have a cap and a delay of their own. */
    private boolean natural;
    /** Whether the pilot of a pod has been told that this figure comes. Server only. */
    private boolean cued;
    /** The side of the hull the figure holds, as the direction from the pod to the figure. */
    private Direction side = Direction.NORTH;
    /** Ticks until the cutter bites again; 0 bites at once. */
    private int biteIn;
    /** Whether the pilot has been told that the cutter bites, this grip. */
    private boolean bitten;

    public LamplessFigure(EntityType<? extends LamplessFigure> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder attributes() {
        return createMobAttributes()
                .add(Attributes.MOVEMENT_SPEED, CreatureTuning.DEFAULT.walkSpeed())
                .add(Attributes.MAX_HEALTH, CreatureTuning.DEFAULT.maxHealth())
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0);
    }

    /** Loads the class so that the data id is assigned at startup, the same way on both sides. */
    static void init() {}

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(CLINGING, false);
        builder.define(LAMP, false);
    }

    /** A figure the spawner or a command makes carries the dim light in its helmet (ADR 0046, amended for #506); the rail's figure of Prospector's Run does not. */
    @Override
    public @Nullable SpawnGroupData finalizeSpawn(
            ServerLevelAccessor level,
            DifficultyInstance difficulty,
            EntitySpawnReason reason,
            @Nullable SpawnGroupData groupData) {
        setLamp(true);
        if (reason == EntitySpawnReason.NATURAL) {
            markNatural();
        }
        return super.finalizeSpawn(level, difficulty, reason, groupData);
    }

    /** The natural spawn's own rules: {@link LamplessFigures#mayAppearNaturally}. Every other way to make one is a caller's choice. */
    @Override
    public boolean checkSpawnRules(LevelAccessor level, EntitySpawnReason reason) {
        return reason != EntitySpawnReason.NATURAL
                || (level instanceof ServerLevelAccessor accessor
                        && LamplessFigures.mayAppearNaturally(accessor.getLevel(), blockPosition(), level.getRandom()));
    }

    @Override
    public int getMaxSpawnClusterSize() {
        return 1;
    }

    /**
     * Walks on whatever happens, turning at a wall. A dark pod within reach of its senses draws it, and it grabs the hull and takes fuel
     * until it dies.
     */
    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        CreatureTuning tuning = CreatureTuning.DEFAULT;
        PodEntity gripped = grip;
        if (gripped != null) {
            cling(gripped, tuning.cling());
            return;
        }
        ServerPlayer person = held;
        if (person != null) {
            clingToPerson(person, level, tuning);
            return;
        }
        boolean checking = tickCount % tuning.senseCheckTicks() == 0;
        if (checking) {
            target = nearestDarkPod(level, tuning.cling());
            quarry = target == null ? nearestDarkPerson(level, tuning.foot()) : null;
            if (target != null && !cued) {
                cued = warn(target);
            } else if (quarry != null && !cued) {
                cued = warnPerson(quarry);
            }
        }
        if (target != null && !target.isRemoved()) {
            approach(level, target, tuning.cling(), checking);
        } else if (quarry != null && !quarry.isRemoved()) {
            approachPerson(quarry, tuning, checking);
        } else {
            wander();
        }
    }

    /** A person on foot hears it and reads it before it arrives, and the first time is told the rule. True once told. */
    private boolean warnPerson(ServerPlayer person) {
        person.sendOverlayMessage(Component.translatable("creature.deepcharter.foot_approach"));
        person.level()
                .playSound(
                        null,
                        person.getX(),
                        person.getY(),
                        person.getZ(),
                        DeepSound.CREATURE_APPROACH.event(),
                        SoundSource.HOSTILE);
        FootGrips.explainFirstExposure(person);
        return true;
    }

    private void approachPerson(ServerPlayer person, CreatureTuning tuning, boolean checking) {
        if (person.getBoundingBox().inflate(tuning.foot().reachBlocks()).intersects(getBoundingBox())) {
            // The person was picked up to a look ago: they may have lit a light, boarded a pod or been taken by another
            // figure.
            if (!FootGrips.draws(person)) {
                quarry = null;
                return;
            }
            latchOnto(person);
        } else if (checking) {
            getNavigation().moveTo(person.getX(), person.getY(), person.getZ(), 1.0);
        }
    }

    private void latchOnto(ServerPlayer person) {
        held = person;
        quarry = null;
        side = pickSide(person);
        getNavigation().stop();
        setNoGravity(true);
        noPhysics = true;
        entityData.set(CLINGING, true);
        FootGrips.take(person, this);
        person.sendOverlayMessage(Component.translatable("creature.deepcharter.foot_grab"));
    }

    /**
     * Holds the person and drains their hunger down to the floor, until it dies or the person is gone (dead, left, in another level,
     * or creative or spectator). It never takes the food level below the floor, so it never starves anyone.
     */
    private void clingToPerson(ServerPlayer person, ServerLevel level, CreatureTuning tuning) {
        if (level.getDifficulty() == Difficulty.PEACEFUL
                || person.isRemoved()
                || !person.isAlive()
                || person.level() != level
                || person.isCreative()
                || person.isSpectator()) {
            release();
            return;
        }
        CreatureTuning.Foot foot = tuning.foot();
        FoodData food = person.getFoodData();
        if (food.getFoodLevel() > foot.hungerFloor()) {
            food.addExhaustion(EXHAUSTION_PER_POINT / (foot.secondsPerPoint() * TICKS_PER_SECOND));
        }
        holdTo(person, tuning.cling());
    }

    /** The nearest person on foot that this figure would take hold of, or null. */
    private @Nullable ServerPlayer nearestDarkPerson(ServerLevel level, CreatureTuning.Foot foot) {
        // Players come from the level's own list (a player's chunk is always loaded), so this is no entity query. The
        // distance test runs first: the light and hand checks in draws() cost more.
        double sense = foot.senseBlocks();
        return level.players().stream()
                .filter(person -> person.distanceToSqr(this) <= sense * sense && FootGrips.draws(person))
                .min(Comparator.comparingDouble(person -> person.distanceToSqr(this)))
                .orElse(null);
    }

    /** The pilot hears it and reads it before it arrives: a pod drives faster than it walks. True once a pilot has been told. */
    private boolean warn(PodEntity pod) {
        if (!(pod.getControllingPassenger() instanceof ServerPlayer pilot)) {
            return false;
        }
        pilot.sendOverlayMessage(Component.translatable("creature.deepcharter.approach"));
        pod.level()
                .playSound(
                        null,
                        pod.getX(),
                        pod.getY(),
                        pod.getZ(),
                        DeepSound.CREATURE_APPROACH.event(),
                        SoundSource.HOSTILE);
        return true;
    }

    private void wander() {
        if (horizontalCollision) {
            heading = heading.getOpposite();
        }
        Vec3 ahead = position().add(heading.getStepX() * LOOK_AHEAD, 0, heading.getStepZ() * LOOK_AHEAD);
        getMoveControl().setWantedPosition(ahead.x, ahead.y, ahead.z, 1.0);
    }

    private void approach(ServerLevel level, PodEntity pod, CreatureTuning.Cling cling, boolean checking) {
        if (pod.getBoundingBox().inflate(cling.reachBlocks()).intersects(getBoundingBox())) {
            // The target was picked up to a look ago: another figure may have taken hold since, and a pod has one grip.
            if (isHeldByAnother(level, pod, cling)) {
                target = null;
                return;
            }
            latch(pod);
        } else if (checking) {
            getNavigation().moveTo(pod.getX(), pod.getY(), pod.getZ(), 1.0);
        }
    }

    /** The side of {@code target} the figure holds: the way it came from, never up or down. */
    private Direction pickSide(Entity target) {
        Direction found = Direction.getApproximateNearest(getX() - target.getX(), 0, getZ() - target.getZ());
        return found.getAxis().isVertical() ? Direction.NORTH : found;
    }

    private void latch(PodEntity pod) {
        grip = pod;
        target = null;
        biteIn = 0;
        bitten = false;
        side = pickSide(pod);
        getNavigation().stop();
        setNoGravity(true);
        noPhysics = true;
        entityData.set(CLINGING, true);
        if (pod.getControllingPassenger() instanceof ServerPlayer pilot) {
            pilot.sendOverlayMessage(Component.translatable("creature.deepcharter.grab"));
        }
    }

    /** Holds the hull and takes its fuel, until the pod is gone or the figure is dead. The cutter bites it while the pod drills. */
    private void cling(PodEntity pod, CreatureTuning.Cling cling) {
        if (pod.isRemoved()) {
            release();
            return;
        }
        PodFuel.drain(pod, cling.drainLitres() / 20f, cling.floorPercent());
        if (pod.drilling()) {
            cut(pod, cling);
        } else {
            biteIn = 0;
        }
        holdTo(pod, cling);
    }

    /** The pod's cutter bites the figure on its hull: the first bite as the drill starts, then one each {@code biteTicks}. */
    private void cut(PodEntity pod, CreatureTuning.Cling cling) {
        if (biteIn-- > 0) {
            return;
        }
        biteIn = cling.biteTicks() - 1;
        if (hurtServer(
                        (ServerLevel) level(),
                        new DamageSource(level().damageSources().generic().typeHolder(), pod),
                        cling.biteDamage())
                && !bitten
                && pod.getControllingPassenger() instanceof ServerPlayer pilot) {
            bitten = true;
            pilot.sendOverlayMessage(Component.translatable("creature.deepcharter.bitten"));
        }
    }

    /** Closes on its place beside what it holds, a hull or a person, side by side, and faces it. */
    private void holdTo(Entity target, CreatureTuning.Cling cling) {
        double reach = target.getBbWidth() / 2 + HULL_GAP;
        Vec3 place = new Vec3(
                target.getX() + side.getStepX() * reach, target.getY(), target.getZ() + side.getStepZ() * reach);
        Vec3 gap = place.subtract(position());
        double distance = gap.length();
        setPos(distance <= cling.latchSpeed() ? place : position().add(gap.scale(cling.latchSpeed() / distance)));
        setDeltaMovement(Vec3.ZERO);
        // Yaw 0 looks toward +z and yaw 90 toward -x, so the direction to the pod (dx, dz) is atan2(-dx, dz): the
        // figure looks at the pod, not away from it (#506).
        float yaw = (float) (Mth.atan2(getX() - target.getX(), target.getZ() - getZ()) * Mth.RAD_TO_DEG);
        setYRot(yaw);
        setYHeadRot(yaw);
        setYBodyRot(yaw);
    }

    private void release() {
        ServerPlayer person = held;
        if (person != null) {
            FootGrips.release(person, this);
        }
        held = null;
        grip = null;
        setNoGravity(false);
        noPhysics = false;
        entityData.set(CLINGING, false);
    }

    /** The nearest pod in sense that this figure would grab, or null. */
    private @Nullable PodEntity nearestDarkPod(ServerLevel level, CreatureTuning.Cling cling) {
        double sense = cling.senseBlocks();
        // entity-query: round a ticking figure, for a pod with a pilot; a pod it does not see only goes undrawn this
        // check
        return level
                .getEntitiesOfClass(
                        PodEntity.class,
                        getBoundingBox().inflate(sense),
                        pod -> pod.distanceToSqr(this) <= sense * sense
                                && draws(pod)
                                && !isHeldByAnother(level, pod, cling))
                .stream()
                .min(Comparator.comparingDouble(pod -> pod.distanceToSqr(this)))
                .orElse(null);
    }

    private boolean isHeldByAnother(ServerLevel level, PodEntity pod, CreatureTuning.Cling cling) {
        // entity-query: the figures within reach of a pod that has a pilot, so all within 30 blocks of the pilot, which
        // tick at the lowest simulation distance (EntityQueryAuditTest measures it)
        return !level.getEntitiesOfClass(
                        LamplessFigure.class,
                        pod.getBoundingBox().inflate(cling.reachBlocks() + 1),
                        other -> other != this && other.grip == pod)
                .isEmpty();
    }

    /**
     * Whether a pod draws a figure: a person aboard, power, no lights, and fuel above the floor. A parked pod is left alone, and so is a
     * wreck (no power) and a lit pod. A pod within the reach of a burning flare ({@link DrawSources}) counts as lit, though it frees no one a
     * figure already holds.
     */
    public static boolean draws(PodEntity pod) {
        return pod.getFirstPassenger() instanceof Player
                && PodEvents.isPowered(pod)
                && !PodLights.isLit(pod)
                && !(pod.level() instanceof ServerLevel level && DrawSources.litByFlare(level, pod.position()))
                && pod.fuel() > CreatureTuning.DEFAULT.cling().floorPercent();
    }

    /** Only a person's hand (a blow, an arrow) and the cutter of a pod hurt it; what the world does to it, a fall and an explosion included, does not. */
    @Override
    public boolean isInvulnerableTo(ServerLevel level, DamageSource source) {
        return !(source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)
                || source.getEntity() instanceof Player
                || source.getDirectEntity() instanceof PodEntity);
    }

    /** A hit shows as ash shaken off it; the sound is {@link #getHurtSound}. */
    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
        boolean hurt = super.hurtServer(level, source, damage);
        if (hurt) {
            level.sendParticles(ParticleTypes.ASH, getX(), getY() + 1.0, getZ(), HURT_ASH, 0.25, 0.6, 0.25, 0.02);
        }
        return hurt;
    }

    /** It lets go of the hull, the pilot is told, and it dissolves into smoke and ash. */
    @Override
    public void die(DamageSource source) {
        boolean alreadyDead = dead;
        super.die(source);
        if (alreadyDead || !(level() instanceof ServerLevel level)) {
            return;
        }
        if (grip != null && grip.getControllingPassenger() instanceof ServerPlayer pilot) {
            pilot.sendOverlayMessage(Component.translatable("creature.deepcharter.dead"));
        }
        if (held != null) {
            held.sendOverlayMessage(Component.translatable("creature.deepcharter.foot_dead"));
        }
        release();
        dissolve(level, this);
        LamplessFigures.killed(level, natural, getBlockY());
    }

    /** The smoke and ash a dead body goes up in: a Retained's, and a person's at a Continuity Event (#1337). */
    public static void dissolve(ServerLevel level, Entity body) {
        level.sendParticles(
                ParticleTypes.LARGE_SMOKE,
                body.getX(),
                body.getY() + 1.0,
                body.getZ(),
                DEATH_SMOKE,
                0.3,
                0.8,
                0.3,
                0.02);
        level.sendParticles(
                ParticleTypes.ASH, body.getX(), body.getY() + 1.0, body.getZ(), DEATH_ASH, 0.4, 0.9, 0.4, 0.02);
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return DeepSound.CREATURE_HURT.event();
    }

    @Override
    protected SoundEvent getDeathSound() {
        return DeepSound.CREATURE_DEATH.event();
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
        return natural;
    }

    public void setHeading(Direction heading) {
        if (heading.getAxis().isVertical()) {
            throw new IllegalArgumentException("a figure walks the level, not " + heading);
        }
        this.heading = heading;
    }

    /** 0 while it lives, up to 1 as a dead figure dissolves. */
    public float fadeFraction() {
        return Math.min(1f, deathTime / (float) DISSOLVE_TICKS);
    }

    /** Whether the figure holds a pod's hull. */
    public boolean isClinging() {
        return entityData.get(CLINGING);
    }

    /** Marks a figure the game's spawner made. */
    public void markNatural() {
        natural = true;
    }

    public boolean isNatural() {
        return natural;
    }

    public boolean hasLamp() {
        return entityData.get(LAMP);
    }

    // const-param: the lamp state is a creature-design knob the creatures session owns; some Retained carry a lamp per
    // LORE section 11
    public void setLamp(boolean lamp) {
        entityData.set(LAMP, lamp);
    }
}
