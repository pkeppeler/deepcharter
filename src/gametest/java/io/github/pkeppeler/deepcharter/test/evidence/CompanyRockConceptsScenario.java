package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.List;
import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.RoomSeal;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientPacks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.ConceptRock;
import io.github.pkeppeler.deepcharter.test.support.TestPacks;

/**
 * Evidence scenario "company-rock-concepts" (#503, docs/design/company-rock-concepts.md): the four looks of Company Rock, and the steel plate
 * it has today, in layers 1 and 2, each shot where a player meets it: in the wall of a bore, close up, in a cave, beside lava, and from the
 * pod at range. Every room is sealed in the layer's rock (granite and tuff, which the Grain test pack of the layer concepts draws as
 * Grain rock, the look #241 is building), so the void is never in a frame, and the pod's lamp is a level 12 light where the camera is.
 * Each still is {@code <option>-l<layer>-<place>}: {@code crystal-l2-lava}. The options are test packs that only this scenario turns on.
 * A look that is more than a cube is drawn on {@link ConceptRock}, Company Rock that lets light in, which the shipped block must be too if it is picked.
 */
public class CompanyRockConceptsScenario extends EvidenceScenario {
	/** One look: its still prefix and its test pack (none for the shipped plate, which is drawn on the shipped block). */
	private record Option(String name, String pack) {
	}

	private static final List<Option> OPTIONS = List.of(new Option("now", null), new Option("joint", TestPacks.COMPANY_ROCK_JOINT),
			new Option("strap", TestPacks.COMPANY_ROCK_STRAP), new Option("crystal", TestPacks.COMPANY_ROCK_CRYSTAL),
			new Option("fused", TestPacks.COMPANY_ROCK_FUSED));
	/** Where the rooms stand in each layer's dimension, on a chunk boundary, and the box of whole chunks of rock round all of them (a chunk the box covers in part never reached the client in two runs of three). */
	private static final BlockPos ORIGIN = new BlockPos(3616, 40, 3616);
	private static final BlockPos ROCK_LOW = new BlockPos(-16, -3, -16);
	private static final BlockPos ROCK_HIGH = new BlockPos(63, 12, 15);
	/** Company Rock, in blocks from the origin: a patch of four in the wall of each room, as the worldgen scatters it. */
	private static final List<BlockPos> BORE_PATCH = List.of(new BlockPos(3, 5, -1), new BlockPos(4, 5, -1), new BlockPos(4, 6, -1), new BlockPos(2, 4, -1));
	private static final List<BlockPos> CAVE_PATCH = List.of(new BlockPos(19, 3, -1), new BlockPos(20, 3, -1), new BlockPos(20, 4, -1), new BlockPos(18, 2, -1),
			new BlockPos(16, 3, 8));
	private static final List<BlockPos> LAVA_PATCH = List.of(new BlockPos(39, 1, -1), new BlockPos(40, 1, -1), new BlockPos(40, 2, -1), new BlockPos(38, 2, -1));
	private static final List<BlockPos> HALL_PATCH = List.of(new BlockPos(53, 2, -1), new BlockPos(54, 2, -1), new BlockPos(54, 3, -1), new BlockPos(56, 3, -1),
			new BlockPos(52, 1, -1));
	private static final int LAMP = 12;
	private static final int SETTLE_TICKS = 20;
	/**
	 * The render distance, in chunks, while the scenario runs: a still waits for every chunk in view, and the layers' tall worlds generate slowly
	 * on a loaded machine, so the default distance did not settle in two minutes. 2 chunks is 32 blocks, and every room is sealed in rock, so no still differs.
	 */
	private static final int RENDER_DISTANCE = 2;
	private static final long CHUNK_WAIT_NANOS = 40_000_000_000L;
	private static final int CHUNK_ATTEMPTS = 4;
	/** Where the camera waits while the chunks are sent again: inside the box of rock, far from every room, 3 blocks up so it is in no block. */
	private static final Vec3 FAR_EYE = new Vec3(-8, 4, -8);
	/** The first frame after the player mounts is tinted, so the pod still waits for the mount's fade. */
	private static final int MOUNT_TICKS = 80;

	/** A view: its still suffix, the eye and the target in blocks from the origin. */
	private record View(String place, Vec3 eye, Vec3 target) {
	}

	private static final List<View> VIEWS = List.of(
			new View("bore", new Vec3(4.5, 5.6, 2.6), new Vec3(3.6, 5.4, -1)),
			new View("close", new Vec3(3.9, 5.4, 1.3), new Vec3(3.6, 5.4, -1)),
			new View("cave", new Vec3(19.5, 3.0, 5.5), new Vec3(19.0, 3.0, -1)),
			new View("lava", new Vec3(40.0, 2.7, 6.5), new Vec3(39.4, 1.8, -1)));
	/** The pod: it stands at the south end of the hall and looks south at the patch on the far wall. */
	private static final Vec3 SEAT_POD = new Vec3(54.5, 1, 5.5);
	private static final Vec3 SEAT_TARGET = new Vec3(54.5, 2.6, -1);

	@Override
	protected String name() {
		return "company-rock-concepts";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		int renderDistance = context.computeOnClient(client -> client.options.renderDistance().get());
		TextureTestWorld.open(context, shot -> screenshot(context, shot), world -> {
			context.runOnClient(client -> client.options.renderDistance().set(RENDER_DISTANCE));
			ClientPacks.enable(context, TestPacks.LAYER_CONCEPT_GRAIN);
			try {
				for (int layer : List.of(1, 2)) {
					world.serverDo(server -> build(server.getLevel(LayerChain.dimension(layer)), layer));
				}
				for (Option option : OPTIONS) {
					if (option.pack() != null) {
						ClientPacks.enable(context, option.pack());
					}
					try {
						for (int layer : List.of(1, 2)) {
							world.serverDo(server -> place(server.getLevel(LayerChain.dimension(layer)), option.pack() == null ? HazardBlocks.COMPANY_ROCK : ConceptRock.BLOCK));
							shoot(context, world, option, layer);
						}
					} finally {
						if (option.pack() != null) {
							ClientPacks.disable(context, option.pack());
						}
					}
				}
				frame(context);
			} finally {
				ClientPacks.disable(context, TestPacks.LAYER_CONCEPT_GRAIN);
				context.runOnClient(client -> client.options.renderDistance().set(renderDistance));
			}
		});
	}

	private void shoot(ClientGameTestContext context, TextureTestWorld world, Option option, int layer) {
		for (View view : VIEWS) {
			arrive(context, world, layer, view);
			world.still(still(option, layer, view.place()));
		}
		world.serverDo(server -> mount(server.getLevel(LayerChain.dimension(layer))));
		context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
		ClientWait.until(context, "the player in the pod in layer " + layer,
				client -> client.player != null && client.player.getVehicle() instanceof PodEntity && client.level.dimension().equals(LayerChain.dimension(layer)));
		context.waitTicks(MOUNT_TICKS);
		world.still(still(option, layer, "pod"));
		context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
		world.serverDo(CompanyRockConceptsScenario::dismount);
		ClientWait.until(context, "the player out of the pod", client -> client.player != null && client.player.getVehicle() == null);
	}

	/**
	 * Puts the camera at the view. A chunk of the built box that the server holds back for its light was, in three runs of five, never sent to
	 * the client (the still then waited out its limit for it), so when a chunk in view has not come in {@link #CHUNK_WAIT_NANOS}, the camera
	 * goes away and comes back, which the server answers by sending the chunks round the player again.
	 */
	private static void arrive(ClientGameTestContext context, TextureTestWorld world, int layer, View view) {
		for (int attempt = 1; attempt <= CHUNK_ATTEMPTS; attempt++) {
			world.view(layer, absolute(view.eye()), absolute(view.target()), SETTLE_TICKS);
			long deadline = System.nanoTime() + CHUNK_WAIT_NANOS;
			while (System.nanoTime() < deadline) {
				if (context.computeOnClient(CompanyRockConceptsScenario::chunksInView)) {
					return;
				}
				context.waitTicks(SETTLE_TICKS);
			}
			world.view(layer, absolute(FAR_EYE), absolute(FAR_EYE.add(0, 0, 1)), SETTLE_TICKS);
		}
		throw new AssertionError("the chunks round the " + view.place() + " view in layer " + layer + " did not reach the client in " + CHUNK_ATTEMPTS + " tries");
	}

	private static boolean chunksInView(Minecraft client) {
		int radius = client.options.getEffectiveRenderDistance();
		int centreX = client.player.chunkPosition().x();
		int centreZ = client.player.chunkPosition().z();
		for (int x = centreX - radius; x <= centreX + radius; x++) {
			for (int z = centreZ - radius; z <= centreZ + radius; z++) {
				if (client.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) == null) {
					return false;
				}
			}
		}
		return true;
	}

	private static String still(Option option, int layer, String place) {
		return "%s-l%d-%s".formatted(option.name(), layer, place);
	}

	private static Vec3 absolute(Vec3 local) {
		return local.add(ORIGIN.getX(), ORIGIN.getY(), ORIGIN.getZ());
	}

	private static BlockPos absolute(BlockPos local) {
		return local.offset(ORIGIN);
	}

	/**
	 * Builds the five rooms of a layer in a box of its rock: a bore (5 x 3, tall), a cave (an irregular room with a mounded floor and a hanging
	 * ceiling), a room with a pit of lava along its wall, and the hall the pod sits in. Everything solid is the layer's rock, so every wall is rock.
	 */
	private static void build(ServerLevel level, int layer) {
		Block rock = layer == 1 ? Blocks.GRANITE : Blocks.TUFF;
		BlockPos low = absolute(ROCK_LOW);
		BlockPos high = absolute(ROCK_HIGH);
		for (int cx = low.getX() >> 4; cx <= high.getX() >> 4; cx++) {
			for (int cz = low.getZ() >> 4; cz <= high.getZ() >> 4; cz++) {
				level.setChunkForced(cx, cz, true);
				level.getChunk(cx, cz);
			}
		}
		// RoomCarver would hold the sending of every chunk it touches until the light tasks queued so far are done, and in four runs of six a held chunk of
		// the box was never sent to the client. Nobody is in the dimension while the box is built and the light is done long before the camera comes, so
		// the box is sealed here and written directly.
		RoomSeal.seal(level, low, high);
		BlockState stone = rock.defaultBlockState();
		for (BlockPos at : BlockPos.betweenClosed(low, high)) {
			level.setBlock(at, stone, Block.UPDATE_CLIENTS);
		}
		air(level, 2, 6, 1, 11, 0, 2);
		air(level, 12, 28, 1, 6, 0, 13);
		air(level, 34, 45, 1, 5, 0, 9);
		air(level, 50, 58, 1, 5, 0, 10);
		// The cave's floor rises in mounds and its roof hangs in stubs, in the rock's own block.
		for (int[] mound : new int[][] {{14, 4}, {16, 8}, {22, 6}, {25, 10}, {13, 11}, {24, 2}}) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					int height = dx == 0 && dz == 0 ? 2 : 1;
					for (int y = 1; y <= height; y++) {
						level.setBlock(absolute(new BlockPos(mound[0] + dx, y, mound[1] + dz)), rock.defaultBlockState(), Block.UPDATE_ALL);
					}
				}
			}
		}
		for (int[] stub : new int[][] {{15, 3}, {21, 5}, {26, 8}, {18, 10}, {13, 7}}) {
			for (int y = 5; y <= 6; y++) {
				level.setBlock(absolute(new BlockPos(stub[0], y, stub[1])), rock.defaultBlockState(), Block.UPDATE_ALL);
			}
		}
		// The pit of lava: its sides, bottom and the floor round it are rock, so the sources stay where they are put.
		for (int x = 38; x <= 41; x++) {
			for (int z = 0; z <= 1; z++) {
				level.setBlock(absolute(new BlockPos(x, 0, z)), Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
			}
		}
		for (View view : VIEWS) {
			lamp(level, view.eye());
		}
		lamp(level, SEAT_POD.add(0, 2, 1));
	}

	private static void air(ServerLevel level, int x1, int x2, int y1, int y2, int z1, int z2) {
		for (BlockPos at : BlockPos.betweenClosed(absolute(new BlockPos(x1, y1, z1)), absolute(new BlockPos(x2, y2, z2)))) {
			level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS); // room-carver: inside the box that build() sealed with RoomSeal, with no player in the dimension
		}
	}

	private static void lamp(ServerLevel level, Vec3 eye) {
		level.setBlock(BlockPos.containing(absolute(eye)), TextureTestWorld.lamp(LAMP), Block.UPDATE_ALL);
	}

	/** Sets every patch of Company Rock of a layer to {@code rock}: the shipped block, or the stand-in of the options. */
	private static void place(ServerLevel level, Block rock) {
		for (List<BlockPos> patch : List.of(BORE_PATCH, CAVE_PATCH, LAVA_PATCH, HALL_PATCH)) {
			for (BlockPos at : patch) {
				level.setBlock(absolute(at), rock.defaultBlockState(), Block.UPDATE_ALL);
			}
		}
	}

	/** Seats the player in a pod at the hall's south end, facing the far wall. */
	private static void mount(ServerLevel level) {
		ServerPlayer player = level.getServer().getPlayerList().getPlayers().getFirst();
		Vec3 at = absolute(SEAT_POD);
		Vec3 target = absolute(SEAT_TARGET);
		player.teleportTo(level, at.x, at.y, at.z, Set.of(), 180.0f, 0.0f, true);
		player.setNoGravity(false);
		PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
		pod.setPos(at.x, at.y, at.z);
		pod.setYRot(180.0f);
		pod.setFuel(100f);
		level.addFreshEntity(pod);
		if (!player.startRiding(pod)) {
			throw new AssertionError("the player could not mount the pod");
		}
		double pitch = Math.toDegrees(Math.atan2(at.y + 1.4 - target.y, Math.hypot(target.x - at.x, target.z - at.z)));
		player.setXRot((float) pitch);
	}

	private static void dismount(MinecraftServer server) {
		ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
		if (player.getVehicle() instanceof PodEntity pod) {
			player.stopRiding();
			pod.discard();
		}
		player.setNoGravity(true);
	}
}
