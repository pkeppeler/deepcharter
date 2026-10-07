package io.github.pkeppeler.deepcharter.pod;

import net.minecraft.core.Direction;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;

/**
 * Every synced field of {@link PodEntity}, declared here and nowhere else. Data ids are assigned
 * in class-init order, so spreading them across classes could give the client and the server
 * different ids. #29 to #31 read and write these through the accessors on {@code PodEntity};
 * they do not add data.
 */
public final class PodData {
	/** Hull integrity, 0 up to {@code PodTuning.Shell.fullHull}. Written by #29 (hard landings); read by the HUD. */
	public static final EntityDataAccessor<Float> HULL = define(EntityDataSerializers.FLOAT);
	/** Fuel left, 0 up to {@code PodTuning.Shell.fullFuel}. Written by #31 (drain, refuel); read by the HUD and the low-fuel beep. */
	public static final EntityDataAccessor<Float> FUEL = define(EntityDataSerializers.FLOAT);
	/** Out of fuel: powered off and dark until rescued. Written by #31. */
	public static final EntityDataAccessor<Boolean> STRANDED = define(EntityDataSerializers.BOOLEAN);
	/** Cargo slots in use. Written by #31; read by the HUD. */
	public static final EntityDataAccessor<Integer> CARGO_USED = define(EntityDataSerializers.INT);
	/** Total mass of the cargo. Written by #31; read by #29 to slow the pod. */
	public static final EntityDataAccessor<Float> CARGO_MASS = define(EntityDataSerializers.FLOAT);
	/** The rotor is spinning: the pod is flying. Written by #29; read by the renderer. */
	public static final EntityDataAccessor<Boolean> FLYING = define(EntityDataSerializers.BOOLEAN);
	/** The drill is cutting. Written by #30; read by the renderer. */
	public static final EntityDataAccessor<Boolean> DRILLING = define(EntityDataSerializers.BOOLEAN);
	/** Where the drill points: down or one of the four horizontals. Written by #30; read by the renderer. */
	public static final EntityDataAccessor<Direction> DRILL_DIRECTION = define(EntityDataSerializers.DIRECTION);

	private PodData() {
	}

	/** Load the class, so that the ids are assigned at startup, the same way on both sides. */
	static void init() {
	}

	/** Give a new pod a value for every field: full hull and fuel, nothing else going on. */
	static void defineAll(SynchedEntityData.Builder builder) {
		PodTuning.Shell shell = PodTuning.DEFAULT.shell();
		builder.define(HULL, shell.fullHull());
		builder.define(FUEL, shell.fullFuel());
		builder.define(STRANDED, false);
		builder.define(CARGO_USED, 0);
		builder.define(CARGO_MASS, 0f);
		builder.define(FLYING, false);
		builder.define(DRILLING, false);
		builder.define(DRILL_DIRECTION, Direction.DOWN);
	}

	private static <T> EntityDataAccessor<T> define(EntityDataSerializer<T> serializer) {
		return SynchedEntityData.defineId(PodEntity.class, serializer);
	}
}
