package io.github.pkeppeler.deepcharter.test.evidence;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;

import io.github.pkeppeler.deepcharter.client.ore.OreCargoScreen;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;

/**
 * Evidence scenario "m2-ore-cargo": a pod loaded with six kinds of ore, and the player sneak-using it. The
 * screenshot is the cargo screen with the ore in its slots and the mass underneath.
 */
public class OreCargoScenario extends EvidenceScenario {
	private static final OreType[] LOAD = {OreType.IRONIUM, OreType.BRONZIUM, OreType.GOLDIUM, OreType.PLATINIUM, OreType.EINSTEINIUM, OreType.CICATRIUM};

	@Override
	protected String name() {
		return "m2-ore-cargo";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitTicks(40);
			frame(context);
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				PodEntity pod = PodRegistry.POD.create(player.level(), EntitySpawnReason.COMMAND);
				pod.setPos(player.position().add(2, 0, 0));
				player.level().addFreshEntity(pod);
				for (OreType type : LOAD) {
					pod.cargo().tryAdd(pod, OreRegistry.stack(type));
				}
				player.setShiftKeyDown(true);
				UseEntityCallback.EVENT.invoker().interact(player, player.level(), InteractionHand.MAIN_HAND, pod, null);
				player.setShiftKeyDown(false);
			});
			context.waitForScreen(OreCargoScreen.class);
			context.waitTicks(10);
			frame(context);
			screenshot(context, "ore-cargo-screen");
		}
	}
}
