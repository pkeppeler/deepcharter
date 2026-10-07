package io.github.pkeppeler.deepcharter.test.evidence;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;

import net.minecraft.client.CameraType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;

/**
 * Evidence scenario "pod-cargo-fuel": a pod that holds the jump key while too heavy to lift off, then lifts once its bay is
 * emptied, then runs out of fuel and is stranded, and flies again after the player feeds it coal.
 */
public class PodCargoFuelScenario extends EvidenceScenario {
	private static final OreType[] OVERLOAD = {OreType.EINSTEINIUM, OreType.EINSTEINIUM, OreType.EINSTEINIUM,
			OreType.PLATINIUM, OreType.PLATINIUM, OreType.PLATINIUM};
	private static final int TICKS_PER_FRAME = 2;
	private static final int HOLD_FRAMES = 15;
	private static final int COAL = 3;

	@Override
	protected String name() {
		return "pod-cargo-fuel";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			TestServerContext server = singleplayer.getServer();
			context.waitTicks(40);
			server.runOnServer(minecraftServer -> {
				ServerPlayer player = minecraftServer.getPlayerList().getPlayers().getFirst();
				PodEntity pod = PodRegistry.POD.create(player.level(), EntitySpawnReason.COMMAND);
				pod.setPos(player.position());
				player.level().addFreshEntity(pod);
				for (OreType type : OVERLOAD) {
					if (!pod.cargo().tryAdd(pod, OreRegistry.stack(type))) {
						throw new AssertionError("the bay should take " + type);
					}
				}
				if (!player.startRiding(pod)) {
					throw new AssertionError("the player could not mount the pod");
				}
			});
			context.waitFor(client -> client.player.getVehicle() instanceof PodEntity);
			context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
			context.waitTicks(20);
			frame(context);

			// Too heavy: the rotor spins up but the pod stays on the ground.
			context.getInput().holdKey(options -> options.keyJump);
			record(context, HOLD_FRAMES);
			screenshot(context, "overloaded-stays-down");

			// Empty the bay: the same key now lifts.
			server.runOnServer(minecraftServer -> {
				PodEntity pod = (PodEntity) minecraftServer.getPlayerList().getPlayers().getFirst().getVehicle();
				pod.cargo().dump(pod);
			});
			record(context, HOLD_FRAMES);
			screenshot(context, "emptied-lifts-off");

			// Out of fuel: the pod ignores the key and falls.
			server.runOnServer(minecraftServer -> {
				PodEntity pod = (PodEntity) minecraftServer.getPlayerList().getPlayers().getFirst().getVehicle();
				pod.setFuel(0f);
			});
			record(context, HOLD_FRAMES);
			screenshot(context, "stranded-falls");

			// Coal on the pod refuels it and rescues it.
			server.runOnServer(minecraftServer -> {
				ServerPlayer player = minecraftServer.getPlayerList().getPlayers().getFirst();
				PodEntity pod = (PodEntity) player.getVehicle();
				for (int i = 0; i < COAL; i++) {
					player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COAL));
					UseEntityCallback.EVENT.invoker().interact(player, player.level(), InteractionHand.MAIN_HAND, pod, null);
				}
			});
			record(context, HOLD_FRAMES);
			screenshot(context, "refuelled-flies-again");
			context.getInput().releaseKey(options -> options.keyJump);
		}
	}

	private void record(ClientGameTestContext context, int frames) {
		for (int i = 0; i < frames; i++) {
			context.waitTicks(TICKS_PER_FRAME);
			frame(context);
		}
	}
}
