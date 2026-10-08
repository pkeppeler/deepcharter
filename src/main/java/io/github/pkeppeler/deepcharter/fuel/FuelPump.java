package io.github.pkeppeler.deepcharter.fuel;

import java.util.Optional;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.CharterRefusal;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.terminal.TerminalAction;
import io.github.pkeppeler.deepcharter.terminal.TerminalActions;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.Terminals;

/**
 * The fuel pump's two actions. {@link #BUY} sells exactly the litres asked for (with {@link #LITRES_KEY}) and refuses when the
 * charter's account cannot pay for all of them or the whole litres of room in the tank are fewer. {@link #FILL} fills the tank, and when the account
 * cannot pay for a full tank it sells as many litres as the account pays for; it refuses only when it can sell none. Litres are
 * whole, at {@link FuelTuning#pricePerLitre()} dollars each. Only {@link #FILL} rounds up: a tank with 4.2 litres of room is charged for 5, and one with less than a litre is charged for 1.
 *
 * <p>The fuel goes into the pod parked at the pump (see {@link Terminals#parkedPods}). Selling clears Stranded, because the pod has fuel.
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
		Optional<PodEntity> parked = Terminals.parkedPods(context.player().level(), context.pos(), context.charter()).stream().findFirst();
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
		int wholeRoom = (int) Math.floor(room + FULL_TOLERANCE);
		if (!partial && litres > wholeRoom) {
			return Optional.of(Component.translatable("message.deepcharter.fuel.no_room", wholeRoom));
		}
		int roomLitres = (int) Math.ceil(room - FULL_TOLERANCE);
		long price = FuelTuning.DEFAULT.pricePerLitre();
		long affordable = Charters.findOrThrow(server, charter.get()).orElseThrow().account() / price;
		int minimum = partial ? 1 : litres;
		if (affordable < minimum) {
			return Optional.of(Component.translatable("message.deepcharter.fuel.cannot_pay", price * minimum));
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
