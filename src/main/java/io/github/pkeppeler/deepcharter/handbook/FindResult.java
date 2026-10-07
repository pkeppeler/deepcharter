package io.github.pkeppeler.deepcharter.handbook;

/** What happened when a player used a Note block ({@link Notes#find}). */
public enum FindResult {
	/** The Note is now in the handbook of every member of the player's charter. */
	FOUND,
	/** The charter had already found it; nothing changed. */
	ALREADY_FOUND,
	/** The player is on no charter, so there is no handbook to file it in; nothing changed. */
	NO_CHARTER,
	/** The block names a Note that does not exist (yet); nothing changed. */
	UNKNOWN,
	/** The saved charters or Notes are of a version this build cannot read; nothing changed. */
	UNREADABLE
}
