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

/** The lampless figure concepts of #250: drawn only while the dev switch {@link #PROPERTY} names one. Files are written by {@code tools/figure_concepts.py}. */
public enum FigureConcept {
	CANDLE,
	HERON,
	REACHER,
	MISFIT;

	public static final String PROPERTY = "deepcharter.figureConcept";
	public static final String IDLE = "animation.figure.idle";
	public static final String WALK = "animation.figure.walk";
	/** Matches CULL_HEIGHT_PX and CULL_REACH_PX in {@code tools/figure_concepts.py}, which fails a model that leaves the box. */
	public static final double CULL_HEIGHT_BLOCKS = 3.0;
	public static final double CULL_REACH_BLOCKS = 1.5;

	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	/** The id GeckoLib knows the model and the animations by. */
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

	/** Empty when the switch is unset or blank; a name that is no concept throws. */
	public static Optional<FigureConcept> selected() {
		String value = System.getProperty(PROPERTY, "").strip();
		if (value.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(Arrays.stream(values()).filter(concept -> concept.id().equals(value)).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("-D" + PROPERTY + "=" + value + " names no figure concept; the concepts are "
						+ Arrays.stream(values()).map(FigureConcept::id).toList())));
	}

	/** Throws, naming the file, when a file or animation is missing: that would otherwise draw a missing-model cube, magenta, or a frozen bind pose. */
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
