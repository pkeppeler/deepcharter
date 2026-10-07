package io.github.pkeppeler.deepcharter.charter.terminal;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterRefusal;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.terminal.TerminalAction;
import io.github.pkeppeler.deepcharter.terminal.TerminalActions;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;

/**
 * The contract terminal (lore canon section 8): the one terminal that never went dark. It is always online and any player may
 * use it, charter or not, because a player with no charter founds one here. A player can found a charter, apply to one, answer
 * an application as Director, and leave. Every action goes through {@link Charters}, the same calls as the operator commands.
 *
 * <p>The block is {@code deepcharter:contract_terminal} ({@link #TYPE}). The colony places it at its plinth.
 *
 * <p>Every argument is untrusted: a name is looked up on the server, never taken as an id. An action that is refused changes
 * nothing, and the player is sent the reason in their {@link ContractState}.
 */
public final class ContractTerminal {
	public static final TerminalType TYPE = TerminalTypes.registerAlwaysOnline(id("contract_terminal"), TerminalType.Access.ANYONE);

	/** Found a charter named {@code args.name}. */
	public static final Identifier FOUND = id("charter_found");
	/** Apply to the charter named {@code args.name}. */
	public static final Identifier APPLY = id("charter_apply");
	/** As Director, approve the applicant named {@code args.name}. */
	public static final Identifier APPROVE = id("charter_approve");
	/** As Director, turn down the applicant named {@code args.name}. */
	public static final Identifier DENY = id("charter_deny");
	/** Leave the charter, or withdraw the application. */
	public static final Identifier LEAVE = id("charter_leave");
	/** The key of the name in the args of every action that takes one. */
	public static final String NAME_KEY = "name";

	static {
		TerminalActions.register(TYPE, FOUND, context -> byName(context, CharterRefusal.INVALID_NAME,
				name -> Charters.found(context.server(), context.player().getUUID(), name)));
		TerminalActions.register(TYPE, APPLY, context -> byName(context, CharterRefusal.NO_SUCH_CHARTER, name -> Charters.findByName(context.server(), name)
				.map(charter -> Charters.apply(context.server(), context.player().getUUID(), charter.id()))
				.orElse(Optional.of(CharterRefusal.NO_SUCH_CHARTER))));
		TerminalActions.register(TYPE, APPROVE, context -> answer(context, applicantNamed(context), Charters::approve));
		TerminalActions.register(TYPE, DENY, context -> {
			Optional<UUID> applicant = applicantNamed(context);
			Optional<Component> refusal = answer(context, applicant, Charters::deny);
			// A denial has no charter event, so the Director's list and the applicant's status are refreshed here.
			if (refusal.isEmpty()) {
				refresh(context.server(), context.player().getUUID());
				applicant.ifPresent(denied -> refresh(context.server(), denied));
			}
			return refusal;
		});
		TerminalActions.register(TYPE, LEAVE, context -> report(context, Charters.leave(context.server(), context.player().getUUID())));
	}

	private ContractTerminal() {
	}

	/** Loads the class, which registers the terminal and its actions. */
	static void register() {
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path);
	}

	private static Optional<String> nameArg(TerminalAction.Context context) {
		return context.args().getString(NAME_KEY);
	}

	private static Optional<Component> byName(TerminalAction.Context context, CharterRefusal missing, Function<String, Optional<CharterRefusal>> run) {
		return report(context, nameArg(context).map(run).orElse(Optional.of(missing)));
	}

	private static Optional<Component> answer(TerminalAction.Context context, Optional<UUID> applicant, Answer answer) {
		if (applicant.isEmpty()) {
			return report(context, Optional.of(CharterRefusal.NO_APPLICATION));
		}
		return report(context, answer.apply(context.server(), context.player().getUUID(), applicant.get()));
	}

	/** The player named by the args among the applications to the acting player's charter. Empty when there is none. */
	private static Optional<UUID> applicantNamed(TerminalAction.Context context) {
		Optional<String> name = nameArg(context);
		Optional<Charter> charter = Charters.charterOf(context.server(), context.player().getUUID());
		if (name.isEmpty() || charter.isEmpty()) {
			return Optional.empty();
		}
		return charter.get().applications().stream().filter(applicant -> displayName(context.server(), applicant).equals(name.get())).findFirst();
	}

	private static Optional<Component> report(TerminalAction.Context context, Optional<CharterRefusal> refusal) {
		if (refusal.isEmpty()) {
			return Optional.empty();
		}
		send(context.player(), stateOf(context.server(), context.player().getUUID()).withNotice(refusal.get().translationKey()));
		return Optional.of(refusal.get().message());
	}

	/** Tells {@code player} where they stand now, if they are online. Does nothing when the saved charters cannot be read. */
	static void refresh(MinecraftServer server, UUID player) {
		ServerPlayer online = server.getPlayerList().getPlayer(player);
		if (online != null && Charters.isReadable(server)) {
			send(online, stateOf(server, player));
		}
	}

	private static void send(ServerPlayer player, ContractState state) {
		if (ServerPlayNetworking.canSend(player, ContractStatePayload.TYPE)) {
			ServerPlayNetworking.send(player, new ContractStatePayload(state));
		}
	}

	/** Where {@code player} stands with the charters now. Throws when the saved charters cannot be read: check {@code Charters.isReadable} first. */
	public static ContractState stateOf(MinecraftServer server, UUID player) {
		int rows = ContractTerminalTuning.DEFAULT.listedRows();
		Optional<Charter> own = Charters.charterOf(server, player);
		if (own.isPresent()) {
			Charter charter = own.get();
			if (!charter.isDirector(player)) {
				return new ContractState(ContractState.Role.CREW, charter.name(), List.of(), List.of(), Optional.empty());
			}
			List<String> applicants = charter.applications().stream().map(applicant -> displayName(server, applicant)).limit(rows).toList();
			return new ContractState(ContractState.Role.DIRECTOR, charter.name(), List.of(), applicants, Optional.empty());
		}
		Optional<Charter> applied = Charters.all(server).stream().filter(charter -> charter.applications().contains(player)).findFirst();
		if (applied.isPresent()) {
			return new ContractState(ContractState.Role.APPLICANT, applied.get().name(), List.of(), List.of(), Optional.empty());
		}
		List<String> open = Charters.all(server).stream().filter(charter -> !charter.dormant()).map(Charter::name)
				.sorted(Comparator.comparing(String::toLowerCase)).limit(rows).toList();
		return new ContractState(ContractState.Role.NONE, "", open, List.of(), Optional.empty());
	}

	/** A player's name: the online one, else the server's cache of names, else the start of the id, which nobody can mistake for a name. */
	static String displayName(MinecraftServer server, UUID player) {
		ServerPlayer online = server.getPlayerList().getPlayer(player);
		if (online != null) {
			return online.getGameProfile().name();
		}
		return server.services().nameToIdCache().get(player).map(NameAndId::name).orElseGet(() -> player.toString().substring(0, 8));
	}

	@FunctionalInterface
	private interface Answer {
		Optional<CharterRefusal> apply(MinecraftServer server, UUID director, UUID applicant);
	}
}
