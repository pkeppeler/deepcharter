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

/** A pod: a vehicle with seats and no walkable interior (ADR 0004). Part logic hooks in through tick() and the accessors. */
public class PodEntity extends Entity {
	private static final String CHASSIS_KEY = "chassis";
	private static final String HULL_KEY = "hull";
	private static final String FUEL_KEY = "fuel";
	private static final String STRANDED_KEY = "stranded";

	// Not synced: M1 has only the Mole, and its hitbox comes from the entity type.
	private Chassis chassis = Chassis.MOLE;
	private final PodCargo cargo = new PodCargo();
	// Neither saved nor synced: a loaded pod starts its slab over. Server only.
	private PodDrill.Progress drillProgress;

	public PodEntity(EntityType<? extends PodEntity> type, Level level) {
		super(type, level);
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
		chassis = Chassis.byId(required(input, CHASSIS_KEY, Codec.STRING));
		setHull(required(input, HULL_KEY, Codec.FLOAT));
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
		// Movement first so drilling sees the new position; fuel last so it sees what the pod did.
		PodMovement.tick(this);
		PodDrill.tick(this);
		PodFuel.tick(this);
	}

	@Override
	public LivingEntity getControllingPassenger() {
		return getFirstPassenger() instanceof Player player ? player : null;
	}

	@Override
	public boolean causeFallDamage(double fallDistance, float damageMultiplier, DamageSource source) {
		PodMovement.onLanding(this, fallDistance, damageMultiplier, source);
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

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		return false;
	}
}
