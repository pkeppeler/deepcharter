package io.github.pkeppeler.deepcharter.charter;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

import com.mojang.serialization.Codec;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;

/**
 * Every charter of the world, saved with it. Holds the rules: who may found, apply, approve, leave and spend. It only changes
 * and saves state. {@link Charters} is what the rest of the mod calls, and it adds events and client sync.
 *
 * <p>Every operation returns the reason it was refused, or empty when it was done. A refusal changes nothing.
 *
 * <p>A player is on at most one charter, or has at most one application, never both. Charter names are unique, ignoring case.
 * Nothing moves money between charters.
 *
 * <p>The saved form has a {@link #VERSION}. Data of another version loads as unreadable, is written back unchanged, and every
 * use of it throws, so a newer world is never overwritten by an older build.
 */
public final class CharterData extends SavedData {
	public static final int VERSION = 1;
	private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "charters");
	private static final Codec<Versioned<List<Charter>>> VERSIONED_CODEC = Versioned.codec(VERSION, Charter.CODEC.listOf().fieldOf("charters"));

	/** The codec of the saved data, which never fails to decode. */
	public static final Codec<CharterData> CODEC = VERSIONED_CODEC.xmap(CharterData::new, CharterData::versioned);
	// Datafixer type: vanilla applies it to saved data it reads. Our data has a version of its own, so the vanilla fixers find nothing to fix.
	public static final SavedDataType<CharterData> TYPE = new SavedDataType<>(ID, CharterData::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	private final Map<CharterId, Charter> charters = new LinkedHashMap<>();
	private final Optional<Versioned.Unreadable<List<Charter>>> unreadable;

	public CharterData() {
		this.unreadable = Optional.empty();
	}

	private CharterData(Versioned<List<Charter>> loaded) {
		switch (loaded) {
			case Versioned.Readable<List<Charter>> readable -> {
				readable.value().forEach(charter -> charters.put(charter.id(), charter));
				unreadable = Optional.empty();
			}
			case Versioned.Unreadable<List<Charter>> raw -> unreadable = Optional.of(raw);
		}
	}

	/** The world's charters. Call on the server thread. */
	public static CharterData get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	private Versioned<List<Charter>> versioned() {
		return unreadable.<Versioned<List<Charter>>>map(raw -> raw).orElseGet(() -> Versioned.of(List.copyOf(charters.values())));
	}

	private Map<CharterId, Charter> readable() {
		if (unreadable.isPresent()) {
			throw new IllegalStateException("the saved charters have version " + unreadable.get().version()
					+ " that this build cannot read (it reads " + VERSION + ")");
		}
		return charters;
	}

	/** False when the saved charters are of a version this build cannot read: every other method then throws. */
	public boolean isReadable() {
		return unreadable.isEmpty();
	}

	public Collection<Charter> all() {
		return List.copyOf(readable().values());
	}

	public Optional<Charter> find(CharterId id) {
		return Optional.ofNullable(readable().get(id));
	}

	public Optional<Charter> findByName(String name) {
		String wanted = name.strip();
		return readable().values().stream().filter(charter -> charter.name().equalsIgnoreCase(wanted)).findFirst();
	}

	/** The charter {@code player} is on, as Director or crew. */
	public Optional<Charter> charterOf(UUID player) {
		return readable().values().stream().filter(charter -> charter.onRoster(player)).findFirst();
	}

	/** The charter {@code player} has applied to, if any. */
	public Optional<Charter> applicationOf(UUID player) {
		return readable().values().stream().filter(charter -> charter.applications().contains(player)).findFirst();
	}

	/** {@code founder} founds a charter named {@code name} and becomes its Director. */
	public Optional<CharterRefusal> found(UUID founder, String name, CharterId id) {
		String trimmed = name.strip();
		if (trimmed.isEmpty() || trimmed.length() > CharterTuning.DEFAULT.maxNameLength()) {
			return Optional.of(CharterRefusal.INVALID_NAME);
		}
		if (charterOf(founder).isPresent() || applicationOf(founder).isPresent()) {
			return Optional.of(CharterRefusal.ALREADY_ON_A_CHARTER);
		}
		if (findByName(trimmed).isPresent()) {
			return Optional.of(CharterRefusal.NAME_TAKEN);
		}
		readable().put(id, Charter.founded(id, trimmed, founder));
		setDirty();
		return Optional.empty();
	}

	/** {@code applicant} applies to join charter {@code id} as crew. The Director approves or denies it. */
	public Optional<CharterRefusal> apply(UUID applicant, CharterId id) {
		Optional<Charter> charter = find(id);
		if (charter.isEmpty()) {
			return Optional.of(CharterRefusal.NO_SUCH_CHARTER);
		}
		if (charter.get().dormant()) {
			return Optional.of(CharterRefusal.CHARTER_DORMANT);
		}
		if (charterOf(applicant).isPresent()) {
			return Optional.of(CharterRefusal.ALREADY_ON_A_CHARTER);
		}
		if (applicationOf(applicant).isPresent()) {
			return Optional.of(CharterRefusal.ALREADY_APPLIED);
		}
		replace(charter.get().withApplication(applicant));
		return Optional.empty();
	}

	/** {@code player} revives the dormant charter {@code id} and becomes its Director. Its name, account and deepest point are kept. */
	public Optional<CharterRefusal> revive(UUID player, CharterId id) {
		Optional<Charter> charter = find(id);
		if (charter.isEmpty()) {
			return Optional.of(CharterRefusal.NO_SUCH_CHARTER);
		}
		if (!charter.get().dormant()) {
			return Optional.of(CharterRefusal.NOT_DORMANT);
		}
		if (charterOf(player).isPresent() || applicationOf(player).isPresent()) {
			return Optional.of(CharterRefusal.ALREADY_ON_A_CHARTER);
		}
		replace(charter.get().revivedBy(player));
		return Optional.empty();
	}

	/** The Director signs {@code applicant} on as the newest crew member. */
	public Optional<CharterRefusal> approve(UUID director, UUID applicant) {
		return decide(director, applicant, charter -> charter.withNewCrew(applicant));
	}

	/** The Director turns {@code applicant} down. */
	public Optional<CharterRefusal> deny(UUID director, UUID applicant) {
		return decide(director, applicant, charter -> charter.withoutApplication(applicant));
	}

	/**
	 * {@code player} leaves their charter, or withdraws their application. A leaving Director hands over to the
	 * longest-serving crew member, and the last person leaving makes the charter dormant.
	 */
	public Optional<CharterRefusal> leave(UUID player) {
		Optional<Charter> charter = charterOf(player);
		if (charter.isPresent()) {
			replace(charter.get().withoutPlayer(player));
			return Optional.empty();
		}
		Optional<Charter> applied = applicationOf(player);
		if (applied.isPresent()) {
			replace(applied.get().withoutApplication(player));
			return Optional.empty();
		}
		return Optional.of(CharterRefusal.NOT_ON_A_CHARTER);
	}

	/** Adds {@code amount}, which must be positive, to the account. A deposit that would pass {@code Long.MAX_VALUE} is refused. */
	public Optional<CharterRefusal> deposit(CharterId id, long amount) {
		if (amount <= 0) {
			return Optional.of(CharterRefusal.INVALID_AMOUNT);
		}
		Optional<Charter> charter = find(id);
		if (charter.isPresent() && charter.get().account() > Long.MAX_VALUE - amount) {
			return Optional.of(CharterRefusal.ACCOUNT_FULL);
		}
		return change(id, found -> found.withAccount(found.account() + amount));
	}

	/** Takes {@code amount}, which must be positive, from the account. The account never goes negative: an overdraft is refused. */
	public Optional<CharterRefusal> spend(CharterId id, long amount) {
		if (amount <= 0) {
			return Optional.of(CharterRefusal.INVALID_AMOUNT);
		}
		Optional<Charter> charter = find(id);
		if (charter.isPresent() && charter.get().account() < amount) {
			return Optional.of(CharterRefusal.INSUFFICIENT_FUNDS);
		}
		return change(id, found -> found.withAccount(found.account() - amount));
	}

	/** Raises the deepest point to {@code depth} if that is deeper than the record. A shallower depth changes nothing. */
	public Optional<CharterRefusal> recordDeepestPoint(CharterId id, int depth) {
		if (depth < 0) {
			return Optional.of(CharterRefusal.INVALID_AMOUNT);
		}
		return change(id, charter -> depth > charter.deepestPoint() ? charter.withDeepestPoint(depth) : charter);
	}

	private Optional<CharterRefusal> decide(UUID director, UUID applicant, UnaryOperator<Charter> decision) {
		Optional<Charter> charter = charterOf(director).filter(found -> found.isDirector(director));
		if (charter.isEmpty()) {
			return Optional.of(CharterRefusal.NOT_THE_DIRECTOR);
		}
		if (!charter.get().applications().contains(applicant)) {
			return Optional.of(CharterRefusal.NO_APPLICATION);
		}
		replace(decision.apply(charter.get()));
		return Optional.empty();
	}

	private Optional<CharterRefusal> change(CharterId id, UnaryOperator<Charter> change) {
		Optional<Charter> charter = find(id);
		if (charter.isEmpty()) {
			return Optional.of(CharterRefusal.NO_SUCH_CHARTER);
		}
		replace(change.apply(charter.get()));
		return Optional.empty();
	}

	private void replace(Charter charter) {
		readable().put(charter.id(), charter);
		setDirty();
	}
}
