package io.github.pkeppeler.deepcharter.test.evidence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Blocks;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;

/**
 * Evidence scenario "skin-swap" (#258, ADR 0033): the Mole as shipped, then the same Mole after a throwaway resource pack is
 * switched on and the resources reload (what F3+T does). The pack is a different hull shape and texture, plus a drill that the
 * default skin does not show; the drill turns while the pod drills. No Java changes between the stills.
 */
public class SkinSwapScenario extends EvidenceScenario {
	private static final String PACK = "skin-demo";
	private static final String PACK_ID = "file/" + PACK;
	private static final int SPIN_FRAMES = 14;

	@Override
	protected String name() {
		return "skin-swap";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitTicks(40);
			int[] moleId = {0};
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				// The player faces +z (south); the Mole stands 5 blocks ahead, facing the player, on a stone floor.
				player.teleportTo(player.level(), player.getX(), player.getY(), player.getZ(), Set.of(), 0f, 5f, true);
				clearStage(player);
				PodEntity mole = PodRegistry.POD.create(player.level(), EntitySpawnReason.COMMAND);
				mole.setPos(player.position().add(0, 0, 5));
				player.level().addFreshEntity(mole);
				moleId[0] = mole.getId();
			});
			context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
			context.waitTicks(60);
			screenshot(context, "skin-default");

			Path pack = context.computeOnClient(client -> client.getResourcePackDirectory().resolve(PACK));
			try {
				writePack(pack);
				context.runOnClient(client -> {
					PackRepository repository = client.getResourcePackRepository();
					repository.reload();
					if (!repository.addPack(PACK_ID)) {
						throw new AssertionError("the pack " + PACK_ID + " could not be selected; available: " + repository.getAvailableIds());
					}
					client.options.updateResourcePacks(repository);
				});
				context.waitFor(client -> client.getResourcePackRepository().getSelectedIds().contains(PACK_ID));
				context.waitTicks(80);
				screenshot(context, "skin-swapped");

				context.runOnClient(client -> {
					PodEntity mole = (PodEntity) client.level.getEntity(moleId[0]);
					mole.setDrillDirection(Direction.SOUTH);
					mole.setDrilling(true);
				});
				for (int i = 0; i < SPIN_FRAMES; i++) {
					context.waitTicks(1);
					frame(context);
				}
				screenshot(context, "skin-swapped-drill-spinning");
			} finally {
				context.runOnClient(client -> removePack(client));
				// Let the reload finish before the world closes; a reload still running at shutdown crashes the client.
				context.waitFor(client -> !client.getResourcePackRepository().getSelectedIds().contains(PACK_ID));
				context.waitTicks(80);
				deleteTree(pack);
			}
		}
	}

	private static void removePack(Minecraft client) {
		PackRepository repository = client.getResourcePackRepository();
		repository.removePack(PACK_ID);
		client.options.updateResourcePacks(repository);
	}

	/** A stone floor and a room of air south of the player, so the Mole is in view wherever the world spawn is. */
	private static void clearStage(ServerPlayer player) {
		BlockPos origin = player.blockPosition();
		for (int dx = -6; dx <= 6; dx++) {
			for (int dz = 1; dz <= 10; dz++) {
				player.level().setBlockAndUpdate(origin.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
				for (int dy = 0; dy <= 5; dy++) {
					player.level().setBlockAndUpdate(origin.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
				}
			}
		}
	}

	private static void writePack(Path pack) {
		try {
			deleteTree(pack);
			write(pack.resolve("pack.mcmeta"), """
					{"pack": {"description": "Deep Charter skin demo (throwaway)", "min_format": 97, "max_format": 97}}
					""");
			Path models = pack.resolve("assets/deepcharter/models/pod");
			// A broader, lower hull in gold with a cabin on top. Block space, true size: 1.9 blocks is 30.4 pixels, centred on 8.
			write(models.resolve("mole.json"), model("gold_block light_blue_concrete",
					cube("body", "-7.2, 0, -7.2", "23.2, 8, 23.2", "gold_block"),
					cube("cabin", "2, 8, 2", "14, 15, 14", "light_blue_concrete")));
			// A drill with one red fin, so the turn shows. Authored pointing down from the middle of the hull.
			write(models.resolve("mole_drill.json"), model("iron_block redstone_block",
					cube("shaft", "5, -16, 5", "11, 7, 11", "iron_block"),
					cube("fin", "11, -16, 7", "16, -4, 9", "redstone_block")));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** A model of cubes. Faces carry an explicit uv: the default is the face's own coordinates, which leave the texture past 16 pixels. */
	private static String model(String blocks, String... cubes) {
		StringBuilder textures = new StringBuilder();
		for (String block : blocks.split(" ")) {
			textures.append("\"").append(block).append("\": \"minecraft:block/").append(block).append("\", ");
		}
		String particle = "\"particle\": \"minecraft:block/" + blocks.split(" ")[0] + "\"";
		return "{\"textures\": {" + textures + particle + "}, \"elements\": [" + String.join(", ", cubes) + "]}";
	}

	private static String cube(String name, String from, String to, String texture) {
		StringBuilder faces = new StringBuilder();
		for (String face : new String[] {"north", "east", "south", "west", "up", "down"}) {
			faces.append(faces.isEmpty() ? "" : ", ")
					.append("\"").append(face).append("\": {\"uv\": [0, 0, 16, 16], \"texture\": \"").append("#").append(texture).append("\"}");
		}
		return "{\"name\": \"" + name + "\", \"from\": [" + from + "], \"to\": [" + to + "], \"faces\": {" + faces + "}}";
	}

	private static void write(Path file, String text) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, text);
	}

	private static void deleteTree(Path dir) {
		if (!Files.exists(dir)) {
			return;
		}
		try (var paths = Files.walk(dir)) {
			for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(p);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
