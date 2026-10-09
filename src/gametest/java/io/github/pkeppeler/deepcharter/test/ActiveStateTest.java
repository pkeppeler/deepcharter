package io.github.pkeppeler.deepcharter.test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.charter.terminal.ContractTerminal;
import io.github.pkeppeler.deepcharter.colony.ColonyBlocks;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalActivity;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import io.github.pkeppeler.deepcharter.test.support.WorldData;
import io.github.pkeppeler.deepcharter.texture.TextureProperties;

/**
 * Server GameTests for #242: the {@code active} block-state property that picks a block's lit layers. A terminal shows whether its
 * type is online: a player places it right, a block set wrong is corrected on its next tick (its block entity agreeing), every
 * loaded terminal of a type lights when the type is repaired, and a reloaded chunk mends a stale one. Unreadable repair data leaves
 * it as it is and never throws. A Company lamp gives full light lit and none dark.
 *
 * <p>A test that repairs swaps in a fresh {@link RepairState} through {@link WorldData#with} and does all its work in that one tick.
 */
public class ActiveStateTest {
	private static final TerminalType PUMP = TerminalTypes.FUEL_PUMP;
	/** Far from every other test's chunks, so the chunk test can unload its own. */
	private static final BlockPos FAR = new BlockPos(-3536, 70, 3536);
	private static final Set<Long> UNLOADED = ConcurrentHashMap.newKeySet();
	private static final Set<Long> LOADED = ConcurrentHashMap.newKeySet();

	static {
		// Fabric events cannot be unregistered: these record only the chunk of FAR.
		ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
			if (chunk.getPos().equals(ChunkPos.containing(FAR))) {
				UNLOADED.add(chunk.getPos().pack());
			}
		});
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
			if (chunk.getPos().equals(ChunkPos.containing(FAR))) {
				LOADED.add(chunk.getPos().pack());
			}
		});
	}

	private static BlockPos place(GameTestHelper helper, BlockState state, int x) {
		BlockPos relative = new BlockPos(x, 1, 1);
		helper.setBlock(relative, state);
		return helper.absolutePos(relative);
	}

	private static void expectActive(GameTestHelper helper, BlockPos absolute, boolean expected, String why) {
		BlockState state = helper.getLevel().getBlockState(absolute);
		if (state.getValue(TextureProperties.ACTIVE) != expected) {
			throw helper.assertionException("%s: the terminal at %s should show active=%s, shows %s", why, absolute.toShortString(), expected, state);
		}
	}

	private static void expectBlockEntityAgrees(GameTestHelper helper, BlockPos absolute) {
		BlockEntity entity = helper.getLevel().getBlockEntity(absolute);
		BlockState state = helper.getLevel().getBlockState(absolute);
		if (entity == null || !entity.getBlockState().equals(state)) {
			throw helper.assertionException("the block entity at %s holds %s, the level %s", absolute.toShortString(),
					entity == null ? "nothing" : entity.getBlockState(), state);
		}
	}

	private static void repairDirectly(GameTestHelper helper, RepairState state, TerminalType type) {
		for (Item part : type.parts()) {
			if (state.insert(type, part).isPresent()) {
				throw helper.assertionException("inserting %s straight into the state should succeed", part);
			}
		}
	}

	private static MockPlayer standBy(GameTestHelper helper, String name, BlockPos absolute) {
		MockPlayer mock = MockPlayers.join(helper, name);
		Vec3 centre = Vec3.atCenterOf(absolute);
		mock.teleportTo(helper.getLevel(), new Vec3(centre.x + 2, centre.y - mock.player().getEyeHeight(), centre.z - 2), 0, 0);
		return mock;
	}

	/** Placed by a player, a terminal shows its type's state at once: no tick passes inside the swapped repair state. */
	@GameTest
	public void aPlayerPlacesATerminalDarkBeforeItsRepairAndLitAfter(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayer mock = standBy(helper, "active-placer", helper.absolutePos(new BlockPos(2, 1, 1)));
		try {
			mock.player().setGameMode(GameType.CREATIVE);
			RepairState fresh = new RepairState();
			WorldData.with(server, RepairState.TYPE, fresh, () -> {
				helper.placeAt(mock.player(), new ItemStack(PUMP.block().asItem()), new BlockPos(1, 0, 1), Direction.UP);
				expectActive(helper, helper.absolutePos(new BlockPos(1, 1, 1)), false, "a fuel pump placed before its repair");
				repairDirectly(helper, fresh, PUMP);
				helper.placeAt(mock.player(), new ItemStack(PUMP.block().asItem()), new BlockPos(3, 0, 1), Direction.UP);
				expectActive(helper, helper.absolutePos(new BlockPos(3, 1, 1)), true, "a fuel pump placed after its repair");
			});
		} finally {
			mock.leave();
		}
		helper.succeed();
	}

	/** Both fuel pumps light when the last part goes into one of them. */
	@GameTest
	public void theLastPartLightsEveryLoadedTerminalOfItsType(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayer mock = standBy(helper, "active-repairer", helper.absolutePos(new BlockPos(1, 1, 1)));
		try {
			ServerPlayer player = mock.player();
			player.setGameMode(GameType.SURVIVAL);
			if (Charters.found(server, player.getUUID(), "Lamp Co " + UUID.randomUUID().toString().substring(0, 8)).isPresent()) {
				throw helper.assertionException("founding a charter should succeed");
			}
			WorldData.with(server, RepairState.TYPE, new RepairState(), () -> {
				BlockPos taker = place(helper, PUMP.block().defaultBlockState(), 1);
				BlockPos other = place(helper, PUMP.block().defaultBlockState(), 5);
				for (int i = 0; i < PUMP.parts().size(); i++) {
					expectActive(helper, taker, false, "the pump taking parts, with " + i + " of " + PUMP.parts().size() + " in");
					expectActive(helper, other, false, "the other pump, with " + i + " parts in");
					Item part = PUMP.parts().get(i);
					player.getInventory().add(new ItemStack(part));
					if (Terminals.insertPart(player, taker, part).isPresent()) {
						throw helper.assertionException("inserting part %s of the fuel pump should succeed", i);
					}
				}
				expectActive(helper, taker, true, "the pump that took the last part");
				expectActive(helper, other, true, "the other pump of the same type");
				expectBlockEntityAgrees(helper, other);
			});
		} finally {
			mock.leave();
		}
		helper.succeed();
	}

	/** A terminal set in the wrong state is right on a later tick, and its block entity holds the same state as the level. */
	@GameTest
	public void aTerminalSetInTheWrongStateIsCorrectedOnItsNextTick(GameTestHelper helper) {
		BlockState lit = ContractTerminal.TYPE.block().defaultBlockState();
		if (!lit.getValue(TextureProperties.ACTIVE)) {
			throw helper.assertionException("the contract terminal's default state should be active: it never went dark");
		}
		BlockPos contract = place(helper, lit.setValue(TextureProperties.ACTIVE, false), 1);
		helper.succeedWhen(() -> {
			expectActive(helper, contract, true, "a contract terminal set with active=false, a tick later");
			expectBlockEntityAgrees(helper, contract);
		});
	}

	/** The real path: a stale terminal is saved with its chunk, the chunk unloads and loads again, and the load mends it. */
	@GameTest(maxTicks = 2 * FarChunks.AWAIT_BUDGET_TICKS + 400)
	public void aStaleTerminalIsMendedWhenItsChunkLoadsAgain(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ChunkPos chunk = ChunkPos.containing(FAR);
		BlockState lit = ContractTerminal.TYPE.block().defaultBlockState();
		int[] phase = {0};
		FarChunks.Deadline[] deadline = {null};
		helper.onEachTick(() -> {
			switch (phase[0]) {
				case 0, 3 -> {
				}
				case 1 -> {
					boolean unloaded = UNLOADED.contains(chunk.pack());
					deadline[0].await(helper, level, unloaded, () -> "the chunk " + chunk + " did not unload");
					if (unloaded) {
						level.setChunkForced(chunk.x(), chunk.z(), true);
						deadline[0] = FarChunks.deadline();
						phase[0] = 2;
					}
				}
				case 2 -> {
					boolean back = LOADED.contains(chunk.pack()) && level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null;
					deadline[0].await(helper, level, back, () -> "the chunk " + chunk + " did not load again");
					if (back) {
						expectActive(helper, FAR, true, "the contract terminal after its chunk loaded again");
						expectBlockEntityAgrees(helper, FAR);
						phase[0] = 3;
						helper.succeed();
					}
				}
				default -> throw new IllegalStateException("phase " + phase[0]);
			}
		});
		FarChunks.awaitEntityTicking(helper, level, FAR, () -> {
			level.setBlock(FAR, lit, Block.UPDATE_ALL);
			// Stale as in a world saved before the state existed: set without the placement's own correction.
			level.setBlock(FAR, lit.setValue(TextureProperties.ACTIVE, false), Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ON_PLACE);
			expectActive(helper, FAR, false, "the contract terminal before its chunk unloads");
			UNLOADED.remove(chunk.pack());
			LOADED.remove(chunk.pack());
			level.setChunkForced(chunk.x(), chunk.z(), false);
			deadline[0] = FarChunks.deadline();
			phase[0] = 1;
		});
	}

	@GameTest
	public void unreadableRepairDataLeavesTerminalsAsTheyAreAndNeverThrows(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos pump = place(helper, PUMP.block().defaultBlockState(), 1);
		BlockState before = level.getBlockState(pump);
		Map<String, Runnable> paths = new LinkedHashMap<>();
		paths.put("place", () -> place(helper, PUMP.block().defaultBlockState(), 3));
		paths.put("sync at a terminal", () -> TerminalActivity.sync(level, pump));
		paths.put("chunk load", () -> TerminalActivity.sync(level, level.getChunkAt(pump)));
		UnreadableChecks.assertSavedDataNoThrow(helper, "terminal activity", level.getServer(), RepairState.TYPE, paths);
		if (!level.getBlockState(pump).equals(before)) {
			throw helper.assertionException("unreadable repair data must leave the terminal as it was: %s, now %s", before, level.getBlockState(pump));
		}
		helper.succeed();
	}

	/** In the world: a lit lamp lights its block to 15, and the same lamp turned dark leaves it unlit. */
	@GameTest
	public void aLitLampGivesFullLightAndADarkOneNone(GameTestHelper helper) {
		BlockPos lamp = new BlockPos(2, 1, 2);
		BlockPos absolute = helper.absolutePos(lamp);
		helper.setBlock(lamp, ColonyBlocks.COMPANY_LAMP.defaultBlockState());
		helper.startSequence()
				.thenWaitUntil(() -> requireLight(helper, absolute, 15))
				.thenExecute(() -> helper.setBlock(lamp, ColonyBlocks.COMPANY_LAMP.defaultBlockState().setValue(TextureProperties.ACTIVE, false)))
				.thenWaitUntil(() -> requireLight(helper, absolute, 0))
				.thenSucceed();
	}

	private static void requireLight(GameTestHelper helper, BlockPos absolute, int expected) {
		int light = helper.getLevel().getBrightness(LightLayer.BLOCK, absolute);
		if (light != expected) {
			throw helper.assertionException("the block light at the lamp is %s, expected %s", light, expected);
		}
	}

	/** Pins the property name: blockstate files and saved worlds use it. */
	@GameTest
	public void thePropertyIsCalledActive(GameTestHelper helper) {
		if (!TextureProperties.ACTIVE.getName().equals("active")) {
			throw helper.assertionException("the property is called %s", TextureProperties.ACTIVE.getName());
		}
		helper.succeed();
	}
}
