package io.github.pkeppeler.deepcharter.test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.Chassis;
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
		return player.getInventory().countItem(RepairRegistry.item(consumable));
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

	/** Where the teleporters land: the colony's Continuity Office, or the world spawn when there is no colony. */
	private static Vec3 spawn(MinecraftServer server) {
		BlockPos at = Colony.respawnPoint(server).map(GlobalPos::pos).orElseGet(() -> server.overworld().getRespawnData().pos());
		return Vec3.atBottomCenterOf(at);
	}

	/** The Mole has one seat and no other chassis exists, so a test that wants a second rider gives this pod a second seat. */
	private static void addSeat(GameTestHelper helper, PodEntity pod) {
		try {
			Field chassis = PodEntity.class.getDeclaredField("chassis");
			chassis.setAccessible(true);
			chassis.set(pod, new Chassis("mole", 2, Chassis.MOLE.width(), Chassis.MOLE.height()));
		} catch (ReflectiveOperationException failure) {
			throw helper.assertionException("could not add a seat: %s", failure);
		}
	}

	private static void teleportFromLayerOne(GameTestHelper helper, Consumable teleporter, double x, double z, double scatter, boolean withPassenger) {
		ServerLevel one = helper.getLevel().getServer().getLevel(LayerChain.dimension(1));
		if (one == null) {
			throw helper.assertionException("layer 1 did not load");
		}
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayer pilot = MockPlayers.joinUnloaded(helper, "Teleportee");
		pilot.player().setGameMode(GameType.SURVIVAL);
		Charters.found(server, pilot.player().getUUID(), "Tele " + UUID.randomUUID().toString().substring(0, 8)).ifPresent(refusal -> {
			throw helper.assertionException("founding the charter: %s", refusal);
		});
		Charter charter = Charters.charterOf(server, pilot.player().getUUID()).orElseThrow();
		MockPlayer passenger = MockPlayers.joinUnloaded(helper, "Stowaway");
		BlockPos at = BlockPos.containing(x, 40, z);
		boolean[] used = {false};
		FarChunks.awaitEntityTicking(helper, one, at, () -> {
			// Riders joined unloaded, so waiting at the join point inside the colony's foundation could not harm them.
			// Only now: layer 1 is solid rock, and a rider put there while the chunk loads suffocates on a slow runner.
			pilot.teleportTo(one, new Vec3(x, 40, z), 0, 0);
			passenger.teleportTo(one, new Vec3(x, 40, z), 0, 0);
			pilot.markLoaded();
			passenger.markLoaded();
			PodEntity pod = PodRegistry.POD.create(one, EntitySpawnReason.COMMAND);
			pod.setPos(x, 40, z);
			one.addFreshEntity(pod);
			PodComponents.register(pod, charter.id());
			pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.IRONIUM));
			pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.IRONIUM));
			if (!pilot.player().startRiding(pod, true, false)) {
				throw helper.assertionException("the pilot could not board the pod");
			}
			if (withPassenger) {
				addSeat(helper, pod);
			}
			if (withPassenger && !passenger.player().startRiding(pod, true, false)) {
				throw helper.assertionException("the second rider could not board the pod");
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
			if (withPassenger) {
				// The arrived pod is a new entity built from the Mole's one seat, so the extra rider stands beside it: carried, not left behind.
				ServerPlayer second = passenger.player();
				if (second.level() != arrived.level() || second.position().distanceTo(arrived.position()) > 3.0) {
					throw helper.assertionException("the second rider should arrive beside the pod in %s, is at %s in %s", arrived.level().dimension(), second.position(), second.level().dimension());
				}
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
		teleportFromLayerOne(helper, Consumable.MATTER_TRANSMITTER, 2400.5, 2400.5, 0.0, false);
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 200)
	public void theMatterTransmitterTakesASecondPassengerToo(GameTestHelper helper) {
		teleportFromLayerOne(helper, Consumable.MATTER_TRANSMITTER, 2600.5, 2600.5, 0.0, true);
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 200)
	public void theQuantumTeleporterLandsWithinItsScatter(GameTestHelper helper) {
		teleportFromLayerOne(helper, Consumable.QUANTUM_TELEPORTER, 2500.5, 2500.5, RepairTuning.DEFAULT.quantumScatter(), false);
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 200)
	public void theQuantumTeleporterTakesASecondPassengerToo(GameTestHelper helper) {
		teleportFromLayerOne(helper, Consumable.QUANTUM_TELEPORTER, 2700.5, 2700.5, RepairTuning.DEFAULT.quantumScatter(), true);
	}

	@GameTest
	public void thePilotStaysSeatedAcrossATeleportWithinOneDimension(GameTestHelper helper) {
		// The Mole has one seat and a seat is the only way onto a pod, so the pilot is the one passenger there can be.
		withStation(helper, station -> {
			PodEntity pod = station.pod();
			board(helper, station);
			Vec3 before = pod.position();
			if (!useFromHotbar(helper, station, Consumable.MATTER_TRANSMITTER).consumesAction()) {
				throw helper.assertionException("the transmitter should work");
			}
			MinecraftServer server = helper.getLevel().getServer();
			if (pod.position().distanceTo(before) < 100 || Math.abs(pod.getX() - spawn(server).x) > 1.0) {
				throw helper.assertionException("the pod should be at the spawn, is at %s", pod.position());
			}
			if (station.pilot().player().getVehicle() != pod) {
				throw helper.assertionException("the pilot should still ride the pod, rides %s", station.pilot().player().getVehicle());
			}
			if (station.pilot().player().position().distanceTo(pod.position()) > 3.0) {
				throw helper.assertionException("the pilot is at %s, the pod at %s", station.pilot().player().position(), pod.position());
			}
			helper.succeed();
		});
	}

	@GameTest
	public void aTeleportGoesToTheColonyEvenWhenTheWorldSpawnMovedAway(GameTestHelper helper) {
		withStation(helper, station -> {
			MinecraftServer server = helper.getLevel().getServer();
			ServerLevel overworld = server.overworld();
			BlockPos office = Colony.respawnPoint(server).orElseThrow().pos();
			LevelData.RespawnData original = overworld.getRespawnData();
			overworld.setRespawnData(LevelData.RespawnData.of(Level.OVERWORLD, new BlockPos(office.getX() + 5000, 64, office.getZ()), 0f, 0f));
			try {
				board(helper, station);
				if (!useFromHotbar(helper, station, Consumable.MATTER_TRANSMITTER).consumesAction()) {
					throw helper.assertionException("the transmitter should work");
				}
			} finally {
				overworld.setRespawnData(original);
			}
			Vec3 pod = station.pod().position();
			if (station.pod().level() != overworld || Math.hypot(pod.x - (office.getX() + 0.5), pod.z - (office.getZ() + 0.5)) > 1.0) {
				throw helper.assertionException("the pod should be at the Continuity Office %s, is at %s in %s", office, pod, station.pod().level().dimension());
			}
			helper.succeed();
		});
	}

	@GameTest
	public void aTeleportToAnUnloadedChunkLoadsItAndArrives(GameTestHelper helper) {
		withStation(helper, station -> {
			ServerLevel overworld = helper.getLevel().getServer().overworld();
			int x = 9000;
			if (overworld.hasChunk(x >> 4, 0)) {
				throw helper.assertionException("chunk %d should not be loaded before the test", x >> 4);
			}
			withSpawnColumn(helper, x, false, ground -> {
				board(helper, station);
				if (!useFromHotbar(helper, station, Consumable.MATTER_TRANSMITTER).consumesAction()) {
					throw helper.assertionException("the transmitter should work into an unloaded chunk");
				}
				if (Math.abs(station.pod().getX() - (x + 0.5)) > 1.0 || station.pod().level() != overworld) {
					throw helper.assertionException("the pod should be at x=%d, is at %s", x, station.pod().position());
				}
				if (station.pilot().player().getVehicle() != station.pod()) {
					throw helper.assertionException("the pilot should still ride the pod");
				}
				// The chunk is loaded now: the pod must stand on its ground, not at the bottom of the world an unloaded chunk reports.
				int surface = overworld.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 64, 0)).getY();
				if (surface <= overworld.getMinY() || Math.abs(station.pod().getY() - surface) > 0.01) {
					throw helper.assertionException("the pod should stand at y=%d, is at y=%s", surface, station.pod().getY());
				}
			});
			helper.succeed();
		});
	}

	/**
	 * Runs {@code body} with the colony unbuilt and the world spawn (where the teleporters then land) moved to column {@code x}, 0
	 * and its blocks changed, and puts all three back after. The body gets the first free block above the ground there and must
	 * not await.
	 */
	private static void withSpawnColumn(GameTestHelper helper, int x, boolean load, Consumer<BlockPos> body, BlockPos... touched) {
		ServerLevel overworld = helper.getLevel().getServer().overworld();
		LevelData.RespawnData original = overworld.getRespawnData();
		ColonySite colony = ColonySite.get(overworld.getServer());
		overworld.getServer().getDataStorage().set(ColonySite.TYPE, new ColonySite());
		overworld.setRespawnData(LevelData.RespawnData.of(Level.OVERWORLD, new BlockPos(x, 64, 0), 0f, 0f));
		if (load) {
			overworld.getChunk(x >> 4, 0);
		}
		BlockPos ground = overworld.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 64, 0));
		Map<BlockPos, BlockState> saved = new HashMap<>();
		for (BlockPos offset : touched) {
			BlockPos at = ground.offset(offset);
			saved.put(at, overworld.getBlockState(at));
		}
		try {
			body.accept(ground);
		} finally {
			saved.forEach((at, state) -> overworld.setBlock(at, state, 3));
			overworld.setRespawnData(original);
			overworld.getServer().getDataStorage().set(ColonySite.TYPE, colony);
		}
	}

	private static void expectTeleportRefused(GameTestHelper helper, Station station, Consumable teleporter, String why) {
		PodEntity pod = station.pod();
		Vec3 before = pod.position();
		ServerLevel level = (ServerLevel) pod.level();
		int cargo = pod.cargo().entries().size();
		board(helper, station);
		if (useFromHotbar(helper, station, teleporter).consumesAction()) {
			throw helper.assertionException("%s: the teleporter should have been refused; pod at %s, spawn %s, level %s", why, pod.position(), spawn(helper.getLevel().getServer()), pod.level().dimension());
		}
		if (!pod.position().equals(before) || pod.level() != level) {
			throw helper.assertionException("%s: the pod moved from %s to %s", why, before, pod.position());
		}
		if (pod.cargo().entries().size() != cargo) {
			throw helper.assertionException("%s: the bay held %d ore and now holds %d", why, cargo, pod.cargo().entries().size());
		}
		if (!level.getEntitiesOfClass(ItemEntity.class, pod.getBoundingBox().inflate(8)).isEmpty()) {
			throw helper.assertionException("%s: cargo was spilled by a refused teleport", why);
		}
		if (station.pilot().player().getVehicle() != pod) {
			throw helper.assertionException("%s: the pilot left the pod", why);
		}
		expectSpent(helper, station.pilot().player(), teleporter, 1, why + ": the item is kept");
	}

	@GameTest
	public void aTeleportOntoLavaIsRefusedAndNothingChanges(GameTestHelper helper) {
		withStation(helper, station -> {
			station.pod().cargo().tryAdd(station.pod(), OreRegistry.stack(OreType.IRONIUM));
			withSpawnColumn(helper, 6000, true, ground -> {
				helper.getLevel().getServer().overworld().setBlock(ground, Blocks.LAVA.defaultBlockState(), 3);
				expectTeleportRefused(helper, station, Consumable.MATTER_TRANSMITTER, "lava at the landing");
			}, BlockPos.ZERO);
			helper.succeed();
		});
	}

	@GameTest
	public void aTeleportIntoAWallIsRefusedAndNothingChanges(GameTestHelper helper) {
		withStation(helper, station -> {
			station.pod().cargo().tryAdd(station.pod(), OreRegistry.stack(OreType.IRONIUM));
			BlockPos[] pillar = {BlockPos.ZERO.east(), BlockPos.ZERO.east().above(), BlockPos.ZERO.east().above(2), BlockPos.ZERO.east().above(3)};
			withSpawnColumn(helper, 6100, true, ground -> {
				for (BlockPos offset : pillar) {
					helper.getLevel().getServer().overworld().setBlock(ground.offset(offset), Blocks.STONE.defaultBlockState(), 3);
				}
				expectTeleportRefused(helper, station, Consumable.MATTER_TRANSMITTER, "a wall at the landing");
			}, pillar);
			helper.succeed();
		});
	}

	@GameTest
	public void theQuantumTeleporterTriesOtherSpotsBeforeRefusing(GameTestHelper helper) {
		withStation(helper, station -> {
			// The exact column is lava, but the quantum scatter has other spots to try.
			withSpawnColumn(helper, 6200, true, ground -> {
				helper.getLevel().getServer().overworld().setBlock(ground, Blocks.LAVA.defaultBlockState(), 3);
				board(helper, station);
				boolean worked = false;
				for (int attempt = 0; attempt < 10 && !worked; attempt++) {
					worked = useFromHotbar(helper, station, Consumable.QUANTUM_TELEPORTER).consumesAction();
				}
				if (!worked) {
					throw helper.assertionException("the quantum teleporter should find a spot off the lava");
				}
				if (station.pod().position().distanceTo(Vec3.atBottomCenterOf(ground)) < 0.5) {
					throw helper.assertionException("the pod landed on the lava column");
				}
			}, BlockPos.ZERO);
			helper.succeed();
		});
	}

	@GameTest
	public void aTeleportWithUnreadableCargoIsRefusedAndNothingChanges(GameTestHelper helper) {
		withStation(helper, station -> {
			PodEntity pod = station.pod();
			ServerLevel level = helper.getLevel();
			pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.IRONIUM));
			TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
			pod.saveWithoutId(output);
			CompoundTag tag = output.buildResult();
			tag.putInt("cargo_version", 99);
			pod.cargo().load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag), pod);
			if (pod.cargo().isReadable()) {
				throw helper.assertionException("the cargo should be unreadable now");
			}
			Vec3 before = pod.position();
			board(helper, station);
			if (useFromHotbar(helper, station, Consumable.MATTER_TRANSMITTER).consumesAction()) {
				throw helper.assertionException("a pod with unreadable cargo must not teleport");
			}
			if (!pod.position().equals(before) || pod.cargo().isReadable()) {
				throw helper.assertionException("the pod and its kept cargo must stay as they were");
			}
			expectSpent(helper, station.pilot().player(), Consumable.MATTER_TRANSMITTER, 1, "the item is kept");
			helper.succeed();
		});
	}

	/** Puts a damaged pod that {@code setUp} registers beside the station, has the station's pilot repair, and says whether it was repaired. */
	private static boolean repairsPodOf(GameTestHelper helper, Station station, Consumer<PodEntity> setUp) {
		return repairsPodOf(helper, station, station.pilot(), setUp);
	}

	/** {@link #repairsPodOf(GameTestHelper, Station, Consumer)} with {@code actor} pressing the button. */
	private static boolean repairsPodOf(GameTestHelper helper, Station station, MockPlayer actor, Consumer<PodEntity> setUp) {
		station.pod().discard();
		PodEntity other = helper.spawn(PodRegistry.POD, new Vec3(3.5, 1, 0.5));
		other.setHull(other.maxHull() - 20f);
		setUp.accept(other);
		Optional<TerminalRefusal> refusal = Terminals.act(actor.player(), station.pos(), RepairStation.REPAIR, hp(5));
		boolean repaired = refusal.isEmpty() && other.hull() == other.maxHull() - 15f;
		if (refusal.isPresent() && other.hull() != other.maxHull() - 20f) {
			throw helper.assertionException("a refused repair changed the hull to %s", other.hull());
		}
		other.discard();
		return repaired;
	}

	@GameTest
	public void repairFollowsPodComponentsMayAccess(GameTestHelper helper) {
		withStation(helper, station -> {
			MinecraftServer server = helper.getLevel().getServer();
			fund(helper, station, 100_000);
			if (!repairsPodOf(helper, station, pod -> { })) {
				throw helper.assertionException("an unregistered pod is anyone's, so it should be repaired");
			}
			if (!repairsPodOf(helper, station, pod -> PodComponents.register(pod, CharterId.random()))) {
				throw helper.assertionException("a pod whose owner charter is gone is anyone's, so it should be repaired");
			}
			MockPlayer lone = MockPlayers.join(helper, "Dormant Director");
			Charters.found(server, lone.player().getUUID(), "Dormant " + UUID.randomUUID().toString().substring(0, 8)).ifPresent(refusal -> {
				throw helper.assertionException("founding the dormant charter: %s", refusal);
			});
			CharterId dormant = Charters.charterOf(server, lone.player().getUUID()).orElseThrow().id();
			Charters.leave(server, lone.player().getUUID()).ifPresent(refusal -> {
				throw helper.assertionException("leaving: %s", refusal);
			});
			if (!Charters.find(server, dormant).orElseThrow().dormant()) {
				throw helper.assertionException("the charter should be dormant now");
			}
			if (!repairsPodOf(helper, station, pod -> PodComponents.register(pod, dormant))) {
				throw helper.assertionException("a pod of a dormant charter is anyone's, so it should be repaired");
			}
			if (!repairsPodOf(helper, station, pod -> PodComponents.register(pod, station.charter().id()))) {
				throw helper.assertionException("the player's own charter's pod should be repaired");
			}
			if (repairsPodOf(helper, station, pod -> pod.setAttached(PodComponents.STATE, new Versioned.Unreadable<PodComponents.State>(new CompoundTag())))) {
				throw helper.assertionException("a pod whose owner cannot be read must be refused");
			}

			MockPlayer member = MockPlayers.join(helper, "Crew Member");
			member.player().setGameMode(GameType.SURVIVAL);
			member.teleportTo(helper.getLevel(), station.pilot().player().position(), 0, 0);
			Charters.apply(server, member.player().getUUID(), station.charter().id()).ifPresent(refusal -> {
				throw helper.assertionException("applying to the charter: %s", refusal);
			});
			Charters.approve(server, station.pilot().player().getUUID(), member.player().getUUID()).ifPresent(refusal -> {
				throw helper.assertionException("approving the member: %s", refusal);
			});
			if (!repairsPodOf(helper, station, member, pod -> PodComponents.register(pod, station.charter().id()))) {
				throw helper.assertionException("a charter member may repair the charter's pod");
			}

			MockPlayer outsider = MockPlayers.join(helper, "Outsider");
			outsider.player().setGameMode(GameType.SURVIVAL);
			outsider.teleportTo(helper.getLevel(), station.pilot().player().position(), 0, 0);
			Charters.found(server, outsider.player().getUUID(), "Outsiders " + UUID.randomUUID().toString().substring(0, 8)).ifPresent(refusal -> {
				throw helper.assertionException("founding the outsiders' charter: %s", refusal);
			});
			Charters.deposit(server, Charters.charterOf(server, outsider.player().getUUID()).orElseThrow().id(), 1_000);
			if (repairsPodOf(helper, station, outsider, pod -> PodComponents.register(pod, station.charter().id()))) {
				throw helper.assertionException("a player of another charter must not repair this charter's pod");
			}

			MockPlayer drifter = MockPlayers.join(helper, "Drifter");
			drifter.player().setGameMode(GameType.SURVIVAL);
			drifter.teleportTo(helper.getLevel(), station.pilot().player().position(), 0, 0);
			if (repairsPodOf(helper, station, drifter, pod -> PodComponents.register(pod, station.charter().id()))) {
				throw helper.assertionException("a player with no charter must not repair a charter's pod");
			}
			helper.succeed();
		});
	}

	@GameTest
	public void aPartOfAHullPointIsChargedRoundedUp(GameTestHelper helper) {
		withStation(helper, station -> {
			PodEntity pod = station.pod();
			fund(helper, station, 1_000);
			pod.setHull(pod.maxHull() - 0.5f);
			expectDone(helper, Terminals.act(station.pilot().player(), station.pos(), RepairStation.REPAIR_TOTAL, new CompoundTag()), "repairing half a point");
			expectHull(helper, pod, pod.maxHull(), "after half a point");
			expectAccount(helper, station, 1_000 - 8, "7.5 dollars are charged as 8");
			helper.succeed();
		});
	}

	@GameTest
	public void floatNoiseInTheHullDoesNotAddADollar(GameTestHelper helper) {
		withStation(helper, station -> {
			PodEntity pod = station.pod();
			fund(helper, station, 1_000);
			pod.setHull(pod.maxHull() - 3.00001f);
			expectDone(helper, Terminals.act(station.pilot().player(), station.pos(), RepairStation.REPAIR_TOTAL, new CompoundTag()), "repairing 3 points");
			expectAccount(helper, station, 1_000 - 3 * PER_HP, "3 points cost $45 whatever the float noise");
			helper.succeed();
		});
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
