package io.github.pkeppeler.deepcharter.fuel;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.CharterRefusal;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.terminal.TerminalAction;
import io.github.pkeppeler.deepcharter.terminal.TerminalActions;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;

/**
 * The fuel pump's two actions. {@link #BUY} sells exactly the litres asked for (with {@link #LITRES_KEY}) and refuses when the
 * charter's account cannot pay for all of them or the tank cannot hold them. {@link #FILL} fills the tank, and when the account
 * cannot pay for a full tank it sells as many litres as the account pays for; it refuses only when it can sell none. Litres are
 * whole, at {@link FuelTuning#pricePerLitre()} dollars each, and a tank with less than a litre of room is charged a whole litre.
 *
 * <p>The fuel goes into the pod parked at the pump (see {@link #parkedPods}). Selling clears Stranded, because the pod has fuel.
 * The screen never decides: it shows what it can see and the server checks every press.
 */
public final class FuelPump {
	public static final Identifier BUY = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "buy_fuel");
	public static final Identifier FILL = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "fill_fuel");
	/** The key of the litres, an int, in the args of {@link #BUY}. */
	public static final String LITRES_KEY = "litres";

	/** A tank counts as full within this many litres, so float rounding never sells a litre of nothing. */
	private static final float FULL_TOLERANCE = 0.001f;

	private FuelPump() {
	}

	static void init() {
		TerminalActions.register(TerminalTypes.FUEL_PUMP, BUY, FuelPump::buy);
		TerminalActions.register(TerminalTypes.FUEL_PUMP, FILL, FuelPump::fill);
	}

	/**
	 * The pods parked at the pump, nearest first: within {@link FuelTuning#pumpRadius()} of it, and neither flying nor drilling.
	 * Safe on both sides. The server serves the first one {@link #mayServe} allows.
	 */
	public static List<PodEntity> parkedPods(Level level, BlockPos pump) {
		double radius = FuelTuning.DEFAULT.pumpRadius();
		Vec3 centre = Vec3.atCenterOf(pump);
		return level.getEntitiesOfClass(PodEntity.class, new AABB(pump).inflate(radius),
						pod -> !pod.flying() && !pod.drilling() && pod.position().distanceToSqr(centre) <= radius * radius)
				.stream().sorted(Comparator.comparingDouble(pod -> pod.position().distanceToSqr(centre))).toList();
	}

	/**
	 * Mirrors the ownership rule of {@code PodComponents.canMount} exactly: an unreadable pod state refuses; an unowned pod, or one
	 * whose owner charter is gone or dormant, is anyone's; any other is its owner charter's members'. When the saved charters
	 * cannot be read there is no owner to check against, so it allows, as {@code canMount} does. Never throws.
	 */
	// TODO switch to PodComponents.mayAccess (#126)
	public static boolean mayServe(MinecraftServer server, PodEntity pod, Optional<CharterId> charter) {
		if (pod.getAttached(PodComponents.STATE) instanceof Versioned.Unreadable<PodComponents.State>) {
			// registration() would read this as unowned, so it is checked first. It logs once.
			PodComponents.registration(pod);
			return false;
		}
		Optional<PodComponents.Registration> registration = PodComponents.registration(pod);
		if (registration.isEmpty()) {
			return true;
		}
		try {
			Optional<Charter> owner = Charters.find(server, registration.get().owner());
			if (owner.isEmpty() || owner.get().dormant()) {
				return true;
			}
			return charter.map(registration.get().owner()::equals).orElse(false);
		} catch (IllegalStateException unreadable) {
			return true;
		}
	}

	/** The litres in the tank now. */
	public static float litres(PodEntity pod) {
		return pod.fuel() / PodTuning.DEFAULT.shell().fullFuel() * PodStats.of(pod).tankLitres();
	}

	private static Optional<Component> buy(TerminalAction.Context context) {
		int litres = context.args().getIntOr(LITRES_KEY, 0);
		if (litres < 1) {
			return Optional.of(Component.translatable("message.deepcharter.fuel.bad_amount"));
		}
		return sell(context, litres, false);
	}

	private static Optional<Component> fill(TerminalAction.Context context) {
		return sell(context, Integer.MAX_VALUE, true);
	}

	/** Sells {@code litres}; when {@code partial}, as many up to that as the tank has room for and the account can pay for. */
	private static Optional<Component> sell(TerminalAction.Context context, int litres, boolean partial) {
		MinecraftServer server = context.server();
		Optional<CharterId> charter = context.charter().map(found -> found.id());
		if (charter.isEmpty()) {
			return Optional.of(CharterRefusal.NOT_ON_A_CHARTER.message());
		}
		Optional<PodEntity> parked = parkedPods(context.player().level(), context.pos()).stream()
				.filter(pod -> mayServe(server, pod, charter)).findFirst();
		if (parked.isEmpty()) {
			return Optional.of(Component.translatable("message.deepcharter.fuel.no_pod"));
		}
		PodEntity pod = parked.get();
		float full = PodTuning.DEFAULT.shell().fullFuel();
		float tank = PodStats.of(pod).tankLitres();
		float room = tank - litres(pod);
		if (room <= FULL_TOLERANCE) {
			return Optional.of(Component.translatable("message.deepcharter.fuel.tank_full"));
		}
		int roomLitres = (int) Math.ceil(room - FULL_TOLERANCE);
		if (!partial && litres > roomLitres) {
			return Optional.of(Component.translatable("message.deepcharter.fuel.no_room", roomLitres));
		}
		long price = FuelTuning.DEFAULT.pricePerLitre();
		long affordable = Charters.find(server, charter.get()).orElseThrow().account() / price;
		if (affordable < (partial ? 1 : litres)) {
			return Optional.of(Component.translatable("message.deepcharter.fuel.cannot_pay", price * (partial ? 1 : litres)));
		}
		int sold = partial ? (int) Math.min(roomLitres, affordable) : litres;
		Optional<CharterRefusal> refusal = Charters.spend(server, charter.get(), sold * price);
		if (refusal.isPresent()) {
			return Optional.of(refusal.get().message());
		}
		pod.setFuel(Math.min(full, pod.fuel() + sold / tank * full));
		pod.setStranded(false);
		return Optional.empty();
	}
}
