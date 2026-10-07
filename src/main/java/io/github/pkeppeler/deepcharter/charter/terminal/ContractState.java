package io.github.pkeppeler.deepcharter.charter.terminal;

import java.util.List;
import java.util.Optional;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * What the contract screen needs to know about one player, as the server sees it now. It has names and no UUIDs: a client
 * learns who applied, never who is online (see {@code CharterView}).
 *
 * @param role       where the player stands with the charters
 * @param charter    the charter the player is on or applied to, empty for {@link Role#NONE}
 * @param charters   for {@link Role#NONE}: the charters that can take an application, at most {@link ContractTerminalTuning#listedRows()}
 * @param applicants for {@link Role#DIRECTOR}: the names of the players waiting for an answer, at most {@link ContractTerminalTuning#listedRows()}
 * @param notice     the translation key of the refusal the last request met, or empty
 */
public record ContractState(Role role, String charter, List<String> charters, List<String> applicants, Optional<String> notice) {
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
			ByteBufCodecs.idMapper(index -> Role.values()[index], Role::ordinal), ContractState::role,
			ByteBufCodecs.stringUtf8(MAX_TEXT), ContractState::charter,
			ByteBufCodecs.stringUtf8(MAX_TEXT).apply(ByteBufCodecs.list(MAX_LIST)), ContractState::charters,
			ByteBufCodecs.stringUtf8(MAX_TEXT).apply(ByteBufCodecs.list(MAX_LIST)), ContractState::applicants,
			ByteBufCodecs.optional(ByteBufCodecs.stringUtf8(MAX_TEXT)), ContractState::notice,
			ContractState::new);

	public ContractState {
		charters = List.copyOf(charters);
		applicants = List.copyOf(applicants);
	}

	public ContractState withNotice(String key) {
		return new ContractState(role, charter, charters, applicants, Optional.of(key));
	}
}
