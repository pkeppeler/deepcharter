package io.github.pkeppeler.deepcharter.creature;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The spore gas a Drifter leaves when it bursts (#973). A cloud hangs where the bladder was for {@link DrifterTuning.Cloud#ticks()}, a
 * ball of {@link DrifterTuning.Cloud#radius()} blocks. A pod with a person aboard whose hull is in it loses hull for every tick, for each
 * cloud it is in (the pilot is shielded by the hull, as from lava and gas pockets); a person on foot in it is poisoned and blinded. A wreck
 * and an empty pod take nothing. Clouds are held in memory and never saved: one that a restart ends was only seconds long.
 */
public final class SporeClouds {
    /**
     * One cloud.
     *
     * @param dimension the level it hangs in
     * @param center    its middle
     * @param born      the game time it burst
     * @param warned    the pods whose pilot has been told, so the line comes once for each pod and cloud
     */
    private record Cloud(ResourceKey<Level> dimension, Vec3 center, long born, Set<UUID> warned) {}

    /** The gas's colour: the cold green of the drifter's own light. */
    private static final int GREEN = 0x7CE0A0;
    /** A mote of the gas, and the puff of the burst: dust tinted green, the burst's larger. */
    private static final DustParticleOptions GAS = new DustParticleOptions(GREEN, 2.0f);

    private static final DustParticleOptions BURST = new DustParticleOptions(GREEN, 3.5f);
    /** Ticks between two puffs of the gas, motes in each, and the bound on what a cloud sends: 20 a tick at most, for 8 seconds. */
    private static final int PUFF_EVERY_TICKS = 2;

    private static final int PUFF_PARTICLES = 40;
    private static final int SPORES = 8;
    private static final int BURST_PARTICLES = 90;

    /** Server thread only. */
    private static final List<Cloud> CLOUDS = new ArrayList<>();

    private SporeClouds() {}

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> CLOUDS.clear());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> CLOUDS.clear());
        ServerTickEvents.END_LEVEL_TICK.register(SporeClouds::tick);
    }

    /** Lets a cloud out at {@code center}. */
    public static void release(ServerLevel level, Vec3 center) {
        CLOUDS.add(new Cloud(level.dimension(), center, level.getGameTime(), new HashSet<>()));
        double radius = DrifterTuning.DEFAULT.cloud().radius();
        level.sendParticles(
                BURST, center.x, center.y, center.z, BURST_PARTICLES, radius / 4, radius / 4, radius / 4, 0.12);
    }

    private static void tick(ServerLevel level) {
        DrifterTuning.Cloud tuning = DrifterTuning.DEFAULT.cloud();
        Iterator<Cloud> clouds = CLOUDS.iterator();
        while (clouds.hasNext()) {
            Cloud cloud = clouds.next();
            if (!cloud.dimension().equals(level.dimension())) {
                continue;
            }
            long age = level.getGameTime() - cloud.born();
            if (age >= tuning.ticks()) {
                clouds.remove();
                continue;
            }
            if (age % PUFF_EVERY_TICKS == 0) {
                // Green dust filling the ball of the damage radius, so the player sees where the gas ends; the standard
                // deviation is a
                // third of the radius, so nearly all of it is inside. The game sends it to players within 32
                // blocks only.
                level.sendParticles(
                        GAS,
                        cloud.center().x,
                        cloud.center().y,
                        cloud.center().z,
                        PUFF_PARTICLES,
                        tuning.radius() / 3,
                        tuning.radius() / 3,
                        tuning.radius() / 3,
                        0.0);
            }
            if (age % 6 == 0) {
                level.sendParticles(
                        ParticleTypes.SPORE_BLOSSOM_AIR,
                        cloud.center().x,
                        cloud.center().y,
                        cloud.center().z,
                        SPORES,
                        tuning.radius() / 3,
                        tuning.radius() / 3,
                        tuning.radius() / 3,
                        0.01);
            }
            eatHulls(level, cloud, tuning);
            if (age % tuning.lookTicks() == 0) {
                choke(level, cloud, tuning);
            }
        }
    }

    /** Every pod with a person aboard whose box is in the gas loses hull. */
    private static void eatHulls(ServerLevel level, Cloud cloud, DrifterTuning.Cloud tuning) {
        double radius = tuning.radius();
        AABB reach = new AABB(cloud.center(), cloud.center()).inflate(radius);
        // entity-query: a cloud hangs where a ticking drifter burst, and it harms only a pod with a person aboard,
        // who keeps the chunk loaded; a pod the query does not see is not near anyone
        for (PodEntity pod : level.getEntitiesOfClass(PodEntity.class, reach)) {
            if (!(pod.getFirstPassenger() instanceof Player)
                    || Wrecks.isWreck(pod)
                    || pod.getBoundingBox().distanceToSqr(cloud.center()) > radius * radius) {
                continue;
            }
            pod.damageHull(DrifterTuning.DEFAULT.hullPerTick());
            if (cloud.warned().add(pod.getUUID()) && pod.getControllingPassenger() instanceof ServerPlayer pilot) {
                pilot.sendOverlayMessage(Component.translatable("creature.deepcharter.spores"));
            }
        }
    }

    /** Every person on foot in the gas is poisoned and blinded: renewed at each look, so it lasts a little after they leave it. */
    private static void choke(ServerLevel level, Cloud cloud, DrifterTuning.Cloud tuning) {
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()
                    || player.isCreative()
                    || player.getVehicle() instanceof PodEntity
                    || player.getBoundingBox().distanceToSqr(cloud.center()) > tuning.radius() * tuning.radius()) {
                continue;
            }
            player.addEffect(new MobEffectInstance(MobEffects.POISON, tuning.poisonTicks(), 0));
            player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, tuning.blindTicks(), 0));
        }
    }
}
