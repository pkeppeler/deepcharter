package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.SignalDecay;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.DecayCells;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/**
 * The recorded descent of the signal-decay scenarios (#502): a pod sits in a tall lit shaft cut in the rock of layer 1 and is lowered a little each frame from clear air
 * into the decay band and to the floor of the layer, held there, and raised out again, with the pilot's view on the cockpit. The pod has a tier 1 scanner, so the
 * scanner's window has a map to fill with noise, and a hull at a quarter, so the hull lamp has something to say when the rest goes dark.
 */
final class SignalDecayDescent {
	static final int X = 5300;
	static final int Z = 5000;
	private static final int ROOM_RADIUS = 5;
	private static final int ROOM_TOP = 46;
	/** The pod's feet start this high over the floor of layer 1 and are lowered to the bottom, above the breach crust. */
	private static final int START_Y = 40;
	private static final int BOTTOM_Y = 4;
	private static final double DOWN_PER_FRAME = 0.4;
	private static final double UP_PER_FRAME = 0.5;
	private static final int HOLD_FRAMES = 30;
	private static final int TICKS_PER_FRAME = 3;
	/** The plate's threshold is 0.45: from here on a drop-out of the plate is likely enough to wait for one. */
	private static final double FAILING_FROM = 0.6;
	private static final float HULL_SHARE = 0.25f;

	private SignalDecayDescent() {
	}

	/** Records the descent of a pod of {@code chassis} with a console of {@code consoleTier} (0 for the stock one) and takes a still at {@code stillY} slabs. */
	static void run(EvidenceScenario scenario, ClientGameTestContext context, Chassis chassis, int consoleTier, String stillPrefix) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitTicks(40);
			EvidencePins.handsHidden = true;
			try {
				singleplayer.getServer().runOnServer(server -> {
					ServerLevel one = server.getLevel(LayerChain.dimension(1));
					RoomCarver.carve(one, X - ROOM_RADIUS - 1, X + ROOM_RADIUS + 1, BOTTOM_Y - 2, ROOM_TOP + 1, Z - ROOM_RADIUS - 1, Z + ROOM_RADIUS + 1, Blocks.STONE);
					RoomCarver.carve(one, new BlockPos(X - ROOM_RADIUS, BOTTOM_Y - 1, Z - ROOM_RADIUS), new BlockPos(X + ROOM_RADIUS, ROOM_TOP, Z + ROOM_RADIUS),
							Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
					for (int y = BOTTOM_Y + 2; y < ROOM_TOP; y += 6) {
						for (BlockPos lamp : new BlockPos[] {new BlockPos(X + ROOM_RADIUS, y, Z), new BlockPos(X - ROOM_RADIUS, y, Z), new BlockPos(X, y, Z + ROOM_RADIUS),
								new BlockPos(X, y, Z - ROOM_RADIUS)}) {
							one.setBlock(lamp, Blocks.GLOWSTONE.defaultBlockState(), 3);
						}
					}
					ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
					player.setGameMode(GameType.SURVIVAL);
					player.teleportTo(one, X + 0.5, START_Y, Z + 0.5, Set.of(), 0f, 25f, true);
					PodEntity pod = PodRegistry.typeOf(chassis).create(one, EntitySpawnReason.COMMAND);
					pod.setPos(X + 0.5, START_Y, Z + 0.5);
					pod.setNoGravity(true);
					one.addFreshEntity(pod);
					ScannerPods.fit(server, player, pod, 1);
					if (consoleTier > 0) {
						ScannerPods.fit(server, player, pod, ComponentTrack.CONSOLE, consoleTier);
					}
					pod.setHull(pod.maxHull() * HULL_SHARE);
					if (!player.startRiding(pod)) {
						throw new AssertionError("the player could not mount the pod");
					}
				});
				ClientWait.until(context, "the pilot seated in the pod in layer 1", client -> client.player != null && client.player.getVehicle() instanceof PodEntity
						&& client.level.dimension().equals(LayerChain.dimension(1)));
				context.runOnClient(client -> {
					client.player.setXRot(25f);
					client.player.setYRot(0f);
				});
				context.waitTicks(30);
				// Founding the charter queued the employer's transmission; it would cover the HUD this scenario shows.
				context.runOnClient(client -> TransmissionOverlay.reset());
				scenario.screenshot(context, stillPrefix + "-clear");
				boolean noisy = false;
				boolean flicker = false;
				for (double y = START_Y; y > BOTTOM_Y; y -= DOWN_PER_FRAME) {
					lower(singleplayer, y);
					context.waitTicks(TICKS_PER_FRAME);
					scenario.frame(context);
					double intensity = intensity(context);
					if (!noisy && intensity > 0.15) {
						noisy = true;
						scenario.screenshot(context, stillPrefix + "-noise");
					}
					if (!flicker && intensity > FAILING_FROM && plateIsDark(context)) {
						flicker = true;
						scenario.screenshot(context, stillPrefix + "-failing");
					}
				}
				lower(singleplayer, BOTTOM_Y);
				for (int i = 0; i < HOLD_FRAMES; i++) {
					context.waitTicks(TICKS_PER_FRAME);
					scenario.frame(context);
				}
				scenario.screenshot(context, stillPrefix + "-floor");
				for (double y = BOTTOM_Y; y < START_Y; y += UP_PER_FRAME) {
					lower(singleplayer, y);
					context.waitTicks(TICKS_PER_FRAME);
					scenario.frame(context);
				}
				scenario.screenshot(context, stillPrefix + "-out");
			} finally {
				EvidencePins.reset();
			}
		}
	}

	private static void lower(TestSingleplayerContext singleplayer, double y) {
		singleplayer.getServer().runOnServer(server -> {
			PodEntity pod = (PodEntity) server.getPlayerList().getPlayers().getFirst().getVehicle();
			pod.setPos(X + 0.5, y, Z + 0.5);
			pod.setDeltaMovement(0, 0, 0);
		});
	}

	/** Whether the readout plate is in a drop-out in the cell the client is drawing, so the "failing" still shows the plate's tag and not a lucky true cell. */
	private static boolean plateIsDark(ClientGameTestContext context) {
		return context.computeOnClient(client -> {
			if (!(client.player.getVehicle() instanceof PodEntity pod)) {
				return false;
			}
			long cell = Math.floorDiv(client.level.getGameTime(), (long) DecayCells.ticks());
			return SignalDecay.fault(SignalDecay.Instrument.PLATE, SignalDecay.intensity(pod), pod.getUUID().getLeastSignificantBits(), cell) == SignalDecay.Fault.DROP;
		});
	}

	private static double intensity(ClientGameTestContext context) {
		return context.computeOnClient(client -> client.player.getVehicle() instanceof PodEntity pod ? SignalDecay.intensity(pod) : 0.0);
	}
}
