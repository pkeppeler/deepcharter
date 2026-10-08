package io.github.pkeppeler.deepcharter.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonyBlocks;
import io.github.pkeppeler.deepcharter.colony.ColonyBuilder;
import io.github.pkeppeler.deepcharter.colony.ColonyEvents;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.colony.ColonyTuning;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalBlockEntity;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.LogCapture;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;

/**
 * Server GameTests for #64: the colony is built once at world spawn, its anchors persist, its terminals start offline, the world
 * spawn is the Continuity Office, and the Conduit is in every layer and cannot be broken. The colony is built when the test
 * server starts, so these tests read the world as it is. A test that swaps the world's {@link ColonySite} puts it back in the
 * same tick.
 */
public class ColonyTest {
	private static final int DRILL_MARGIN_TICKS = 1000;
	private static final int DRILL_MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + DRILL_MARGIN_TICKS;
	private static final Input FORWARD = new Input(true, false, false, false, false, false, false);
	private static final float EAST = -90f;
	/** The world spawn right after the colony was built. */
	private static final AtomicReference<GlobalPos> SPAWN_AT_START = new AtomicReference<>();

	static {
		ColonyEvents.BUILT.register((server, colony) -> SPAWN_AT_START.set(server.getRespawnData().globalPos()));
	}

	@GameTest
	public void theColonyIsBuiltOnceAndASecondStartDoesNotRebuildIt(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		ServerLevel overworld = server.overworld();
		ColonySite.Placed before = placed(helper);
		if (!before.anchors().keySet().containsAll(List.of(ColonyAnchor.values()))) {
			throw failure(helper, "the colony is missing anchors: %s", before.anchors());
		}
		// A ruin that was changed after the build must stay as it is: a rebuild would put the statue back.
		BlockPos statue = before.anchors().get(ColonyAnchor.STATUE);
		BlockState original = overworld.getBlockState(statue);
		overworld.setBlock(statue, Blocks.AIR.defaultBlockState(), 2);
		try {
			if (ColonyBuilder.buildIfNeeded(server)) {
				throw failure(helper, "a second start built the colony again");
			}
			if (!overworld.getBlockState(statue).isAir()) {
				throw failure(helper, "a second start rebuilt the colony: the statue block is back");
			}
			if (!placed(helper).equals(before)) {
				throw failure(helper, "a second start changed the colony's record");
			}
		} finally {
			overworld.setBlock(statue, original, 2);
		}
		helper.succeed();
	}

	@GameTest
	public void theAnchorsPersistThroughASaveAndARestart(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		ColonySite.Placed before = placed(helper);
		Path dir = tempDir();
		try {
			saveCopy(server, dir);
			try (SavedDataStorage second = storage(server, dir)) {
				ColonySite reloaded = second.computeIfAbsent(ColonySite.TYPE);
				if (!reloaded.placed().equals(Optional.of(before))) {
					throw failure(helper, "the anchors changed over a save: %s, reloaded %s", before, reloaded.placed());
				}
				// The restart itself: the reloaded record is the world's, and the start finds the colony built.
				ColonySite world = ColonySite.get(server);
				server.getDataStorage().set(ColonySite.TYPE, reloaded);
				try {
					if (ColonyBuilder.buildIfNeeded(server)) {
						throw failure(helper, "a start with the reloaded record built the colony again");
					}
				} finally {
					server.getDataStorage().set(ColonySite.TYPE, world);
				}
			}
		} finally {
			deleteTree(dir);
		}
		helper.succeed();
	}

	/**
	 * Vanilla runs saved data through its fixers by {@code DataVersion}, and ours carries a version of its own, so the fixers
	 * must find nothing to change (ADR 0007). Keep it green on every Minecraft bump.
	 */
	@GameTest
	public void aFileFromAnOlderMinecraftLoadsUnchanged(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		int olderDataVersion = 4000;
		Path dir = tempDir();
		try {
			saveCopy(server, dir);
			Path file = savedFile(dir);
			CompoundTag stamped = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
			if (NbtUtils.getDataVersion(stamped) <= olderDataVersion) {
				throw failure(helper, "the test needs a saved DataVersion above %s, got %s", olderDataVersion, NbtUtils.getDataVersion(stamped));
			}
			Tag savedBody = stamped.get("data");
			NbtIo.writeCompressed(NbtUtils.addDataVersion(stamped, olderDataVersion), file);
			try (SavedDataStorage second = storage(server, dir)) {
				ColonySite loaded = second.computeIfAbsent(ColonySite.TYPE);
				Tag reloaded = ColonySite.CODEC.encodeStart(NbtOps.INSTANCE, loaded).getOrThrow();
				if (!reloaded.equals(savedBody) || !loaded.placed().equals(Optional.of(placed(helper)))) {
					throw failure(helper, "the fixer changed the colony: saved %s, loaded %s", savedBody, reloaded);
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} finally {
			deleteTree(dir);
		}
		helper.succeed();
	}

	@GameTest
	public void dataOfAnotherVersionIsKeptAndTheCallbackPathsSkipIt(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		Tag current = ColonySite.CODEC.encodeStart(NbtOps.INSTANCE, ColonySite.get(server)).getOrThrow();
		CompoundTag future = ((CompoundTag) current).copy();
		future.putInt("version", 7741);
		ColonySite unreadable = ColonySite.CODEC.parse(NbtOps.INSTANCE, future).getOrThrow();
		if (unreadable.isReadable() || !ColonySite.CODEC.encodeStart(NbtOps.INSTANCE, unreadable).getOrThrow().equals(future)) {
			throw failure(helper, "data of version 7741 should load as unreadable and be written back unchanged");
		}
		try {
			unreadable.isBuilt();
			throw failure(helper, "an explicit use of unreadable data should throw");
		} catch (IllegalStateException expected) {
			// Explicit API calls throw; tick, join and callback paths do not.
		}

		LogCapture log = LogCapture.start("version 7741");
		ColonySite world = ColonySite.get(server);
		server.getDataStorage().set(ColonySite.TYPE, unreadable);
		try {
			// A start, the lookups of other features, and a chunk load (the far chunk is made by asking for it).
			if (ColonyBuilder.buildIfNeeded(server) || ColonyBuilder.buildIfNeeded(server)) {
				throw failure(helper, "a colony was built over unreadable data");
			}
			if (Colony.placed(server).isPresent() || Colony.anchor(server, ColonyAnchor.CONDUIT).isPresent() || Colony.respawnPoint(server).isPresent()) {
				throw failure(helper, "unreadable data answered a lookup");
			}
			server.getLevel(LayerChain.dimension(1)).getChunk(7000 >> 4, 7000 >> 4);
		} finally {
			server.getDataStorage().set(ColonySite.TYPE, world);
		}
		if (log.errors().size() != 1) {
			throw failure(helper, "unreadable data should be logged once, was logged %s: %s", log.errors().size(), log.errors());
		}
		helper.succeed();
	}

	@GameTest
	public void theTerminalsStartOffline(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		ServerLevel overworld = server.overworld();
		ColonySite.Placed placed = placed(helper);
		for (TerminalType type : List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR, TerminalTypes.UPGRADE_TERMINAL, TerminalTypes.REPAIR_STATION)) {
			ColonyAnchor anchor = ColonyAnchor.forTerminal(type).orElseThrow();
			BlockPos pos = placed.anchors().get(anchor);
			if (!overworld.getBlockState(pos).is(type.block())
					|| !(overworld.getBlockEntity(pos) instanceof TerminalBlockEntity terminal) || terminal.type() != type) {
				throw failure(helper, "%s should stand on its plinth at %s, the block there is %s", type.id(), pos.toShortString(), overworld.getBlockState(pos));
			}
			if (RepairState.get(server).repaired(type)) {
				throw failure(helper, "%s should start offline (unrepaired)", type.id());
			}
		}
		// The contract terminal is #72's. Until it registers one, its plinth is bare; after, the colony stands one on it.
		BlockPos contract = placed.anchors().get(ColonyAnchor.CONTRACT_TERMINAL);
		BlockState onPlinth = overworld.getBlockState(contract);
		if (!onPlinth.isAir() && TerminalTypes.of(onPlinth.getBlock()).isEmpty()) {
			throw failure(helper, "the contract plinth at %s holds %s", contract.toShortString(), onPlinth);
		}
		if (overworld.getBlockState(contract.below()).isAir()) {
			throw failure(helper, "the contract terminal has no plinth under %s", contract.toShortString());
		}
		helper.succeed();
	}

	@GameTest
	public void theWorldSpawnIsTheContinuityOffice(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		ServerLevel overworld = server.overworld();
		BlockPos office = placed(helper).anchors().get(ColonyAnchor.CONTINUITY_OFFICE);
		// The game test server moves the world spawn to a random far place before its first test, so the spawn the colony set
		// is the one seen when it was built.
		GlobalPos spawn = SPAWN_AT_START.get();
		if (spawn == null || !spawn.dimension().equals(Level.OVERWORLD) || !spawn.pos().equals(office)) {
			throw failure(helper, "the world spawn when the colony was built was %s, expected %s in the overworld", spawn, office);
		}
		if (!Colony.respawnPoint(server).equals(Optional.of(GlobalPos.of(Level.OVERWORLD, office)))) {
			throw failure(helper, "Colony.respawnPoint should give the Continuity Office, gave %s", Colony.respawnPoint(server));
		}
		if (server.getGameRules().get(GameRules.RESPAWN_RADIUS) != 0) {
			throw failure(helper, "the respawn radius should be 0 so a new player stands in the office, it is %s", server.getGameRules().get(GameRules.RESPAWN_RADIUS));
		}
		if (!overworld.getBlockState(office.below()).isSolid() || !overworld.getBlockState(office).isAir() || !overworld.getBlockState(office.above()).isAir()) {
			throw failure(helper, "a player cannot stand at the world spawn %s", office.toShortString());
		}
		helper.succeed();
	}

	@GameTest
	public void aSpawnInWaterMakesTheBuildSearchForDryGround(GameTestHelper helper) {
		ServerLevel level = server(helper).overworld();
		// A lake 181 blocks across on the flat ground, far from every other test.
		int lake = 9000;
		int half = 90;
		level.getChunk(lake >> 4, lake >> 4);
		int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, lake, lake);
		for (int x = lake - half; x <= lake + half; x++) {
			for (int z = lake - half; z <= lake + half; z++) {
				level.setBlock(new BlockPos(x, surface, z), Blocks.WATER.defaultBlockState(), 2);
			}
		}
		BlockPos wet = new BlockPos(lake, 0, lake);
		Optional<BlockPos> found = ColonyBuilder.findDryGround(level, wet);
		if (found.isEmpty()) {
			throw failure(helper, "no dry ground was found beside a lake of %s blocks", 2 * half + 1);
		}
		BlockPos centre = found.get();
		int padHalf = ColonyTuning.DEFAULT.padSize() / 2;
		if (Math.abs(centre.getX() - lake) <= half + padHalf - 1 && Math.abs(centre.getZ() - lake) <= half + padHalf - 1) {
			throw failure(helper, "the dry ground %s still reaches into the lake", centre.toShortString());
		}
		if (!level.getFluidState(new BlockPos(centre.getX(), level.getHeight(Heightmap.Types.WORLD_SURFACE, centre.getX(), centre.getZ()) - 1, centre.getZ())).isEmpty()) {
			throw failure(helper, "the centre %s of the dry ground is in water", centre.toShortString());
		}
		// On dry ground the spawn itself is the centre.
		BlockPos dry = new BlockPos(9500, 0, 9500);
		if (!ColonyBuilder.findDryGround(level, dry).equals(Optional.of(dry))) {
			throw failure(helper, "a spawn on dry ground should be the centre, got %s", ColonyBuilder.findDryGround(level, dry));
		}
		helper.succeed();
	}

	@GameTest
	public void aBuildThatStoppedHalfWayIsBuiltAgainAtTheSameGround(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		ServerLevel overworld = server.overworld();
		ColonySite.Placed before = placed(helper);
		Tag current = ColonySite.CODEC.encodeStart(NbtOps.INSTANCE, ColonySite.get(server)).getOrThrow();
		CompoundTag unfinished = ((CompoundTag) current).copy();
		unfinished.getCompound("colony").orElseThrow().putBoolean("finished", false);
		ColonySite interrupted = ColonySite.CODEC.parse(NbtOps.INSTANCE, unfinished).getOrThrow();
		if (interrupted.isBuilt() || interrupted.started().isEmpty()) {
			throw failure(helper, "the interrupted record should be begun and not finished");
		}
		ColonySite world = ColonySite.get(server);
		var spawn = server.getRespawnData();
		server.getDataStorage().set(ColonySite.TYPE, interrupted);
		try {
			if (!ColonyBuilder.buildIfNeeded(server)) {
				throw failure(helper, "an unfinished colony should be built again");
			}
			if (!ColonySite.get(server).placed().equals(Optional.of(before))) {
				throw failure(helper, "the rebuilt colony differs: %s, expected %s", ColonySite.get(server).placed(), before);
			}
			// Built over itself at the recorded ground: the statue stands where it did, and no second pad sits above the first.
			BlockPos statue = before.anchors().get(ColonyAnchor.STATUE);
			if (!overworld.getBlockState(statue).is(Blocks.STONE_BRICKS)) {
				throw failure(helper, "the statue's pedestal is not at %s after the rebuild", statue.toShortString());
			}
			for (BlockPos corner : List.of(before.center().offset(9, 1, 9), before.center().offset(-30, 1, -30), before.center().offset(28, 1, 28))) {
				if (!overworld.getBlockState(corner).isAir()) {
					throw failure(helper, "a second pad sits above the first at %s: %s", corner.toShortString(), overworld.getBlockState(corner));
				}
			}
		} finally {
			server.getDataStorage().set(ColonySite.TYPE, world);
			server.setRespawnData(LevelData.RespawnData.of(spawn.dimension(), spawn.pos(), spawn.yaw(), spawn.pitch()));
		}
		helper.succeed();
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 300)
	public void aBreachCrossingAtTheConduitLeavesItWholeAndArrivesBesideIt(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		BlockPos centre = placed(helper).anchors().get(ColonyAnchor.CONDUIT);
		int radius = ColonyTuning.DEFAULT.conduitRadius();
		// The arrival pocket reaches pocketRadius blocks out, so from here it touches the casing.
		double x = centre.getX() + radius + LayerTuning.DEFAULT.pocketRadius() + 0.5;
		double z = centre.getZ() + 0.5;
		ServerLevel surface = server.overworld();
		ServerLevel one = level(helper, 1);
		for (int y = surface.getMinY(); y <= surface.getMinY() + 10; y++) {
			surface.setBlock(BlockPos.containing(x, y, z), Blocks.AIR.defaultBlockState(), 3);
		}
		MockPlayer mock = MockPlayers.join(helper, "conduit-breach");
		mock.teleportTo(surface, new Vec3(x, surface.getMinY() + 6, z), 0, 0);
		int[] ticking = {0};
		FarChunks.awaitEntityTicking(helper, surface, mock.player().blockPosition(), () -> ticking[0]++);
		for (BlockPos corner : corners(centre)) {
			FarChunks.awaitEntityTicking(helper, one, corner.atY(one.getMinY() + 8), () -> ticking[0]++);
		}
		helper.onEachTick(() -> {
			ServerPlayer player = mock.player();
			if (ticking[0] == 5 && player.level().dimension().equals(surface.dimension())) {
				player.setPos(player.getX(), player.getY() - 1, player.getZ());
			}
		});
		helper.succeedWhen(() -> {
			ServerPlayer player = mock.player();
			if (!player.level().dimension().equals(one.dimension())) {
				throw failure(helper, "waiting for the crossing into layer 1");
			}
			if (Math.abs(player.getX() - x) < 0.01 && Math.abs(player.getZ() - z) < 0.01) {
				throw failure(helper, "the player arrived at the requested column %s, which touches the casing", player.blockPosition().toShortString());
			}
			for (int cx = centre.getX() - radius; cx <= centre.getX() + radius; cx++) {
				for (int cz = centre.getZ() - radius; cz <= centre.getZ() + radius; cz++) {
					for (int y = one.getMaxY() - LayerTuning.DEFAULT.pocketHeight() - 1; y <= one.getMaxY(); y++) {
						if (!one.getBlockState(new BlockPos(cx, y, cz)).is(ColonyBlocks.CONDUIT)) {
							throw failure(helper, "the crossing carved the casing at (%s, %s, %s)", cx, y, cz);
						}
					}
				}
			}
		});
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
	public void theConduitFillsEveryLayerWhenItsChunkLoads(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		BlockPos centre = placed(helper).anchors().get(ColonyAnchor.CONDUIT);
		int layers = LayerChain.count(server.registryAccess());
		int[] loaded = {0};
		for (int layer = 0; layer <= layers; layer++) {
			ServerLevel level = level(helper, layer);
			// The corners of the casing: they name every chunk that holds a part of it.
			for (BlockPos corner : corners(centre)) {
				FarChunks.awaitEntityTicking(helper, level, corner.atY(level.getMinY() + 8), () -> loaded[0]++);
			}
		}
		helper.succeedWhen(() -> {
			if (loaded[0] < (layers + 1) * 4) {
				throw failure(helper, "waiting for the Conduit's chunks to tick");
			}
			for (int layer = 0; layer <= layers; layer++) {
				ServerLevel level = level(helper, layer);
				int top = layer == LayerChain.SURFACE ? placed(helper).groundY() + ColonyTuning.DEFAULT.conduitStack() : level.getMaxY();
				int radius = ColonyTuning.DEFAULT.conduitRadius();
				for (int x = centre.getX() - radius; x <= centre.getX() + radius; x++) {
					for (int z = centre.getZ() - radius; z <= centre.getZ() + radius; z++) {
						for (int y = level.getMinY(); y <= top; y++) {
							if (!level.getBlockState(new BlockPos(x, y, z)).is(ColonyBlocks.CONDUIT)) {
								throw failure(helper, "layer %s has no Conduit at (%s, %s, %s)", layer, x, y, z);
							}
						}
					}
				}
				// Same X and Z in every layer, and nothing but the casing above its end in the overworld.
				if (layer == LayerChain.SURFACE && !level.getBlockState(new BlockPos(centre.getX(), top + 1, centre.getZ())).isAir()) {
					throw failure(helper, "the Conduit rises past its top in the overworld");
				}
			}
		});
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
	public void theConduitCannotBeBrokenByHandInAnyLayer(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		BlockPos centre = placed(helper).anchors().get(ColonyAnchor.CONDUIT);
		int layers = LayerChain.count(server.registryAccess());
		MockPlayer mock = MockPlayers.join(helper, "conduit-hands");
		int[] loaded = {0};
		for (int layer = 0; layer <= layers; layer++) {
			ServerLevel level = level(helper, layer);
			FarChunks.awaitEntityTicking(helper, level, centre.atY(level.getMinY() + 8), () -> loaded[0]++);
		}
		helper.succeedWhen(() -> {
			if (loaded[0] < layers + 1) {
				throw failure(helper, "waiting for the Conduit's chunks to tick");
			}
			ServerPlayer player = mock.player();
			// Creative while it waited at the join point inside the colony's foundation, where survival could suffocate.
			player.setGameMode(GameType.SURVIVAL);
			for (int layer = 0; layer <= layers; layer++) {
				ServerLevel level = level(helper, layer);
				player.teleportTo(level, centre.getX() + 0.5, level.getMinY() + 20, centre.getZ() + 4.5, Set.of(), 0, 0, true);
				int highest = layer == LayerChain.SURFACE ? placed(helper).groundY() + 5 : level.getMaxY() - 2;
				for (int y : new int[] {level.getMinY() + 5, level.getMinY() + 8, level.getMinY() + 20, highest}) {
					BlockPos pos = centre.atY(y);
					if (!level.getBlockState(pos).is(ColonyBlocks.CONDUIT)) {
						throw failure(helper, "layer %s has no Conduit at %s", layer, pos.toShortString());
					}
					if (level.getBlockState(pos).getDestroyProgress(player, level, pos) != 0.0F) {
						throw failure(helper, "a survival player makes progress on the Conduit in layer %s", layer);
					}
					player.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, Direction.UP, level.getMaxY(), 0);
					player.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, Direction.UP, level.getMaxY(), 1);
					if (!level.getBlockState(pos).is(ColonyBlocks.CONDUIT)) {
						throw failure(helper, "a survival player broke the Conduit in layer %s at %s", layer, pos.toShortString());
					}
				}
			}
		});
	}

	@GameTest(maxTicks = DRILL_MAX_TICKS)
	public void aPodCannotDrillTheConduitInLayerOne(GameTestHelper helper) {
		podMeetsTheConduit(helper, 1);
	}

	@GameTest(maxTicks = DRILL_MAX_TICKS)
	public void aPodCannotDrillTheConduitInLayerTwo(GameTestHelper helper) {
		podMeetsTheConduit(helper, 2);
	}

	/**
	 * A pod in a room west of the Conduit, pushing east into it. The pod must reach the casing, never start to drill, and leave
	 * every block of it standing.
	 */
	private static void podMeetsTheConduit(GameTestHelper helper, int layer) {
		ServerLevel level = level(helper, layer);
		BlockPos centre = placed(helper).anchors().get(ColonyAnchor.CONDUIT);
		int radius = ColonyTuning.DEFAULT.conduitRadius();
		int floor = 60;
		int roomFrom = centre.getX() - radius - 8;
		int roomTo = centre.getX() - radius - 1;
		// Stone under the room, air over it, and the casing east of it, which the chunk load has set.
		for (int x = roomFrom; x <= roomTo; x++) {
			for (int z = centre.getZ() - 4; z <= centre.getZ() + 4; z++) {
				level.setBlock(new BlockPos(x, floor - 1, z), Blocks.STONE.defaultBlockState(), 2);
				for (int y = floor; y <= floor + 10; y++) {
					level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
				}
			}
		}
		Vec3 start = new Vec3(roomFrom + 1.3, floor, centre.getZ() + 0.3);
		MockPlayer pilot = MockPlayers.join(helper, "conduit-drill-" + layer);
		pilot.teleportTo(level, start, EAST, 0f);
		PodEntity[] pod = {null};
		// The pod starts once every chunk it can meet ticks: its own and the ones that hold the casing.
		int[] ticking = {0};
		FarChunks.awaitEntityTicking(helper, level, BlockPos.containing(start), () -> ticking[0]++);
		for (BlockPos corner : corners(centre)) {
			FarChunks.awaitEntityTicking(helper, level, corner.atY(floor), () -> ticking[0]++);
		}
		helper.onEachTick(() -> {
			if (pod[0] == null && ticking[0] == 5) {
				PodEntity created = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
				created.setPos(start);
				level.addFreshEntity(created);
				if (!pilot.player().startRiding(created)) {
					throw failure(helper, "the pilot could not mount the pod");
				}
				pilot.setInput(FORWARD);
				pod[0] = created;
			}
			if (pod[0] == null) {
				return;
			}
			if (pod[0].drilling()) {
				throw failure(helper, "the pod started drilling the Conduit in layer %s at %s", layer, pod[0].position());
			}
			if (pod[0].tickCount < 300) {
				return;
			}
			if (!pod[0].horizontalCollision || pod[0].getX() < centre.getX() - radius - 3) {
				throw failure(helper, "the pod is not pressing against the Conduit, so the test proved nothing: %s", pod[0].position());
			}
			for (int x = centre.getX() - radius; x <= centre.getX() + radius; x++) {
				for (int z = centre.getZ() - radius; z <= centre.getZ() + radius; z++) {
					for (int y = floor; y <= floor + 10; y++) {
						if (!level.getBlockState(new BlockPos(x, y, z)).is(ColonyBlocks.CONDUIT)) {
							throw failure(helper, "a Conduit block at (%s, %s, %s) in layer %s was bored", x, y, z, layer);
						}
					}
				}
			}
			pod[0].discard();
			helper.succeed();
		});
	}

	private static List<BlockPos> corners(BlockPos centre) {
		int radius = ColonyTuning.DEFAULT.conduitRadius();
		return List.of(centre.offset(-radius, 0, -radius), centre.offset(radius, 0, -radius),
				centre.offset(-radius, 0, radius), centre.offset(radius, 0, radius));
	}

	private static MinecraftServer server(GameTestHelper helper) {
		return helper.getLevel().getServer();
	}

	private static ServerLevel level(GameTestHelper helper, int layer) {
		ServerLevel level = server(helper).getLevel(LayerChain.dimension(layer));
		if (level == null) {
			throw failure(helper, "dimension %s did not load", LayerChain.dimension(layer));
		}
		return level;
	}

	private static ColonySite.Placed placed(GameTestHelper helper) {
		return Colony.placed(server(helper)).orElseThrow(() -> failure(helper, "the colony was not built when the server started"));
	}

	// assertionException(String, Object...) leaves the placeholders unfilled in the report.
	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}

	/** Writes the world's colony record to a file of its own under {@code dir}. */
	private static void saveCopy(MinecraftServer server, Path dir) {
		ColonySite world = ColonySite.get(server);
		world.setDirty();
		try (SavedDataStorage first = storage(server, dir)) {
			first.set(ColonySite.TYPE, world);
			first.saveAndJoin();
		}
	}

	private static SavedDataStorage storage(MinecraftServer server, Path dir) {
		return new SavedDataStorage(dir, server.getFixerUpper(), server.registryAccess());
	}

	private static Path tempDir() {
		try {
			return Files.createTempDirectory("colony-saved-data");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static Path savedFile(Path dir) throws IOException {
		try (Stream<Path> files = Files.walk(dir)) {
			return files.filter(path -> path.toString().endsWith(".dat")).findFirst().orElseThrow();
		}
	}

	private static void deleteTree(Path dir) {
		try (Stream<Path> files = Files.walk(dir)) {
			for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(path);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
