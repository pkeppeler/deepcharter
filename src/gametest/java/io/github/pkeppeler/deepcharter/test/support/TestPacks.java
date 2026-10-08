package io.github.pkeppeler.deepcharter.test.support;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.pack.PackActivationType;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Registers the resource packs under {@code src/gametest/resources/resourcepacks/} as built-in packs that nothing turns on by itself: a
 * test turns one on with {@link ClientPacks}, as a player does in the pack screen. Used by the UI theme test and its evidence scenario.
 */
public final class TestPacks implements ModInitializer {
	/** Recolours the CRT terminals from phosphor green to amber, naming only the keys that change. */
	public static final String AMBER_CRT = "amber_crt";

	@Override
	public void onInitialize() {
		ResourceLoader.registerBuiltinPack(Identifier.fromNamespaceAndPath("deepcharter-test", AMBER_CRT),
				FabricLoader.getInstance().getModContainer("deepcharter-test").orElseThrow(),
				Component.literal("Amber CRT (test pack)"), PackActivationType.NORMAL);
	}
}
