package io.github.pkeppeler.deepcharter.test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.colony.ColonyTuning;
import io.github.pkeppeler.deepcharter.colony.FounderStatue;
import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.CreatureTuning;
import io.github.pkeppeler.deepcharter.creature.LamplessFigure;
import io.github.pkeppeler.deepcharter.creature.LamplessFigures;
import io.github.pkeppeler.deepcharter.hangar.HangarData;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ColonyChunks;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.UnloadedChunk;
import io.github.pkeppeler.deepcharter.test.support.WorldData;

/**
 * Server GameTests for #380, the audit of gameplay decisions that ask an entity query. A query sees only the entities of loaded
 * chunks, so "none found" can mean "not loaded" (m2-rules.md). Each test here asks the question while the chunk that holds the
 * answer is not loaded, through {@link UnloadedChunk}; the decision must come from saved data. A test uses columns of its own.
 * The figure caps need no saved data, because a figure is not saved; one test pins that.
 */
public class EntityQueryAuditTest {
	private static final int CYCLE_TICKS = 2 * FarChunks.AWAIT_BUDGET_TICKS + 400;
	private static final CreatureTuning CREATURES = CreatureTuning.DEFAULT;
	private static final UUID STALE_UUID = UUID.fromString("0f3380e1-2380-4380-8380-000000000380");
	/** The UUID as the int array that a summon command reads. */
	private static final UUID OTHER_UUID = UUID.fromString("0f3380e1-2380-4380-8380-000000000381");
	private static final String OTHER_UUID_NBT = "[I;" + (int) (OTHER_UUID.getMostSignificantBits() >> 32) + "," + (int) OTHER_UUID.getMostSignificantBits() + ","
			+ (int) (OTHER_UUID.getLeastSignificantBits() >> 32) + "," + (int) OTHER_UUID.getLeastSignificantBits() + "]";
	private static final String STALE_UUID_NBT = "[I;" + (int) (STALE_UUID.getMostSignificantBits() >> 32) + "," + (int) STALE_UUID.getMostSignificantBits() + ","
			+ (int) (STALE_UUID.getLeastSignificantBits() >> 32) + "," + (int) STALE_UUID.getLeastSignificantBits() + "]";

	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}

	/** Holds once the entities saved with the chunk at {@code pos} have loaded into the world, which is later than the chunk ticking. */
	private static BooleanSupplier entitiesLoaded(ServerLevel level, BlockPos pos) {
		ChunkPos chunk = ChunkPos.containing(pos);
		return () -> level.areEntitiesActuallyLoadedAndTicking(chunk);
	}

	/** The price of a pod rises with the pods a charter has, so a pod in a chunk that is not loaded must still count. */
	@GameTest(maxTicks = CYCLE_TICKS)
	public void aCharterCountsItsPodsWhoseChunkIsNotLoaded(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel level = server.overworld();
		BlockPos far = new BlockPos(16_000, 140, 16_000);
		CharterId charter = CharterId.random();
		UUID[] pod = {null};
		UnloadedChunk.cycle(helper, level, far, new UnloadedChunk.Stages(
				() -> {
					PodEntity made = PodRegistry.POD.create(level, EntitySpawnReason.TRIGGERED);
					made.setPos(Vec3.atBottomCenterOf(far));
					PodComponents.register(made, charter);
					level.addFreshEntity(made);
					pod[0] = made.getUUID();
				},
				() -> {
					int counted = HangarData.get(server).podCount(charter);
					if (counted != 1) {
						throw failure(helper, "the charter owns 1 pod, in a chunk that is not loaded; the hangar counted %d", counted);
					}
				},
				() -> level.getEntity(pod[0]) != null, () -> "the charter's pod",
				() -> level.getEntity(pod[0]).discard()));
	}

	/**
	 * The caps on figures count them with an entity query, which is right only because a figure is not saved: it is gone when its chunk
	 * unloads, so no figure ever stands in a chunk that is not loaded. If figures are ever saved, the caps must move to saved data.
	 */
	@GameTest(maxTicks = CYCLE_TICKS)
	public void aFigureIsGoneWhenItsChunkUnloadsSoTheCapsNeedNoSavedCount(GameTestHelper helper) {
		ServerLevel level = helper.getLevel().getServer().getLevel(Level.NETHER);
		BlockPos far = new BlockPos(16_040, 100, 16_040);
		List<UUID> figures = new ArrayList<>();
		UnloadedChunk.cycle(helper, level, far, new UnloadedChunk.Stages(
				() -> {
					floor(level, far, 3);
					for (int i = 0; i < CREATURES.maxPerLevel(); i++) {
						LamplessFigure figure = CreatureRegistry.LAMPLESS_FIGURE.create(level, EntitySpawnReason.EVENT);
						figure.setPos(Vec3.atBottomCenterOf(far.above()));
						figure.setNoAi(true);
						level.addFreshEntity(figure);
						figures.add(figure.getUUID());
					}
					if (LamplessFigures.levelHasRoom(level, false)) {
						throw failure(helper, "the level holds its %d figures and still has room for another", CREATURES.maxPerLevel());
					}
				},
				() -> {
					if (!LamplessFigures.levelHasRoom(level, false)) {
						throw failure(helper, "the figures of a chunk that unloaded still use up the level's room");
					}
				},
				entitiesLoaded(level, far), () -> "the chunk's entities to load",
				() -> {
					long back = figures.stream().filter(id -> level.getEntity(id) != null).count();
					if (back != 0) {
						throw failure(helper, "%d figures came back with their chunk: they are saved now, and the caps must count them from saved data", back);
					}
				}));
	}

	/** The rule says a query sees only entity-ticking chunks; here a chunk that is loaded and does not tick shows what the query sees. */
	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 400)
	public void aQuerySeesEntitiesOfALoadedChunkThatDoesNotTick(GameTestHelper helper) {
		ServerLevel level = helper.getLevel().getServer().overworld();
		BlockPos far = new BlockPos(16_300, 140, 16_300);
		ChunkPos chunk = ChunkPos.containing(far);
		UUID[] pod = {null};
		boolean[] placed = {false};
		FarChunks.awaitEntityTicking(helper, level, far, () -> {
			PodEntity made = PodRegistry.POD.create(level, EntitySpawnReason.TRIGGERED);
			made.setPos(Vec3.atBottomCenterOf(far));
			level.addFreshEntity(made);
			pod[0] = made.getUUID();
			// A ticket of radius 0 holds the chunk loaded and not ticking once the force is let go.
			level.getChunkSource().addTicketWithRadius(TicketType.PORTAL, chunk, 0);
			level.setChunkForced(chunk.x(), chunk.z(), false);
			placed[0] = true;
		});
		helper.succeedWhen(() -> {
			if (!placed[0] || level.isPositionEntityTicking(far)) {
				throw failure(helper, "waiting for the chunk to stop ticking");
			}
			if (level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) == null) {
				throw failure(helper, "the chunk is not loaded, so it shows nothing about a chunk that is loaded and does not tick");
			}
			if (level.getEntitiesOfClass(PodEntity.class, new AABB(far).inflate(4)).stream().noneMatch(found -> found.getUUID().equals(pod[0]))) {
				throw failure(helper, "a query did not see the pod of a chunk that is loaded and does not tick");
			}
			level.getEntity(pod[0]).discard();
			level.getChunkSource().removeTicketWithRadius(TicketType.PORTAL, chunk, 0);
		});
	}

	/** The Host's hands are given again while the chunk of the first pair is not loaded: only the new pair may stand. */
	@GameTest(maxTicks = CYCLE_TICKS)
	public void handsGivenAgainWhileTheFirstPairsChunkIsNotLoadedLeaveOnlyTheNewPair(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel level = server.overworld();
		ColonySite real = ColonySite.get(server);
		ColonySite moved = shifted(real, 20_000, 20_000);
		BlockPos statue = moved.placed().orElseThrow().anchors().get(ColonyAnchor.STATUE);
		AABB around = new AABB(statue).inflate(6);
		UnloadedChunk.cycle(helper, level, statue, new UnloadedChunk.Stages(
				() -> WorldData.with(server, ColonySite.TYPE, moved, () -> FounderStatue.restoreHands(server)),
				() -> WorldData.with(server, ColonySite.TYPE, moved, () -> FounderStatue.restoreHands(server)),
				entitiesLoaded(level, statue), () -> "the first pair of hands",
				() -> {
					List<Display.BlockDisplay> hands = level.getEntitiesOfClass(Display.BlockDisplay.class, around, display -> display.getBlockState().equals(FounderStatue.handsState()));
					hands.forEach(Entity::discard);
					if (hands.size() != 1) {
						throw failure(helper, "the Founder was given his hands twice: %d pairs stand, not 1", hands.size());
					}
				}));
	}

	/**
	 * A display that an earlier build of the colony left, and that a rebuild could not find, discards itself when its chunk loads. A display
	 * numbered for another place is not the colony's to retire, so it stays.
	 */
	@GameTest(maxTicks = CYCLE_TICKS)
	public void aDisplayOfAnEarlierColonyBuildDiscardsItselfWhenItsChunkLoads(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel level = server.overworld();
		BlockPos centre = Colony.placed(server).orElseThrow(() -> failure(helper, "the colony was not built")).center();
		BlockPos far = new BlockPos(16_200, 140, 16_200);
		UnloadedChunk.cycle(helper, level, far, new UnloadedChunk.Stages(
				() -> {
					summonDisplay(server, far, STALE_UUID_NBT, String.format("deepcharter_colony_build_%d_%d_%d_0", centre.getX(), centre.getY(), centre.getZ()));
					summonDisplay(server, far, OTHER_UUID_NBT, "deepcharter_colony_build_90000_0_90000_0");
				},
				() -> {
				},
				entitiesLoaded(level, far), () -> "the displays",
				() -> {
					Entity other = level.getEntity(OTHER_UUID);
					if (other != null) {
						other.discard();
					}
					Entity left = level.getEntity(STALE_UUID);
					if (left != null) {
						left.discard();
						throw failure(helper, "a display of build 0 of the colony survived the load of its chunk, though the colony is of a later build");
					}
					if (other == null) {
						throw failure(helper, "a display numbered for another place was voided by the colony's build");
					}
				}));
	}

	private static void summonDisplay(MinecraftServer server, BlockPos at, String uuidNbt, String tag) {
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), String.format(
				"summon minecraft:block_display %d %d %d {UUID:%s,Tags:[\"%s\"]}", at.getX(), at.getY(), at.getZ(), uuidNbt, tag));
	}

	/**
	 * Hands given at one statue never void the pair at another: the pair number is scoped to its place. A chunk with the first pair is
	 * not loaded while the second statue is given its hands, as a work order of another test does at the same time.
	 */
	@GameTest(maxTicks = CYCLE_TICKS)
	public void handsGivenAtOneStatueLeaveThePairAtAnotherStanding(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel level = server.overworld();
		ColonySite first = shifted(ColonySite.get(server), 40_000, 40_000);
		ColonySite second = shifted(ColonySite.get(server), 50_000, 50_000);
		BlockPos statue = first.placed().orElseThrow().anchors().get(ColonyAnchor.STATUE);
		AABB around = new AABB(statue).inflate(6);
		UnloadedChunk.cycle(helper, level, statue, new UnloadedChunk.Stages(
				() -> WorldData.with(server, ColonySite.TYPE, first, () -> FounderStatue.restoreHands(server)),
				() -> WorldData.with(server, ColonySite.TYPE, second, () -> FounderStatue.restoreHands(server)),
				entitiesLoaded(level, statue), () -> "the first pair of hands",
				() -> {
					List<Display.BlockDisplay> hands = level.getEntitiesOfClass(Display.BlockDisplay.class, around, display -> display.getBlockState().equals(FounderStatue.handsState()));
					hands.forEach(Entity::discard);
					if (hands.size() != 1) {
						throw failure(helper, "giving the Host at another statue his hands changed the pair here: %d pairs stand, not 1", hands.size());
					}
				}));
	}

	/** A pod that is registered and then refused entry to the world is not one of the charter's, so a failed purchase leaves the price alone. */
	@GameTest
	public void aRegisteredPodThatNeverEntersTheWorldIsNotCounted(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		CharterId charter = CharterId.random();
		PodEntity pod = PodRegistry.POD.create(helper.getLevel(), EntitySpawnReason.TRIGGERED);
		PodComponents.register(pod, charter);
		if (HangarData.get(server).podCount(charter) != 0) {
			throw failure(helper, "a pod that never entered the world counts for its charter");
		}
		pod.setPos(helper.absoluteVec(new Vec3(2.5, 2, 2.5)));
		helper.getLevel().addFreshEntity(pod);
		try {
			if (HangarData.get(server).podCount(charter) != 1) {
				throw failure(helper, "a pod in the world should count once, counted %d", HangarData.get(server).podCount(charter));
			}
		} finally {
			pod.discard();
		}
		helper.succeed();
	}

	/**
	 * The (a) comments rely on the area round a player ticking at the server's lowest simulation distance, 3. The worst reach is a console
	 * 6 blocks from the player with a wreck 24 blocks beyond it, so this puts a pod 30 blocks from a player standing at a chunk's edge.
	 */
	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 200)
	public void anEntityThirtyBlocksFromAPlayerTicksAtTheLowestSimulationDistance(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel level = server.getLevel(Level.NETHER);
		int before = server.getPlayerList().getSimulationDistance();
		server.getPlayerList().setSimulationDistance(3);
		MockPlayer mock = MockPlayers.join(helper, "simulation-distance");
		// The player stands in the last block column of its chunk, so 30 blocks east is the far end of the second chunk beyond.
		Vec3 at = new Vec3(17_007.5, 100, 17_000.5);
		BlockPos podPos = BlockPos.containing(at.x + 30, at.y, at.z);
		FarChunks.Deadline deadline = FarChunks.deadline();
		boolean[] moved = {false};
		PodEntity[] pod = {null};
		Runnable restore = () -> {
			server.getPlayerList().setSimulationDistance(before);
			mock.leave();
			if (pod[0] != null) {
				pod[0].discard();
			}
		};
		helper.onEachTick(() -> {
			try {
				if (!moved[0]) {
					mock.teleportTo(level, at, 0, 0);
					moved[0] = true;
					return;
				}
				boolean ticking = level.isPositionEntityTicking(podPos);
				deadline.await(helper, level, ticking, () -> "the chunk 30 blocks from the player did not tick at simulation distance 3");
				if (!ticking) {
					return;
				}
				pod[0] = PodRegistry.POD.create(level, EntitySpawnReason.TRIGGERED);
				pod[0].setPos(Vec3.atBottomCenterOf(podPos));
				level.addFreshEntity(pod[0]);
				if (level.getEntitiesOfClass(PodEntity.class, new AABB(podPos).inflate(2)).isEmpty()) {
					throw failure(helper, "a query did not see a pod 30 blocks from a player at simulation distance 3");
				}
				restore.run();
				helper.succeed();
			} catch (RuntimeException e) {
				restore.run();
				throw e;
			}
		});
	}

	/** The displays that the colony's build made carry the number of the build, as the tag that lets a later build find them in a chunk that is not loaded. */
	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 200)
	public void everyDisplayOnThePadCarriesTheBuildThatMadeIt(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel level = server.overworld();
		ColonySite.Placed colony = Colony.placed(server).orElseThrow(() -> failure(helper, "the colony was not built"));
		ColonyChunks.whenTicking(helper, level, ColonyChunks.of(colony), () -> ColonyChunks.displaysOnThePad(level, colony) == ColonyChunks.displaysOf(server), () -> {
			Set<String> builds = new HashSet<>();
			int displays = 0;
			for (Display.BlockDisplay display : level.getEntitiesOfClass(Display.BlockDisplay.class, new AABB(colony.center()).inflate(ColonyTuning.DEFAULT.padSize() / 2.0, ColonyTuning.DEFAULT.clearHeight(), ColonyTuning.DEFAULT.padSize() / 2.0),
					each -> !each.getBlockState().equals(FounderStatue.handsState()))) {
				displays++;
				List<String> tags = display.entityTags().stream().filter(tag -> tag.startsWith("deepcharter_colony_build_")).toList();
				if (tags.size() != 1) {
					throw failure(helper, "a display of the colony at %s has the build tags %s, not one", display.blockPosition().toShortString(), tags);
				}
				builds.add(tags.getFirst());
			}
			if (displays == 0 || builds.size() != 1) {
				throw failure(helper, "%d displays stand on the pad, made by the builds %s, not one", displays, builds);
			}
		});
	}

	/** Stone under a square of {@code half} blocks round {@code centre}, for a figure to stand on. */
	private static void floor(ServerLevel level, BlockPos centre, int half) {
		for (int dx = -half; dx <= half; dx++) {
			for (int dz = -half; dz <= half; dz++) {
				level.setBlock(centre.offset(dx, 0, dz), Blocks.STONE.defaultBlockState(), 3);
			}
		}
	}

	/** A copy of the colony's record with its pad moved {@code dx}, {@code dz} blocks, for a build at another place. */
	private static ColonySite shifted(ColonySite site, int dx, int dz) {
		CompoundTag saved = ((CompoundTag) ColonySite.CODEC.encodeStart(NbtOps.INSTANCE, site).getOrThrow()).copy();
		CompoundTag colony = saved.getCompound("colony").orElseThrow();
		shift(colony, "center", dx, dz);
		CompoundTag anchors = colony.getCompound("anchors").orElseThrow();
		for (String key : List.copyOf(anchors.keySet())) {
			shift(anchors, key, dx, dz);
		}
		return ColonySite.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
	}

	private static void shift(CompoundTag tag, String key, int dx, int dz) {
		int[] pos = tag.getIntArray(key).orElseThrow();
		tag.putIntArray(key, new int[] {pos[0] + dx, pos[1], pos[2] + dz});
	}
}
