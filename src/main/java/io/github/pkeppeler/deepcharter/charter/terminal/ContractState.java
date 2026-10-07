package io.github.pkeppeler.deepcharter.charter.terminal;

import java.util.List;
import java.util.Optional;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * What the contract screen needs to know about one player, as the server sees it now. It has names and no UUIDs: a client
 * learns who applied, never who is online (see {@code CharterView}).
 *
 * @param role       where the player stands with the charters
 * @param charter    the charter the player is on or applied to, empty for {@link Role#NONE}
 * @param charters   for {@link Role#NONE}: the charters that can take an application, at most {@link ContractTerminalTuning#listedRows()}
 * @param charterCount every charter that can take an application, so a screen can say that more exist than it lists
 * @param applicants for {@link Role#DIRECTOR}: the names of the players waiting for an answer, at most {@link ContractTerminalTuning#listedRows()}
 * @param applicantCount every application waiting, listed or not
 * @param notice     the translation key of the refusal the last request met, or empty
 */
public record ContractState(Role role, String charter, List<String> charters, int charterCount, List<String> applicants, int applicantCount,
		Optional<String> notice) {
	/** Longest name or key sent: a player name is at most 16 characters, and a charter name is capped by {@code CharterTuning}. */
	private static final int MAX_TEXT = 128;
	private static final int MAX_LIST = 64;

	public enum Role {
		/** On no charter and no application open. */
		NONE,
		/** Has applied and waits. */
		APPLICANT,
		/** On the roster, not the Director. */
		CREW,
		DIRECTOR
	}

	public static final StreamCodec<ByteBuf, ContractState> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.idMapper(ContractState::roleAt, Role::ordinal), ContractState::role,
			ByteBufCodecs.stringUtf8(MAX_TEXT), ContractState::charter,
			ByteBufCodecs.stringUtf8(MAX_TEXT).apply(ByteBufCodecs.list(MAX_LIST)), ContractState::charters,
			ByteBufCodecs.VAR_INT, ContractState::charterCount,
			ByteBufCodecs.stringUtf8(MAX_TEXT).apply(ByteBufCodecs.list(MAX_LIST)), ContractState::applicants,
			ByteBufCodecs.VAR_INT, ContractState::applicantCount,
			ByteBufCodecs.optional(ByteBufCodecs.stringUtf8(MAX_TEXT)), ContractState::notice,
			ContractState::new);

	private static Role roleAt(int ordinal) {
		if (ordinal < 0 || ordinal >= Role.values().length) {
			throw new DecoderException("unknown contract role " + ordinal);
		}
		return Role.values()[ordinal];
	}

	public ContractState {
		if (charterCount < charters.size() || applicantCount < applicants.size()) {
			throw new IllegalArgumentException("a total cannot be below the names listed");
		}
		charters = List.copyOf(charters);
		applicants = List.copyOf(applicants);
	}

	public ContractState withNotice(String key) {
		return new ContractState(role, charter, charters, charterCount, applicants, applicantCount, Optional.of(key));
	}
}
