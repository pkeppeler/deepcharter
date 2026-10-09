package io.github.pkeppeler.deepcharter.test;

import java.util.List;
import java.util.Optional;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntitySpawnReason;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.client.pod.PodStatusHud;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodLiner;
import io.github.pkeppeler.deepcharter.pod.PodLining;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/**
 * Client GameTest for the liner HUD (#339): a pod with no liner shows no liner line; once a liner is fitted the HUD shows the rack's slag
 * and the slabs to the next ring, and the count follows the pod as it sinks.
 */
public class PodLinerClientTest implements FabricClientGameTest {
	private static final int RACK = 5;
	private static final int RING_EVERY = 4;
	private static final int SUNK = 3;

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				PodEntity pod = PodRegistry.POD.create(player.level(), EntitySpawnReason.COMMAND);
				pod.setPos(player.position());
				player.level().addFreshEntity(pod);
				if (!player.startRiding(pod)) {
					throw new AssertionError("the player could not mount the new pod");
				}
				PodLining.modify(pod, state -> new PodLining.State(0, RACK, 0, false, false));
			});
			ClientWait.until(context, "the stock line on the pod HUD", client -> client.player != null && client.player.getVehicle() instanceof PodEntity pod
					&& keys(PodStatusHud.lines(pod)).contains("hud.deepcharter.pod.slag_only"));
			boolean shownWithoutPart = context.computeOnClient(client -> PodStatusHud.linerLine((PodEntity) client.player.getVehicle()).isPresent());
			if (shownWithoutPart) {
				throw new AssertionError("A pod with no liner shows no liner line");
			}

			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				ScannerPods.fit(server, player, (PodEntity) player.getVehicle(), ComponentTrack.LINER, 1);
			});
			ClientWait.until(context, "the liner line on the pod HUD", client -> client.player.getVehicle() instanceof PodEntity pod
					&& PodStatusHud.linerLine(pod).isPresent());
			int slabs = context.computeOnClient(client -> slabsInLine(PodStatusHud.linerLine((PodEntity) client.player.getVehicle())));
			if (slabs != RING_EVERY) {
				throw new AssertionError("A new tier 1 liner rings in " + RING_EVERY + " slabs, the HUD says " + slabs);
			}

			// The pod has sunk three slabs since its last ring: one more slab and the liner rings.
			singleplayer.getServer().runOnServer(server -> {
				PodEntity pod = (PodEntity) server.getPlayerList().getPlayers().getFirst().getVehicle();
				PodLiner.Anchor here = new PodLiner.Anchor(pod.getBlockY(), Mth.floor(pod.getX() - 0.5), Mth.floor(pod.getZ() - 0.5));
				pod.setAttached(PodLiner.STATE, Versioned.of(new PodLiner.State(Optional.of(new PodLiner.Anchor(here.feetY() + SUNK, here.lowX(), here.lowZ())))));
			});
			ClientWait.until(context, "one slab to the next ring on the pod HUD", client -> client.player.getVehicle() instanceof PodEntity pod
					&& PodStatusHud.linerLine(pod).map(PodLinerClientTest::slabsOf).orElse(-1) == RING_EVERY - SUNK);
			List<Component> lines = context.computeOnClient(client -> PodStatusHud.lines((PodEntity) client.player.getVehicle()));
			boolean rackShown = lines.stream().anyMatch(line -> line.getContents() instanceof TranslatableContents contents
					&& contents.getKey().equals("hud.deepcharter.pod.slag_only") && contents.getArgs()[0].equals(RACK));
			if (!rackShown) {
				throw new AssertionError("The stock line shows the rack's " + RACK + " bricks beside the liner line, the lines are " + keys(lines));
			}
		}
	}

	private static int slabsInLine(Optional<Component> line) {
		return line.map(PodLinerClientTest::slabsOf).orElse(-1);
	}

	private static int slabsOf(Component line) {
		if (!(line.getContents() instanceof TranslatableContents contents) || !contents.getKey().equals("hud.deepcharter.pod.liner")) {
			throw new AssertionError("not the liner line: " + line);
		}
		return (Integer) contents.getArgs()[0];
	}

	private static List<String> keys(List<Component> lines) {
		return lines.stream().map(line -> line.getContents() instanceof TranslatableContents contents ? contents.getKey() : line.getString()).toList();
	}
}
