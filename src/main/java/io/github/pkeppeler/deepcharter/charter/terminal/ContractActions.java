package io.github.pkeppeler.deepcharter.charter.terminal;

import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;

/** The ids of the contract terminal's actions and the key of their one argument. Plain constants, so the client screen can use them. */
public final class ContractActions {
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

	private ContractActions() {
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path);
	}
}
