package io.github.pkeppeler.deepcharter.pod;

import net.minecraft.core.Direction;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * A pod: a vehicle with seats and no walkable interior (ADR 0004). This is the shell only: the
 * chassis, the seats and the synced data. Movement is #29, drilling #30, cargo and fuel #31; they
 * hook in through the accessors below and never add data of their own (see {@link PodData}).
 */
public class PodEntity extends Entity {
	private static final String CHASSIS_KEY = "chassis";
	private static final String HULL_KEY = "hull";
	private static final String FUEL_KEY = "fuel";
	private static final String STRANDED_KEY = "stranded";
	private static final String CARGO_USED_KEY = "cargo_used";
	private static final String CARGO_MASS_KEY = "cargo_mass";
	private static final String FLYING_KEY = "flying";
	private static final String DRILLING_KEY = "drilling";
	private static final String DRILL_DIRECTION_KEY = "drill_direction";

	// The chassis is not synced: M1 has only the Mole, and the hitbox comes from the entity type.
	// A second chassis needs a synced id and its own entity dimensions.
	private Chassis chassis = Chassis.MOLE;

	public PodEntity(EntityType<? extends PodEntity> type, Level level) {
		super(type, level);
	}

	public Chassis chassis() {
		return chassis;
	}

	public float hull() {
		return entityData.get(PodData.HULL);
	}

	public void setHull(float hull) {
		entityData.set(PodData.HULL, hull);
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
		chassis = Chassis.byId(input.getStringOr(CHASSIS_KEY, Chassis.MOLE.id()));
		PodTuning.Shell shell = PodTuning.DEFAULT.shell();
		setHull(input.getFloatOr(HULL_KEY, shell.fullHull()));
		setFuel(input.getFloatOr(FUEL_KEY, shell.fullFuel()));
		setStranded(input.getBooleanOr(STRANDED_KEY, false));
		setCargoUsed(input.getIntOr(CARGO_USED_KEY, 0));
		setCargoMass(input.getFloatOr(CARGO_MASS_KEY, 0f));
		setFlying(input.getBooleanOr(FLYING_KEY, false));
		setDrilling(input.getBooleanOr(DRILLING_KEY, false));
		setDrillDirection(input.read(DRILL_DIRECTION_KEY, Direction.CODEC).orElse(Direction.DOWN));
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		output.putString(CHASSIS_KEY, chassis.id());
		output.putFloat(HULL_KEY, hull());
		output.putFloat(FUEL_KEY, fuel());
		output.putBoolean(STRANDED_KEY, stranded());
		output.putInt(CARGO_USED_KEY, cargoUsed());
		output.putFloat(CARGO_MASS_KEY, cargoMass());
		output.putBoolean(FLYING_KEY, flying());
		output.putBoolean(DRILLING_KEY, drilling());
		output.store(DRILL_DIRECTION_KEY, Direction.CODEC, drillDirection());
	}

	/** Use the pod to mount it. Sneak-use is left to other interactions. */
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
		return getPassengers().size() < chassis.seats();
	}

	@Override
	protected boolean couldAcceptPassenger() {
		return getPassengers().size() < chassis.seats();
	}

	@Override
	public boolean isPickable() {
		return !isRemoved();
	}

	/** Hull damage is #29's: until then nothing hurts a pod. */
	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		return false;
	}
}
