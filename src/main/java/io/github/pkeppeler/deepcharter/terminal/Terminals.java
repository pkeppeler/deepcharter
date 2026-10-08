package io.github.pkeppeler.deepcharter.terminal;

import java.util.Optional;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.Charters;

/**
 * The server-side API of terminals, and the one place a player's request reaches one. Every request is checked first, in this
 * order: the player is within {@link TerminalTuning#maxDistance()} blocks, the block is a terminal, the charter data is readable,
 * the player is on a charter (for a charter-only terminal), the repair data is readable (for a terminal that needs repair), and
 * then what the request needs of the repair state. Unreadable saved data is refused as {@code STATE_UNREADABLE}, never thrown.
 * A refused request changes nothing, and the player is told why.
 * Call everything on the server thread.
 */
public final class Terminals {
	/** The built-in action: put the part named by {@code args.part} (an item id) into an unrepaired terminal. */
	public static final Identifier INSERT_PART = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "insert_part");
	/** The key of the part id in the args of {@link #INSERT_PART}. */
	public static final String PART_KEY = "part";

	private static boolean loggedUnreadable;
	private static boolean loggedUnreadableCharters;

	private Terminals() {
	}

	/**
	 * Opens the terminal at {@code pos} for {@code player}: sends the client its {@link TerminalView}. An unrepaired terminal
	 * opens too, as its offline screen, which is where parts go in.
	 */
	public static Optional<TerminalRefusal> open(ServerPlayer player, BlockPos pos) {
		return switch (access(player, pos)) {
			case Access.Denied denied -> refuse(player, denied.refusal());
			case Access.Granted granted -> {
				sendView(player, pos, granted.type());
				TerminalEvents.OPENED.invoker().onOpened(player.level().getServer(), granted.type(), player);
				yield Optional.empty();
			}
		};
	}

	/**
	 * Runs {@code action} at the terminal at {@code pos} for {@code player}. {@link #INSERT_PART} works on an unrepaired terminal
	 * only; every other action needs a repaired one and a handler from {@link TerminalActions#register}.
	 */
	public static Optional<TerminalRefusal> act(ServerPlayer player, BlockPos pos, Identifier action, CompoundTag args) {
		return switch (access(player, pos)) {
			case Access.Denied denied -> refuse(player, denied.refusal());
			case Access.Granted granted -> {
				Optional<TerminalRefusal> refusal = action.equals(INSERT_PART)
						? insert(player, granted, args)
						: run(player, granted, pos, action, args);
				if (refusal.isPresent()) {
					yield refuse(player, refusal.get());
				}
				TerminalEvents.ACTED.invoker().onActed(player.level().getServer(), granted.type(), player, action);
				sendView(player, pos, granted.type());
				yield Optional.empty();
			}
		};
	}

	/** {@link #act} with {@link #INSERT_PART} and {@code part}. */
	public static Optional<TerminalRefusal> insertPart(ServerPlayer player, BlockPos pos, Item part) {
		CompoundTag args = new CompoundTag();
		args.putString(PART_KEY, BuiltInRegistries.ITEM.getKey(part).toString());
		return act(player, pos, INSERT_PART, args);
	}

	private sealed interface Access {
		record Granted(TerminalType type, Optional<Charter> charter) implements Access {
		}

		record Denied(TerminalRefusal refusal) implements Access {
		}
	}

	private static Access access(ServerPlayer player, BlockPos pos) {
		double range = TerminalTuning.DEFAULT.maxDistance();
		if (player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > range * range) {
			return new Access.Denied(TerminalRefusal.TOO_FAR);
		}
		if (!(player.level().getBlockEntity(pos) instanceof TerminalBlockEntity terminal)) {
			return new Access.Denied(TerminalRefusal.NO_SUCH_TERMINAL);
		}
		TerminalType type = terminal.type();
		MinecraftServer server = player.level().getServer();
		if (!Charters.isReadable(server)) {
			if (!loggedUnreadableCharters) {
				loggedUnreadableCharters = true;
				DeepCharter.LOGGER.error("The saved charters are of a version this build cannot read: terminals are refused until the world is opened by a build that reads them");
			}
			return new Access.Denied(TerminalRefusal.STATE_UNREADABLE);
		}
		Optional<Charter> charter = Charters.charterOf(server, player.getUUID());
		if (type.access() == TerminalType.Access.CHARTER_ONLY && charter.isEmpty()) {
			return new Access.Denied(TerminalRefusal.NOT_ON_A_CHARTER);
		}
		// Gameplay code never throws on unreadable saved data: it refuses, and the data stays as it was.
		if (type.needsRepair() && !RepairState.get(server).isReadable()) {
			logUnreadableOnce(RepairState.get(server));
			return new Access.Denied(TerminalRefusal.STATE_UNREADABLE);
		}
		return new Access.Granted(type, charter);
	}

	private static void logUnreadableOnce(RepairState state) {
		if (!loggedUnreadable) {
			loggedUnreadable = true;
			DeepCharter.LOGGER.error("The saved terminal repairs have version {} that this build cannot read: terminals that need repair are refused until the world is opened by a build that reads it",
					state.unreadableVersion().orElse("?"));
		}
	}

	private static Optional<TerminalRefusal> insert(ServerPlayer player, Access.Granted access, CompoundTag args) {
		if (!access.type().needsRepair()) {
			return Optional.of(TerminalRefusal.ALREADY_REPAIRED);
		}
		MinecraftServer server = player.level().getServer();
		RepairState state = RepairState.get(server);
		Optional<Item> part = args.getString(PART_KEY).map(Identifier::tryParse).flatMap(BuiltInRegistries.ITEM::getOptional);
		if (part.isEmpty()) {
			return Optional.of(TerminalRefusal.NOT_A_PART);
		}
		Optional<TerminalRefusal> refusal = state.check(access.type(), part.get());
		if (refusal.isPresent()) {
			return refusal;
		}
		Optional<ItemStack> held = takeOne(player.getInventory(), part.get());
		if (held.isEmpty()) {
			return Optional.of(TerminalRefusal.MISSING_PART);
		}
		state.insert(access.type(), part.get()).ifPresent(unexpected -> {
			throw new IllegalStateException("a part that passed the check was refused: " + unexpected);
		});
		if (state.repaired(access.type())) {
			TerminalEvents.REPAIRED.invoker().onRepaired(server, access.type(), access.charter().orElseThrow(), player);
		}
		return Optional.empty();
	}

	/** Takes one {@code item} from the inventory, or returns empty and changes nothing when there is none. */
	private static Optional<ItemStack> takeOne(Inventory inventory, Item item) {
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			if (inventory.getItem(slot).is(item)) {
				return Optional.of(inventory.removeItem(slot, 1));
			}
		}
		return Optional.empty();
	}

	private static Optional<TerminalRefusal> run(ServerPlayer player, Access.Granted access, BlockPos pos, Identifier action, CompoundTag args) {
		MinecraftServer server = player.level().getServer();
		if (access.type().needsRepair() && !RepairState.get(server).repaired(access.type())) {
			return Optional.of(TerminalRefusal.UNREPAIRED);
		}
		Optional<TerminalAction> handler = TerminalActions.find(access.type(), action);
		if (handler.isEmpty()) {
			return Optional.of(TerminalRefusal.NO_SUCH_ACTION);
		}
		Optional<Component> refusal = handler.get().run(new TerminalAction.Context(server, player, access.charter(), access.type(), pos.immutable(), args));
		if (refusal.isPresent()) {
			player.sendOverlayMessage(refusal.get());
			return Optional.of(TerminalRefusal.ACTION_REFUSED);
		}
		return Optional.empty();
	}

	private static Optional<TerminalRefusal> refuse(ServerPlayer player, TerminalRefusal refusal) {
		// An action refused by its own handler has told the player already.
		if (refusal != TerminalRefusal.ACTION_REFUSED) {
			player.sendOverlayMessage(refusal.message());
		}
		TerminalEvents.REFUSED.invoker().onRefused(player, refusal);
		return Optional.of(refusal);
	}

	private static void sendView(ServerPlayer player, BlockPos pos, TerminalType type) {
		if (ServerPlayNetworking.canSend(player, TerminalViewPayload.TYPE)) {
			ServerPlayNetworking.send(player, new TerminalViewPayload(TerminalView.of(pos, type, RepairState.get(player.level().getServer()))));
		}
	}
}
