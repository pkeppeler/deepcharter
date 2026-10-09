package io.github.pkeppeler.deepcharter.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.pod.PodLook;
import io.github.pkeppeler.deepcharter.pod.Chassis;

/**
 * Server GameTests for #258 and #243 (ADR 0040): every pod look has the model and textures it names, and every mod particle has the
 * resource files a skin replaces. A texture in another namespace is a pack's business, not this mod's.
 */
public class SkinAssetsTest {
	@GameTest
	public void everyPodLookHasItsFiles(GameTestHelper helper) throws IOException {
		for (Chassis chassis : Chassis.all()) {
			PodLook look = PodGeoModelTest.look(helper, chassis);
			String model = "/assets/%s/%s".formatted(look.model().getNamespace(), look.modelFile().getPath());
			if (SkinAssetsTest.class.getResource(model) == null) {
				throw failure(helper, "the look of %s names the model %s, which is not on the classpath at %s", chassis.id(), look.model(), model);
			}
			for (PodLook.Variant variant : List.of(look.intact(), look.wreck())) {
				checkTexture(helper, variant.texture());
				if (variant.glow() != PodLook.Glow.NEVER) {
					checkTexture(helper, variant.glowmask());
				}
			}
		}
		helper.succeed();
	}

	@GameTest
	public void everyModParticleHasItsFiles(GameTestHelper helper) throws IOException {
		List<Identifier> ours = new ArrayList<>();
		for (ParticleType<?> type : BuiltInRegistries.PARTICLE_TYPE) {
			Identifier id = BuiltInRegistries.PARTICLE_TYPE.getKey(type);
			if (id.getNamespace().equals(DeepCharter.MOD_ID)) {
				ours.add(id);
			}
		}
		Identifier cable = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "tow_cable");
		if (!ours.contains(cable)) {
			throw failure(helper, "the particle %s is not registered; mod particles are %s", cable, ours);
		}
		for (Identifier id : ours) {
			JsonObject description = json(helper, "/assets/%s/particles/%s.json".formatted(id.getNamespace(), id.getPath()));
			if (!description.has("textures") || description.getAsJsonArray("textures").isEmpty()) {
				throw failure(helper, "particles/%s.json lists no textures", id.getPath());
			}
			for (JsonElement texture : description.getAsJsonArray("textures")) {
				checkTexture(helper, Identifier.parse(texture.getAsString()), "textures/particle/");
			}
		}
		helper.succeed();
	}

	/** The figure's model is the vanilla zombie's (ADR 0033), so its look is its texture, which a pack replaces. */
	@GameTest
	public void theLamplessFigureHasItsTexture(GameTestHelper helper) {
		checkTexture(helper, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "entity/lampless_figure"), "textures/");
		helper.succeed();
	}

	/** A pod texture named by its full path, as a look names it. */
	private static void checkTexture(GameTestHelper helper, Identifier texture) {
		if (!texture.getNamespace().equals(DeepCharter.MOD_ID)) {
			return;
		}
		String path = "/assets/%s/%s".formatted(texture.getNamespace(), texture.getPath());
		if (SkinAssetsTest.class.getResource(path) == null) {
			throw failure(helper, "the texture %s is missing: no %s on the classpath", texture, path);
		}
	}

	private static void checkTexture(GameTestHelper helper, Identifier texture, String root) {
		if (!texture.getNamespace().equals(DeepCharter.MOD_ID)) {
			return;
		}
		String path = "/assets/%s/%s%s.png".formatted(texture.getNamespace(), root, texture.getPath());
		if (SkinAssetsTest.class.getResource(path) == null) {
			throw failure(helper, "the texture %s is missing: no %s on the classpath", texture, path);
		}
	}

	private static JsonObject json(GameTestHelper helper, String path) throws IOException {
		try (InputStream stream = SkinAssetsTest.class.getResourceAsStream(path)) {
			if (stream == null) {
				throw failure(helper, "%s is not on the classpath", path);
			}
			try (Reader reader = new InputStreamReader(stream)) {
				return JsonParser.parseReader(reader).getAsJsonObject();
			}
		}
	}

	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}
}
