package io.github.pkeppeler.deepcharter.pod;

import com.mojang.serialization.Codec;

import net.minecraft.core.Direction;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.DeepCharter;

/** A pod: a vehicle with seats and no walkable interior (ADR 0004). Part logic hooks in through tick() and the accessors. */
public class PodEntity extends Entity {
	private static final String CHASSIS_KEY = "chassis";
	private static final String HULL_KEY = "hull";
	private static final String FUEL_KEY = "fuel";
	private static final String STRANDED_KEY = "stranded";

	// Not synced: the entity type says which chassis a pod is, and its hitbox comes from the type too (ADR 0027).
	private final Chassis chassis;
	private final PodCargo cargo = new PodCargo();
	// Neither saved nor synced: a loaded pod starts its slab over. Server only.
	private PodDrill.Progress drillProgress;

	public PodEntity(EntityType<? extends PodEntity> type, Level level) {
		super(type, level);
		chassis = PodRegistry.chassisOf(type);
	}

	public Chassis chassis() {
		return chassis;
	}

	public PodCargo cargo() {
		return cargo;
	}

	/** The slab the drill is partway through, or null. Written only by PodDrill. */
	PodDrill.Progress drillProgress() {
		return drillProgress;
	}

	void setDrillProgress(PodDrill.Progress progress) {
		drillProgress = progress;
	}

	/** Hull points left. At most {@link #maxHull} after any change here; a pod loaded with more keeps it until the next change. */
	public float hull() {
		return entityData.get(PodData.HULL);
	}

	/** The most hull the pod can have, from its stats. */
	public float maxHull() {
		return PodStats.of(this).maxHull();
	}

	/** Sets the hull, held between 0 and {@link #maxHull}. A NaN is a bug in the caller, so it throws. */
	public void setHull(float hull) {
		if (Float.isNaN(hull)) {
			throw new IllegalArgumentException("pod hull must be a number");
		}
		float before = hull();
		float clamped = Math.max(0f, Math.min(hull, maxHull()));
		entityData.set(PodData.HULL, clamped);
		if (before > 0f && clamped <= 0f && !level().isClientSide()) {
			PodEvents.HULL_DEPLETED.invoker().onHullDepleted(this);
		}
	}

	/** Takes {@code damage} (not negative) off the hull; reaching 0 fires {@link PodEvents#HULL_DEPLETED}. */
	public void damageHull(float damage) {
		if (!(damage >= 0f)) {
			throw new IllegalArgumentException("pod hull damage must be a number, not negative, got " + damage);
		}
		setHull(hull() - damage);
	}

	/** True while lava is burning the hull (set each tick by LavaHazard); the HUD shows it. Not saved: a loaded pod starts cool. */
	public boolean hullBurning() {
		return entityData.get(PodData.HULL_BURNING);
	}

	public void setHullBurning(boolean hullBurning) {
		entityData.set(PodData.HULL_BURNING, hullBurning);
	}

	public float fuel() {
		return entityData.get(PodData.FUEL);
	}

	public void setFuel(float fuel) {
		entityData.set(PodData.FUEL, fuel);
	}

	public boolean stranded() {
		return entityData.get(PodData.STRANDED);
	}

	public void setStranded(boolean stranded) {
		entityData.set(PodData.STRANDED, stranded);
	}

	public int cargoUsed() {
		return entityData.get(PodData.CARGO_USED);
	}

	public void setCargoUsed(int cargoUsed) {
		entityData.set(PodData.CARGO_USED, cargoUsed);
	}

	public float cargoMass() {
		return entityData.get(PodData.CARGO_MASS);
	}

	public void setCargoMass(float cargoMass) {
		entityData.set(PodData.CARGO_MASS, cargoMass);
	}

	public boolean flying() {
		return entityData.get(PodData.FLYING);
	}

	public void setFlying(boolean flying) {
		entityData.set(PodData.FLYING, flying);
	}

	public boolean drilling() {
		return entityData.get(PodData.DRILLING);
	}

	public void setDrilling(boolean drilling) {
		entityData.set(PodData.DRILLING, drilling);
	}

	public Direction drillDirection() {
		return entityData.get(PodData.DRILL_DIRECTION);
	}

	public void setDrillDirection(Direction direction) {
		entityData.set(PodData.DRILL_DIRECTION, direction);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		PodData.defineAll(builder);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		// The entity type decides the chassis, so the saved id is only a cross-check: a different, unknown or missing one never fails the load.
		String saved = input.read(CHASSIS_KEY, Codec.STRING).orElse("(none)");
		if (!saved.equals(chassis.id())) {
			DeepCharter.LOGGER.error("Pod {} was saved as the chassis {} but is a {}: it keeps the type's chassis", getUUID(), saved, chassis.id());
		}
		// Not setHull: loading a pod that has no hull left is not the hull running out.
		float hull = required(input, HULL_KEY, Codec.FLOAT);
		if (!Float.isFinite(hull) || hull < 0f) {
			DeepCharter.LOGGER.error("Pod {} was saved with hull {}, which is not a hull: it loads with none", getUUID(), hull);
			hull = 0f;
		}
		entityData.set(PodData.HULL, hull);
		setFuel(required(input, FUEL_KEY, Codec.FLOAT));
		setStranded(required(input, STRANDED_KEY, Codec.BOOL));
		// Flying, drilling and the drill direction are transient: a loaded pod starts idle.
		setFlying(false);
		setDrilling(false);
		setDrillDirection(Direction.DOWN);
		cargo.load(input, this);
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		output.putString(CHASSIS_KEY, chassis.id());
		output.putFloat(HULL_KEY, hull());
		output.putFloat(FUEL_KEY, fuel());
		output.putBoolean(STRANDED_KEY, stranded());
		cargo.save(output);
	}

	/** A missing or undecodable value fails the load rather than quietly becoming a default. */
	private static <T> T required(ValueInput input, String key, Codec<T> codec) {
		return input.read(key, codec).orElseThrow(() -> new IllegalStateException("saved pod has no valid '" + key + "'"));
	}

	@Override
	public void tick() {
		super.tick();
		// Everything a pod does on its own is the server's: clients only see the result.
		if (level().isClientSide()) {
			return;
		}
		// One snapshot of the stats for the whole tick, so the three parts agree on what the pod is.
		PodStats stats = PodStats.of(this);
		// Movement first so drilling sees the new position; fuel last so it sees what the pod did.
		PodMovement.tick(this, stats);
		PodDrill.tick(this, stats);
		PodFuel.tick(this, stats);
		PodEvents.AFTER_TICK.invoker().afterTick(this);
	}

	@Override
	public LivingEntity getControllingPassenger() {
		return getFirstPassenger() instanceof Player player ? player : null;
	}

	@Override
	public boolean causeFallDamage(double fallDistance, float damageMultiplier, DamageSource source) {
		HardLanding.onLanding(this, damageMultiplier);
		return false;
	}

	/** The server moves the pod from the pilot's input (PodMovement); the default would trust a player pilot's client. */
	@Override
	public boolean isClientAuthoritative() {
		return false;
	}

	/** The pilot's own client must follow the server's pod too, not simulate it (the default trusts the local pilot). */
	@Override
	protected boolean isLocalClientAuthoritative() {
		return false;
	}

	@Override
	public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
		if (player.isSecondaryUseActive() || !canAddPassenger(player)) {
			return InteractionResult.PASS;
		}
		if (level().isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		return player.startRiding(this) ? InteractionResult.CONSUME : InteractionResult.PASS;
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		// Only the server asks the listeners: the client guesses, and the server settles it.
		return getPassengers().size() < chassis.seats() && (level().isClientSide() || PodEvents.canMount(this, passenger));
	}

	@Override
	protected boolean couldAcceptPassenger() {
		return getPassengers().size() < chassis.seats();
	}

	@Override
	public boolean isPickable() {
		return !isRemoved();
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		return false;
	}
}
