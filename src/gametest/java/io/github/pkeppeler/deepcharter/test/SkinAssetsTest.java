package io.github.pkeppeler.deepcharter.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.pod.PodSkins;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;

/**
 * Server GameTests for #258 (ADR 0033): every pod, wreck and drill model and every mod particle has the resource files a skin
 * replaces. A texture in another namespace (the default models borrow vanilla's) is a pack's business, not this mod's.
 */
public class SkinAssetsTest {
	@GameTest
	public void everyPodModelHasItsFiles(GameTestHelper helper) throws IOException {
		List<EntityType<?>> pods = List.of(PodRegistry.POD, PodRegistry.PROSPECTOR);
		for (EntityType<?> type : pods) {
			PodSkins skins = PodSkins.of(PodRegistry.chassisOf(type));
			for (Identifier id : skins.all()) {
				checkModelFiles(helper, id);
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

	/** The item model definition the renderer asks for, the model it names, and the model's own textures. */
	private static void checkModelFiles(GameTestHelper helper, Identifier id) throws IOException {
		JsonObject definition = json(helper, "/assets/%s/items/%s.json".formatted(id.getNamespace(), id.getPath()));
		String named = definition.getAsJsonObject("model").get("model").getAsString();
		if (!Identifier.parse(named).equals(id)) {
			throw failure(helper, "items/%s.json names the model %s, not %s", id.getPath(), named, id);
		}
		JsonObject model = json(helper, "/assets/%s/models/%s.json".formatted(id.getNamespace(), id.getPath()));
		if (!model.has("elements") && !model.has("parent")) {
			throw failure(helper, "models/%s.json has neither elements nor a parent", id.getPath());
		}
		if (model.has("textures")) {
			for (Map.Entry<String, JsonElement> texture : model.getAsJsonObject("textures").entrySet()) {
				String value = texture.getValue().getAsString();
				if (!value.startsWith("#")) {
					checkTexture(helper, Identifier.parse(value), "textures/");
				}
			}
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
