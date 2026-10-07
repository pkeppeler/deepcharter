package io.github.pkeppeler.deepcharter.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.repair.RepairStation;
import io.github.pkeppeler.deepcharter.repair.RepairTuning;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalRefusal;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;

/**
 * Server GameTests for #70: hull repair, the shop, and the six items the shop sells.
 *
 * <p>The repair state is one per world, so a test that needs a working repair station swaps in a fresh {@link RepairState} with
 * the whole chain repaired for its own duration ({@link #withStation}), and does all its work inside its first tick.
 */
public class RepairStationTest {
	/** The originals' prices (original_flash_game/REFERENCE.md section 3), written out so that the code cannot move them. */
	private static final Map<Consumable, Long> ORIGINAL_PRICES = Map.of(
			Consumable.RESERVE_FUEL_TANK, 2_000L,
			Consumable.HULL_NANOBOTS, 7_500L,
			Consumable.DYNAMITE, 2_000L,
			Consumable.PLASTIC_EXPLOSIVES, 5_000L,
			Consumable.QUANTUM_TELEPORTER, 2_000L,
			Consumable.MATTER_TRANSMITTER, 10_000L);
	private static final long PER_HP = 15L;
	private static final int ARENA = 4;

	/** A working repair station at {@code pos}, a pilot on a charter that owns {@code pod}, and the charter's id. */
	private record Station(BlockPos pos, MockPlayer pilot, Charter charter, PodEntity pod) {
	}

	private static void withStation(GameTestHelper helper, Consumer<Station> body) {
		MinecraftServer server = helper.getLevel().getServer();
		RepairState original = RepairState.get(server);
		RepairState fresh = new RepairState();
		server.getDataStorage().set(RepairState.TYPE, fresh);
		try {
			for (TerminalType type : List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR, TerminalTypes.UPGRADE_TERMINAL, TerminalTypes.REPAIR_STATION)) {
				for (var part : type.parts()) {
					fresh.insert(type, part).ifPresent(refusal -> {
						throw helper.assertionException("repairing %s: %s", type.id(), refusal);
					});
				}
			}
			BlockPos relative = new BlockPos(0, 1, 0);
			helper.setBlock(relative, TerminalTypes.REPAIR_STATION.block().defaultBlockState());
			BlockPos pos = helper.absolutePos(relative);
			MockPlayer pilot = MockPlayers.join(helper, "Pilot");
			pilot.player().setGameMode(GameType.SURVIVAL);
			String name = "Repair " + UUID.randomUUID().toString().substring(0, 8);
			if (Charters.found(server, pilot.player().getUUID(), name).isPresent()) {
				throw helper.assertionException("founding the charter should succeed");
			}
			Charter charter = Charters.charterOf(server, pilot.player().getUUID()).orElseThrow();
			pilot.teleportTo(helper.getLevel(), Vec3.atCenterOf(pos).add(2, -pilot.player().getEyeHeight(), 0), 0, 0);
			PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(relative.getX() + 3.5, relative.getY(), relative.getZ() + 0.5));
			PodComponents.register(pod, charter.id());
			body.accept(new Station(pos, pilot, charter, pod));
			pod.discard();
		} finally {
			server.getDataStorage().set(RepairState.TYPE, original);
		}
	}

	private static long account(MinecraftServer server, Station station) {
		return Charters.find(server, station.charter().id()).orElseThrow().account();
	}

	private static void fund(GameTestHelper helper, Station station, long amount) {
		if (Charters.deposit(helper.getLevel().getServer(), station.charter().id(), amount).isPresent()) {
			throw helper.assertionException("funding the account should succeed");
		}
	}

	private static CompoundTag hp(int amount) {
		CompoundTag args = new CompoundTag();
		args.putInt(RepairStation.HP_KEY, amount);
		return args;
	}

	private static CompoundTag item(Consumable consumable) {
		CompoundTag args = new CompoundTag();
		args.putString(RepairStation.ITEM_KEY, consumable.itemId().toString());
		return args;
	}

	private static void expectDone(GameTestHelper helper, Optional<TerminalRefusal> refusal, String what) {
		if (refusal.isPresent()) {
			throw helper.assertionException("%s should succeed, was refused: %s", what, refusal.get());
		}
	}

	private static void expectRefused(GameTestHelper helper, Optional<TerminalRefusal> refusal, String what) {
		if (!refusal.equals(Optional.of(TerminalRefusal.ACTION_REFUSED))) {
			throw helper.assertionException("%s should be refused by the action, got %s", what, refusal);
		}
	}

	private static void expectHull(GameTestHelper helper, PodEntity pod, float expected, String what) {
		if (pod.hull() != expected) {
			throw helper.assertionException("%s: hull should be %s, was %s", what, expected, pod.hull());
		}
	}

	private static void expectAccount(GameTestHelper helper, Station station, long expected, String what) {
		long actual = account(helper.getLevel().getServer(), station);
		if (actual != expected) {
			throw helper.assertionException("%s: account should be $%d, was $%d", what, expected, actual);
		}
	}

	private static int count(ServerPlayer player, Consumable consumable) {
		Inventory inventory = player.getInventory();
		int total = 0;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			if (inventory.getItem(slot).is(RepairRegistry.item(consumable))) {
				total += inventory.getItem(slot).getCount();
			}
		}
		return total;
	}

	/** Puts one of the item in the selected hotbar slot, makes the player the pod's pilot, and uses it as a right click does. */
	private static InteractionResult useFromHotbar(GameTestHelper helper, Station station, Consumable consumable) {
		ServerPlayer player = station.pilot().player();
		player.getInventory().setItem(player.getInventory().getSelectedSlot(), new ItemStack(RepairRegistry.item(consumable)));
		// A use starts a cooldown; these tests use the same item twice in one tick.
		player.getCooldowns().removeCooldown(player.getCooldowns().getCooldownGroup(player.getItemInHand(InteractionHand.MAIN_HAND)));
		return player.gameMode.useItem(player, player.level(), player.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND);
	}

	private static void board(GameTestHelper helper, Station station) {
		if (!station.pilot().player().startRiding(station.pod(), true, false)) {
			throw helper.assertionException("the pilot could not board the pod");
		}
	}

	private static void expectSpent(GameTestHelper helper, ServerPlayer player, Consumable consumable, int expected, String what) {
		if (count(player, consumable) != expected) {
			throw helper.assertionException("%s: the pilot should hold %d, held %d", what, expected, count(player, consumable));
		}
	}

	@GameTest
	public void repairDebitsFifteenDollarsPerHpAndCapsAtMaxHull(GameTestHelper helper) {
		withStation(helper, station -> {
			PodEntity pod = station.pod();
			float max = pod.maxHull();
			pod.setHull(max - 40f);
			fund(helper, station, 1_000);

			expectDone(helper, Terminals.act(station.pilot().player(), station.pos(), RepairStation.REPAIR, hp(10)), "repairing 10 HP");
			expectHull(helper, pod, max - 30f, "after 10 HP");
			expectAccount(helper, station, 1_000 - 10 * PER_HP, "after 10 HP");

			expectDone(helper, Terminals.act(station.pilot().player(), station.pos(), RepairStation.REPAIR, hp(500)), "repairing more than is missing");
			expectHull(helper, pod, max, "after asking for more than is missing");
			expectAccount(helper, station, 1_000 - 40 * PER_HP, "the cap bills only the 30 HP repaired");

			expectRefused(helper, Terminals.act(station.pilot().player(), station.pos(), RepairStation.REPAIR, hp(1)), "repairing a full hull");
			expectAccount(helper, station, 1_000 - 40 * PER_HP, "a full hull costs nothing");
			if (RepairTuning.DEFAULT.repairCostPerHp() != PER_HP) {
				throw helper.assertionException("the tuned price should be $%d/HP", PER_HP);
			}
			helper.succeed();
		});
	}

	@GameTest
	public void repairTotalFillsTheHull(GameTestHelper helper) {
		withStation(helper, station -> {
			PodEntity pod = station.pod();
			pod.setHull(pod.maxHull() - 25f);
			fund(helper, station, 1_000);
			expectDone(helper, Terminals.act(station.pilot().player(), station.pos(), RepairStation.REPAIR_TOTAL, new CompoundTag()), "repairing the total");
			expectHull(helper, pod, pod.maxHull(), "after the total");
			expectAccount(helper, station, 1_000 - 25 * PER_HP, "after the total");
			helper.succeed();
		});
	}

	@GameTest
	public void repairRefusesWhenTheAccountCannotPay(GameTestHelper helper) {
		withStation(helper, station -> {
			PodEntity pod = station.pod();
			float damaged = pod.maxHull() - 40f;
			pod.setHull(damaged);
			expectRefused(helper, Terminals.act(station.pilot().player(), station.pos(), RepairStation.REPAIR, hp(10)), "repairing from an empty account");
			expectHull(helper, pod, damaged, "after the refusal");
			expectAccount(helper, station, 0, "after the refusal");

			fund(helper, station, 10 * PER_HP - 1);
			expectRefused(helper, Terminals.act(station.pilot().player(), station.pos(), RepairStation.REPAIR, hp(10)), "repairing with $1 too little");
			expectHull(helper, pod, damaged, "after the short refusal");
			expectAccount(helper, station, 10 * PER_HP - 1, "after the short refusal");

			expectRefused(helper, Terminals.act(station.pilot().player(), station.pos(), RepairStation.REPAIR_TOTAL, new CompoundTag()), "repairing the total from a short account");
			expectHull(helper, pod, damaged, "after the short total");
			helper.succeed();
		});
	}

	@GameTest
	public void repairRefusesAWreck(GameTestHelper helper) {
		withStation(helper, station -> {
			PodEntity pod = station.pod();
			pod.damageHull(pod.maxHull());
			if (!Wrecks.isWreck(pod)) {
				throw helper.assertionException("the pod at hull 0 should be a wreck");
			}
			fund(helper, station, 10_000);
			expectRefused(helper, Terminals.act(station.pilot().player(), station.pos(), RepairStation.REPAIR, hp(10)), "repairing a wreck");
			expectRefused(helper, Terminals.act(station.pilot().player(), station.pos(), RepairStation.REPAIR_TOTAL, new CompoundTag()), "repairing a wreck in total");
			expectHull(helper, pod, 0f, "a wreck stays at 0");
			if (!Wrecks.isWreck(pod)) {
				throw helper.assertionException("repair must not restore a wreck: only the hangar does");
			}
			expectAccount(helper, station, 10_000, "a refused wreck repair costs nothing");
			helper.succeed();
		});
	}

	@GameTest
	public void repairNeedsTheCharterPodParkedAtTheStation(GameTestHelper helper) {
		withStation(helper, station -> {
			PodEntity pod = station.pod();
			pod.setHull(pod.maxHull() - 20f);
			fund(helper, station, 1_000);
			float tooFar = (float) RepairTuning.DEFAULT.parkRadius() + 2f;
			pod.setPos(Vec3.atCenterOf(station.pos()).add(tooFar, 0, 0));
			expectRefused(helper, Terminals.act(station.pilot().player(), station.pos(), RepairStation.REPAIR, hp(5)), "repairing a pod parked out of reach");
			pod.setPos(Vec3.atCenterOf(station.pos()).add(2, 0, 0));

			PodEntity foreign = helper.spawn(PodRegistry.POD, new Vec3(0.5, 1, 3.5));
			MockPlayer stranger = MockPlayers.join(helper, "Stranger");
			if (Charters.found(helper.getLevel().getServer(), stranger.player().getUUID(), "Other " + UUID.randomUUID().toString().substring(0, 8)).isPresent()) {
				throw helper.assertionException("founding the other charter should succeed");
			}
			PodComponents.register(foreign, Charters.charterOf(helper.getLevel().getServer(), stranger.player().getUUID()).orElseThrow().id());
			foreign.setHull(foreign.maxHull() - 20f);
			pod.discard();
			expectRefused(helper, Terminals.act(station.pilot().player(), station.pos(), RepairStation.REPAIR, hp(5)), "repairing another charter's pod");
			expectHull(helper, foreign, foreign.maxHull() - 20f, "the other charter's pod");
			foreign.discard();
			helper.succeed();
		});
	}

	@GameTest
	public void repairRejectsBadArguments(GameTestHelper helper) {
		withStation(helper, station -> {
			PodEntity pod = station.pod();
			float damaged = pod.maxHull() - 20f;
			pod.setHull(damaged);
			fund(helper, station, 1_000);
			CompoundTag text = new CompoundTag();
			text.putString(RepairStation.HP_KEY, "10");
			List<CompoundTag> bad = List.of(new CompoundTag(), hp(0), hp(-5), hp(Integer.MIN_VALUE), text);
			for (CompoundTag args : bad) {
				expectRefused(helper, Terminals.act(station.pilot().player(), station.pos(), RepairStation.REPAIR, args), "repairing with " + args);
			}
			expectHull(helper, pod, damaged, "after bad arguments");
			expectAccount(helper, station, 1_000, "after bad arguments");
			helper.succeed();
		});
	}

	@GameTest
	public void theShopSellsTheSixItemsAtTheOriginalPrices(GameTestHelper helper) {
		withStation(helper, station -> {
			if (ORIGINAL_PRICES.size() != Consumable.values().length) {
				throw helper.assertionException("the shop sells %d items, the original six are %d", Consumable.values().length, ORIGINAL_PRICES.size());
			}
			ServerPlayer player = station.pilot().player();
			fund(helper, station, 100_000);
			long balance = 100_000;
			for (Consumable consumable : Consumable.values()) {
				long price = ORIGINAL_PRICES.get(consumable);
				if (consumable.price() != price) {
					throw helper.assertionException("%s costs $%d, the original charges $%d", consumable, consumable.price(), price);
				}
				expectDone(helper, Terminals.act(player, station.pos(), RepairStation.BUY, item(consumable)), "buying " + consumable);
				balance -= price;
				expectAccount(helper, station, balance, "after buying " + consumable);
				expectSpent(helper, player, consumable, 1, "after buying " + consumable);
			}
			helper.succeed();
		});
	}

	@GameTest
	public void theShopRefusesWhatItCannotSell(GameTestHelper helper) {
		withStation(helper, station -> {
			ServerPlayer player = station.pilot().player();
			expectRefused(helper, Terminals.act(player, station.pos(), RepairStation.BUY, item(Consumable.DYNAMITE)), "buying with an empty account");
			fund(helper, station, Consumable.DYNAMITE.price() - 1);
			expectRefused(helper, Terminals.act(player, station.pos(), RepairStation.BUY, item(Consumable.DYNAMITE)), "buying with $1 too little");
			expectAccount(helper, station, Consumable.DYNAMITE.price() - 1, "after the short purchase");
			expectSpent(helper, player, Consumable.DYNAMITE, 0, "after the short purchase");

			fund(helper, station, 1);
			CompoundTag notSold = new CompoundTag();
			notSold.putString(RepairStation.ITEM_KEY, "minecraft:diamond");
			CompoundTag notAnId = new CompoundTag();
			notAnId.putString(RepairStation.ITEM_KEY, "not an id!");
			CompoundTag wrongType = new CompoundTag();
			wrongType.putInt(RepairStation.ITEM_KEY, 3);
			for (CompoundTag args : List.of(new CompoundTag(), notSold, notAnId, wrongType)) {
				expectRefused(helper, Terminals.act(player, station.pos(), RepairStation.BUY, args), "buying with " + args);
			}
			expectAccount(helper, station, Consumable.DYNAMITE.price(), "after the bad requests");

			Inventory inventory = player.getInventory();
			for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
				inventory.setItem(slot, new ItemStack(Blocks.DIRT));
			}
			expectRefused(helper, Terminals.act(player, station.pos(), RepairStation.BUY, item(Consumable.DYNAMITE)), "buying with a full inventory");
			expectAccount(helper, station, Consumable.DYNAMITE.price(), "a full inventory costs nothing");
			helper.succeed();
		});
	}

	@GameTest
	public void theReserveFuelTankRefillsFromTheHotbar(GameTestHelper helper) {
		withStation(helper, station -> {
			PodEntity pod = station.pod();
			ServerPlayer player = station.pilot().player();
			pod.setFuel(10f);
			if (useFromHotbar(helper, station, Consumable.RESERVE_FUEL_TANK).consumesAction()) {
				throw helper.assertionException("the tank should not work while the player is not piloting");
			}
			if (pod.fuel() != 10f) {
				throw helper.assertionException("an unpiloted use changed the fuel to %s", pod.fuel());
			}
			expectSpent(helper, player, Consumable.RESERVE_FUEL_TANK, 1, "an unpiloted use keeps the item");

			board(helper, station);
			if (!useFromHotbar(helper, station, Consumable.RESERVE_FUEL_TANK).consumesAction()) {
				throw helper.assertionException("the tank should work while piloting");
			}
			if (pod.fuel() <= 10f) {
				throw helper.assertionException("the tank should add fuel, the pod has %s", pod.fuel());
			}
			expectSpent(helper, player, Consumable.RESERVE_FUEL_TANK, 0, "after use");
			helper.succeed();
		});
	}

	@GameTest
	public void theNanobotsRepairThirtyHpFromTheHotbar(GameTestHelper helper) {
		withStation(helper, station -> {
			PodEntity pod = station.pod();
			ServerPlayer player = station.pilot().player();
			pod.setHull(pod.maxHull() - 50f);
			board(helper, station);
			if (!useFromHotbar(helper, station, Consumable.HULL_NANOBOTS).consumesAction()) {
				throw helper.assertionException("the nanobots should work while piloting");
			}
			expectHull(helper, pod, pod.maxHull() - 20f, "after 30 HP of nanobots");
			expectSpent(helper, player, Consumable.HULL_NANOBOTS, 0, "after use");

			pod.setHull(pod.maxHull() - 10f);
			useFromHotbar(helper, station, Consumable.HULL_NANOBOTS);
			expectHull(helper, pod, pod.maxHull(), "nanobots cap at the maximum");

			if (useFromHotbar(helper, station, Consumable.HULL_NANOBOTS).consumesAction()) {
				throw helper.assertionException("nanobots on a full hull should not be used up");
			}
			expectSpent(helper, player, Consumable.HULL_NANOBOTS, 1, "a full hull keeps the item");
			helper.succeed();
		});
	}

	@GameTest
	public void nanobotsDoNotReviveAWreck(GameTestHelper helper) {
		withStation(helper, station -> {
			PodEntity pod = station.pod();
			pod.damageHull(pod.maxHull());
			// A wreck throws its pilot out and cannot be boarded, so the player is on foot beside it: the use is refused either way.
			useFromHotbar(helper, station, Consumable.HULL_NANOBOTS);
			expectHull(helper, pod, 0f, "a wreck stays at 0");
			if (!Wrecks.isWreck(pod)) {
				throw helper.assertionException("nanobots must not restore a wreck");
			}
			helper.succeed();
		});
	}

	private static void fillArena(GameTestHelper helper, BlockPos centre) {
		for (int dx = -ARENA; dx <= ARENA; dx++) {
			for (int dy = -ARENA; dy <= ARENA; dy++) {
				for (int dz = -ARENA; dz <= ARENA; dz++) {
					helper.getLevel().setBlock(centre.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), 3);
				}
			}
		}
	}

	private static void blast(GameTestHelper helper, Consumable explosive, int radius) {
		withStation(helper, station -> {
			ServerLevel level = helper.getLevel();
			PodEntity pod = station.pod();
			BlockPos centre = helper.absolutePos(new BlockPos(0, 12, 8));
			pod.setPos(Vec3.atBottomCenterOf(centre));
			BlockPos middle = BlockPos.containing(pod.getBoundingBox().getCenter());
			fillArena(helper, middle);
			// One of each thing that must survive, at the edge of the blast and inside it.
			Block[] survivors = {HazardBlocks.COMPANY_ROCK, Blocks.LAVA, Blocks.OAK_PLANKS};
			List<BlockPos> kept = new ArrayList<>();
			for (int i = 0; i < survivors.length; i++) {
				BlockPos inside = middle.offset(radius, i - 1, radius);
				level.setBlock(inside, survivors[i].defaultBlockState(), 3);
				kept.add(inside);
			}
			board(helper, station);
			if (!useFromHotbar(helper, station, explosive).consumesAction()) {
				throw helper.assertionException("%s should work while piloting", explosive);
			}
			for (int dx = -ARENA; dx <= ARENA; dx++) {
				for (int dy = -ARENA; dy <= ARENA; dy++) {
					for (int dz = -ARENA; dz <= ARENA; dz++) {
						BlockPos pos = middle.offset(dx, dy, dz);
						boolean inBlast = Math.abs(dx) <= radius && Math.abs(dy) <= radius && Math.abs(dz) <= radius;
						boolean stone = level.getBlockState(pos).is(Blocks.STONE);
						if (kept.contains(pos)) {
							if (level.getBlockState(pos).isAir()) {
								throw helper.assertionException("%s cleared %s at %s, which is not natural rock", explosive, pos, level.getBlockState(pos));
							}
						} else if (inBlast && stone) {
							throw helper.assertionException("%s left stone at %s inside its %dx%dx%d blast", explosive, pos, 2 * radius + 1, 2 * radius + 1, 2 * radius + 1);
						} else if (!inBlast && !stone) {
							throw helper.assertionException("%s cleared %s outside its blast", explosive, pos);
						}
					}
				}
			}
			for (BlockPos pos : kept) {
				if (level.getBlockState(pos).isAir()) {
					throw helper.assertionException("%s cleared the block at %s that must survive", explosive, pos);
				}
			}
			helper.succeed();
		});
	}

	@GameTest
	public void dynamiteClearsThreeByThreeOfNaturalRock(GameTestHelper helper) {
		blast(helper, Consumable.DYNAMITE, 1);
	}

	@GameTest
	public void plasticExplosivesClearFiveByFiveOfNaturalRock(GameTestHelper helper) {
		blast(helper, Consumable.PLASTIC_EXPLOSIVES, 2);
	}

	@GameTest
	public void explosivesNeedAPilot(GameTestHelper helper) {
		withStation(helper, station -> {
			ServerLevel level = helper.getLevel();
			BlockPos middle = BlockPos.containing(station.pod().getBoundingBox().getCenter());
			BlockPos neighbour = middle.above(2);
			level.setBlock(neighbour, Blocks.STONE.defaultBlockState(), 3);
			if (useFromHotbar(helper, station, Consumable.DYNAMITE).consumesAction()) {
				throw helper.assertionException("dynamite should not work for a player on foot");
			}
			if (!level.getBlockState(neighbour).is(Blocks.STONE)) {
				throw helper.assertionException("dynamite from a player on foot cleared a block");
			}
			expectSpent(helper, station.pilot().player(), Consumable.DYNAMITE, 1, "an unpiloted use keeps the item");
			level.setBlock(neighbour, Blocks.AIR.defaultBlockState(), 3);
			helper.succeed();
		});
	}

	/** Worlds' spawn, where the teleporters land until the colony is built. */
	private static Vec3 spawn(MinecraftServer server) {
		LevelData.RespawnData data = server.overworld().getRespawnData();
		return Vec3.atBottomCenterOf(data.pos());
	}

	private static void teleportFromLayerOne(GameTestHelper helper, Consumable teleporter, double x, double z, double scatter) {
		ServerLevel one = helper.getLevel().getServer().getLevel(LayerChain.dimension(1));
		if (one == null) {
			throw helper.assertionException("layer 1 did not load");
		}
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayer pilot = MockPlayers.join(helper, "Teleportee");
		pilot.player().setGameMode(GameType.SURVIVAL);
		Charters.found(server, pilot.player().getUUID(), "Tele " + UUID.randomUUID().toString().substring(0, 8)).ifPresent(refusal -> {
			throw helper.assertionException("founding the charter: %s", refusal);
		});
		Charter charter = Charters.charterOf(server, pilot.player().getUUID()).orElseThrow();
		pilot.teleportTo(one, new Vec3(x, 40, z), 0, 0);
		BlockPos at = BlockPos.containing(x, 40, z);
		boolean[] used = {false};
		FarChunks.awaitEntityTicking(helper, one, at, () -> {
			PodEntity pod = PodRegistry.POD.create(one, EntitySpawnReason.COMMAND);
			pod.setPos(x, 40, z);
			one.addFreshEntity(pod);
			PodComponents.register(pod, charter.id());
			pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.IRONIUM));
			pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.IRONIUM));
			if (!pilot.player().startRiding(pod, true, false)) {
				throw helper.assertionException("the pilot could not board the pod");
			}
			ServerPlayer player = pilot.player();
			player.getInventory().setItem(player.getInventory().getSelectedSlot(), new ItemStack(RepairRegistry.item(teleporter)));
			player.gameMode.useItem(player, player.level(), player.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND);
			used[0] = true;
		});
		helper.succeedWhen(() -> {
			if (!used[0]) {
				throw helper.assertionException("waiting for the chunk");
			}
			ServerPlayer player = pilot.player();
			if (!player.level().dimension().equals(server.overworld().dimension())) {
				throw helper.assertionException("the pilot is in %s, not at the colony's dimension", player.level().dimension());
			}
			if (!(player.getVehicle() instanceof PodEntity arrived)) {
				throw helper.assertionException("the pilot should still be riding the pod, rides %s", player.getVehicle());
			}
			if (arrived.level() != player.level()) {
				throw helper.assertionException("the pod is in %s, the pilot in %s", arrived.level().dimension(), player.level().dimension());
			}
			Vec3 target = spawn(server);
			double away = Math.hypot(arrived.getX() - target.x, arrived.getZ() - target.z);
			if (away > scatter + 1.0) {
				throw helper.assertionException("the pod landed %.1f blocks from the destination, allowed %.1f", away, scatter + 1.0);
			}
			if (scatter == 0.0 && away > 1.0) {
				throw helper.assertionException("the transmitter should land on the destination, landed %.1f away", away);
			}
			if (!arrived.cargo().entries().isEmpty()) {
				throw helper.assertionException("nothing mined teleports: the bay holds %d", arrived.cargo().entries().size());
			}
			int spilled = 0;
			for (ItemEntity drop : one.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(6))) {
				if (OreRegistry.typeOf(drop.getItem()).equals(Optional.of(OreType.IRONIUM))) {
					spilled += drop.getItem().getCount();
				}
			}
			if (spilled != 2) {
				throw helper.assertionException("the cargo should spill at the departure point: %d ironium there, expected 2", spilled);
			}
			if (PodComponents.registration(arrived).map(registration -> !registration.owner().equals(charter.id())).orElse(true)) {
				throw helper.assertionException("the pod lost its owner in the teleport");
			}
		});
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 200)
	public void theMatterTransmitterTakesThePodAndPilotAcrossDimensionsAndSpillsTheCargo(GameTestHelper helper) {
		teleportFromLayerOne(helper, Consumable.MATTER_TRANSMITTER, 2400.5, 2400.5, 0.0);
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 200)
	public void theQuantumTeleporterLandsWithinItsScatter(GameTestHelper helper) {
		teleportFromLayerOne(helper, Consumable.QUANTUM_TELEPORTER, 2500.5, 2500.5, RepairTuning.DEFAULT.quantumScatter());
	}

	@GameTest
	public void consumableIdsAreTheOriginalsAndAreRegistered(GameTestHelper helper) {
		for (Consumable consumable : Consumable.values()) {
			Identifier id = consumable.itemId();
			if (!id.getNamespace().equals("deepcharter")) {
				throw helper.assertionException("%s is not in our namespace", id);
			}
			if (!BuiltInRegistries.ITEM.getValue(id).equals(RepairRegistry.item(consumable))) {
				throw helper.assertionException("%s is not the registered item", id);
			}
			if (Component.translatable(RepairRegistry.item(consumable).getDescriptionId()).getString().equals(RepairRegistry.item(consumable).getDescriptionId())) {
				throw helper.assertionException("%s has no name in the lang file", id);
			}
		}
		helper.succeed();
	}
}
