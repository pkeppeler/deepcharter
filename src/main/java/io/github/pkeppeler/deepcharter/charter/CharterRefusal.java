package io.github.pkeppeler.deepcharter.charter;

import java.util.Locale;

import net.minecraft.network.chat.Component;

/** Why a charter operation was refused. A refusal changes nothing. */
public enum CharterRefusal {
	INVALID_NAME,
	NAME_TAKEN,
	ALREADY_ON_A_CHARTER,
	ALREADY_APPLIED,
	NO_SUCH_CHARTER,
	CHARTER_DORMANT,
	NOT_DORMANT,
	NOT_THE_DIRECTOR,
	NO_APPLICATION,
	NOT_ON_A_CHARTER,
	INVALID_AMOUNT,
	INSUFFICIENT_FUNDS,
	ACCOUNT_FULL;

	public String translationKey() {
		return "deepcharter.charter.refusal." + name().toLowerCase(Locale.ROOT);
	}

	public Component message() {
		return Component.translatable(translationKey());
	}
}
