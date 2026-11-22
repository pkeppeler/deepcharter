package io.github.pkeppeler.deepcharter.creature;

import io.github.pkeppeler.deepcharter.sound.DeepSound;
import java.util.Optional;
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
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A Drifter (#973, CREATURES.md section 5): a pod-sized spore bladder that drifts toward what is loud ({@link Drifters}) and bursts into gas
 * ({@link SporeClouds}) on touch or when a pod drills beside it, but only after {@link DrifterTuning#armTicks()} of hearing a noise, so there
 * is always a tell. A hit that pops it raises the alarm of its zone ({@link DrifterAlarm}); a burst of its own raises nothing. Sonar does not
 * exist yet: the entity type tag {@code deepcharter:sonar_echo/empty_cave} says it reads as empty cave, for #924. It is not saved.
 */
public class Drifter extends Mob {
    /** Whether the drifter is drawn to a noise now. Synced, for the client's pulse. */
    private static final EntityDataAccessor<Boolean> DRAWN =
            SynchedEntityData.defineId(Drifter.class, EntityDataSerializers.BOOLEAN);
    /** How much of the gap between its drift and the one it wants it closes each tick: it eases into a turn, it does not snap. */
    private static final double EASE = 0.1;
    /** Ticks between two new wander headings. */
    private static final int WANDER_TICKS = 100;
    /** Flecks of spore a burst shakes out, and what a hiss shows. */
    private static final int BURST_FLECKS = 60;

    private static final int HISS_FLECKS = 3;
    /** How far a hiss carries, as the sound's volume: 1 is 16 blocks, and a drawn drifter hisses louder. */
    private static final float HISS_VOLUME = 0.8f;

    private static final float HISS_VOLUME_DRAWN = 1.6f;

    /** Where it drifts now, blocks a tick: the field is the intent, the entity's own movement is only what the game does with it. */
    private Vec3 drift = Vec3.ZERO;
    /** The place of the last noise it heard, null when it has heard none. Server only. */
    private @Nullable Vec3 memory;
    /** The game time of the last noise it heard. */
    private long lastHeard = Long.MIN_VALUE / 2;
    /** Ticks it has been drawn without a break. */
    private int drawnTicks;
    /** Whether the person it is drawn to has been told that it comes. */
    private boolean cued;
    /** Ticks to the next hiss. */
    private int hissIn;
    /** The way it wanders when nothing is loud, and ticks to the next one. */
    private Vec3 wander = Vec3.ZERO;

    private int wanderIn;

    public Drifter(EntityType<? extends Drifter> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder attributes() {
        return createMobAttributes().add(Attributes.MAX_HEALTH, DrifterTuning.DEFAULT.maxHealth());
    }

    /** Loads the class so that the data id is assigned at startup, the same way on both sides. */
    static void init() {}

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DRAWN, false);
    }

    /** Makes it drawn to {@code place} as it appears, so a drifter the spawner places comes to the drill. */
    void hunt(Vec3 place) {
        memory = place;
        lastHeard = level().getGameTime();
    }

    /** Whether the drifter is drawn to a noise now. */
    public boolean isDrawn() {
        return entityData.get(DRAWN);
    }

    /** Drifts toward the noise it hears, or on; bursts when it is armed and touched. */
    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        // Peaceful removes monsters: one the mod placed before the difficulty was lowered goes too.
        if (level.getDifficulty() == Difficulty.PEACEFUL) {
            discard();
            return;
        }
        DrifterTuning tuning = DrifterTuning.DEFAULT;
        double sense =
                DrifterAlarm.isRinging(level, getBlockY()) ? tuning.alarm().senseMult() : 1.0;
        Optional<Drifters.Noise> heard = Drifters.heardBy(level, position(), sense);
        if (heard.isPresent()) {
            memory = heard.get().pos();
            lastHeard = level.getGameTime();
            if (!cued) {
                cued = warn(level, heard.get());
            }
        }
        boolean drawn = memory != null && level.getGameTime() - lastHeard <= tuning.calmTicks();
        // The tell counts only the ticks a noise is heard: a drifter that has just lost its noise is on its way past,
        // and bursts at nothing.
        drawnTicks = heard.isPresent() ? drawnTicks + 1 : drawn ? drawnTicks : 0;
        if (drawn != isDrawn()) {
            entityData.set(DRAWN, drawn);
        }
        steer(level, drawn, tuning);
        if (heard.isPresent() && drawnTicks >= tuning.armTicks() && Drifters.touched(level, this)) {
            pop(level, false);
            return;
        }
        hiss(level, drawn, tuning);
    }

    /** The person whose noise draws it hears and reads it before it arrives: seconds of tell. True once someone has been told. */
    private boolean warn(ServerLevel level, Drifters.Noise noise) {
        if (!(level.getPlayerByUUID(noise.listener()) instanceof ServerPlayer person)) {
            return false;
        }
        person.sendOverlayMessage(Component.translatable(
                noise.pod() ? "creature.deepcharter.drifter_pod" : "creature.deepcharter.drifter_foot"));
        level.playSound(
                null,
                getX(),
                getY(),
                getZ(),
                DeepSound.CREATURE_DRIFTER_HISS.event(),
                SoundSource.HOSTILE,
                HISS_VOLUME_DRAWN,
                0.8f);
        return true;
    }

    /**
     * Eases its drift toward the noise at the drift speed, or toward the way it wanders. A decoy in reach of it is the noise it heads for,
     * not the one it was drawn to ({@link DrawSources#noiseTarget}).
     */
    private void steer(ServerLevel level, boolean drawn, DrifterTuning tuning) {
        Vec3 want;
        if (drawn && memory != null) {
            Vec3 centre = position().add(0, getBbHeight() / 2, 0);
            Vec3 to = DrawSources.noiseTarget(level, centre, memory).subtract(centre);
            want = to.lengthSqr() < 1.0e-4 ? Vec3.ZERO : to.normalize().scale(tuning.driftSpeed());
        } else {
            if (wanderIn-- <= 0 || horizontalCollision || verticalCollision) {
                wanderIn = WANDER_TICKS;
                double angle = random.nextDouble() * Math.PI * 2;
                wander = new Vec3(Math.cos(angle), (random.nextDouble() - 0.5) * 0.4, Math.sin(angle))
                        .normalize()
                        .scale(tuning.wanderSpeed());
            }
            want = wander;
        }
        drift = drift.add(want.subtract(drift).scale(EASE));
        setDeltaMovement(drift);
    }

    private void hiss(ServerLevel level, boolean drawn, DrifterTuning tuning) {
        if (hissIn-- > 0) {
            return;
        }
        hissIn = tuning.hissTicks() / 2 + random.nextInt(tuning.hissTicks() / 2 + 1);
        level.playSound(
                null,
                getX(),
                getY(),
                getZ(),
                DeepSound.CREATURE_DRIFTER_HISS.event(),
                SoundSource.HOSTILE,
                drawn ? HISS_VOLUME_DRAWN : HISS_VOLUME,
                0.9f + random.nextFloat() * 0.2f);
        level.sendParticles(
                ParticleTypes.SPORE_BLOSSOM_AIR,
                getX(),
                getY() + getBbHeight() / 2,
                getZ(),
                HISS_FLECKS,
                getBbWidth() / 2,
                getBbHeight() / 2,
                getBbWidth() / 2,
                0.0);
    }

    /** Bursts into gas where it floats: with {@code killed}, a hit did it and the zone's alarm rings; a burst on touch raises nothing. */
    private void pop(ServerLevel level, boolean killed) {
        Vec3 middle = position().add(0, getBbHeight() / 2, 0);
        SporeClouds.release(level, middle);
        level.playSound(
                null,
                getX(),
                getY(),
                getZ(),
                DeepSound.CREATURE_DRIFTER_BURST.event(),
                SoundSource.HOSTILE,
                1.5f,
                1.0f);
        level.sendParticles(
                ParticleTypes.SPORE_BLOSSOM_AIR,
                middle.x,
                middle.y,
                middle.z,
                BURST_FLECKS,
                getBbWidth() / 2,
                getBbHeight() / 2,
                getBbWidth() / 2,
                0.05);
        if (killed) {
            DrifterAlarm.raise(level, getBlockY());
        }
        discard();
    }

    /** It bursts, and the zone's alarm rings. */
    @Override
    public void die(DamageSource source) {
        boolean alreadyDead = dead;
        super.die(source);
        if (!alreadyDead && level() instanceof ServerLevel level) {
            pop(level, true);
        }
    }

    /** Only a person's hand, a projectile and an explosion pop it; what the world does to it, a fall and fire included, does not. */
    @Override
    public boolean isInvulnerableTo(ServerLevel level, DamageSource source) {
        return !(source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)
                || source.is(DamageTypeTags.IS_PROJECTILE)
                || source.is(DamageTypeTags.IS_EXPLOSION)
                || source.getEntity() instanceof Player);
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return DeepSound.CREATURE_DRIFTER_HISS.event();
    }

    @Override
    protected @Nullable SoundEvent getDeathSound() {
        return null;
    }

    /** A bladder of gas floats. */
    @Override
    public boolean isNoGravity() {
        return true;
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
}
