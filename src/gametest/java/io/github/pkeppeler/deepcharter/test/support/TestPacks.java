package io.github.pkeppeler.deepcharter.test.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonParser;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.pack.PackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Registers the resource packs under {@code src/gametest/resources/resourcepacks/} as built-in packs that nothing turns on by itself: a
 * test turns one on with {@link ClientPacks}, as a player does in the pack screen. Used by the UI theme test and its evidence scenario.
 * A run with a look-book skin ({@link LookSkin}) also has the pack {@link #SKIN}, whose data is on by default in a new world.
 */
public final class TestPacks implements ModInitializer {
	/** Recolours the CRT terminals from phosphor green to amber, naming only the keys that change. */
	public static final String AMBER_CRT = "amber_crt";

	/** Has one bad colour in {@code crt.json}, so a reload with it must fail and name the pack. */
	public static final String BAD_CRT = "bad_crt";
	/** Turns the handbook's ink red. */
	public static final String RED_INK = "red_ink";
	/** The look-book skin of the run, which gradle/skins.gradle copies from {@code skins/<id>/}. */
	public static final String SKIN = "skin";

	@Override
	public void onInitialize() {
		ModContainer container = FabricLoader.getInstance().getModContainer("deepcharter-test").orElseThrow();
		for (String pack : new String[] {AMBER_CRT, BAD_CRT, RED_INK}) {
			ResourceLoader.registerBuiltinPack(Identifier.fromNamespaceAndPath("deepcharter-test", pack), container,
					Component.literal(pack + " (test pack)"), PackActivationType.NORMAL);
		}
		LookSkin.active().ifPresent(skin -> {
			Path spec = container.findPath("resourcepacks/" + SKIN + "/skin.json").orElseThrow(() -> new IllegalStateException(
					LookSkin.ENV + " is '" + skin + "', but the test mod holds no skin pack: build with the same " + LookSkin.ENV
					+ " (tools/record-evidence.sh <scenario> --skin=" + skin + ")"));
			String packed = packedId(spec);
			if (!packed.equals(skin)) {
				throw new IllegalStateException(LookSkin.ENV + " is '" + skin + "', but the test mod holds the skin '" + packed
						+ "': build with the same " + LookSkin.ENV);
			}
			ResourceLoader.registerBuiltinPack(Identifier.fromNamespaceAndPath("deepcharter-test", SKIN), container,
					Component.literal("Look book: " + skin), PackActivationType.DEFAULT_ENABLED);
		});
	}

	private static String packedId(Path spec) {
		try {
			return JsonParser.parseString(Files.readString(spec)).getAsJsonObject().get("id").getAsString();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
