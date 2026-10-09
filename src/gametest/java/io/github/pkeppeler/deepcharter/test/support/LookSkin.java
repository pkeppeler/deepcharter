package io.github.pkeppeler.deepcharter.test.support;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.layer.LayerChain;

/**
 * The look-book skin a run was built with (docs/tooling/look-book.md): {@code DEEPCHARTER_SKIN} names one of {@code skins/<id>/},
 * which gradle/skins.gradle packs into the test mod and {@link TestPacks} registers. Its data is on in every new world; a scenario
 * turns its assets on with {@link #enable} and keeps the grade of the place the player is in with {@link #holdGrade}. With no skin
 * both do nothing, so a scenario's plain run is unchanged.
 */
public final class LookSkin {
	/** Names the skin; tools/record-evidence.sh --skin sets it. */
	public static final String ENV = "DEEPCHARTER_SKIN";

	private LookSkin() {
	}

	/** The skin id of this run, or empty for a plain run. */
	public static Optional<String> active() {
		return Optional.ofNullable(System.getenv(ENV));
	}

	/** Selects the skin's resource pack as the top pack and waits for the reload, when the run has a skin. */
	public static void enable(ClientGameTestContext context) {
		if (active().isPresent()) {
			ClientPacks.enable(context, TestPacks.SKIN);
		}
	}

	/**
	 * Gives the player the skin's grade of the place they are in, and returns whether the client shows it yet: a scenario asks on
	 * each poll of its wait for a still, so the grade follows the player across a breach as well as a teleport. A place's grade is the
	 * post effect {@code deepcharter:grade/surface} or {@code deepcharter:grade/layer_<n>} when the skin has one, and none otherwise.
	 * True at once in a run with no skin.
	 */
	public static boolean holdGrade(ClientGameTestContext context, TestServerContext server) {
		if (active().isEmpty()) {
			return true;
		}
		int layer = server.computeOnServer(minecraft -> layerOf(minecraft.getPlayerList().getPlayers().getFirst()));
		List<Identifier> grade = context.computeOnClient(client -> gradeOf(client, layer));
		server.runOnServer(minecraft -> {
			ServerPlayer player = minecraft.getPlayerList().getPlayers().getFirst();
			if (!player.getPostEffects().equals(grade)) {
				player.clearPostEffects();
				grade.forEach(player::addPostEffect);
			}
		});
		return context.computeOnClient(client -> client.player.getActivePostEffects().equals(grade)
				&& client.gameRenderer.getAppliedPostEffects().containsAll(grade));
	}

	/** The post effects the client's player has and the ones the renderer applies, for a failure message. */
	public static String describeGrade(Minecraft client) {
		return "post effects " + client.player.getActivePostEffects() + ", applied " + client.gameRenderer.getAppliedPostEffects();
	}

	private static List<Identifier> gradeOf(Minecraft client, int layer) {
		Identifier grade = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "grade/" + (layer == LayerChain.SURFACE ? "surface" : "layer_" + layer));
		Identifier file = Identifier.fromNamespaceAndPath(grade.getNamespace(), "post_effect/" + grade.getPath() + ".json");
		return client.getResourceManager().getResource(file).isPresent() ? List.of(grade) : List.of();
	}

	private static int layerOf(ServerPlayer player) {
		Identifier dimension = player.level().dimension().identifier();
		OptionalInt layer = LayerChain.indexOf(dimension);
		if (layer.isEmpty()) {
			throw new AssertionError("the player is in " + dimension + ", which is not in the layer chain");
		}
		return layer.getAsInt();
	}
}
