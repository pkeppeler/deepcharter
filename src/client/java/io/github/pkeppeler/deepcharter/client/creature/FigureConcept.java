package io.github.pkeppeler.deepcharter.client.creature;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * The lampless figure concepts of #250, for the user to pick one before the build. A concept is drawn only when the dev switch
 * {@value #PROPERTY} names it; without the switch the figure keeps its placeholder look ({@link LamplessFigureRenderer}). Each concept is
 * three resource-pack files, written by {@code tools/figure_concepts.py}: a GeckoLib model, a texture and an animation file. Java names the
 * files and the two animations, and holds no visual of its own.
 */
public enum FigureConcept {
	CANDLE,
	HERON,
	REACHER,
	MISFIT;

	/** The system property that picks a concept. It is read whenever the renderers are built, which is on every resource reload. */
	public static final String PROPERTY = "deepcharter.figureConcept";
	/** The animation played while the figure stands, and while it walks. */
	public static final String IDLE = "animation.figure.idle";
	public static final String WALK = "animation.figure.walk";
	/**
	 * The box the figure is culled by, round its feet: the tallest and widest any concept stands, with its lean and its reach. It is
	 * 48 by 24 px in {@code tools/figure_concepts.py}, which fails a model that leaves it.
	 */
	public static final double CULL_HEIGHT_BLOCKS = 3.0;
	public static final double CULL_REACH_BLOCKS = 1.5;

	/** The concept's name in the switch and in its file names. */
	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	/** The id GeckoLib knows the model and the animations by: {@code geckolib/models/<path>.geo.json} and {@code geckolib/animations/<path>.animation.json}. */
	public Identifier resource() {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "creature/figure/" + id());
	}

	public Identifier modelFile() {
		return resource().withPath("geckolib/models/creature/figure/" + id() + ".geo.json");
	}

	public Identifier animationFile() {
		return resource().withPath("geckolib/animations/creature/figure/" + id() + ".animation.json");
	}

	public Identifier texture() {
		return resource().withPath("textures/entity/creature/figure/" + id() + ".png");
	}

	/** The concept the switch names, or empty when it is unset or blank. A name that is no concept throws. */
	public static Optional<FigureConcept> selected() {
		String value = System.getProperty(PROPERTY, "").strip();
		if (value.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(Arrays.stream(values()).filter(concept -> concept.id().equals(value)).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("-D" + PROPERTY + "=" + value + " names no figure concept; the concepts are "
						+ Arrays.stream(values()).map(FigureConcept::id).toList())));
	}

	/**
	 * Returns this concept after checking that its files are there: the model, the texture, and an animation file with the idle and the
	 * walk. A missing one would draw a missing-model cube, or magenta, or a figure frozen in its bind pose, with no error, so this throws
	 * and names the file.
	 */
	public FigureConcept checked(ResourceManager resources) {
		for (Identifier file : new Identifier[] {modelFile(), texture()}) {
			if (resources.getResource(file).isEmpty()) {
				throw new IllegalStateException("The figure concept " + id() + " has no file " + file);
			}
		}
		Resource animations = resources.getResource(animationFile())
				.orElseThrow(() -> new IllegalStateException("The figure concept " + id() + " has no file " + animationFile()));
		JsonObject held;
		try (Reader reader = animations.openAsReader()) {
			held = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("animations");
		} catch (IOException e) {
			throw new UncheckedIOException("Could not read " + animationFile(), e);
		} catch (JsonParseException | IllegalStateException | ClassCastException e) {
			throw new IllegalStateException(animationFile() + " is not an animation file: " + e.getMessage(), e);
		}
		for (String name : new String[] {IDLE, WALK}) {
			if (held == null || !held.has(name)) {
				throw new IllegalStateException("The figure concept " + id() + " has no animation " + name + " in " + animationFile());
			}
		}
		return this;
	}
}
