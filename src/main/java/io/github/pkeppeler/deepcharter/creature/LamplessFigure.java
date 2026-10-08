package io.github.pkeppeler.deepcharter.creature;

import net.minecraft.core.Direction;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

/**
 * PLACEHOLDER until the creatures session (#13). The figure that walks the rail line of Prospector's Run with no lamp: it never
 * attacks, never stops, and fades when it is lit or approached. It is not saved: a figure whose chunk unloads is gone, and the level
 * spawns another.
 */
public class LamplessFigure extends PathfinderMob {
	/** Ticks of fade so far; 0 when the figure is not fading. Synced, for the client's alpha. */
	private static final EntityDataAccessor<Integer> FADE = SynchedEntityData.defineId(LamplessFigure.class, EntityDataSerializers.INT);
	/** How far ahead along its heading the figure aims, so that it never reaches the point it walks to. */
	private static final double LOOK_AHEAD = 4;

	private Direction heading = Direction.NORTH;

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
	}

	/** Walks on whatever happens, turning at a wall; a light or a player near it starts a fade, which does not turn back. */
	@Override
	protected void customServerAiStep(ServerLevel level) {
		super.customServerAiStep(level);
		CreatureTuning tuning = CreatureTuning.DEFAULT;
		int fade = entityData.get(FADE);
		if (fade >= tuning.fadeTicks()) {
			discard();
			LamplessFigures.faded(level);
			return;
		}
		if (fade > 0) {
			entityData.set(FADE, fade + 1);
		} else if (tickCount % tuning.fadeCheckTicks() == 0 && (isLit(level, tuning) || isApproached(level, tuning))) {
			entityData.set(FADE, 1);
		}
		if (horizontalCollision) {
			heading = heading.getOpposite();
		}
		Vec3 ahead = position().add(heading.getStepX() * LOOK_AHEAD, 0, heading.getStepZ() * LOOK_AHEAD);
		getMoveControl().setWantedPosition(ahead.x, ahead.y, ahead.z, 1.0);
	}

	private boolean isLit(ServerLevel level, CreatureTuning tuning) {
		return level.getBrightness(LightLayer.BLOCK, blockPosition()) >= tuning.fadeBlockLight();
	}

	private boolean isApproached(ServerLevel level, CreatureTuning tuning) {
		return level.getNearestPlayer(getX(), getY(), getZ(), tuning.approachBlocks(), EntitySelector.NO_SPECTATORS) != null;
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
		return false;
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
}
