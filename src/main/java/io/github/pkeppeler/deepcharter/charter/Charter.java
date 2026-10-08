package io.github.pkeppeler.deepcharter.charter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;

/**
 * One charter: its name, who runs it, who is crew, who has applied, its account and its deepest point. Immutable: every change
 * returns a new charter, and the constructor rejects a state that cannot exist.
 *
 * <p>{@code crew} is every player on the charter except the Director, longest-serving first: a player joins at the end, so the
 * order is the seniority that decides who takes over when the Director leaves. A charter with no Director is dormant, and a
 * dormant charter has no crew and no applications. The account is never negative.
 *
 * @param deepestPoint the deepest point the charter has reached, in blocks below the surface, never negative
 */
public record Charter(
		CharterId id,
		String name,
		Optional<UUID> director,
		List<UUID> crew,
		List<UUID> applications,
		long account,
		int deepestPoint) {

	/** The body of a saved charter. Invalid saved state decodes to an error, never to an exception. */
	public static final Codec<Charter> CODEC = RecordCodecBuilder.<Fields>create(instance -> instance.group(
			CharterId.CODEC.fieldOf("id").forGetter(Fields::id),
			Codec.STRING.fieldOf("name").forGetter(Fields::name),
			UUIDUtil.CODEC.optionalFieldOf("director").forGetter(Fields::director),
			UUIDUtil.CODEC.listOf().fieldOf("crew").forGetter(Fields::crew),
			UUIDUtil.CODEC.listOf().fieldOf("applications").forGetter(Fields::applications),
			Codec.LONG.fieldOf("account").forGetter(Fields::account),
			Codec.INT.fieldOf("deepest_point").forGetter(Fields::deepestPoint)).apply(instance, Fields::new))
			.flatXmap(Fields::toCharter, charter -> DataResult.success(Fields.of(charter)));

	public Charter {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(director, "director");
		crew = List.copyOf(crew);
		applications = List.copyOf(applications);
		String problem = problem(director, crew, applications, account, deepestPoint);
		if (problem != null) {
			throw new IllegalArgumentException("charter " + name + ": " + problem);
		}
	}

	/** A new charter with its founder as Director and nothing else. */
	public static Charter founded(CharterId id, String name, UUID founder) {
		return new Charter(id, name, Optional.of(founder), List.of(), List.of(), 0, 0);
	}

	/** True when nobody is left to run the charter. Its account and progress are kept. */
	public boolean dormant() {
		return director.isEmpty();
	}

	public boolean isDirector(UUID player) {
		return director.equals(Optional.of(player));
	}

	/** True for the Director and for crew. An applicant is not on the roster. */
	public boolean onRoster(UUID player) {
		return isDirector(player) || crew.contains(player);
	}

	/** The Director first, then the crew, longest-serving first. */
	public List<UUID> roster() {
		List<UUID> roster = new ArrayList<>();
		director.ifPresent(roster::add);
		roster.addAll(crew);
		return List.copyOf(roster);
	}

	/** A dormant charter with {@code player} as its Director. Name, account and deepest point are kept. */
	public Charter revivedBy(UUID player) {
		if (!dormant()) {
			throw new IllegalStateException("charter " + name + " is not dormant");
		}
		return new Charter(id, name, Optional.of(player), List.of(), List.of(), account, deepestPoint);
	}

	public Charter withApplication(UUID applicant) {
		List<UUID> applied = new ArrayList<>(applications);
		applied.add(applicant);
		return new Charter(id, name, director, crew, applied, account, deepestPoint);
	}

	public Charter withoutApplication(UUID applicant) {
		List<UUID> applied = new ArrayList<>(applications);
		applied.remove(applicant);
		return new Charter(id, name, director, crew, applied, account, deepestPoint);
	}

	/** Signs the applicant on as the newest crew member and removes the application. */
	public Charter withNewCrew(UUID applicant) {
		List<UUID> signed = new ArrayList<>(crew);
		signed.add(applicant);
		return withoutApplication(applicant).withCrew(signed);
	}

	/**
	 * Takes {@code player} off the roster. When the Director leaves, the longest-serving crew member takes over; with no crew
	 * left the charter goes dormant and drops its applications, because nobody can approve them.
	 */
	public Charter withoutPlayer(UUID player) {
		if (isDirector(player)) {
			if (crew.isEmpty()) {
				return new Charter(id, name, Optional.empty(), List.of(), List.of(), account, deepestPoint);
			}
			return new Charter(id, name, Optional.of(crew.getFirst()), crew.subList(1, crew.size()), applications, account, deepestPoint);
		}
		List<UUID> remaining = new ArrayList<>(crew);
		remaining.remove(player);
		return withCrew(remaining);
	}

	public Charter withAccount(long newAccount) {
		return new Charter(id, name, director, crew, applications, newAccount, deepestPoint);
	}

	public Charter withDeepestPoint(int newDeepestPoint) {
		return new Charter(id, name, director, crew, applications, account, newDeepestPoint);
	}

	private Charter withCrew(List<UUID> newCrew) {
		return new Charter(id, name, director, newCrew, applications, account, deepestPoint);
	}

	private static String problem(Optional<UUID> director, List<UUID> crew, List<UUID> applications, long account, int deepestPoint) {
		if (account < 0) {
			return "the account cannot be negative, got " + account;
		}
		if (deepestPoint < 0) {
			return "the deepest point cannot be negative, got " + deepestPoint;
		}
		if (director.isEmpty() && !(crew.isEmpty() && applications.isEmpty())) {
			return "a dormant charter has no crew and no applications";
		}
		List<UUID> everyone = new ArrayList<>(crew);
		everyone.addAll(applications);
		director.ifPresent(everyone::add);
		if (new HashSet<>(everyone).size() != everyone.size()) {
			return "a player can be only one of Director, crew and applicant, once";
		}
		return null;
	}

	/** The saved fields as they are read, before the charter's rules are checked. */
	private record Fields(
			CharterId id, String name, Optional<UUID> director, List<UUID> crew, List<UUID> applications, long account, int deepestPoint) {
		static Fields of(Charter charter) {
			return new Fields(charter.id, charter.name, charter.director, charter.crew, charter.applications, charter.account, charter.deepestPoint);
		}

		DataResult<Charter> toCharter() {
			String problem = problem(director, crew, applications, account, deepestPoint);
			if (problem != null) {
				return DataResult.error(() -> "charter " + name + ": " + problem);
			}
			return DataResult.success(new Charter(id, name, director, crew, applications, account, deepestPoint));
		}
	}
}
