package io.github.pkeppeler.deepcharter.client.creature;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * The ids of the lampless figure's look, a skin a resource pack replaces file by file ({@code docs/design/skins.md}): the model and the
 * animations under {@code geckolib/}, the texture under {@code textures/}; a figure that carries a lamp has a model, a texture and a glowmask
 * of its own. The files are written by {@code tools/lampless_figure.py}.
 */
public final class LamplessFigureAssets {
	/** The id GeckoLib knows the model ({@code geckolib/models/<RESOURCE>.geo.json}) and the animations by. */
	public static final String RESOURCE = "creature/lampless_figure";
	/** {@code textures/<TEXTURE>.png}. */
	public static final String TEXTURE = "entity/lampless_figure";
	/** The model of a figure that carries a lamp: the same bones and one more, {@code lantern}. */
	public static final String LAMP_RESOURCE = "creature/lampless_figure_lamp";
	/** {@code textures/<LAMP_TEXTURE>.png}; the glowmask is {@code <LAMP_TEXTURE>_glowmask.png}, clear except on the lamp's glass and drawn at full bright. */
	public static final String LAMP_TEXTURE = "entity/lampless_figure_lamp";
	public static final String IDLE = "animation.figure.idle";
	public static final String WALK = "animation.figure.walk";
	/** Pressed to a pod's hull. */
	public static final String CLING = "animation.figure.cling";
	/** Match CULL_HEIGHT_PX and CULL_REACH_PX in {@code tools/lampless_figure.py}, which fails a model that leaves the box. */
	public static final double CULL_HEIGHT_BLOCKS = 3.0;
	public static final double CULL_REACH_BLOCKS = 2.0;

	private LamplessFigureAssets() {
	}

	public static Identifier resource() {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, RESOURCE);
	}

	public static Identifier modelFile() {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "geckolib/models/" + RESOURCE + ".geo.json");
	}

	public static Identifier animationFile() {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "geckolib/animations/" + RESOURCE + ".animation.json");
	}

	public static Identifier texture() {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "textures/" + TEXTURE + ".png");
	}

	public static Identifier lampModelFile() {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "geckolib/models/" + LAMP_RESOURCE + ".geo.json");
	}

	public static Identifier lampTexture() {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "textures/" + LAMP_TEXTURE + ".png");
	}

	public static Identifier glowmask() {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "textures/" + LAMP_TEXTURE + "_glowmask.png");
	}

	/** The model {@code lamp} draws: the lamp-carrier's or the lampless figure's. */
	public static Identifier resource(boolean lamp) {
		return lamp ? Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, LAMP_RESOURCE) : resource();
	}

	public static Identifier texture(boolean lamp) {
		return lamp ? lampTexture() : texture();
	}

	/** Throws, naming the file, when a file or animation is missing: that would otherwise draw a missing-model cube, magenta, or a frozen bind pose. */
	public static void check(ResourceManager resources) {
		for (Identifier file : new Identifier[] {modelFile(), texture(), lampModelFile(), lampTexture(), glowmask()}) {
			if (resources.getResource(file).isEmpty()) {
				throw new IllegalStateException("The lampless figure has no file " + file);
			}
		}
		Resource animations = resources.getResource(animationFile())
				.orElseThrow(() -> new IllegalStateException("The lampless figure has no file " + animationFile()));
		JsonObject held;
		try (Reader reader = animations.openAsReader()) {
			held = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("animations");
		} catch (IOException e) {
			throw new UncheckedIOException("Could not read " + animationFile(), e);
		} catch (JsonParseException | IllegalStateException | ClassCastException e) {
			throw new IllegalStateException(animationFile() + " is not an animation file: " + e.getMessage(), e);
		}
		for (String name : new String[] {IDLE, WALK, CLING}) {
			if (held == null || !held.has(name)) {
				throw new IllegalStateException("The lampless figure has no animation " + name + " in " + animationFile());
			}
		}
	}
}
