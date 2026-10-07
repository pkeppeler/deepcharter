package io.github.pkeppeler.deepcharter.upgrade;

import java.util.Locale;

import net.minecraft.network.chat.Component;

/** Why the upgrade terminal refused a purchase. A refusal changes nothing. The account's own refusals are {@code CharterRefusal}. */
public enum UpgradeRefusal {
	/** The request names no track, a track that does not exist, or a tier that track has no part of. */
	BAD_REQUEST,
	/** None of the player's charter's pods is parked at the terminal, and no other pod either. */
	NO_POD,
	/** A pod is parked at the terminal, but none of the player's charter's. */
	NOT_YOUR_POD,
	/** The pod has no owner, so a part in it would be void and a refill would repair it. */
	NOT_REGISTERED,
	/** The world's saved serials are of a version this build cannot read, so no part can be minted. */
	SERIALS_UNREADABLE,
	/** The pod has a part of this track that is as good or better, and works for the charter. */
	NOT_AN_UPGRADE;

	public String translationKey() {
		return "deepcharter.upgrade.refusal." + name().toLowerCase(Locale.ROOT);
	}

	public Component message() {
		return Component.translatable(translationKey());
	}
}
