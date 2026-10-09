package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.client.pod.PodGeoRenderer;
import io.github.pkeppeler.deepcharter.client.ui.CrtButton;
import io.github.pkeppeler.deepcharter.client.upgrade.UpgradeScreen;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.test.UpgradeTerminalClientTest;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;

/**
 * Evidence scenario "pod-upgrade" (#243): a Mole with its stock drill stands beside the upgrade terminal and turns slowly; the pilot
 * opens the terminal, buys a tier 1 drill and shuts it, then buys a tier 2 drill. The Mole's cutter is the tricone before, the stacked
 * rings after the first and the fluted auger after the second: the cutter is picked by the drill tier, and the swap is at once. Frames
 * before, with the terminal open and after each; stills {@code before-tricone}, {@code terminal-buy-drill}, {@code after-stacked} and
 * {@code after-fluted}.
 */
public class PodUpgradeScenario extends EvidenceScenario {
	private static final int TICKS_PER_FRAME = 2;
	private static final int BEFORE_FRAMES = 14;
	private static final int TYPING_FRAMES = 14;
	private static final int HOLD_FRAMES = 6;
	private static final int AFTER_FRAMES = 18;
	private static final float TURN_DEGREES_PER_FRAME = 7f;
	/** The camera stands this far east and south of the pod, which faces south, and looks at its middle. */
	private static final Vec3 CAMERA_OFFSET = new Vec3(3.6, 0, 3.0);
	private static final double POD_MIDDLE = 0.9;

	/** The pod's heading when each turn starts, before the terminal and after it: the same views either side of the swap. */
	private static final float START_YAW = 200f;

	private float yaw = START_YAW;

	@Override
	protected String name() {
		return "pod-upgrade";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			UpgradeTerminalClientTest.Scene scene = singleplayer.getServer().computeOnServer(UpgradeTerminalClientTest::setUp);
			boolean[] hudWasHidden = {false};
			context.runOnClient(client -> {
				client.options.setCameraType(CameraType.FIRST_PERSON);
				hudWasHidden[0] = client.gui.hud.isHidden();
				if (!hudWasHidden[0]) {
					client.gui.hud.toggle();
				}
			});
			try {
				Vec3 pod = singleplayer.getServer().computeOnServer(server -> server.overworld().getEntity(scene.pod()).position());
				singleplayer.getServer().runOnServer(server -> lookAt(server.getPlayerList().getPlayers().getFirst(), pod));
				ClientWait.until(context, "the stock Mole drawn with the tricone", client -> cutter(client, scene).equals("tricone"), client -> cutter(client, scene));
				context.waitTicks(10); // tick-wait: the view settles before the first frame
				for (int i = 0; i < BEFORE_FRAMES; i++) {
					turn(context, singleplayer, scene);
					context.waitTicks(TICKS_PER_FRAME); // tick-wait: the clip is cut at fixed frames
					frame(context);
					if (i == BEFORE_FRAMES / 2) {
						screenshot(context, "before-tricone");
					}
				}

				// T0 to T1: the tricone becomes the stacked rings. T1 to T2: the stacked rings become the fluted auger (the Mole's cap).
				buyAtTheTerminal(context, singleplayer, scene, 1, "stacked");
				afterFrames(context, singleplayer, scene, "after-stacked");
				buyAtTheTerminal(context, singleplayer, scene, 2, "fluted");
				afterFrames(context, singleplayer, scene, "after-fluted");
			} finally {
				context.runOnClient(client -> {
					if (client.gui.hud.isHidden() != hudWasHidden[0]) {
						client.gui.hud.toggle();
					}
				});
			}
		}
	}

	/** Opens the terminal, buys the drill of {@code tier}, waits until the Mole draws {@code cutter}, and shuts the terminal. */
	private void buyAtTheTerminal(ClientGameTestContext context, TestSingleplayerContext singleplayer, UpgradeTerminalClientTest.Scene scene, int tier,
			String cutter) {
		context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(scene.terminal())));
		ClientWait.screen(context, UpgradeScreen.class);
		UpgradeScreen screen = context.computeOnClient(client -> (UpgradeScreen) client.gui.screen());
		for (int i = 0; i < TYPING_FRAMES && !screen.typewriter().done(); i++) {
			turn(context, singleplayer, scene);
			context.waitTicks(TICKS_PER_FRAME); // tick-wait: the clip is cut at fixed frames
			frame(context);
		}
		hold(context, singleplayer, scene);
		String buy = context.computeOnClient(client -> label(screen, "BUY TIER " + tier));
		if (tier == 1) {
			screenshot(context, "terminal-buy-drill");
		}
		context.clickScreenButton(buy);
		// The server installs the part and the client's pod is told: the cutter changes with the next frame.
		ClientWait.until(context, "the Mole drawn with the " + cutter, client -> cutter(client, scene).equals(cutter), client -> cutter(client, scene));
		hold(context, singleplayer, scene);
		context.setScreen(() -> null);
	}

	/** The pod turns again from the heading it started with, so the views match the ones before the swap. */
	private void afterFrames(ClientGameTestContext context, TestSingleplayerContext singleplayer, UpgradeTerminalClientTest.Scene scene, String still) {
		yaw = START_YAW;
		for (int i = 0; i < AFTER_FRAMES; i++) {
			turn(context, singleplayer, scene);
			context.waitTicks(TICKS_PER_FRAME); // tick-wait: the clip is cut at fixed frames
			frame(context);
			if (i == AFTER_FRAMES / 2) {
				screenshot(context, still);
			}
		}
	}

	private void hold(ClientGameTestContext context, TestSingleplayerContext singleplayer, UpgradeTerminalClientTest.Scene scene) {
		for (int i = 0; i < HOLD_FRAMES; i++) {
			turn(context, singleplayer, scene);
			context.waitTicks(TICKS_PER_FRAME); // tick-wait: the clip is cut at fixed frames
			frame(context);
		}
	}

	/** The pod turns a little each frame, so the cutter shows from every side. */
	private void turn(ClientGameTestContext context, TestSingleplayerContext singleplayer, UpgradeTerminalClientTest.Scene scene) {
		yaw += TURN_DEGREES_PER_FRAME;
		float turned = yaw;
		singleplayer.getServer().runOnServer(server -> server.overworld().getEntity(scene.pod()).setYRot(turned));
		context.runOnClient(client -> {
			Entity pod = clientPod(client, scene);
			if (pod != null) {
				pod.setYRot(turned);
				pod.yRotO = turned;
			}
		});
	}

	/** The player stands east and south of {@code pod}, looking at its middle. */
	private static void lookAt(ServerPlayer player, Vec3 pod) {
		Vec3 eye = pod.add(CAMERA_OFFSET);
		Vec3 d = pod.add(0, POD_MIDDLE, 0).subtract(eye.add(0, 1.62, 0));
		player.teleportTo(player.level(), eye.x, eye.y, eye.z, Set.of(), (float) Math.toDegrees(Math.atan2(-d.x, d.z)),
				(float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z))), true);
	}

	private static String cutter(Minecraft client, UpgradeTerminalClientTest.Scene scene) {
		if (!(clientPod(client, scene) instanceof PodEntity pod)) {
			return "no pod";
		}
		Object drawn = client.getEntityRenderDispatcher().getRenderer(pod);
		return drawn instanceof PodGeoRenderer geo ? String.valueOf(geo.appearanceOf(pod).cutter()) : String.valueOf(drawn);
	}

	/** The client's copy of the pod, which the scene names by its UUID, or null while it has not arrived. */
	private static Entity clientPod(Minecraft client, UpgradeTerminalClientTest.Scene scene) {
		for (Entity entity : client.level.entitiesForRendering()) {
			if (entity.getUUID().equals(scene.pod())) {
				return entity;
			}
		}
		return null;
	}

	/** The label of the button that starts with {@code prefix}. */
	private static String label(UpgradeScreen screen, String prefix) {
		return screen.children().stream().filter(CrtButton.class::isInstance).map(button -> ((CrtButton) button).getMessage().getString())
				.map(text -> text.startsWith("> ") ? text.substring(2) : text).filter(text -> text.startsWith(prefix)).findFirst()
				.orElseThrow(() -> new AssertionError("the upgrade screen has no button starting " + prefix));
	}
}
