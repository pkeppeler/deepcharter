package io.github.pkeppeler.deepcharter.pod;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
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

	private static final List<String> SAVED_KEYS = List.of(CHASSIS_KEY, HULL_KEY, FUEL_KEY, STRANDED_KEY, PodCargo.CARGO_KEY, PodCargo.VERSION_KEY);

	// Not synced: the entity type says which chassis a pod is, and its hitbox comes from the type too (ADR 0027).
	private final Chassis chassis;
	private final PodCargo cargo = new PodCargo();
	// Neither saved nor synced: a loaded pod starts its slab over. Server only.
	private PodDrill.Progress drillProgress;
	// The pod's own saved data when its chassis id is not one this build knows, held as read and written back unchanged; null for a normal pod. Server only.
	private CompoundTag unreadable;

	public PodEntity(EntityType<? extends PodEntity> type, Level level) {
		super(type, level);
		chassis = PodRegistry.chassisOf(type);
	}

	public Chassis chassis() {
		return chassis;
	}

	/**
	 * True for a pod whose saved chassis id this build does not know (a removed chassis, a missing mod). It is an inert placeholder: it
	 * keeps its saved data and writes it back, but does not tick, carry riders or take part in any pod feature. Code that
	 * acts on the pods it finds skips it. Server only; the client never learns it.
	 */
	public boolean isUnreadable() {
		return unreadable != null;
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
		Chassis saved = input.read(CHASSIS_KEY, Codec.STRING).flatMap(PodRegistry::findChassis).orElse(null);
		if (saved == null) {
			keepUnreadable(input);
			return;
		}
		unreadable = null;
		if (saved != chassis) {
			// The entity type decides the hitbox, so the type's chassis wins.
			DeepCharter.LOGGER.error("Pod {} was saved as a {} but is a {}: it keeps the type's chassis", getUUID(), saved.id(), chassis.id());
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

	/** The chassis is none this build knows: hold the pod's saved data untouched, so the next save writes it back as it was. */
	private void keepUnreadable(ValueInput input) {
		CompoundTag raw = new CompoundTag();
		for (String key : SAVED_KEYS) {
			input.read(key, Codec.PASSTHROUGH).ifPresent(value -> raw.put(key, value.convert(NbtOps.INSTANCE).getValue()));
		}
		unreadable = raw;
		DeepCharter.LOGGER.error("Pod {} was saved with chassis {}, which this build does not know: it is kept unchanged and does nothing",
				getUUID(), raw.get(CHASSIS_KEY));
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		if (unreadable != null) {
			unreadable.keySet().forEach(key -> output.store(key, Codec.PASSTHROUGH, new Dynamic<>(NbtOps.INSTANCE, unreadable.get(key))));
			return;
		}
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
		if (level().isClientSide() || unreadable != null) {
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
		if (unreadable != null || player.isSecondaryUseActive() || !canAddPassenger(player)) {
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
		return unreadable == null && getPassengers().size() < chassis.seats() && (level().isClientSide() || PodEvents.canMount(this, passenger));
	}

	@Override
	protected boolean couldAcceptPassenger() {
		return unreadable == null && getPassengers().size() < chassis.seats();
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
