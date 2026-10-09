package io.github.pkeppeler.deepcharter.test.evidence;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyBlocks;
import io.github.pkeppeler.deepcharter.colony.ColonyKit;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidenceWorld;

/**
 * Evidence scenario "colony-concepts" for #335 and #353: each colony layout (tools/colony/concepts.py,
 * docs/design/colony-concepts-2.md), the four concepts and the four sizes of the statue, is built in turn over the colony's pad in a
 * test world, with the Mole pods and players its layout stands in it for scale, then shot from the views its layout names, by day
 * and by night; a concept is also flown round once for its GIF. Nothing a player's world builds changes: the layouts and structure
 * files are test resources.
 *
 * <p>Before each layout the pad above the ground is cleared, but for the Conduit and the row of terminal plinths; beyond the pad
 * only the last layout's blocks go, so the plain round it keeps its shape. The ground row is put back as it was before the first
 * layout, so each stands on the same ground. Stills are named {@code <layout>-<view>}; the frames are the orbits of the layouts
 * that have one, in name order, {@link #ORBIT_FRAMES} each.
 */
public class ColonyConceptsScenario extends EvidenceScenario {
	private static final double EYE = 1.62;
	/** How far round the colony's centre, and how high over its ground, a concept may build. */
	private static final int RADIUS = 56;
	private static final int HEIGHT = 72;
	/** Half the shipping colony's pad: inside it everything above the ground goes; outside, only what a concept built. */
	private static final int PAD = 32;
	/** The terminal plinths of the shipping colony, which every concept keeps: X -9..9, Z -9..-7 from the centre, Y 1..2. */
	private static final int PLINTH_Z_MIN = -9;
	private static final int PLINTH_Z_MAX = -7;
	private static final int ORBIT_FRAMES = 60;
	private static final int ORBIT_TICKS = 2;
	private static final long SETTLE_LIMIT_NANOS = 120_000_000_000L;
	private static final int SETTLE_POLL_TICKS = 2;
	private static final int SETTLE_STABLE_POLLS = 5;
	/** The tag of the pods and players a layout stands in for scale, so the next layout takes away only those. */
	private static final String FIGURE_TAG = "colony_concepts_figure";

	private ClientGameTestContext ctx;
	private TestSingleplayerContext sp;
	private BlockPos centre;
	private final Map<BlockPos, BlockState> ground = new HashMap<>();

	private record View(String name, Vec3 eye, Vec3 target, boolean night, boolean aboveGround) {
	}

	private record Layout(String name, List<Placed> pieces, List<View> views, List<Figure> figures, Optional<Orbit> orbit) {
		int displays() {
			return pieces.stream().mapToInt(Placed::displays).sum();
		}
	}

	private record Placed(Identifier structure, BlockPos offset, int displays) {
	}

	/** Something stood in a layout for scale; {@code at} is from the colony's centre, {@code yaw} Minecraft's. */
	private record Figure(Kind kind, Vec3 at, float yaw) {
	}

	private enum Kind {
		POD, PLAYER
	}

	private record Orbit(Vec3 centre, double radius, double height) {
	}

	@Override
	protected String name() {
		return "colony-concepts";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		ctx = context;
		try (TestSingleplayerContext singleplayer = context.worldBuilder().setUseConsistentSettings(false)
				.adjustSettings(state -> state.setSeed("deepcharter-colony-concepts")).create()) {
			sp = singleplayer;
			ClientWait.until(ctx, "the player in the world", client -> client.player != null && client.level != null);
			centre = serverGet(server -> Colony.placed(server).orElseThrow(() -> new AssertionError("the colony was not built")).center());
			EvidenceWorld.pin(ctx, sp);
			setUpCamera();
			try {
				List<Layout> layouts = serverGet(ColonyConceptsScenario::layouts);
				if (layouts.isEmpty()) {
					throw new AssertionError("no concept layouts under data/deepcharter/colony_concept/: run tools/colony/build.py");
				}
				for (Layout layout : layouts) {
					build(layout);
					for (View view : layout.views()) {
						shoot(layout.name() + "-" + view.name(), view);
					}
					layout.orbit().ifPresent(orbit -> orbit(layout.name(), orbit));
				}
			} finally {
				ctx.runOnClient(client -> {
					if (client.gui.hud.isHidden()) {
						client.gui.hud.toggle();
					}
				});
			}
		}
	}

	private static List<Layout> layouts(MinecraftServer server) {
		Map<Identifier, Resource> found = server.getResourceManager().listResources("colony_concept", id -> id.getPath().endsWith(".json"));
		List<Layout> out = new ArrayList<>();
		for (Map.Entry<Identifier, Resource> entry : found.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
			String path = entry.getKey().getPath();
			String name = path.substring(path.lastIndexOf('/') + 1, path.length() - ".json".length());
			try (Reader reader = entry.getValue().openAsReader()) {
				out.add(layout(name, JsonParser.parseReader(reader).getAsJsonObject()));
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
		return out;
	}

	private static Layout layout(String name, JsonObject json) {
		List<Placed> pieces = new ArrayList<>();
		for (JsonElement element : json.getAsJsonArray("pieces")) {
			JsonObject piece = element.getAsJsonObject();
			JsonArray offset = piece.getAsJsonArray("offset");
			pieces.add(new Placed(Identifier.parse(piece.get("structure").getAsString()),
					new BlockPos(offset.get(0).getAsInt(), offset.get(1).getAsInt(), offset.get(2).getAsInt()), piece.get("displays").getAsInt()));
		}
		List<View> views = new ArrayList<>();
		for (JsonElement element : json.getAsJsonArray("views")) {
			JsonObject view = element.getAsJsonObject();
			views.add(new View(view.get("name").getAsString(), vec(view.getAsJsonArray("eye")), vec(view.getAsJsonArray("target")),
					view.get("night").getAsBoolean(), view.get("above_ground").getAsBoolean()));
		}
		List<Figure> figures = new ArrayList<>();
		for (JsonElement element : json.getAsJsonArray("figures")) {
			JsonObject figure = element.getAsJsonObject();
			figures.add(new Figure(Kind.valueOf(figure.get("kind").getAsString().toUpperCase(Locale.ROOT)), vec(figure.getAsJsonArray("at")),
					figure.get("yaw").getAsFloat()));
		}
		JsonElement orbit = json.get("orbit");
		Optional<Orbit> lap = orbit.isJsonNull() ? Optional.empty() : Optional.of(new Orbit(vec(orbit.getAsJsonObject().getAsJsonArray("centre")),
				orbit.getAsJsonObject().get("radius").getAsDouble(), orbit.getAsJsonObject().get("height").getAsDouble()));
		return new Layout(name, pieces, views, figures, lap);
	}

	private static Vec3 vec(JsonArray array) {
		return new Vec3(array.get(0).getAsDouble(), array.get(1).getAsDouble(), array.get(2).getAsDouble());
	}

	private void setUpCamera() {
		serverDo(server -> {
			ServerPlayer player = player(server);
			player.setGameMode(GameType.CREATIVE);
			player.getInventory().clearContent();
			player.getAbilities().mayfly = true;
			player.getAbilities().flying = true;
			player.onUpdateAbilities();
			player.setPermanentlyInvulnerable(true);
		});
		ctx.runOnClient(client -> {
			client.options.setCameraType(CameraType.FIRST_PERSON);
			if (!client.gui.hud.isHidden()) {
				client.gui.hud.toggle();
			}
		});
	}

	// ------------------------------------------------------------------------------------------------ building

	/** Clears the pad of the last concept and places this one's pieces, then checks every display entity arrived. */
	private void build(Layout layout) {
		view(Vec3.atBottomCenterOf(centre).add(0, 30, 40), Vec3.atBottomCenterOf(centre));
		serverDo(server -> {
			ServerLevel level = server.overworld();
			for (int cx = (centre.getX() - RADIUS) >> 4; cx <= (centre.getX() + RADIUS) >> 4; cx++) {
				for (int cz = (centre.getZ() - RADIUS) >> 4; cz <= (centre.getZ() + RADIUS) >> 4; cz++) {
					level.getChunk(cx, cz);
				}
			}
			AABB box = new AABB(centre.getX() - RADIUS, centre.getY() - 2, centre.getZ() - RADIUS, centre.getX() + RADIUS + 1,
					centre.getY() + HEIGHT + 32, centre.getZ() + RADIUS + 1);
			level.getEntitiesOfClass(Display.BlockDisplay.class, box).forEach(Entity::discard);
			level.getEntitiesOfClass(Entity.class, box, entity -> entity.entityTags().contains(FIGURE_TAG)).forEach(Entity::discard);
			Set<Block> kit = Set.copyOf(ColonyKit.all());
			// The plain the plateau is made of, from beyond the pad: the pad gets it too, so no floor of the shipping colony shows.
			BlockState plain = level.getBlockState(centre.offset(0, 0, PAD + 12));
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			for (int dx = -RADIUS; dx <= RADIUS; dx++) {
				for (int dz = -RADIUS; dz <= RADIUS; dz++) {
					BlockPos row = centre.offset(dx, 0, dz);
					ground.computeIfAbsent(row.immutable(), level::getBlockState);
					boolean pad = Math.max(Math.abs(dx), Math.abs(dz)) <= PAD;
					if (!level.getBlockState(row).is(ColonyBlocks.CONDUIT)) {
						level.setBlock(row, pad ? plain : ground.get(row), Block.UPDATE_CLIENTS);
					}
					for (int dy = 1; dy <= HEIGHT; dy++) {
						pos.setWithOffset(centre, dx, dy, dz);
						BlockState state = level.getBlockState(pos);
						boolean plinth = Math.abs(dx) <= 9 && dz >= PLINTH_Z_MIN && dz <= PLINTH_Z_MAX && dy <= 2;
						boolean ours = Math.max(Math.abs(dx), Math.abs(dz)) <= PAD || kit.contains(state.getBlock()) || state.is(Blocks.BARRIER);
						if (!state.isAir() && ours && !state.is(ColonyBlocks.CONDUIT) && !plinth) {
							level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
						}
					}
				}
			}
			for (Placed piece : layout.pieces()) {
				StructureTemplate template = server.getStructureTemplateManager().get(piece.structure())
						.orElseThrow(() -> new AssertionError("concept " + layout.name() + ": " + piece.structure() + " does not load"));
				BlockPos at = centre.offset(piece.offset());
				template.placeInWorld(level, at, at, new StructurePlaceSettings(), level.getRandom(), Block.UPDATE_CLIENTS);
			}
			int displays = level.getEntitiesOfClass(Display.BlockDisplay.class, box).size();
			if (displays != layout.displays()) {
				throw new AssertionError("concept " + layout.name() + ": " + displays + " block displays stand on the pad, its layout places " + layout.displays());
			}
			for (Figure figure : layout.figures()) {
				stand(server, figure);
			}
			int figures = level.getEntitiesOfClass(Entity.class, box, entity -> entity.entityTags().contains(FIGURE_TAG)).size();
			if (figures != layout.figures().size()) {
				throw new AssertionError("concept " + layout.name() + ": " + figures + " figures stand on the pad, its layout stands " + layout.figures().size());
			}
		});
	}

	/** Stands a Mole pod, or a mannequin in a player's shape and skin, where the layout says, for scale. */
	private void stand(MinecraftServer server, Figure figure) {
		ServerLevel level = server.overworld();
		Vec3 at = Vec3.atLowerCornerOf(centre).add(figure.at());
		switch (figure.kind()) {
			case POD -> {
				PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
				pod.setPos(at);
				pod.setYRot(figure.yaw());
				pod.addTag(FIGURE_TAG);
				level.addFreshEntity(pod);
			}
			case PLAYER -> command(server, String.format(Locale.ROOT,
					"summon minecraft:mannequin %.2f %.2f %.2f {Rotation:[%.1ff,0f],immovable:1b,hide_description:1b,Tags:[\"%s\"]}",
					at.x, at.y, at.z, figure.yaw(), FIGURE_TAG));
		}
	}

	// ------------------------------------------------------------------------------------------------ stills and frames

	private void shoot(String still, View view) {
		if (view.night()) {
			serverDo(server -> command(server, "time set 18000"));
			EvidenceWorld.skyPhase(ctx, sp, EvidenceWorld.SKY_DARKEST);
		}
		Vec3 eye = Vec3.atLowerCornerOf(centre).add(view.eye());
		if (view.aboveGround()) {
			int x = Mth.floor(eye.x);
			int z = Mth.floor(eye.z);
			eye = new Vec3(eye.x, serverGet(server -> {
				server.overworld().getChunk(x >> 4, z >> 4);
				return server.overworld().getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
			}) + view.eye().y, eye.z);
		}
		view(eye, Vec3.atLowerCornerOf(centre).add(view.target()));
		settle(still);
		screenshot(ctx, still);
		if (view.night()) {
			serverDo(server -> command(server, "time set noon"));
			EvidenceWorld.skyPhase(ctx, sp, EvidenceWorld.SKY_BRIGHTEST);
		}
	}

	/** One lap round the layout, looking in at its centre, from the south and on round to the east. */
	private void orbit(String name, Orbit orbit) {
		Vec3 middle = Vec3.atLowerCornerOf(centre).add(orbit.centre());
		for (int i = 0; i < ORBIT_FRAMES; i++) {
			double angle = 2 * Math.PI * i / ORBIT_FRAMES + Math.PI / 2;
			Vec3 eye = middle.add(Math.cos(angle) * orbit.radius(), orbit.height(), Math.sin(angle) * orbit.radius());
			view(eye, middle);
			if (i == 0) {
				settle(name + " orbit");
			} else {
				ctx.waitTicks(ORBIT_TICKS);
			}
			frame(ctx);
		}
	}

	/** Puts the camera at {@code eye}, looking at {@code target}, and waits until the client stands there. */
	private void view(Vec3 eye, Vec3 target) {
		Vec3 d = target.subtract(eye);
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
		serverDo(server -> {
			ServerPlayer player = player(server);
			player.setNoGravity(true);
			player.getAbilities().flying = true;
			player.onUpdateAbilities();
			player.teleportTo(server.overworld(), eye.x, eye.y - EYE, eye.z, Set.of(), yaw, pitch, true);
		});
		ClientWait.until(ctx, "the camera at " + eye, client -> client.player.distanceToSqr(eye.x, eye.y - EYE, eye.z) < 0.0001
				&& Math.abs(Mth.wrapDegrees(client.player.getYRot() - yaw)) < 0.01f && Math.abs(client.player.getXRot() - pitch) < 0.01f);
	}

	/**
	 * Waits, on the wall clock, until the server has no light work, the client holds and has drawn every chunk round the camera,
	 * and the camera has stood still for a few polls. Fails naming the still after {@link #SETTLE_LIMIT_NANOS}.
	 */
	private void settle(String still) {
		long deadline = System.nanoTime() + SETTLE_LIMIT_NANOS;
		int stable = 0;
		String lastPose = null;
		while (stable < SETTLE_STABLE_POLLS) {
			ctx.runOnClient(client -> client.particleEngine.clearParticles());
			ctx.waitTicks(SETTLE_POLL_TICKS);
			boolean lit = serverGet(server -> !server.overworld().getLightEngine().hasLightWork());
			boolean drawn = ctx.computeOnClient(client -> chunksLoaded(client) && client.levelRenderer.hasRenderedAllSections());
			String pose = ctx.computeOnClient(client -> client.getCameraEntity().position() + " " + client.getCameraEntity().getYRot());
			stable = lit && drawn && pose.equals(lastPose) ? stable + 1 : 0;
			lastPose = pose;
			if (System.nanoTime() > deadline) {
				throw new AssertionError("still " + still + ": the world did not settle in " + SETTLE_LIMIT_NANOS / 1_000_000_000L + " s");
			}
		}
	}

	private static boolean chunksLoaded(Minecraft client) {
		int radius = client.options.getEffectiveRenderDistance();
		int x = client.player.chunkPosition().x();
		int z = client.player.chunkPosition().z();
		for (int cx = x - radius; cx <= x + radius; cx++) {
			for (int cz = z - radius; cz <= z + radius; cz++) {
				if (client.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) == null) {
					return false;
				}
			}
		}
		return true;
	}

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().getFirst();
	}

	private static void command(MinecraftServer server, String command) {
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
	}

	private <T> T serverGet(java.util.function.Function<MinecraftServer, T> action) {
		return sp.getServer().computeOnServer(action::apply);
	}

	private void serverDo(java.util.function.Consumer<MinecraftServer> action) {
		sp.getServer().runOnServer(action::accept);
	}
}
