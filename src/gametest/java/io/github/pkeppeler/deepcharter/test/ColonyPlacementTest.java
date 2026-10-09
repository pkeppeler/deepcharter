package io.github.pkeppeler.deepcharter.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ProblemReporter;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonyBlocks;
import io.github.pkeppeler.deepcharter.colony.ColonyEdge;
import io.github.pkeppeler.deepcharter.colony.ColonyKit;
import io.github.pkeppeler.deepcharter.colony.ColonyLayout;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.colony.ColonyTuning;
import io.github.pkeppeler.deepcharter.colony.FounderStatue;
import io.github.pkeppeler.deepcharter.handbook.HandbookRegistry;
import io.github.pkeppeler.deepcharter.handbook.NoteBlock;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.surface.SurfaceBlocks;
import io.github.pkeppeler.deepcharter.test.support.ColonyChunks;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;

/**
 * Server GameTests for #244, the colony rebuild: the town the world builds at spawn (tools/colony/town.py, the layout file
 * {@link ColonyLayout}) is all there, the Host stands at ten blocks without his hands, a player walks from the Continuity Office to
 * every place the colony is for, a player fits every door and a pod every bay and the hangar, and no block of it floats or hangs
 * over the void. The colony is built when the test server starts, so these read the world as it is.
 *
 * <p>The structure files' own contents (block names, properties, the data version) are checked by the build itself: a structure
 * with an unknown block places as air, which the first test sees as a hole.
 */
public class ColonyPlacementTest {
	/** A player's hitbox. */
	private static final double PLAYER_WIDTH = 0.6;
	private static final double PLAYER_HEIGHT = 1.8;
	private static final double SWEEP_STEP = 0.25;
	/** How far a sweep starts outside an opening, and how far it runs inside a door and inside a bay. */
	private static final double SWEEP_OUT = 4;
	private static final double SWEEP_IN_DOOR = 2;
	private static final double SWEEP_IN_BAY = 6;
	/** A player jumps up one block and drops three. */
	private static final int DROP = 3;
	/** The most blocks above the ground a walk looks at: steps, not ladders. */
	private static final int WALK_HEIGHT = 3;
	/** How far a player's eyes are above the feet, and how far a player reaches to use a block. */
	private static final double EYE = 1.62;
	private static final double REACH = 4.5;

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
	public void everyBlockOfEveryPieceOfTheLayoutStandsInTheWorld(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		ServerLevel level = server.overworld();
		ColonySite.Placed colony = placed(helper);
		int[] ticking = {0};
		List<BlockPos> chunks = ColonyChunks.of(colony);
		FarChunks.awaitEntityTicking(helper, level, chunks, index -> ticking[0]++);
		helper.succeedWhen(() -> {
			if (ticking[0] < chunks.size()) {
				throw failure(helper, "waiting for the pad's chunks to tick: %s of %s", ticking[0], chunks.size());
			}
			ColonyLayout layout = ColonyLayout.read(server);
			List<String> problems = new ArrayList<>();
			int displays = 0;
			for (ColonyLayout.Piece piece : layout.pieces()) {
				displays += piece.displays();
				try {
					checkPiece(server, level, colony.center().offset(piece.offset()), piece, problems);
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}
			long standing = level.getEntitiesOfClass(Display.BlockDisplay.class, pad(colony), display -> !display.getBlockState().equals(FounderStatue.handsState())).size();
			if (standing != displays) {
				problems.add(standing + " block displays stand on the pad, the layout's pieces place " + displays);
			}
			if (!problems.isEmpty()) {
				throw failure(helper, "%s problem(s):\n  %s", problems.size(), String.join("\n  ", problems));
			}
		});
	}

	/** Every block of the piece's file at its place, above the ground row (the ground row is where a footing goes over paving). */
	private static void checkPiece(MinecraftServer server, ServerLevel level, BlockPos origin, ColonyLayout.Piece piece, List<String> problems) throws IOException {
		var file = net.minecraft.resources.Identifier.fromNamespaceAndPath(piece.structure().getNamespace(), "structure/" + piece.structure().getPath() + ".nbt");
		CompoundTag root;
		try (InputStream stream = server.getResourceManager().getResourceOrThrow(file).open()) {
			root = NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());
		}
		List<BlockState> palette = new ArrayList<>();
		for (Tag tag : root.getListOrEmpty("palette")) {
			palette.add(BlockState.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow(message -> new IllegalStateException(piece.structure() + " palette: " + message)));
		}
		int wrong = 0;
		String first = "";
		for (Tag tag : root.getListOrEmpty("blocks")) {
			CompoundTag block = (CompoundTag) tag;
			var list = block.getListOrEmpty("pos");
			int[] pos = {list.getIntOr(0, 0), list.getIntOr(1, 0), list.getIntOr(2, 0)};
			BlockPos at = origin.offset(pos[0], pos[1], pos[2]);
			BlockState wanted = palette.get(block.getIntOr("state", 0));
			if (pos[1] + piece.offset().getY() >= 1 && !level.getBlockState(at).equals(wanted)) {
				if (wrong++ == 0) {
					first = at.toShortString() + " is " + level.getBlockState(at) + ", the file has " + wanted;
				}
			}
		}
		if (wrong > 0) {
			problems.add(piece.structure() + ": " + wrong + " block(s) are not as the file has them, the first: " + first);
		}
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
	public void theHostStandsAtTenBlocksOnHisPlinthWithoutHisHands(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		ServerLevel level = server.overworld();
		ColonySite.Placed colony = placed(helper);
		BlockPos feet = colony.anchors().get(ColonyAnchor.STATUE);
		boolean[] ticking = {false};
		FarChunks.awaitEntityTicking(helper, level, feet, () -> ticking[0] = true);
		helper.succeedWhen(() -> {
			if (!ticking[0]) {
				throw failure(helper, "waiting for the Host's chunk to tick");
			}
			checkTheHost(helper, server, level, feet);
		});
	}

	private static void checkTheHost(GameTestHelper helper, MinecraftServer server, ServerLevel level, BlockPos feet) {
		List<Display.BlockDisplay> found = FounderStatue.body(server);
		if (found.size() != 1) {
			throw failure(helper, "one body of the Host should stand at %s, found %s", feet.toShortString(), found.size());
		}
		Vec3 at = found.getFirst().position();
		if (Math.abs(at.x - (feet.getX() + 0.5)) > 0.01 || Math.abs(at.y - feet.getY()) > 0.01 || Math.abs(at.z - (feet.getZ() + 0.5)) > 0.01) {
			throw failure(helper, "the Host's feet are at %s, not at the middle of the bottom of %s", at, feet.toShortString());
		}
		// A display scales the model to the height the square wants: ten blocks is this scale (tools/colony/town.py HOST, whose test
		// ties the number to the figure).
		TagValueOutput saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		found.getFirst().saveWithoutId(saved);
		float scale = saved.buildResult().getCompoundOrEmpty("transformation").getListOrEmpty("scale").getFloatOr(1, 0F);
		if (Math.abs(scale - HOST_SCALE) > 0.001) {
			throw failure(helper, "the Host's display scale is %s, which stands him %s blocks tall, not 10", scale, scale / HOST_SCALE * 10);
		}
		if (!level.getBlockState(feet.below()).is(ColonyKit.BRASS_TRIM)) {
			throw failure(helper, "the Host has no plinth under his feet at %s: %s", feet.toShortString(), level.getBlockState(feet.below()));
		}
		if (!level.getBlockState(feet).is(Blocks.BARRIER)) {
			throw failure(helper, "no collision stands in the Host at %s, so a pod could fly through him", feet.toShortString());
		}
		if (!FounderStatue.hands(server).isEmpty()) {
			throw failure(helper, "the built colony should leave the Host without hands");
		}
	}

	/** The display scale that makes the Host 10 blocks tall, from his soles to his raised hand. */
	private static final double HOST_SCALE = 3.4752;

	@GameTest(maxTicks = 400)
	public void aPlayerWalksFromTheContinuityOfficeToEveryPlaceTheColonyIsFor(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		ServerLevel level = server.overworld();
		ColonySite.Placed colony = placed(helper);
		loadPad(level, colony);
		BlockPos spawn = colony.anchors().get(ColonyAnchor.CONTINUITY_OFFICE);
		Set<BlockPos> reach = reach(level, colony, spawn);
		if (!reach.contains(spawn)) {
			throw failure(helper, "a player cannot stand at the world spawn %s", spawn.toShortString());
		}
		List<String> problems = new ArrayList<>();
		// Each function of the colony and the block it is used at.
		Map<String, BlockPos> places = new java.util.LinkedHashMap<>();
		for (ColonyAnchor anchor : List.of(ColonyAnchor.FUEL_PUMP, ColonyAnchor.ORE_PROCESSOR, ColonyAnchor.UPGRADE_TERMINAL, ColonyAnchor.REPAIR_STATION,
				ColonyAnchor.CONTRACT_TERMINAL, ColonyAnchor.CHAPEL_CANDLE, ColonyAnchor.CONDUIT)) {
			places.put(anchor.getSerializedName(), colony.anchors().get(anchor));
		}
		places.put("hangar console", io.github.pkeppeler.deepcharter.hangar.Hangar.consolePos(server).orElseThrow());
		places.put("the Host's plinth", colony.center().offset(0, 1, 4));
		for (ColonyAnchor anchor : List.of(ColonyAnchor.HANGAR, ColonyAnchor.BUNKHOUSE, ColonyAnchor.PAY_OFFICE, ColonyAnchor.PERSONNEL_OFFICE, ColonyAnchor.LAMP_AND_PICK)) {
			BlockPos inside = colony.anchors().get(anchor);
			if (!reach.contains(inside)) {
				problems.add("a player cannot walk to the " + anchor.getSerializedName() + " at " + inside.toShortString());
			}
		}
		BlockPos centre = colony.center();
		for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-ColonyTuning.DEFAULT.padSize() / 2, 1, -ColonyTuning.DEFAULT.padSize() / 2),
				centre.offset(ColonyTuning.DEFAULT.padSize() / 2 - 1, WALK_HEIGHT + 1, ColonyTuning.DEFAULT.padSize() / 2 - 1))) {
			BlockState state = level.getBlockState(pos);
			if (state.is(HandbookRegistry.NOTE)) {
				places.put("note N0" + state.getValue(NoteBlock.NOTE), pos.immutable());
			} else if (state.getBlock() instanceof BedBlock) {
				places.putIfAbsent("a bed", pos.immutable());
			}
		}
		for (Map.Entry<String, BlockPos> place : places.entrySet()) {
			if (reach.stream().noneMatch(stand -> canUse(level, stand, place.getValue()))) {
				problems.add("no place a player can walk to reaches " + place.getKey() + " at " + place.getValue().toShortString() + " with a clear line to it");
			}
		}
		if (places.keySet().stream().filter(name -> name.startsWith("note N0")).count() != 4) {
			problems.add("the colony holds notes " + places.keySet() + ", expected N01 to N04");
		}
		finish(helper, problems);
	}

	/** True when a player standing at {@code stand} can use the block at {@code target}: it is within reach of the eyes and the first block the line to it meets is that block (or, for the casing, one of its kind). */
	private static boolean canUse(ServerLevel level, BlockPos stand, BlockPos target) {
		Vec3 eye = Vec3.atBottomCenterOf(stand).add(0, EYE, 0);
		BlockState state = level.getBlockState(target);
		Vec3 aim = Vec3.atLowerCornerOf(target).add(state.getShape(level, target).bounds().getCenter());
		if (eye.distanceTo(aim) > REACH) {
			return false;
		}
		BlockHitResult hit = level.clip(new ClipContext(eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
		if (hit.getType() != HitResult.Type.BLOCK) {
			return false;
		}
		BlockState first = level.getBlockState(hit.getBlockPos());
		return hit.getBlockPos().equals(target) || (state.is(ColonyBlocks.CONDUIT) && first.is(ColonyBlocks.CONDUIT));
	}

	/** The standing places a player reaches from {@code start} on the pad: walking, a jump up one block, a drop of up to three. */
	private static Set<BlockPos> reach(ServerLevel level, ColonySite.Placed colony, BlockPos start) {
		int half = ColonyTuning.DEFAULT.padSize() / 2;
		BlockPos centre = colony.center();
		Set<BlockPos> seen = new HashSet<>();
		Deque<BlockPos> frontier = new ArrayDeque<>();
		seen.add(start);
		frontier.add(start);
		while (!frontier.isEmpty()) {
			BlockPos from = frontier.poll();
			for (Direction side : Direction.Plane.HORIZONTAL) {
				for (int dy = 1; dy >= -DROP; dy--) {
					BlockPos to = from.relative(side).above(dy);
					boolean inside = Math.abs(to.getX() - centre.getX()) < half && Math.abs(to.getZ() - centre.getZ()) < half
							&& to.getY() >= centre.getY() && to.getY() <= centre.getY() + WALK_HEIGHT;
					if (!inside || seen.contains(to) || !stands(level, to)) {
						continue;
					}
					// The way: up, the head clears one block higher where the player is; level or down, the body clears the way out.
					boolean passes = dy > 0 ? clear(level, playerAt(from.above())) : clear(level, playerAt(from.relative(side).atY(from.getY())));
					if (passes) {
						seen.add(to);
						frontier.add(to);
						break;
					}
				}
			}
		}
		return seen;
	}

	private static boolean stands(ServerLevel level, BlockPos feet) {
		BlockPos below = feet.below();
		return clear(level, playerAt(feet)) && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
	}

	private static AABB playerAt(BlockPos feet) {
		return box(Vec3.atBottomCenterOf(feet), PLAYER_WIDTH, PLAYER_HEIGHT);
	}

	private static AABB box(Vec3 feet, double width, double height) {
		return new AABB(feet.x - width / 2, feet.y, feet.z - width / 2, feet.x + width / 2, feet.y + height, feet.z + width / 2);
	}

	/** True when no block's collision shape meets the box. Entities, such as the derelict Mole, do not count. */
	private static boolean clear(ServerLevel level, AABB box) {
		return !level.getBlockCollisions(null, box).iterator().hasNext();
	}

	@GameTest
	public void aPlayerFitsEveryDoorAndAPodEveryBayAndTheHangar(GameTestHelper helper) throws IOException {
		MinecraftServer server = server(helper);
		ServerLevel level = server.overworld();
		ColonySite.Placed colony = placed(helper);
		loadPad(level, colony);
		List<String> problems = new ArrayList<>();
		JsonObject layout;
		try (Reader reader = server.getResourceManager().getResourceOrThrow(ColonyLayout.ID).openAsReader()) {
			layout = JsonParser.parseReader(reader).getAsJsonObject();
		}
		int doors = 0;
		int bays = 0;
		for (JsonElement element : layout.getAsJsonArray("doors")) {
			JsonObject door = element.getAsJsonObject();
			var outside = door.getAsJsonArray("outside");
			BlockPos cell = colony.center().offset(outside.get(0).getAsInt(), outside.get(1).getAsInt(), outside.get(2).getAsInt());
			Direction facing = Direction.byName(door.get("facing").getAsString());
			int width = door.get("width").getAsInt();
			int height = door.get("height").getAsInt();
			String name = door.get("building").getAsString();
			if (width == 1) {
				doors++;
				if (!sweep(level, cell, facing, 0.5, PLAYER_WIDTH, PLAYER_HEIGHT, SWEEP_IN_DOOR)) {
					problems.add("a player does not fit the door of " + name + " at " + cell.toShortString());
				}
				if (height < 2) {
					problems.add("the door of " + name + " is " + height + " high");
				}
			} else {
				bays++;
				for (EntityType<?> pod : List.of(PodRegistry.POD, PodRegistry.PROSPECTOR)) {
					var size = pod.getDimensions();
					boolean fits = false;
					for (double lateral = size.width() / 2; lateral <= width - size.width() / 2 + 1e-9 && !fits; lateral += SWEEP_STEP) {
						fits = sweep(level, cell, facing, lateral, size.width(), size.height(), SWEEP_IN_BAY);
					}
					if (!fits) {
						problems.add("the " + EntityType.getKey(pod).getPath() + " does not fit the " + name + " at " + cell.toShortString());
					}
				}
			}
		}
		if (doors != 8 || bays != 2) {
			problems.add("the layout lists " + doors + " doors and " + bays + " bays, expected a door to each of eight buildings and two bays");
		}
		// The hangar holds a pod at its anchor, whichever chassis.
		BlockPos hangar = colony.anchors().get(ColonyAnchor.HANGAR);
		for (EntityType<?> pod : List.of(PodRegistry.POD, PodRegistry.PROSPECTOR)) {
			var size = pod.getDimensions();
			if (!clear(level, box(Vec3.atBottomCenterOf(hangar), size.width(), size.height()))) {
				problems.add("the " + EntityType.getKey(pod).getPath() + " does not fit in the hangar at " + hangar.toShortString());
			}
		}
		finish(helper, problems);
	}

	/**
	 * Moves a box of the given size through an opening: it starts {@link #SWEEP_OUT} blocks outside the cell and ends {@link #SWEEP_IN}
	 * inside it ({@code inside}), along {@code facing} in the opposite way, with its middle {@code lateral} blocks along the wall from the cell's low
	 * edge. True when no block meets it on the way.
	 */
	private static boolean sweep(ServerLevel level, BlockPos cell, Direction facing, double lateral, double width, double height, double inside) {
		// The opening's wall runs along X when it faces north or south, along Z when it faces east or west.
		boolean wallAlongX = facing.getAxis() == Direction.Axis.Z;
		int outward = facing.getAxisDirection().getStep();
		for (double t = -SWEEP_OUT; t <= inside; t += SWEEP_STEP) {
			double depth = (wallAlongX ? cell.getZ() : cell.getX()) + 0.5 - outward * t;
			double x = wallAlongX ? cell.getX() + lateral : depth;
			double z = wallAlongX ? depth : cell.getZ() + lateral;
			if (!clear(level, box(new Vec3(x, cell.getY(), z), width, height))) {
				return false;
			}
		}
		return true;
	}

	@GameTest(maxTicks = 400)
	public void noBlockOfTheColonyFloatsOrHangsOverTheVoid(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		ServerLevel level = server.overworld();
		ColonySite.Placed colony = placed(helper);
		loadPad(level, colony);
		int half = ColonyTuning.DEFAULT.padSize() / 2;
		BlockPos centre = colony.center();
		List<String> problems = new ArrayList<>();
		// Every column of the pad has solid ground under it: nothing of the colony stands over a gap.
		for (int x = -half; x < half; x++) {
			for (int z = -half; z < half; z++) {
				for (int dy = 0; dy >= -3; dy--) {
					BlockPos pos = centre.offset(x, dy, z);
					if (level.getBlockState(pos).isAir() || !level.getFluidState(pos).isEmpty()) {
						problems.add("the pad has no ground at " + pos.toShortString() + ": " + level.getBlockState(pos));
						dy = -4;
					}
				}
			}
		}
		// Every block above the ground joins the ground or the Conduit's casing, by a face or along an edge.
		Set<BlockPos> blocks = new HashSet<>();
		Deque<BlockPos> frontier = new ArrayDeque<>();
		Set<BlockPos> supported = new HashSet<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = -half; x < half; x++) {
			for (int z = -half; z < half; z++) {
				for (int y = 1; y <= ColonyTuning.DEFAULT.clearHeight(); y++) {
					pos.setWithOffset(centre, x, y, z);
					BlockState state = level.getBlockState(pos);
					// A pod's light blocks hang in the air by design (ADR 0024); they are not the colony's.
					if (!state.isAir() && !state.is(Blocks.LIGHT)) {
						BlockPos found = pos.immutable();
						blocks.add(found);
						if (y == 1 || state.is(ColonyBlocks.CONDUIT)) {
							supported.add(found);
							frontier.add(found);
						}
					}
				}
			}
		}
		while (!frontier.isEmpty()) {
			BlockPos from = frontier.poll();
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						int apart = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
						BlockPos near = from.offset(dx, dy, dz);
						if ((apart == 1 || apart == 2) && blocks.contains(near) && supported.add(near)) {
							frontier.add(near);
						}
					}
				}
			}
		}
		blocks.removeAll(supported);
		if (!blocks.isEmpty()) {
			problems.add(blocks.size() + " block(s) hang in the air, the first at " + blocks.iterator().next().toShortString());
		}
		if (blocks.size() + supported.size() < 3000) {
			problems.add("only " + (blocks.size() + supported.size()) + " blocks stand on the pad: the colony was not built");
		}
		finish(helper, problems);
	}

	/** A player climbs one block. */
	private static final int STEP = 1;

	/**
	 * The land around the pad is graded to it, with no step between neighbouring columns taller than a player climbs, so the town
	 * can be left on any side. Read from the built world: the margin's surface, and the pad's ground at its edge.
	 */
	@GameTest(maxTicks = 100)
	public void aPlayerWalksOutOfTheTownOnEverySideOverTheGradedMargin(GameTestHelper helper) {
		ServerLevel level = server(helper).overworld();
		ColonySite.Placed colony = placed(helper);
		BlockPos centre = colony.center();
		int half = ColonyTuning.DEFAULT.padSize() / 2;
		int reach = half + ColonyTuning.DEFAULT.edgeMargin();
		int size = 2 * reach;
		for (int x = centre.getX() - reach; x < centre.getX() + reach; x += 16) {
			for (int z = centre.getZ() - reach; z < centre.getZ() + reach; z += 16) {
				level.getChunk(x >> 4, z >> 4);
			}
		}
		level.getChunk((centre.getX() + reach - 1) >> 4, (centre.getZ() + reach - 1) >> 4);
		int[][] surface = new int[size][size];
		for (int i = 0; i < size; i++) {
			for (int j = 0; j < size; j++) {
				int x = centre.getX() - reach + i;
				int z = centre.getZ() - reach + j;
				boolean onPad = x >= centre.getX() - half && x < centre.getX() + half && z >= centre.getZ() - half && z < centre.getZ() + half;
				surface[i][j] = onPad ? centre.getY() : level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
			}
		}
		List<String> problems = new ArrayList<>();
		for (int i = 0; i < size; i++) {
			for (int j = 0; j < size; j++) {
				if (i + 1 < size && Math.abs(surface[i][j] - surface[i + 1][j]) > STEP) {
					problems.add("step of " + (surface[i + 1][j] - surface[i][j]) + " at " + (centre.getX() - reach + i) + " " + (centre.getZ() - reach + j) + " going east");
				}
				if (j + 1 < size && Math.abs(surface[i][j] - surface[i][j + 1]) > STEP) {
					problems.add("step of " + (surface[i][j + 1] - surface[i][j]) + " at " + (centre.getX() - reach + i) + " " + (centre.getZ() - reach + j) + " going south");
				}
			}
		}
		finish(helper, problems.size() > 5 ? problems.subList(0, 5) : problems);
	}

	/**
	 * The grade holds on land the test world does not have: a hill 40 blocks above the pad on the east, a pit 40 below it on the
	 * west, and mesa terraces of four blocks on the south. Every step between neighbours is one block at most, the pad keeps its
	 * ground, and the same land gives the same grade.
	 */
	@GameTest(maxTicks = 100)
	public void theMarginGradesAHillAPitAndTerracesWithoutAStepOverOneBlock(GameTestHelper helper) {
		int pad = ColonyTuning.DEFAULT.padSize();
		int margin = ColonyTuning.DEFAULT.edgeMargin();
		int size = pad + 2 * margin;
		int ground = 64;
		int[][] natural = new int[size][size];
		for (int i = 0; i < size; i++) {
			for (int j = 0; j < size; j++) {
				natural[i][j] = ground + (i > size * 3 / 4 ? 40 : 0) - (i < size / 4 ? 40 : 0) + (j > size * 3 / 4 ? 4 * ((i / 5) % 2) : 0);
			}
		}
		int[][] first = ColonyEdge.heights(natural, 1000, -2000, ground);
		List<String> problems = new ArrayList<>();
		if (!java.util.Arrays.deepEquals(first, ColonyEdge.heights(natural, 1000, -2000, ground))) {
			problems.add("the same land gave two different grades");
		}
		for (int i = 0; i < size; i++) {
			for (int j = 0; j < size; j++) {
				boolean onPad = i >= margin && i < margin + pad && j >= margin && j < margin + pad;
				if (onPad && first[i][j] != ground) {
					problems.add("the pad's column " + i + " " + j + " is at " + first[i][j]);
				}
				if (i + 1 < size && Math.abs(first[i][j] - first[i + 1][j]) > STEP || j + 1 < size && Math.abs(first[i][j] - first[i][j + 1]) > STEP) {
					problems.add("a step over one block at " + i + " " + j);
				}
			}
		}
		// A hill reaches as high as a slope of one block a column allows over the margin.
		int rim = size - 1;
		if (first[rim][size / 2] < ground + 24) {
			problems.add("the hill's grade is " + first[rim][size / 2] + " at the rim, not near its natural " + natural[rim][size / 2]);
		}
		finish(helper, problems.size() > 5 ? problems.subList(0, 5) : problems);
	}

	/** The pad is the plain's own ground: regolith, with the rock of its layers scattered only on the rim. */
	@GameTest(maxTicks = 100)
	public void thePadCornersAreThePlainsRegolith(GameTestHelper helper) {
		ServerLevel level = server(helper).overworld();
		ColonySite.Placed colony = placed(helper);
		loadPad(level, colony);
		int half = ColonyTuning.DEFAULT.padSize() / 2;
		BlockPos centre = colony.center();
		List<String> problems = new ArrayList<>();
		for (int dx = 4; dx < 12; dx++) {
			for (int dz = 4; dz < 12; dz++) {
				BlockPos at = new BlockPos(centre.getX() - half + dx, centre.getY(), centre.getZ() - half + dz);
				if (!level.getBlockState(at).is(SurfaceBlocks.REGOLITH)) {
					problems.add(at.toShortString() + " is " + level.getBlockState(at));
				}
			}
		}
		finish(helper, problems.size() > 5 ? problems.subList(0, 5) : problems);
	}

	private static AABB pad(ColonySite.Placed colony) {
		int half = ColonyTuning.DEFAULT.padSize() / 2;
		BlockPos centre = colony.center();
		return new AABB(centre.getX() - half, centre.getY(), centre.getZ() - half, centre.getX() + half, centre.getY() + ColonyTuning.DEFAULT.clearHeight(), centre.getZ() + half);
	}

	/** Loads every chunk of the pad: a block read in an unloaded chunk answers void air. */
	private static void loadPad(ServerLevel level, ColonySite.Placed colony) {
		int half = ColonyTuning.DEFAULT.padSize() / 2;
		BlockPos centre = colony.center();
		for (int x = centre.getX() - half; x < centre.getX() + half; x += 16) {
			for (int z = centre.getZ() - half; z < centre.getZ() + half; z += 16) {
				level.getChunk(x >> 4, z >> 4);
			}
		}
		level.getChunk((centre.getX() + half - 1) >> 4, (centre.getZ() + half - 1) >> 4);
	}

	private static MinecraftServer server(GameTestHelper helper) {
		return helper.getLevel().getServer();
	}

	private static ColonySite.Placed placed(GameTestHelper helper) {
		Optional<ColonySite.Placed> colony = Colony.placed(server(helper));
		return colony.orElseThrow(() -> failure(helper, "the colony was not built when the server started"));
	}

	private static void finish(GameTestHelper helper, List<String> problems) {
		if (!problems.isEmpty()) {
			throw helper.assertionException(Component.literal(problems.size() + " problem(s):\n  " + String.join("\n  ", problems)));
		}
		helper.succeed();
	}

	// assertionException(String, Object...) leaves the placeholders unfilled in the report.
	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}
}
