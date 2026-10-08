package io.github.pkeppeler.deepcharter.pod;

import net.minecraft.core.Direction;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;

/** Every synced field of PodEntity, in one class: data ids are assigned in class-init order, so spreading them risks a client/server mismatch. */
public final class PodData {
	public static final EntityDataAccessor<Float> HULL = define(EntityDataSerializers.FLOAT);
	public static final EntityDataAccessor<Float> FUEL = define(EntityDataSerializers.FLOAT);
	public static final EntityDataAccessor<Boolean> STRANDED = define(EntityDataSerializers.BOOLEAN);
	public static final EntityDataAccessor<Integer> CARGO_USED = define(EntityDataSerializers.INT);
	public static final EntityDataAccessor<Float> CARGO_MASS = define(EntityDataSerializers.FLOAT);
	public static final EntityDataAccessor<Boolean> FLYING = define(EntityDataSerializers.BOOLEAN);
	public static final EntityDataAccessor<Boolean> DRILLING = define(EntityDataSerializers.BOOLEAN);
	public static final EntityDataAccessor<Boolean> HULL_BURNING = define(EntityDataSerializers.BOOLEAN);
	public static final EntityDataAccessor<Direction> DRILL_DIRECTION = define(EntityDataSerializers.DIRECTION);

	private PodData() {
	}

	/** Loads the class so the ids are assigned at startup, the same way on both sides. */
	static void init() {
	}

	static void defineAll(SynchedEntityData.Builder builder) {
		PodTuning.Shell shell = PodTuning.DEFAULT.shell();
		builder.define(HULL, shell.fullHull());
		builder.define(FUEL, shell.fullFuel());
		builder.define(STRANDED, false);
		builder.define(CARGO_USED, 0);
		builder.define(CARGO_MASS, 0f);
		builder.define(FLYING, false);
		builder.define(DRILLING, false);
		builder.define(HULL_BURNING, false);
		builder.define(DRILL_DIRECTION, Direction.DOWN);
	}

	private static <T> EntityDataAccessor<T> define(EntityDataSerializer<T> serializer) {
		return SynchedEntityData.defineId(PodEntity.class, serializer);
	}
}
