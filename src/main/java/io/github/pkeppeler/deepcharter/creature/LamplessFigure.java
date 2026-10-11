package io.github.pkeppeler.deepcharter.creature;

import java.util.Comparator;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodFuel;
import io.github.pkeppeler.deepcharter.pod.PodLights;

/**
 * A Retained: a veteran of the Workings that the Company rebuilt too often, and that stopped using its lamp. A normal mob of the deep
 * layers (never a boss, ADR 0046): it never attacks anyone and nothing hurts it. It walks on whatever happens, fades when it is lit or
 * a person on foot comes near, and comes to a dark pod, one with a pilot, power and no lights. It grabs the hull and takes fuel until it
 * has had its fill; the pilot shakes it off by moving the pod, or a lit pod frees itself. Some carry a lamp, so that they show from afar.
 * It is not saved: a figure whose chunk unloads is gone, and the level makes another.
 */
public class LamplessFigure extends PathfinderMob {
	/** Ticks of fade so far; 0 when the figure is not fading. Synced, for the client's alpha. */
	private static final EntityDataAccessor<Integer> FADE = SynchedEntityData.defineId(LamplessFigure.class, EntityDataSerializers.INT);
	/** Whether the figure holds a pod's hull. Synced, for the client's pose. */
	private static final EntityDataAccessor<Boolean> CLINGING = SynchedEntityData.defineId(LamplessFigure.class, EntityDataSerializers.BOOLEAN);
	/** Whether the figure carries a lamp. Synced, for the client's model and glow. */
	private static final EntityDataAccessor<Boolean> LAMP = SynchedEntityData.defineId(LamplessFigure.class, EntityDataSerializers.BOOLEAN);
	/** How far ahead along its heading the figure aims, so that it never reaches the point it walks to. */
	private static final double LOOK_AHEAD = 4;
	/** Blocks per tick, squared, over which a pod counts as driving: a quarter of its walk (0.2) is far above a drift or a push. */
	private static final double MOVING_SPEED_SQR = 0.05 * 0.05;
	/** How far off the hull the figure stands, beyond the half width of the pod. */
	private static final double HULL_GAP = 0.3;

	private Direction heading = Direction.NORTH;
	/** The pod this figure holds, null when it holds none. Server only. */
	private @Nullable PodEntity grip;
	/** The pod this figure walks to, null when none draws it. Server only. */
	private @Nullable PodEntity target;
	/** Whether the game's spawner made this figure: only those go when no player is near, so that the level's cap is not held by figures nobody will meet. */
	private boolean natural;
	/** The side of the hull the figure holds, as the direction from the pod to the figure. */
	private Direction side = Direction.NORTH;
	/** Litres taken from the pod this grip. */
	private float taken;
	/** Ticks of rotor thrust against the grip, less the ticks without. */
	private int shake;

	public LamplessFigure(EntityType<? extends LamplessFigure> type, Level level) {
		super(type, level);
	}

	public static AttributeSupplier.Builder attributes() {
		return createMobAttributes().add(Attributes.MOVEMENT_SPEED, CreatureTuning.DEFAULT.walkSpeed());
	}

	/** Loads the class so that the data id is assigned at startup, the same way on both sides. */
	static void init() {
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(FADE, 0);
		builder.define(CLINGING, false);
		builder.define(LAMP, false);
	}

	/** A natural spawn rolls whether it carries a lamp. */
	@Override
	public @Nullable SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, EntitySpawnReason reason, @Nullable SpawnGroupData groupData) {
		setLamp(level.getRandom().nextDouble() < CreatureTuning.DEFAULT.natural().lampShare());
		natural = reason == EntitySpawnReason.NATURAL;
		return super.finalizeSpawn(level, difficulty, reason, groupData);
	}

	/** The natural spawn's own rules: {@link LamplessFigures#mayAppearNaturally}. Every other way to make one is a caller's choice. */
	@Override
	public boolean checkSpawnRules(LevelAccessor level, EntitySpawnReason reason) {
		return reason != EntitySpawnReason.NATURAL
				|| level instanceof ServerLevelAccessor accessor && LamplessFigures.mayAppearNaturally(accessor.getLevel(), blockPosition(), level.getRandom());
	}

	@Override
	public int getMaxSpawnClusterSize() {
		return 1;
	}

	/**
	 * Walks on whatever happens, turning at a wall; a light or a person on foot near it starts a fade, which does not turn back. A dark
	 * pod within reach of its senses draws it, and it grabs the hull and takes fuel until it is shaken off, lit or full.
	 */
	@Override
	protected void customServerAiStep(ServerLevel level) {
		super.customServerAiStep(level);
		CreatureTuning tuning = CreatureTuning.DEFAULT;
		int fade = entityData.get(FADE);
		if (fade >= tuning.fadeTicks()) {
			release();
			discard();
			LamplessFigures.faded(level);
			return;
		}
		if (fade > 0) {
			entityData.set(FADE, fade + 1);
		}
		if (grip != null) {
			cling(level, tuning);
			return;
		}
		boolean checking = tickCount % tuning.fadeCheckTicks() == 0;
		if (fade == 0 && checking) {
			if (isLit(level, tuning) || isApproached(level, tuning)) {
				entityData.set(FADE, 1);
				target = null;
			} else {
				target = nearestDarkPod(level, tuning.cling());
			}
		}
		if (fade == 0 && target != null && !target.isRemoved()) {
			approach(target, tuning.cling(), checking);
		} else {
			wander();
		}
	}

	private void wander() {
		if (horizontalCollision) {
			heading = heading.getOpposite();
		}
		Vec3 ahead = position().add(heading.getStepX() * LOOK_AHEAD, 0, heading.getStepZ() * LOOK_AHEAD);
		getMoveControl().setWantedPosition(ahead.x, ahead.y, ahead.z, 1.0);
	}

	private void approach(PodEntity pod, CreatureTuning.Cling cling, boolean checking) {
		if (pod.getBoundingBox().inflate(cling.reachBlocks()).intersects(getBoundingBox())) {
			latch(pod);
		} else if (checking) {
			getNavigation().moveTo(pod.getX(), pod.getY(), pod.getZ(), 1.0);
		}
	}

	private void latch(PodEntity pod) {
		grip = pod;
		target = null;
		taken = 0;
		shake = 0;
		side = Direction.getApproximateNearest(getX() - pod.getX(), 0, getZ() - pod.getZ());
		if (side.getAxis().isVertical()) {
			side = Direction.NORTH;
		}
		getNavigation().stop();
		setNoGravity(true);
		noPhysics = true;
		entityData.set(CLINGING, true);
		if (pod.getControllingPassenger() instanceof ServerPlayer pilot) {
			pilot.sendOverlayMessage(Component.translatable("creature.deepcharter.grab"));
		}
	}

	/** Holds the hull, takes its fuel, and lets go when lit, shaken off, full, or when there is nothing left to take. */
	private void cling(ServerLevel level, CreatureTuning tuning) {
		CreatureTuning.Cling cling = tuning.cling();
		PodEntity pod = grip;
		if (pod.isRemoved()) {
			letGo();
			return;
		}
		boolean lit = PodLights.isLit(pod) || (tickCount % tuning.fadeCheckTicks() == 0 && isLit(level, tuning));
		shake = isMoving(pod) ? shake + 1 : Math.max(0, shake - 1);
		if (lit || shake >= cling.shakeTicks()) {
			letGo();
			return;
		}
		float took = PodFuel.drain(pod, cling.drainLitres() / 20f, cling.floorPercent());
		taken += took;
		if (took <= 0 || taken >= cling.fillLitres()) {
			letGo();
			return;
		}
		holdTo(pod, cling);
	}

	/** Whether the pod is under way: the rotor is on, or the treads carry it along. A pod that only sinks as its drill bites is not. */
	private static boolean isMoving(PodEntity pod) {
		return pod.flying() || pod.getDeltaMovement().horizontalDistanceSqr() > MOVING_SPEED_SQR;
	}

	/** Closes on its place on the hull, side by side with the pod, and faces the pod. */
	private void holdTo(PodEntity pod, CreatureTuning.Cling cling) {
		double reach = pod.getBbWidth() / 2 + HULL_GAP;
		Vec3 place = new Vec3(pod.getX() + side.getStepX() * reach, pod.getY(), pod.getZ() + side.getStepZ() * reach);
		Vec3 gap = place.subtract(position());
		double distance = gap.length();
		setPos(distance <= cling.latchSpeed() ? place : position().add(gap.scale(cling.latchSpeed() / distance)));
		setDeltaMovement(Vec3.ZERO);
		float yaw = (float) (Mth.atan2(pod.getX() - getX(), getZ() - pod.getZ()) * Mth.RAD_TO_DEG);
		setYRot(yaw);
		setYHeadRot(yaw);
		setYBodyRot(yaw);
	}

	private void letGo() {
		release();
		if (entityData.get(FADE) == 0) {
			entityData.set(FADE, 1);
		}
	}

	private void release() {
		grip = null;
		setNoGravity(false);
		noPhysics = false;
		entityData.set(CLINGING, false);
	}

	/** The nearest pod in sense that this figure would grab, or null. */
	private @Nullable PodEntity nearestDarkPod(ServerLevel level, CreatureTuning.Cling cling) {
		double sense = cling.senseBlocks();
		return level.getEntitiesOfClass(PodEntity.class, getBoundingBox().inflate(sense), pod -> pod.distanceToSqr(this) <= sense * sense && draws(pod) && !isHeldByAnother(level, pod, cling))
				.stream().min(Comparator.comparingDouble(pod -> pod.distanceToSqr(this))).orElse(null);
	}

	private boolean isHeldByAnother(ServerLevel level, PodEntity pod, CreatureTuning.Cling cling) {
		return !level.getEntitiesOfClass(LamplessFigure.class, pod.getBoundingBox().inflate(cling.reachBlocks() + 1), other -> other != this && other.grip == pod).isEmpty();
	}

	/**
	 * Whether a pod draws a figure: a person aboard, power, no lights, and fuel above the floor. A parked pod is left alone, and so is a
	 * wreck (no power) and a lit pod.
	 */
	public static boolean draws(PodEntity pod) {
		return pod.getFirstPassenger() instanceof Player && PodEvents.isPowered(pod) && !PodLights.isLit(pod)
				&& pod.fuel() > CreatureTuning.DEFAULT.cling().floorPercent();
	}

	private boolean isLit(ServerLevel level, CreatureTuning tuning) {
		return level.getBrightness(LightLayer.BLOCK, blockPosition()) >= tuning.fadeBlockLight();
	}

	/** A person on foot near it. A pilot in a pod is not approaching: the pod is what draws it. */
	private boolean isApproached(ServerLevel level, CreatureTuning tuning) {
		return level.getNearestPlayer(getX(), getY(), getZ(), tuning.approachBlocks(), EntitySelector.NO_SPECTATORS.and(player -> !(player.getVehicle() instanceof PodEntity))) != null;
	}

	/** Nothing hurts it, a fall and an explosion included; only what bypasses invulnerability does: the void and /kill. */
	@Override
	public boolean isInvulnerableTo(ServerLevel level, DamageSource source) {
		return !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY);
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

	/** 0 when whole, up to 1 as it goes. */
	public float fadeFraction() {
		return Math.min(1f, entityData.get(FADE) / (float) CreatureTuning.DEFAULT.fadeTicks());
	}

	/** Whether the figure holds a pod's hull. */
	public boolean isClinging() {
		return entityData.get(CLINGING);
	}

	public boolean hasLamp() {
		return entityData.get(LAMP);
	}

	public void setLamp(boolean lamp) {
		entityData.set(LAMP, lamp);
	}

	/** The pod this figure holds, or null. Server only. */
	public @Nullable PodEntity grip() {
		return grip;
	}

	/** Litres taken from the pod it holds now. Server only. */
	public float taken() {
		return taken;
	}
}
