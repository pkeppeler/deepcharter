package io.github.pkeppeler.deepcharter.test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonyEvents;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.hangar.Hangar;
import io.github.pkeppeler.deepcharter.hangar.HangarData;
import io.github.pkeppeler.deepcharter.hangar.HangarParts;
import io.github.pkeppeler.deepcharter.hangar.HangarTerminal;
import io.github.pkeppeler.deepcharter.hangar.HangarTuning;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.Serials;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalEvents;
import io.github.pkeppeler.deepcharter.terminal.TerminalRefusal;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.LogCapture;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.wreck.WreckRegistry;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;

/**
 * Server GameTests for #77: the derelict Mole in the hangar, its repair by the first charter, a refurbished Mole for a later
 * one, and the restore of a wreck.
 *
 * <p>The hangar data, the repair state and the serials are one each per world. A test that changes them swaps in fresh ones for
 * its own duration ({@link #withFreshWorld}) and puts the world's back at the end. Each such test does all its work inside one
 * tick after the hangar's chunk ticks, so no two of them overlap. A test discards every pod it made, and none of the world's.
 */
public class FoundingMoleHangarTest {
	static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 200;
	static final long RICH = 100_000;
	private static final int FLOOR = 9;
	private static final String FUTURE_HANGAR = "7741";
	private static final String FUTURE_SERIALS = "7742";

	static final Item CATALYST = OreRegistry.item(HangarTuning.DEFAULT.catalyst());
	private static final List<Item> PARTS = HangarParts.ALL;
	private static final List<String> REPAIRED_BY = new ArrayList<>();

	static {
		TerminalEvents.REPAIRED.register((server, type, charter, player) -> {
			if (type == HangarTerminal.TYPE) {
				REPAIRED_BY.add(charter.name());
			}
		});
	}

	// assertionException(String, Object...) leaves the placeholders unfilled in the report.
	static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}

	static void expect(GameTestHelper helper, boolean condition, String format, Object... args) {
		if (!condition) {
			throw failure(helper, format, args);
		}
	}

	static MinecraftServer server(GameTestHelper helper) {
		return helper.getLevel().getServer();
	}

	private static BlockPos hangarAnchor(GameTestHelper helper) {
		return Colony.anchor(server(helper), ColonyAnchor.HANGAR).orElseThrow(() -> failure(helper, "the colony was not built when the server started"));
	}

	/**
	 * Waits for the hangar's chunk to tick and for its entities to load (a tick or more after the chunk ticks), then runs
	 * {@code body} with the pods that stood in the hangar before it.
	 */
	static void inTheHangar(GameTestHelper helper, Consumer<Set<UUID>> body) {
		boolean[] chunkTicks = {false};
		boolean[] done = {false};
		FarChunks.awaitEntityTicking(helper, server(helper).overworld(), hangarAnchor(helper), () -> chunkTicks[0] = true);
		helper.onEachTick(() -> {
			if (!chunkTicks[0] || done[0]) {
				return;
			}
			if (Hangar.derelict(server(helper)).isEmpty()) {
				expect(helper, helper.getTick() <= FarChunks.AWAIT_BUDGET_TICKS, "the derelict Mole did not load in %s ticks", FarChunks.AWAIT_BUDGET_TICKS);
				return;
			}
			done[0] = true;
			Set<UUID> before = new HashSet<>();
			podsInTheHangar(helper).forEach(pod -> before.add(pod.getUUID()));
			try {
				body.accept(before);
			} finally {
				// Only the pods this test made: the world's own derelict Mole stays.
				podsInTheHangar(helper).stream().filter(pod -> !before.contains(pod.getUUID())).forEach(PodEntity::discard);
			}
		});
	}

	/** Runs {@code body} with a fresh hangar record, repair state and serials, and puts the world's own back after. */
	static void withFreshWorld(GameTestHelper helper, Runnable body) {
		MinecraftServer server = server(helper);
		HangarData hangar = HangarData.get(server);
		RepairState repairs = RepairState.get(server);
		Serials serials = Serials.get(server);
		server.getDataStorage().set(HangarData.TYPE, new HangarData());
		server.getDataStorage().set(RepairState.TYPE, new RepairState());
		server.getDataStorage().set(Serials.TYPE, new Serials());
		try {
			body.run();
		} finally {
			server.getDataStorage().set(HangarData.TYPE, hangar);
			server.getDataStorage().set(RepairState.TYPE, repairs);
			server.getDataStorage().set(Serials.TYPE, serials);
		}
	}

	/** Every pod standing in or near the hangar bay, wherever it came from. */
	static List<PodEntity> podsInTheHangar(GameTestHelper helper) {
		return server(helper).overworld().getEntitiesOfClass(PodEntity.class,
				new AABB(hangarAnchor(helper)).inflate(HangarTuning.DEFAULT.bayRadius() + 4));
	}

	private static List<PodEntity> madeSince(GameTestHelper helper, Set<UUID> before) {
		return podsInTheHangar(helper).stream().filter(pod -> !before.contains(pod.getUUID())).toList();
	}

	static MockPlayer member(GameTestHelper helper, String name) {
		MockPlayer mock = MockPlayers.join(helper, name);
		mock.player().setGameMode(GameType.SURVIVAL);
		if (Charters.found(server(helper), mock.player().getUUID(), name + " " + UUID.randomUUID().toString().substring(0, 8)).isPresent()) {
			throw failure(helper, "founding a charter for %s should succeed", name);
		}
		return mock;
	}

	static Charter charterOf(GameTestHelper helper, MockPlayer mock) {
		return Charters.charterOf(server(helper), mock.player().getUUID()).orElseThrow();
	}

	static long balance(GameTestHelper helper, MockPlayer mock) {
		return charterOf(helper, mock).account();
	}

	static void deposit(GameTestHelper helper, MockPlayer mock, long amount) {
		if (Charters.deposit(server(helper), charterOf(helper, mock).id(), amount).isPresent()) {
			throw failure(helper, "depositing %s should succeed", amount);
		}
	}

	/** Puts the player 2 blocks from the middle of {@code pos}, eyes level with it. */
	static void stand(GameTestHelper helper, MockPlayer mock, BlockPos pos) {
		Vec3 centre = Vec3.atCenterOf(pos);
		mock.teleportTo(helper.getLevel(), new Vec3(centre.x + 2, centre.y - mock.player().getEyeHeight(), centre.z), 0, 0);
	}

	/** A floor with a console on it, and the player next to it. The wrecks of a test lie on this floor. */
	static BlockPos console(GameTestHelper helper, MockPlayer mock) {
		for (int x = 0; x < FLOOR; x++) {
			for (int z = 0; z < FLOOR; z++) {
				helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
			}
		}
		BlockPos relative = new BlockPos(1, 2, 1);
		helper.setBlock(relative, HangarTerminal.TYPE.block().defaultBlockState());
		BlockPos pos = helper.absolutePos(relative);
		stand(helper, mock, pos);
		return pos;
	}

	static void clearFloor(GameTestHelper helper) {
		for (int x = 0; x < FLOOR; x++) {
			for (int z = 0; z < FLOOR; z++) {
				helper.setBlock(new BlockPos(x, 1, z), Blocks.AIR);
				helper.setBlock(new BlockPos(x, 2, z), Blocks.AIR);
			}
		}
	}

	/** A pod on the floor of the test, owned by {@code owner} if there is one, and a wreck if {@code wrecked}. */
	private static PodEntity pod(GameTestHelper helper, Optional<CharterId> owner, boolean wrecked) {
		PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(5.5, 2, 5.5));
		owner.ifPresent(charter -> PodComponents.register(pod, charter));
		if (wrecked) {
			pod.damageHull(pod.maxHull());
		}
		return pod;
	}

	/** A wreck owned by {@code owner}, standing at {@code at} in the world. */
	private static PodEntity wreckAt(GameTestHelper helper, Vec3 at, CharterId owner) {
		PodEntity pod = PodRegistry.POD.create(helper.getLevel(), EntitySpawnReason.TRIGGERED);
		pod.setPos(at);
		helper.getLevel().addFreshEntity(pod);
		PodComponents.register(pod, owner);
		pod.damageHull(pod.maxHull());
		return pod;
	}

	static void give(ServerPlayer player, Item item, int count) {
		for (int i = 0; i < count; i++) {
			player.getInventory().add(new ItemStack(item));
		}
	}

	static int count(ServerPlayer player, Item item) {
		int total = 0;
		for (ItemStack stack : player.getInventory()) {
			if (stack.is(item)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	/** Puts the four parts into the repair state, so the console works but the founding Mole is not recorded as repaired. */
	private static void insertTheParts(GameTestHelper helper) {
		RepairState state = RepairState.get(server(helper));
		for (Item part : PARTS) {
			expect(helper, state.insert(HangarTerminal.TYPE, part).isEmpty(), "inserting %s straight into the repair state should work", part);
		}
	}

	/** A hangar record in which the founding Mole is repaired, as the saved form of an empty one with {@code founded} set. */
	private static HangarData foundedHangar() {
		CompoundTag saved = ((CompoundTag) HangarData.CODEC.encodeStart(NbtOps.INSTANCE, new HangarData()).getOrThrow()).copy();
		saved.putBoolean("founded", true);
		return HangarData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
	}

	/** Repairs the console and the founding Mole, as if someone had: the world's hangar record is the one {@link #withFreshWorld} swapped in. */
	static void repairTheConsole(GameTestHelper helper) {
		insertTheParts(helper);
		server(helper).getDataStorage().set(HangarData.TYPE, foundedHangar());
	}

	/** Runs {@code body} with a fresh repair state and puts the world's back after. */
	private static void withFreshRepairs(GameTestHelper helper, Runnable body) {
		MinecraftServer server = server(helper);
		RepairState repairs = RepairState.get(server);
		server.getDataStorage().set(RepairState.TYPE, new RepairState());
		try {
			body.run();
		} finally {
			server.getDataStorage().set(RepairState.TYPE, repairs);
		}
	}

	static Optional<TerminalRefusal> act(ServerPlayer player, BlockPos pos, Identifier action) {
		return Terminals.act(player, pos, action, new CompoundTag());
	}

	static void expectRefused(GameTestHelper helper, Optional<TerminalRefusal> actual, String what) {
		expect(helper, actual.equals(Optional.of(TerminalRefusal.ACTION_REFUSED)), "%s should be refused by the hangar, got %s", what, actual);
	}

	static void expectDone(GameTestHelper helper, Optional<TerminalRefusal> actual, String what) {
		expect(helper, actual.isEmpty(), "%s should work, was refused: %s", what, actual);
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void theDerelictMoleIsPlacedInTheHangarExactlyOnce(GameTestHelper helper) {
		inTheHangar(helper, before -> {
			MinecraftServer server = server(helper);
			PodEntity derelict = Hangar.derelict(server).orElseThrow(() -> failure(helper, "the colony's world should hold the derelict Mole"));
			expect(helper, Wrecks.isWreck(derelict) && PodComponents.registration(derelict).isEmpty(),
					"the derelict Mole is a wreck that nobody owns");
			expect(helper, derelict.blockPosition().closerThan(hangarAnchor(helper), 2), "the derelict stands at the hangar anchor, it is at %s", derelict.blockPosition());
			int pods = podsInTheHangar(helper).size();

			// The colony was built once, so it is not built again: the event fires again only here.
			ColonySite.Placed placed = Colony.placed(server).orElseThrow();
			ColonyEvents.BUILT.invoker().onBuilt(server, placed);
			expect(helper, podsInTheHangar(helper).size() == pods && Hangar.derelict(server).orElseThrow() == derelict,
					"a second BUILT must not place a second derelict: %s pods before, %s after", pods, podsInTheHangar(helper).size());

			// A world saved and loaded again remembers it, so it places none on a restart.
			Tag saved = HangarData.CODEC.encodeStart(NbtOps.INSTANCE, HangarData.get(server)).getOrThrow();
			HangarData reloaded = HangarData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
			expect(helper, reloaded.state().derelict().equals(Optional.of(derelict.getUUID())), "the saved hangar names the derelict, it names %s", reloaded.state());

			withFreshWorld(helper, () -> {
				Hangar.onBuilt(server, placed);
				Hangar.onBuilt(server, placed);
				int added = podsInTheHangar(helper).size() - pods;
				expect(helper, added == 1, "a hangar with no record places one derelict, and not one for each call: it placed %s", added);
			});
			helper.succeed();
		});
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void theFourPartsRepairTheDerelictAndTheFirstCharterOwnsIt(GameTestHelper helper) {
		inTheHangar(helper, before -> withFreshWorld(helper, () -> {
			MinecraftServer server = server(helper);
			REPAIRED_BY.clear();
			Hangar.onBuilt(server, Colony.placed(server).orElseThrow());
			PodEntity derelict = Hangar.derelict(server).orElseThrow();
			MockPlayer first = member(helper, "Founder");
			MockPlayer second = member(helper, "Latecomer");
			BlockPos console = console(helper, first);
			stand(helper, second, console);
			give(first.player(), PARTS.get(0), 1);
			give(first.player(), PARTS.get(1), 1);
			give(second.player(), PARTS.get(2), 1);
			give(second.player(), PARTS.get(3), 1);
			expect(helper, PARTS.size() == 4, "the founding Mole needs four parts, it lists %s", PARTS.size());

			expectDone(helper, Terminals.insertPart(first.player(), console, PARTS.get(0)), "the first part");
			expectDone(helper, Terminals.insertPart(first.player(), console, PARTS.get(1)), "the second part");
			expectDone(helper, Terminals.insertPart(second.player(), console, PARTS.get(2)), "the third part");
			expect(helper, Wrecks.isWreck(derelict) && PodComponents.registration(derelict).isEmpty() && REPAIRED_BY.isEmpty(),
					"three parts leave the Mole a wreck that nobody owns");
			expectDone(helper, Terminals.insertPart(second.player(), console, PARTS.get(3)), "the last part");

			PodComponents.Registration registration = PodComponents.registration(derelict).orElseThrow(() -> failure(helper, "the repaired Mole should be registered"));
			expect(helper, registration.owner().equals(charterOf(helper, second).id()),
					"the charter that puts in the last part repairs it, and owns it: the owner is %s", registration.owner());
			expect(helper, registration.serial().equals("MOLE-0001"), "the founding Mole is MOLE-0001, it is %s", registration.serial());
			expect(helper, !Wrecks.isWreck(derelict) && derelict.hull() > 0f, "a repaired Mole is not a wreck, it has hull %s", derelict.hull());
			expect(helper, REPAIRED_BY.equals(List.of(charterOf(helper, second).name())), "the repair fires once: %s", REPAIRED_BY);
			expect(helper, PodEvents.canMount(derelict, second.player()) && PodEvents.isPowered(derelict), "the owner can pilot the repaired Mole");
			expect(helper, !PodEvents.canMount(derelict, first.player()), "another charter cannot pilot it");
			expect(helper, HangarData.get(server).state().founded(), "the hangar records the founding");

			// The first to repair it owns it: nobody repairs it a second time.
			expect(helper, Terminals.insertPart(first.player(), console, PARTS.get(0)).equals(Optional.of(TerminalRefusal.ALREADY_REPAIRED)),
					"a repaired console takes no part");
			expect(helper, PodComponents.registration(derelict).orElseThrow().owner().equals(charterOf(helper, second).id()), "the owner stays");
			clearFloor(helper);
			helper.succeed();
		}));
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void aRefurbishedMoleCostsFiveHundredPlusTwoFiftyForEachPodTheCharterHas(GameTestHelper helper) {
		inTheHangar(helper, before -> withFreshWorld(helper, () -> {
			repairTheConsole(helper);
			MockPlayer buyer = member(helper, "Buyer");
			MockPlayer newcomer = member(helper, "Newcomer");
			BlockPos console = console(helper, buyer);
			stand(helper, newcomer, console);
			deposit(helper, buyer, RICH);
			deposit(helper, newcomer, RICH);
			CharterId charter = charterOf(helper, buyer).id();
			// The founding Mole is the buyer's first pod. Pods of another charter do not count.
			pod(helper, Optional.of(charter), false);
			pod(helper, Optional.of(charterOf(helper, newcomer).id()), false);
			pod(helper, Optional.of(charterOf(helper, newcomer).id()), false);

			long[] prices = {500 + 250, 500 + 250 * 2, 500 + 250 * 3};
			long expected = RICH;
			for (int owned = 1; owned <= prices.length; owned++) {
				long price = prices[owned - 1];
				expect(helper, HangarTuning.DEFAULT.refurbishedPrice(owned) == price, "the price for %s pods is %s, the tuning says %s", owned, price,
						HangarTuning.DEFAULT.refurbishedPrice(owned));
				int podsBefore = madeSince(helper, before).size();
				expectDone(helper, act(buyer.player(), console, HangarTerminal.BUY_MOLE), "buying a refurbished Mole with " + owned + " pods");
				expected -= price;
				expect(helper, balance(helper, buyer) == expected, "with %s pods the Mole costs exactly $%s: the account holds %s, should hold %s", owned, price,
						balance(helper, buyer), expected);
				List<PodEntity> bought = madeSince(helper, before);
				expect(helper, bought.size() == podsBefore + 1, "buying makes one pod in the hangar, the hangar holds %s new", bought.size());
				PodEntity newest = bought.stream().filter(pod -> !pod.isRemoved() && PodComponents.registration(pod).isPresent()
						&& PodComponents.registration(pod).get().owner().equals(charter)).reduce((a, b) -> b).orElseThrow();
				expect(helper, !Wrecks.isWreck(newest) && newest.hull() > 0f, "a refurbished Mole works");
				// The bought pod is a pod of the charter now, so the next one costs $250 more.
			}
			// A charter with two pods pays $500 + $500, and is not charged for the buyer's pods.
			long newcomerBefore = balance(helper, newcomer);
			expectDone(helper, act(newcomer.player(), console, HangarTerminal.BUY_MOLE), "the newcomer buying");
			expect(helper, newcomerBefore - balance(helper, newcomer) == 500 + 250 * 2, "the newcomer has two pods, so it pays $1000: it paid %s",
					newcomerBefore - balance(helper, newcomer));
			clearFloor(helper);
			helper.succeed();
		}));
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void aMoleIsNotForSaleUntilTheFoundingMoleIsRepaired(GameTestHelper helper) {
		inTheHangar(helper, before -> withFreshWorld(helper, () -> {
			insertTheParts(helper);
			MockPlayer buyer = member(helper, "Early Buyer");
			BlockPos console = console(helper, buyer);
			deposit(helper, buyer, RICH);

			expectRefused(helper, act(buyer.player(), console, HangarTerminal.BUY_MOLE), "buying before the founding Mole is repaired");
			expect(helper, balance(helper, buyer) == RICH && madeSince(helper, before).isEmpty(), "the refusal takes nothing and makes no pod");

			server(helper).getDataStorage().set(HangarData.TYPE, foundedHangar());
			expectDone(helper, act(buyer.player(), console, HangarTerminal.BUY_MOLE), "buying once the founding Mole is repaired");
			clearFloor(helper);
			helper.succeed();
		}));
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void aRefurbishedMoleRefusesOnInsufficientFundsAndChangesNothing(GameTestHelper helper) {
		inTheHangar(helper, before -> withFreshWorld(helper, () -> {
			repairTheConsole(helper);
			MockPlayer buyer = member(helper, "Poor Buyer");
			BlockPos console = console(helper, buyer);
			CharterId charter = charterOf(helper, buyer).id();
			pod(helper, Optional.of(charter), false);
			long price = HangarTuning.DEFAULT.refurbishedPrice(1);
			deposit(helper, buyer, price - 1);

			expectRefused(helper, act(buyer.player(), console, HangarTerminal.BUY_MOLE), "buying one dollar short");
			expect(helper, balance(helper, buyer) == price - 1, "a refused purchase takes nothing: the account holds %s", balance(helper, buyer));
			expect(helper, madeSince(helper, before).isEmpty(), "a refused purchase makes no pod");

			deposit(helper, buyer, 1);
			expectDone(helper, act(buyer.player(), console, HangarTerminal.BUY_MOLE), "buying with exactly the price");
			expect(helper, balance(helper, buyer) == 0, "exactly the price empties the account, it holds %s", balance(helper, buyer));
			PodEntity bought = madeSince(helper, before).getFirst();
			// The refused purchase used no serial: the pod made before it is MOLE-0001, so this one is MOLE-0002.
			expect(helper, PodComponents.registration(bought).orElseThrow().serial().equals("MOLE-0002"),
					"a refused purchase burns no serial, the bought Mole is %s", PodComponents.registration(bought).orElseThrow().serial());
			clearFloor(helper);
			helper.succeed();
		}));
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void theHangarRestoresAWreckForMoneyAndACatalyst(GameTestHelper helper) {
		inTheHangar(helper, before -> withFreshWorld(helper, () -> {
			repairTheConsole(helper);
			MockPlayer owner = member(helper, "Salvager");
			BlockPos console = console(helper, owner);
			CharterId charter = charterOf(helper, owner).id();
			PodEntity wreck = pod(helper, Optional.of(charter), true);
			deposit(helper, owner, RICH);
			give(owner.player(), CATALYST, HangarTuning.DEFAULT.restoreCost(Chassis.MOLE).catalysts() + 1);
			expect(helper, Wrecks.isWreck(wreck) && !PodEvents.isPowered(wreck), "the pod starts as a wreck");

			expectDone(helper, act(owner.player(), console, HangarTerminal.RESTORE_WRECK), "restoring a wreck");
			expect(helper, balance(helper, owner) == RICH - HangarTuning.DEFAULT.restoreCost(Chassis.MOLE).money(), "restoring costs $%s: the account holds %s",
					HangarTuning.DEFAULT.restoreCost(Chassis.MOLE).money(), balance(helper, owner));
			expect(helper, count(owner.player(), CATALYST) == 1, "restoring uses up %s catalyst, the player holds %s", HangarTuning.DEFAULT.restoreCost(Chassis.MOLE).catalysts(),
					count(owner.player(), CATALYST));
			expect(helper, !Wrecks.isWreck(wreck) && wreck.hull() == wreck.maxHull(), "the restored pod is whole: hull %s of %s", wreck.hull(), wreck.maxHull());
			expect(helper, PodEvents.canMount(wreck, owner.player()) && PodEvents.isPowered(wreck), "the restored pod can be piloted and has power");
			wreck.setFuel(10f);
			wreck.damageHull(1f);
			expect(helper, !Wrecks.isWreck(wreck), "a restored pod takes damage like any other and is not a wreck again");

			// Nothing left to restore: the hangar refuses and charges nothing.
			long held = balance(helper, owner);
			expectRefused(helper, act(owner.player(), console, HangarTerminal.RESTORE_WRECK), "restoring a pod that works");
			expect(helper, balance(helper, owner) == held && count(owner.player(), CATALYST) == 1, "a refused restore takes nothing");
			clearFloor(helper);
			helper.succeed();
		}));
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void aStrangersNearerWreckDoesNotBlockTheRestoreOfYourOwn(GameTestHelper helper) {
		inTheHangar(helper, before -> withFreshWorld(helper, () -> {
			repairTheConsole(helper);
			MockPlayer owner = member(helper, "Owner");
			MockPlayer stranger = member(helper, "Neighbour");
			BlockPos console = console(helper, owner);
			PodEntity strangers = wreckAt(helper, helper.absoluteVec(new Vec3(2.5, 2, 2.5)), charterOf(helper, stranger).id());
			PodEntity own = pod(helper, Optional.of(charterOf(helper, owner).id()), true);
			expect(helper, strangers.position().distanceTo(Vec3.atCenterOf(console)) < own.position().distanceTo(Vec3.atCenterOf(console)),
					"the stranger's wreck is nearer the console");
			deposit(helper, owner, RICH);
			give(owner.player(), CATALYST, HangarTuning.DEFAULT.restoreCost(Chassis.MOLE).catalysts());

			expectDone(helper, act(owner.player(), console, HangarTerminal.RESTORE_WRECK), "restoring your own wreck past a stranger's nearer one");
			expect(helper, !Wrecks.isWreck(own) && Wrecks.isWreck(strangers), "the owner's wreck is restored and the stranger's is not");
			clearFloor(helper);
			helper.succeed();
		}));
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void theWorldsDerelictNearestTheConsoleDoesNotBlockTheRestoreOfYourOwn(GameTestHelper helper) {
		inTheHangar(helper, before -> withFreshRepairs(helper, () -> {
			insertTheParts(helper);
			MinecraftServer server = server(helper);
			BlockPos console = Hangar.consolePos(server).orElseThrow(() -> failure(helper, "the hangar should have a console"));
			PodEntity derelict = Hangar.derelict(server).orElseThrow();
			MockPlayer owner = member(helper, "Bay Salvager");
			stand(helper, owner, console);
			BlockPos anchor = hangarAnchor(helper);
			PodEntity own = wreckAt(helper, Vec3.atBottomCenterOf(anchor.offset(-4, 0, 6)), charterOf(helper, owner).id());
			expect(helper, derelict.position().distanceTo(Vec3.atCenterOf(console)) < own.position().distanceTo(Vec3.atCenterOf(console)),
					"the derelict is nearer the console than the charter's wreck");
			deposit(helper, owner, RICH);
			give(owner.player(), CATALYST, HangarTuning.DEFAULT.restoreCost(Chassis.MOLE).catalysts());

			expectDone(helper, act(owner.player(), console, HangarTerminal.RESTORE_WRECK), "restoring your own wreck farther than the derelict");
			expect(helper, !Wrecks.isWreck(own) && Wrecks.isWreck(derelict) && PodComponents.registration(derelict).isEmpty(),
					"the charter's wreck is restored and the derelict is left alone");
			helper.succeed();
		}));
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void aRestoreRefusesWithoutTheCatalystOrTheMoneyAndChangesNothing(GameTestHelper helper) {
		inTheHangar(helper, before -> withFreshWorld(helper, () -> {
			repairTheConsole(helper);
			MockPlayer owner = member(helper, "Short");
			MockPlayer stranger = member(helper, "Stranger");
			BlockPos console = console(helper, owner);
			stand(helper, stranger, console);
			CharterId charter = charterOf(helper, owner).id();
			PodEntity wreck = pod(helper, Optional.of(charter), true);
			long money = HangarTuning.DEFAULT.restoreCost(Chassis.MOLE).money();

			// Money but no catalyst.
			deposit(helper, owner, RICH);
			expectRefused(helper, act(owner.player(), console, HangarTerminal.RESTORE_WRECK), "restoring without the catalyst");
			expect(helper, Wrecks.isWreck(wreck) && balance(helper, owner) == RICH, "a restore without the catalyst changes nothing");

			// A catalyst but a dollar short.
			MockPlayer poor = member(helper, "Poor");
			stand(helper, poor, console);
			PodEntity poorWreck = pod(helper, Optional.of(charterOf(helper, poor).id()), true);
			deposit(helper, poor, money - 1);
			give(poor.player(), CATALYST, HangarTuning.DEFAULT.restoreCost(Chassis.MOLE).catalysts());
			expectRefused(helper, act(poor.player(), console, HangarTerminal.RESTORE_WRECK), "restoring a dollar short");
			expect(helper, Wrecks.isWreck(poorWreck) && balance(helper, poor) == money - 1
					&& count(poor.player(), CATALYST) == HangarTuning.DEFAULT.restoreCost(Chassis.MOLE).catalysts(), "a restore without the money changes nothing");

			// Both, but the wreck belongs to another charter.
			deposit(helper, stranger, RICH);
			give(stranger.player(), CATALYST, HangarTuning.DEFAULT.restoreCost(Chassis.MOLE).catalysts());
			expectRefused(helper, act(stranger.player(), console, HangarTerminal.RESTORE_WRECK), "restoring another charter's wreck");
			expect(helper, Wrecks.isWreck(wreck) && balance(helper, stranger) == RICH
					&& count(stranger.player(), CATALYST) == HangarTuning.DEFAULT.restoreCost(Chassis.MOLE).catalysts(), "a restore of another charter's wreck changes nothing");

			// The founding Mole is not a wreck to buy back: it is repaired with its four parts. The real console stands beside it.
			// This world's own derelict is not in the fresh hangar record, so it stands out of reach while the fresh one is tested.
			PodEntity worldDerelict = podsInTheHangar(helper).stream().filter(pod -> before.contains(pod.getUUID())).findFirst().orElseThrow();
			Vec3 home = worldDerelict.position();
			worldDerelict.setPos(home.add(0, 100, 0));
			server(helper).getDataStorage().set(HangarData.TYPE, new HangarData());
			try {
				Hangar.onBuilt(server(helper), Colony.placed(server(helper)).orElseThrow());
				PodEntity derelict = Hangar.derelict(server(helper)).orElseThrow();
				BlockPos hangarConsole = Hangar.consolePos(server(helper)).orElseThrow(() -> failure(helper, "the hangar should have a console"));
				expect(helper, server(helper).overworld().getBlockState(hangarConsole).is(HangarTerminal.TYPE.block()), "the colony stands a console in the hangar");
				stand(helper, stranger, hangarConsole);
				expectRefused(helper, act(stranger.player(), hangarConsole, HangarTerminal.RESTORE_WRECK), "restoring the derelict Mole");
				expect(helper, Wrecks.isWreck(derelict) && PodComponents.registration(derelict).isEmpty() && balance(helper, stranger) == RICH,
						"the derelict Mole stays a wreck that nobody owns");
			} finally {
				worldDerelict.setPos(home);
			}
			clearFloor(helper);
			helper.succeed();
		}));
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void unreadableDataIsLoggedOnceAndSkippedWithoutThrowing(GameTestHelper helper) {
		inTheHangar(helper, before -> withFreshWorld(helper, () -> {
			MinecraftServer server = server(helper);
			ColonySite.Placed placed = Colony.placed(server).orElseThrow();
			Tag current = HangarData.CODEC.encodeStart(NbtOps.INSTANCE, HangarData.get(server)).getOrThrow();
			CompoundTag future = ((CompoundTag) current).copy();
			future.putInt("version", Integer.parseInt(FUTURE_HANGAR));
			// Started before the first check of the data: the data logs the first time anything asks if it is readable.
			LogCapture hangarLog = LogCapture.start(FUTURE_HANGAR);
			HangarData unreadable = HangarData.CODEC.parse(NbtOps.INSTANCE, future).getOrThrow();
			expect(helper, !unreadable.isReadable() && HangarData.CODEC.encodeStart(NbtOps.INSTANCE, unreadable).getOrThrow().equals(future),
					"data of version %s loads as unreadable and is written back unchanged", FUTURE_HANGAR);
			try {
				unreadable.state();
				throw failure(helper, "an explicit use of unreadable data should throw");
			} catch (IllegalStateException expected) {
				// Explicit API calls throw; tick, join and callback paths do not.
			}

			repairTheConsole(helper);
			MockPlayer player = member(helper, "Reader");
			BlockPos console = console(helper, player);
			deposit(helper, player, RICH);
			give(player.player(), CATALYST, 2);
			PodEntity wreck = pod(helper, Optional.of(charterOf(helper, player).id()), true);
			PodEntity odd = pod(helper, Optional.of(charterOf(helper, player).id()), false);
			int pods = podsInTheHangar(helper).size();

			// The hangar record is unreadable: the colony event, the repair event and both actions skip it.
			server.getDataStorage().set(HangarData.TYPE, unreadable);
			// Only the derelict is skipped: the console is a block and is placed even when the hangar record cannot be read.
			BlockPos hangarConsole = Hangar.consolePos(server).orElseThrow();
			server.overworld().setBlock(hangarConsole, Blocks.AIR.defaultBlockState(), 3);
			Hangar.onBuilt(server, placed);
			expect(helper, server.overworld().getBlockState(hangarConsole).is(HangarTerminal.TYPE.block()), "the console is placed although the hangar data is unreadable");
			ColonyEvents.BUILT.invoker().onBuilt(server, placed);
			TerminalEvents.REPAIRED.invoker().onRepaired(server, HangarTerminal.TYPE, charterOf(helper, player), player.player());
			expectRefused(helper, act(player.player(), console, HangarTerminal.BUY_MOLE), "buying with an unreadable hangar");
			expectRefused(helper, act(player.player(), console, HangarTerminal.RESTORE_WRECK), "restoring with an unreadable hangar");
			expect(helper, Hangar.derelict(server).isEmpty(), "an unreadable hangar knows no derelict");
			expect(helper, hangarLog.errors().size() == 1, "unreadable hangar data is logged once, it was logged %s times: %s", hangarLog.errors().size(), hangarLog.errors());
			expect(helper, podsInTheHangar(helper).size() == pods && Wrecks.isWreck(wreck) && balance(helper, player) == RICH && count(player.player(), CATALYST) == 2,
					"nothing changed while the hangar was unreadable");

			// The serials are unreadable: a purchase is refused before it takes anything.
			server.getDataStorage().set(HangarData.TYPE, foundedHangar());
			Tag serials = Serials.CODEC.encodeStart(NbtOps.INSTANCE, new Serials()).getOrThrow();
			CompoundTag futureSerials = ((CompoundTag) serials).copy();
			futureSerials.putInt("version", Integer.parseInt(FUTURE_SERIALS));
			server.getDataStorage().set(Serials.TYPE, Serials.CODEC.parse(NbtOps.INSTANCE, futureSerials).getOrThrow());
			LogCapture serialsLog = LogCapture.start(FUTURE_SERIALS);
			expectRefused(helper, act(player.player(), console, HangarTerminal.BUY_MOLE), "buying with unreadable serials");
			expectRefused(helper, act(player.player(), console, HangarTerminal.BUY_MOLE), "buying again with unreadable serials");
			expect(helper, serialsLog.errors().size() == 1, "unreadable serials are logged once, they were logged %s times: %s", serialsLog.errors().size(), serialsLog.errors());
			expect(helper, balance(helper, player) == RICH && podsInTheHangar(helper).size() == pods, "a purchase refused for the serials takes nothing and makes no pod");

			// A pod whose wreck state is unreadable is not a wreck the hangar can see.
			odd.setAttached(WreckRegistry.STATE, new Versioned.Unreadable<>(new CompoundTag()));
			wreck.discard();
			expectRefused(helper, act(player.player(), console, HangarTerminal.RESTORE_WRECK), "restoring a pod with unreadable wreck state");
			expect(helper, balance(helper, player) == RICH && count(player.player(), CATALYST) == 2, "nothing was taken for a pod it could not restore");
			clearFloor(helper);
			helper.succeed();
		}));
	}
}
