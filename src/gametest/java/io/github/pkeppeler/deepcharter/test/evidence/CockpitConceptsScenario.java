package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.List;
import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

import io.github.pkeppeler.deepcharter.client.pod.CockpitHud;
import io.github.pkeppeler.deepcharter.client.pod.FrontCameraFeed;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientPacks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import io.github.pkeppeler.deepcharter.test.support.EvidenceWorld;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.test.support.TestPacks;

/**
 * Evidence scenario "cockpit-concepts" for #444 and #448 (docs/design/cockpit-concepts.md, cockpit-concepts-2.md), the concept rounds of #247: each cockpit HUD option from the
 * seat of a Mole with a scanner, in a sealed stone room, at GUI scale 2, in four states: calm, low fuel, hull burning (lava under the pod) and deep (the last layer's floor);
 * and once on foot beside the pod, where the vanilla HUD is back. The HUD as it is now is shot the same way, as the first option, and so is round 1's Consoles. Each cockpit
 * option is also shot calm at GUI scale 1. Then each option is shot calm through the front camera (a pod with the part: the cab is out of the view), and the plain HUD and the
 * Cab option go through the camera's hazards: heat haze while the hull burns, a dead feed once the lens has cooked, and static after a hit. The options are test packs
 * that only this scenario turns on, so nothing a player sees has changed. Stills are named {@code <option>-<state>}; the options are {@code current}, {@code consoles},
 * {@code corners}, {@code ribbon}, {@code posts} and {@code cab}, and {@code DEEPCHARTER_COCKPIT_OPTIONS=cab,posts} shoots only those.
 */
public class CockpitConceptsScenario extends EvidenceScenario {
	/** One option, with the test pack that picks it; the pack is null for the HUD as it is. */
	private record Option(String slug, String pack) {
	}

	private static final List<Option> OPTIONS = List.of(new Option("current", null), new Option("consoles", TestPacks.COCKPIT_CONSOLES),
			new Option("corners", TestPacks.COCKPIT_CORNERS), new Option("ribbon", TestPacks.COCKPIT_RIBBON), new Option("posts", TestPacks.COCKPIT_POSTS),
			new Option("cab", TestPacks.COCKPIT_CAB));
	/** The options that also go through the camera's hazards: the plain HUD, and the option whose point is the unobstructed view. */
	private static final Set<String> HAZARD_OPTIONS = Set.of("current", "cab");

	private static final int X = 5000;
	private static final int Z = 5000;
	/** Near the top of layer 1, which is 192 tall, and near the floor of the last layer: the two ends of the depth gauge. */
	private static final int UPPER_FLOOR_Y = 150;
	private static final int DEEP_FLOOR_ABOVE_MIN = 20;
	private static final int ROOM_RADIUS = 7;
	private static final int ROOM_HEIGHT = 8;
	private static final int LAVA_RADIUS = 2;
	private static final int GUI_SCALE = 2;
	private static final int SETTLE_TICKS = 25;
	private static final int COOL_TICKS = 70;
	private static final int TRANSMISSION_TICKS = 60;
	private static final float HIT = 20f;
	private static final double ON_FOOT_BACK = 4.5;
	private static final float EYE_PITCH = 4f;
	/** The heat gauge is at its top from here, and the static a hit of {@link #HIT} leaves is over this, with the camera pack that holds it. */
	private static final double HAZE_HEAT = 0.99;
	private static final double HELD_STATIC = 0.5;
	private static final float CALM_FUEL = 78f;
	private static final float LOW_FUEL = 12f;
	private static final float DEEP_FUEL = 55f;

	@Override
	protected String name() {
		return "cockpit-concepts";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		List<Option> options = chosen(System.getenv("DEEPCHARTER_COCKPIT_OPTIONS"));
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			EvidenceWorld.pin(context, singleplayer);
			context.runOnClient(client -> {
				client.options.guiScale().set(GUI_SCALE);
				client.options.setCameraType(CameraType.FIRST_PERSON);
			});
			EvidencePins.lightFlickerOff = true;
			EvidencePins.handsHidden = true;
			try {
				Room upper = new Room(LayerChain.dimension(1), UPPER_FLOOR_Y);
				Room deep = singleplayer.getServer().computeOnServer(server -> {
					int last = LayerChain.count(server.registryAccess());
					int minY = server.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE).getValueOrThrow(LayerChain.type(last)).minY();
					return new Room(LayerChain.dimension(last), minY + DEEP_FLOOR_ABOVE_MIN);
				});
				singleplayer.getServer().runOnServer(server -> {
					buildRoom(server, upper);
					buildRoom(server, deep);
				});
				PodEntity upperPod = enter(context, singleplayer, upper);
				for (Option option : options) {
					upperStates(context, singleplayer, upperPod, option);
				}
				for (Option option : options) {
					cameraStates(context, singleplayer, upperPod, option);
				}
				leave(context, singleplayer, upperPod);
				PodEntity deepPod = enter(context, singleplayer, deep);
				for (Option option : options) {
					deepStates(context, singleplayer, deepPod, deep, option);
				}
			} finally {
				EvidencePins.reset();
			}
		}
	}

	private static List<Option> chosen(String only) {
		if (only == null) {
			return OPTIONS;
		}
		List<String> slugs = List.of(only.split(","));
		for (String slug : slugs) {
			if (OPTIONS.stream().noneMatch(option -> option.slug().equals(slug))) {
				throw new IllegalStateException("DEEPCHARTER_COCKPIT_OPTIONS has \"" + slug + "\", which is not one of " + OPTIONS.stream().map(Option::slug).toList());
			}
		}
		return OPTIONS.stream().filter(option -> slugs.contains(option.slug())).toList();
	}

	/** A sealed stone room in a layer: where it is and the Y of its floor. */
	private record Room(ResourceKey<Level> dimension, int floorY) {
	}

	/** Calm, low fuel, then hull burning with lava under the pod. */
	private void upperStates(ClientGameTestContext context, TestSingleplayerContext singleplayer, PodEntity pod, Option option) {
		if (option.pack() != null) {
			ClientPacks.enable(context, option.pack());
		}
		singleplayer.getServer().runOnServer(server -> {
			pod.setHull(pod.maxHull());
			pod.setFuel(CALM_FUEL);
		});
		shoot(context, option.slug() + "-calm");
		if (option.pack() != null) {
			context.runOnClient(client -> client.getWindow().setGuiScale(1));
			shoot(context, option.slug() + "-calm-scale1");
			context.runOnClient(client -> client.getWindow().setGuiScale(GUI_SCALE));
		}
		singleplayer.getServer().runOnServer(server -> pod.setFuel(LOW_FUEL));
		shoot(context, option.slug() + "-low-fuel");
		singleplayer.getServer().runOnServer(server -> {
			pod.setFuel(CALM_FUEL);
			lava(pod, true);
		});
		ClientWait.until(context, "the pod's hull burning on the client", client -> client.player.getVehicle() instanceof PodEntity shown && shown.hullBurning());
		shoot(context, option.slug() + "-burning");
		singleplayer.getServer().runOnServer(server -> lava(pod, false));
		ClientWait.until(context, "the pod's hull cool on the client", client -> client.player.getVehicle() instanceof PodEntity shown && !shown.hullBurning());
		context.waitTicks(COOL_TICKS); // tick-wait: the heat gauge eases back to 0 over client ticks
		if (option.pack() != null) {
			ClientPacks.disable(context, option.pack());
		}
	}

	/**
	 * Calm through the front camera, for every option. The plain HUD and the Cab option also shoot the camera's hazards: the heat haze while the hull burns, the dead feed once
	 * the lens has cooked (the cab is back in the view), and static after a hit. The camera pack holds a hit's static, so it is last, and every pack is off again after.
	 */
	private void cameraStates(ClientGameTestContext context, TestSingleplayerContext singleplayer, PodEntity pod, Option option) {
		if (option.pack() != null) {
			ClientPacks.enable(context, option.pack());
		}
		ClientPacks.enable(context, TestPacks.COCKPIT_CAMERA_HOLD);
		singleplayer.getServer().runOnServer(server -> {
			pod.setHull(pod.maxHull());
			pod.setFuel(CALM_FUEL);
		});
		ClientWait.until(context, "a live front camera feed", client -> client.player.getVehicle() instanceof PodEntity shown && FrontCameraFeed.hidesCab(shown));
		shoot(context, option.slug() + "-camera-calm");
		if (HAZARD_OPTIONS.contains(option.slug())) {
			singleplayer.getServer().runOnServer(server -> lava(pod, true));
			ClientWait.until(context, "the heat gauge at its top", client -> CockpitHud.heat() >= HAZE_HEAT, client -> "heat " + CockpitHud.heat());
			shoot(context, option.slug() + "-camera-haze");
			ClientWait.until(context, "the lens failed in the lava", client -> FrontCameraFeed.state().lensFailed(), client -> FrontCameraFeed.state().toString());
			shoot(context, option.slug() + "-camera-dead");
			singleplayer.getServer().runOnServer(server -> {
				lava(pod, false);
				pod.setHull(pod.maxHull());
			});
			ClientWait.until(context, "the lens cooled and the feed back", client -> client.player.getVehicle() instanceof PodEntity shown && FrontCameraFeed.hidesCab(shown)
					&& CockpitHud.heat() == 0.0, client -> FrontCameraFeed.state().toString() + " heat " + CockpitHud.heat());
			singleplayer.getServer().runOnServer(server -> pod.setHull(pod.hull() - HIT));
			ClientWait.until(context, "static after the hit", client -> FrontCameraFeed.state().staticLevel() > HELD_STATIC, client -> FrontCameraFeed.state().toString());
			shoot(context, option.slug() + "-camera-static");
		}
		ClientPacks.disable(context, TestPacks.COCKPIT_CAMERA_HOLD);
		if (option.pack() != null) {
			ClientPacks.disable(context, option.pack());
		}
		singleplayer.getServer().runOnServer(server -> pod.setHull(pod.maxHull()));
	}

	/** The deepest floor, then the pilot on foot beside the pod. */
	private void deepStates(ClientGameTestContext context, TestSingleplayerContext singleplayer, PodEntity pod, Room room, Option option) {
		if (option.pack() != null) {
			ClientPacks.enable(context, option.pack());
		}
		singleplayer.getServer().runOnServer(server -> {
			pod.setHull(pod.maxHull());
			pod.setFuel(DEEP_FUEL);
		});
		shoot(context, option.slug() + "-deep");
		singleplayer.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			player.stopRiding();
			player.teleportTo(server.getLevel(room.dimension()), X + 0.5, room.floorY(), Z + 0.5 - ON_FOOT_BACK, Set.of(), 0f, 0f, true);
		});
		ClientWait.until(context, "the pilot on foot", client -> client.player.getVehicle() == null);
		shoot(context, option.slug() + "-on-foot");
		singleplayer.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			player.teleportTo(server.getLevel(room.dimension()), X + 0.5, room.floorY(), Z + 0.5, Set.of(), 0f, 0f, true);
			if (!player.startRiding(pod)) {
				throw new AssertionError("the pilot could not board the pod again");
			}
		});
		ClientWait.until(context, "the pilot aboard again", client -> client.player.getVehicle() instanceof PodEntity);
		if (option.pack() != null) {
			ClientPacks.disable(context, option.pack());
		}
	}

	/** Takes the first player into the room, boards a new pod with a tier 1 scanner, and waits until the client sits in it. */
	private PodEntity enter(ClientGameTestContext context, TestSingleplayerContext singleplayer, Room room) {
		PodEntity[] made = {null};
		singleplayer.getServer().runOnServer(server -> {
			ServerLevel level = server.getLevel(room.dimension());
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			player.setGameMode(GameType.SURVIVAL);
			player.teleportTo(level, X + 0.5, room.floorY(), Z + 0.5, Set.of(), 0f, 0f, true);
			PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
			pod.setPos(X + 0.5, room.floorY(), Z + 0.5);
			pod.setYRot(0f);
			level.addFreshEntity(pod);
			ScannerPods.fit(server, player, pod, 1);
			if (!player.startRiding(pod)) {
				throw new AssertionError("the pilot could not board the pod");
			}
			made[0] = pod;
		});
		ClientWait.until(context, "the pilot seated in the pod on the client", client -> client.player != null && client.player.getVehicle() instanceof PodEntity
				&& client.level.dimension().equals(room.dimension()));
		context.waitTicks(TRANSMISSION_TICKS); // tick-wait: a charter's first transmission arrives a moment after the first mount, and every shot clears it
		return made[0];
	}

	private void leave(ClientGameTestContext context, TestSingleplayerContext singleplayer, PodEntity pod) {
		singleplayer.getServer().runOnServer(server -> {
			server.getPlayerList().getPlayers().getFirst().stopRiding();
			pod.discard();
		});
		ClientWait.until(context, "the pilot out of the pod", client -> client.player.getVehicle() == null);
	}

	/** Lava, or air again, in a square under the pod, so the hull burns while it sits there. */
	private static void lava(PodEntity pod, boolean on) {
		ServerLevel level = (ServerLevel) pod.level();
		BlockPos feet = pod.blockPosition();
		RoomCarver.carve(level, feet.offset(-LAVA_RADIUS, 0, -LAVA_RADIUS), feet.offset(LAVA_RADIUS, 0, LAVA_RADIUS),
				(on ? Blocks.LAVA : Blocks.AIR).defaultBlockState());
	}

	/** A sealed room: a shell of stone, air inside, and a ring of glowstone so the pod and the walls are lit. */
	private static void buildRoom(MinecraftServer server, Room room) {
		ServerLevel level = server.getLevel(room.dimension());
		int floor = room.floorY();
		RoomCarver.carve(level, new BlockPos(X - ROOM_RADIUS - 1, floor - 1, Z - ROOM_RADIUS - 1), new BlockPos(X + ROOM_RADIUS + 1, floor + ROOM_HEIGHT + 1, Z + ROOM_RADIUS + 1),
				Blocks.STONE.defaultBlockState());
		RoomCarver.carve(level, new BlockPos(X - ROOM_RADIUS, floor, Z - ROOM_RADIUS), new BlockPos(X + ROOM_RADIUS, floor + ROOM_HEIGHT, Z + ROOM_RADIUS),
				Blocks.AIR.defaultBlockState());
		for (int[] at : new int[][] {{ROOM_RADIUS, 0}, {-ROOM_RADIUS, 0}, {0, ROOM_RADIUS}, {0, -ROOM_RADIUS}, {ROOM_RADIUS, ROOM_RADIUS}, {-ROOM_RADIUS, ROOM_RADIUS},
				{ROOM_RADIUS, -ROOM_RADIUS}, {-ROOM_RADIUS, -ROOM_RADIUS}}) {
			level.setBlock(new BlockPos(X + at[0], floor + 4, Z + at[1]), Blocks.GLOWSTONE.defaultBlockState(), 3);
		}
	}

	private void shoot(ClientGameTestContext context, String still) {
		context.runOnClient(client -> {
			client.player.setYRot(0f);
			client.player.yRotO = 0f;
			client.player.setXRot(EYE_PITCH);
			client.player.xRotO = EYE_PITCH;
			client.gui.toastManager().clear();
			TransmissionOverlay.reset();
		});
		ClientWait.until(context, "every section in view rendered", client -> client.levelRenderer.hasRenderedAllSections());
		context.waitTicks(SETTLE_TICKS); // tick-wait: the scanner rescans, the gauges ease and the pod settles over client ticks
		screenshot(context, still);
		frame(context);
	}
}
