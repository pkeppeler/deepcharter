package io.github.pkeppeler.deepcharter.test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
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
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import io.github.pkeppeler.deepcharter.test.support.WorldData;
import io.github.pkeppeler.deepcharter.texture.TextureProperties;

/**
 * Server GameTests for #242: the {@code active} block-state property that picks a block's lit layers. A terminal shows whether its
 * type is online, from the moment it is placed, when its last part goes in, and when its chunk loads; unreadable repair data leaves
 * it as it is and never throws. A Company lamp gives full light lit and none dark.
 *
 * <p>A test that repairs swaps in a fresh {@link RepairState} through {@link WorldData#with} and does all its work in that one tick.
 */
public class ActiveStateTest {
	private static final TerminalType PUMP = TerminalTypes.FUEL_PUMP;

	private static BlockPos place(GameTestHelper helper, BlockState state, int x) {
		BlockPos relative = new BlockPos(x, 1, 1);
		helper.setBlock(relative, state);
		return helper.absolutePos(relative);
	}

	private static boolean active(GameTestHelper helper, BlockPos absolute) {
		return helper.getLevel().getBlockState(absolute).getValue(TextureProperties.ACTIVE);
	}

	private static void expectActive(GameTestHelper helper, BlockPos absolute, boolean expected, String why) {
		if (active(helper, absolute) != expected) {
			throw helper.assertionException("%s: the terminal at %s should show active=%s, shows %s", why, absolute.toShortString(), expected,
					helper.getLevel().getBlockState(absolute));
		}
	}

	private static void repairDirectly(GameTestHelper helper, RepairState state, TerminalType type) {
		for (Item part : type.parts()) {
			if (state.insert(type, part).isPresent()) {
				throw helper.assertionException("inserting %s straight into the state should succeed", part);
			}
		}
	}

	@GameTest
	public void anUnrepairedTerminalIsPlacedDarkAndARepairedOneLit(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		RepairState fresh = new RepairState();
		WorldData.with(server, RepairState.TYPE, fresh, () -> {
			BlockPos dark = place(helper, PUMP.block().defaultBlockState(), 1);
			expectActive(helper, dark, false, "a fuel pump placed before its repair");
			repairDirectly(helper, fresh, PUMP);
			BlockPos lit = place(helper, PUMP.block().defaultBlockState(), 3);
			expectActive(helper, lit, true, "a fuel pump placed after its repair, from a dark default state");
		});
		helper.succeed();
	}

	@GameTest
	public void theLastPartLightsTheTerminalItWentInto(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayer mock = MockPlayers.join(helper, "active-repairer");
		try {
			ServerPlayer player = mock.player();
			player.setGameMode(GameType.SURVIVAL);
			if (Charters.found(server, player.getUUID(), "Lamp Co " + UUID.randomUUID().toString().substring(0, 8)).isPresent()) {
				throw helper.assertionException("founding a charter should succeed");
			}
			WorldData.with(server, RepairState.TYPE, new RepairState(), () -> {
				BlockPos pump = place(helper, PUMP.block().defaultBlockState(), 1);
				Vec3 centre = Vec3.atCenterOf(pump);
				mock.teleportTo(helper.getLevel(), new Vec3(centre.x + 2, centre.y - player.getEyeHeight(), centre.z), 0, 0);
				for (int i = 0; i < PUMP.parts().size(); i++) {
					expectActive(helper, pump, false, "a fuel pump with " + i + " of its " + PUMP.parts().size() + " parts in");
					Item part = PUMP.parts().get(i);
					player.getInventory().add(new ItemStack(part));
					if (Terminals.insertPart(player, pump, part).isPresent()) {
						throw helper.assertionException("inserting part %s of the fuel pump should succeed", i);
					}
				}
				expectActive(helper, pump, true, "a fuel pump with its last part in");
			});
		} finally {
			mock.leave();
		}
		helper.succeed();
	}

	@GameTest
	public void aTerminalThatNeedsNoRepairIsAlwaysLit(GameTestHelper helper) {
		BlockState contract = ContractTerminal.TYPE.block().defaultBlockState();
		if (!contract.getValue(TextureProperties.ACTIVE)) {
			throw helper.assertionException("the contract terminal's default state should be active: it never went dark");
		}
		WorldData.with(helper.getLevel().getServer(), RepairState.TYPE, new RepairState(), () -> {
			BlockPos placed = place(helper, contract.setValue(TextureProperties.ACTIVE, false), 1);
			expectActive(helper, placed, true, "a contract terminal placed with active=false");
		});
		helper.succeed();
	}

	/** A terminal of a type repaired while it was elsewhere (or saved before the state existed) is mended when its chunk loads. */
	@GameTest
	public void aLoadingChunkMendsAStaleTerminal(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		RepairState fresh = new RepairState();
		WorldData.with(level.getServer(), RepairState.TYPE, fresh, () -> {
			BlockPos pump = place(helper, PUMP.block().defaultBlockState(), 1);
			repairDirectly(helper, fresh, PUMP);
			expectActive(helper, pump, false, "a fuel pump repaired without an insert at it, before its chunk loads again");
			TerminalActivity.sync(level, level.getChunkAt(pump));
			expectActive(helper, pump, true, "the same fuel pump once its chunk loads");
		});
		helper.succeed();
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

	@GameTest
	public void aLitLampGivesFullLightAndADarkOneNone(GameTestHelper helper) {
		BlockState lamp = ColonyBlocks.COMPANY_LAMP.defaultBlockState();
		if (!lamp.getValue(TextureProperties.ACTIVE) || lamp.getLightEmission() != 15) {
			throw helper.assertionException("a lamp is placed lit, giving light 15: %s gives %s", lamp, lamp.getLightEmission());
		}
		BlockState dark = lamp.setValue(TextureProperties.ACTIVE, false);
		if (dark.getLightEmission() != 0) {
			throw helper.assertionException("a dark lamp gives no light, not %s", dark.getLightEmission());
		}
		helper.succeed();
	}
}
