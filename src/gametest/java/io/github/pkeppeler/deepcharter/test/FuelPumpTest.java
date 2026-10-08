package io.github.pkeppeler.deepcharter.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.fuel.FuelPump;
import io.github.pkeppeler.deepcharter.fuel.FuelRegistry;
import io.github.pkeppeler.deepcharter.fuel.FuelTuning;
import io.github.pkeppeler.deepcharter.fuel.ReserveTank;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodFuelItems;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalRefusal;
import io.github.pkeppeler.deepcharter.terminal.TerminalTuning;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
import io.github.pkeppeler.deepcharter.test.support.LogCapture;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.WorldData;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTuning;

/**
 * Server GameTests for #69: the pump sells fuel at a dollar a litre into a parked pod, an empty account refuses, biofuel is
 * crafted from four crops and refuels, and a reserve tank adds 25 litres of capacity and clears Stranded.
 */
public class FuelPumpTest {
	private static final float EPSILON = 0.001f;
	private static final float FULL = PodTuning.DEFAULT.shell().fullFuel();
	private static final float TANK = PodTuning.DEFAULT.fuel().tankLitres();
	private static final FuelTuning TUNING = FuelTuning.DEFAULT;
	private static final String ATTACHMENTS_KEY = "fabric:attachments";
	private static final AtomicInteger CHARTERS = new AtomicInteger();

	/** A pump, a floor, a player and a pod, on a charter with {@code balance} dollars. */
	private record Scene(MockPlayer mock, BlockPos pump, PodEntity pod, CharterId charter) {
		ServerPlayer player() {
			return mock.player();
		}
	}

	/** Runs {@code body} with the pump repaired and puts the world's own repair state back after. */
	private static void withRepairedPump(MinecraftServer server, Runnable body) {
		RepairState fresh = new RepairState();
		TerminalTypes.FUEL_PUMP.parts().forEach(part -> fresh.insert(TerminalTypes.FUEL_PUMP, part));
		WorldData.with(server, RepairState.TYPE, fresh, body);
	}

	private static Scene scene(GameTestHelper helper, long balance) {
		MinecraftServer server = helper.getLevel().getServer();
		for (int x = 0; x <= 8; x++) {
			for (int z = 0; z <= 8; z++) {
				helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
			}
		}
		BlockPos pump = helper.absolutePos(new BlockPos(1, 2, 1));
		helper.getLevel().setBlock(pump, TerminalTypes.FUEL_PUMP.block().defaultBlockState(), 3);
		MockPlayer mock = MockPlayers.join(helper, "pump-buyer-" + CHARTERS.incrementAndGet());
		mock.player().setGameMode(GameType.SURVIVAL);
		if (Charters.found(server, mock.player().getUUID(), "Pump Test " + CHARTERS.incrementAndGet()).isPresent()) {
			throw helper.assertionException("founding the charter should succeed");
		}
		CharterId charter = Charters.charterOfOrThrow(server, mock.player().getUUID()).orElseThrow().id();
		if (balance > 0 && Charters.deposit(server, charter, balance).isPresent()) {
			throw helper.assertionException("the deposit should succeed");
		}
		Vec3 centre = Vec3.atCenterOf(pump);
		mock.teleportTo(helper.getLevel(), new Vec3(centre.x + 1, centre.y - mock.player().getEyeHeight(), centre.z), 0, 0);
		PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(4.5, 2, 1.5));
		PodComponents.register(pod, charter);
		return new Scene(mock, pump, pod, charter);
	}

	private static long balance(GameTestHelper helper, CharterId charter) {
		return Charters.findOrThrow(helper.getLevel().getServer(), charter).orElseThrow().account();
	}

	private static float litres(PodEntity pod) {
		return pod.fuel() / FULL * PodStats.of(pod).tankLitres();
	}

	private static Optional<TerminalRefusal> buy(Scene scene, int litres) {
		CompoundTag args = new CompoundTag();
		args.putInt(FuelPump.LITRES_KEY, litres);
		return Terminals.act(scene.player(), scene.pump(), FuelPump.BUY, args);
	}

	private static Optional<TerminalRefusal> fill(Scene scene) {
		return Terminals.act(scene.player(), scene.pump(), FuelPump.FILL, new CompoundTag());
	}

	private static void run(GameTestHelper helper, Scene scene, Consumer<Scene> body) {
		try {
			withRepairedPump(helper.getLevel().getServer(), () -> body.accept(scene));
			helper.succeed();
		} finally {
			scene.pod().discard();
		}
	}

	@GameTest
	public void buyingLitresDebitsExactlyThatManyDollarsAndAddsThatManyLitres(GameTestHelper helper) {
		Scene scene = scene(helper, 100);
		run(helper, scene, s -> {
			s.pod().setFuel(0f);
			s.pod().setStranded(true);
			expectDone(helper, buy(s, 4), "buying 4 litres");
			expectEqual(helper, "dollars after buying 4", 96, balance(helper, s.charter()));
			expectEqual(helper, "litres after buying 4", 4f, litres(s.pod()));
			if (s.pod().stranded()) {
				throw helper.assertionException("fuel in the tank should clear Stranded");
			}
			expectDone(helper, buy(s, 3), "buying 3 more");
			expectEqual(helper, "dollars after buying 7 in all", 93, balance(helper, s.charter()));
			expectEqual(helper, "litres after buying 7 in all", 7f, litres(s.pod()));
			if (TUNING.pricePerLitre() != 1) {
				throw helper.assertionException("the pump sells at a dollar a litre, not %s", TUNING.pricePerLitre());
			}
		});
	}

	@GameTest
	public void fillBuysUpToAFullTank(GameTestHelper helper) {
		Scene scene = scene(helper, 100);
		run(helper, scene, s -> {
			s.pod().setFuel(30f);
			expectDone(helper, fill(s), "filling a 30 percent tank");
			expectEqual(helper, "litres after a fill", TANK, litres(s.pod()));
			expectEqual(helper, "dollars after a fill", 93, balance(helper, s.charter()));
			expectRefused(helper, TerminalRefusal.ACTION_REFUSED, fill(s), "filling a full tank");
			expectRefused(helper, TerminalRefusal.ACTION_REFUSED, buy(s, 1), "buying into a full tank");
			expectEqual(helper, "dollars after the refused purchases", 93, balance(helper, s.charter()));
		});
	}

	@GameTest
	public void anEmptyAccountRefusesAndChangesNothing(GameTestHelper helper) {
		Scene scene = scene(helper, 0);
		run(helper, scene, s -> {
			s.pod().setFuel(0f);
			s.pod().setStranded(true);
			expectRefused(helper, TerminalRefusal.ACTION_REFUSED, buy(s, 1), "buying with an empty account");
			expectRefused(helper, TerminalRefusal.ACTION_REFUSED, fill(s), "filling with an empty account");
			expectEqual(helper, "litres after the refusals", 0f, litres(s.pod()));
			expectEqual(helper, "dollars after the refusals", 0, balance(helper, s.charter()));
			if (!s.pod().stranded()) {
				throw helper.assertionException("a refused purchase must not clear Stranded");
			}
		});
	}

	@GameTest
	public void aPartialAccountRefusesAnExactPurchaseAndFillsOnlyWhatItCanPayFor(GameTestHelper helper) {
		Scene scene = scene(helper, 3);
		run(helper, scene, s -> {
			s.pod().setFuel(0f);
			expectRefused(helper, TerminalRefusal.ACTION_REFUSED, buy(s, 5), "buying 5 litres with $3");
			expectEqual(helper, "dollars after the refused purchase", 3, balance(helper, s.charter()));
			expectEqual(helper, "litres after the refused purchase", 0f, litres(s.pod()));
			expectDone(helper, fill(s), "filling with $3");
			expectEqual(helper, "dollars after a partial fill", 0, balance(helper, s.charter()));
			expectEqual(helper, "litres after a partial fill", 3f, litres(s.pod()));
		});
	}

	@GameTest
	public void theArgumentIsUntrusted(GameTestHelper helper) {
		Scene scene = scene(helper, 50);
		run(helper, scene, s -> {
			s.pod().setFuel(0f);
			for (int litres : new int[] {0, -5, Integer.MAX_VALUE}) {
				expectRefused(helper, TerminalRefusal.ACTION_REFUSED, buy(s, litres), "buying " + litres + " litres");
			}
			expectRefused(helper, TerminalRefusal.ACTION_REFUSED,
					Terminals.act(s.player(), s.pump(), FuelPump.BUY, new CompoundTag()), "buying with no amount");
			expectEqual(helper, "dollars after the bad requests", 50, balance(helper, s.charter()));
			expectEqual(helper, "litres after the bad requests", 0f, litres(s.pod()));
		});
	}

	@GameTest
	public void theFuelGoesIntoAParkedPodOfTheCharterOrNobody(GameTestHelper helper) {
		Scene scene = scene(helper, 50);
		run(helper, scene, s -> {
			s.pod().setFuel(0f);
			// Out of range: the pod is not at the pump.
			Vec3 parked = s.pod().position();
			s.pod().setPos(parked.add(TerminalTuning.DEFAULT.parkedRadius() + 3, 0, 0));
			expectRefused(helper, TerminalRefusal.ACTION_REFUSED, buy(s, 1), "buying with the pod out of range");
			s.pod().setPos(parked);

			// Another charter's pod is not served, and the other pod in range is not touched.
			CharterId other = otherCharter(helper);
			PodEntity foreign = helper.spawn(PodRegistry.POD, new Vec3(3.5, 2, 1.5));
			try {
				PodComponents.register(foreign, other);
				foreign.setFuel(0f);
				s.pod().setPos(parked.add(TerminalTuning.DEFAULT.parkedRadius() + 3, 0, 0));
				expectRefused(helper, TerminalRefusal.ACTION_REFUSED, buy(s, 1), "buying with only another charter's pod in range");
				expectEqual(helper, "litres in the other charter's pod", 0f, litres(foreign));
				s.pod().setPos(parked);

				// A pod in flight is not parked.
				s.pod().setFlying(true);
				expectRefused(helper, TerminalRefusal.ACTION_REFUSED, buy(s, 1), "buying into a flying pod");
				s.pod().setFlying(false);

				expectDone(helper, buy(s, 2), "buying with the own pod and another charter's pod in range");
				expectEqual(helper, "litres in the own pod", 2f, litres(s.pod()));
				expectEqual(helper, "litres in the other charter's pod, still", 0f, litres(foreign));
			} finally {
				foreign.discard();
			}

			// A pod nobody owns is anyone's.
			PodEntity unowned = helper.spawn(PodRegistry.POD, new Vec3(3.5, 2, 1.5));
			try {
				s.pod().setPos(parked.add(TerminalTuning.DEFAULT.parkedRadius() + 3, 0, 0));
				unowned.setFuel(0f);
				expectDone(helper, buy(s, 2), "buying into an unowned pod");
				expectEqual(helper, "litres in the unowned pod", 2f, litres(unowned));
			} finally {
				unowned.discard();
			}
		});
	}

	@GameTest
	public void aPodWithUnreadableComponentsIsNotServed(GameTestHelper helper) {
		Scene scene = scene(helper, 50);
		CompoundTag future = new CompoundTag();
		future.putInt("version", 99);
		CompoundTag attachments = new CompoundTag();
		attachments.put(PodComponents.STATE.identifier().toString(), future);
		Vec3 spot = scene.pod().position();
		Entity loaded = reload(helper, scene.pod(), attachments);
		try {
			loaded.setPos(spot);
			helper.getLevel().addFreshEntity(loaded);
			PodEntity copy = (PodEntity) loaded;
			copy.setFuel(0f);
			withRepairedPump(helper.getLevel().getServer(), () ->
					expectRefused(helper, TerminalRefusal.ACTION_REFUSED, buy(scene, 1), "buying into a pod whose owner cannot be read"));
			expectEqual(helper, "litres", 0f, litres(copy));
			expectEqual(helper, "dollars", 50, balance(helper, scene.charter()));
			helper.succeed();
		} finally {
			loaded.discard();
		}
	}

	@GameTest
	public void aDormantOwnersPodIsServedToAnyCharter(GameTestHelper helper) {
		Scene scene = scene(helper, 50);
		run(helper, scene, s -> {
			MinecraftServer server = helper.getLevel().getServer();
			UUID founder = UUID.randomUUID();
			if (Charters.found(server, founder, "Dormant Pump Test " + CHARTERS.incrementAndGet()).isPresent()) {
				throw helper.assertionException("founding the dormant charter should succeed");
			}
			CharterId dormant = Charters.charterOfOrThrow(server, founder).orElseThrow().id();
			if (Charters.leave(server, founder).isPresent() || !Charters.findOrThrow(server, dormant).orElseThrow().dormant()) {
				throw helper.assertionException("the only director leaving should make the charter dormant");
			}
			PodEntity orphan = helper.spawn(PodRegistry.POD, new Vec3(3.5, 2, 1.5));
			PodComponents.register(orphan, dormant);
			s.pod().setPos(s.pod().position().add(TerminalTuning.DEFAULT.parkedRadius() + 3, 0, 0));
			try {
				orphan.setFuel(0f);
				expectDone(helper, buy(s, 2), "buying into a pod whose owner charter is dormant");
				expectEqual(helper, "litres in the dormant charter's pod", 2f, litres(orphan));
				expectEqual(helper, "dollars", 48, balance(helper, s.charter()));
			} finally {
				orphan.discard();
			}
		});
	}

	@GameTest
	public void anExactPurchaseBeyondTheRoomIsRefused(GameTestHelper helper) {
		// 4.2 litres of room: 5.8 of 10 litres is in the tank. BUY n is exact, so it takes whole litres of the room only.
		Scene scene = scene(helper, 50);
		run(helper, scene, s -> {
			s.pod().setFuel(58f);
			expectRefused(helper, TerminalRefusal.ACTION_REFUSED, buy(s, 5), "buying 5 litres into 4.2 litres of room");
			expectEqual(helper, "dollars after the refusal", 50, balance(helper, s.charter()));
			expectEqual(helper, "litres after the refusal", 5.8f, litres(s.pod()));
			expectDone(helper, buy(s, 4), "buying 4 litres into 4.2 litres of room");
			expectEqual(helper, "dollars after buying 4", 46, balance(helper, s.charter()));
			expectEqual(helper, "litres after buying 4", 9.8f, litres(s.pod()));
		});
	}

	@GameTest
	public void fillRoundsFractionalRoomUpToAWholeLitre(GameTestHelper helper) {
		Scene scene = scene(helper, 50);
		run(helper, scene, s -> {
			s.pod().setFuel(58f);
			expectDone(helper, fill(s), "filling 4.2 litres of room");
			expectEqual(helper, "dollars after the fill", 45, balance(helper, s.charter()));
			expectEqual(helper, "litres after the fill", TANK, litres(s.pod()));
		});
	}

	@GameTest
	public void anUnrepairedPumpSellsNothing(GameTestHelper helper) {
		Scene scene = scene(helper, 50);
		try {
			scene.pod().setFuel(0f);
			WorldData.with(helper.getLevel().getServer(), RepairState.TYPE, new RepairState(),
					() -> expectRefused(helper, TerminalRefusal.UNREPAIRED, buy(scene, 1), "buying from an unrepaired pump"));
			expectEqual(helper, "dollars", 50, balance(helper, scene.charter()));
			helper.succeed();
		} finally {
			scene.pod().discard();
		}
	}

	@GameTest
	public void biofuelIsCraftedFromFourCropsAndRefuels(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		for (Item crop : List.of(Items.WHEAT, Items.CARROT, Items.POTATO, Items.BEETROOT)) {
			ItemStack made = craft(helper, List.of(new ItemStack(crop), new ItemStack(crop), new ItemStack(crop), new ItemStack(crop)));
			if (!made.is(FuelRegistry.BIOFUEL) || made.getCount() != 1) {
				throw helper.assertionException("four %s should craft one biofuel, got %s", crop, made);
			}
		}
		ItemStack mixed = craft(helper, List.of(new ItemStack(Items.WHEAT), new ItemStack(Items.CARROT), new ItemStack(Items.POTATO), new ItemStack(Items.BEETROOT)));
		if (!mixed.is(FuelRegistry.BIOFUEL)) {
			throw helper.assertionException("four different crops should craft biofuel, got %s", mixed);
		}
		ItemStack three = craft(helper, List.of(new ItemStack(Items.WHEAT), new ItemStack(Items.WHEAT), new ItemStack(Items.WHEAT)));
		if (!three.isEmpty()) {
			throw helper.assertionException("three crops should craft nothing, got %s", three);
		}
		ItemStack sticks = craft(helper, List.of(new ItemStack(Items.STICK), new ItemStack(Items.STICK), new ItemStack(Items.STICK), new ItemStack(Items.STICK)));
		if (!sticks.isEmpty()) {
			throw helper.assertionException("four sticks are not crops, got %s", sticks);
		}

		var litres = PodFuelItems.litresOf(new ItemStack(FuelRegistry.BIOFUEL));
		if (litres.isEmpty()) {
			throw helper.assertionException("biofuel should be a pod fuel with a litres entry");
		}
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		MockPlayer player = MockPlayers.join(helper, "biofuel-user");
		try {
			pod.setFuel(0f);
			pod.setStranded(true);
			player.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(FuelRegistry.BIOFUEL, 2));
			InteractionResult result = UseEntityCallback.EVENT.invoker().interact(player.player(), level, InteractionHand.MAIN_HAND, pod, null);
			if (!result.consumesAction()) {
				throw helper.assertionException("using biofuel on a pod should refuel it, got %s", result);
			}
			expectEqual(helper, "litres after one biofuel", (float) litres.getAsDouble(), litres(pod));
			if (pod.stranded()) {
				throw helper.assertionException("biofuel should clear Stranded");
			}
			helper.succeed();
		} finally {
			player.leave();
			pod.discard();
		}
	}

	@GameTest
	public void aReserveTankAddsTwentyFiveLitresOfCapacityAndKeepsTheLitres(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		try {
			pod.setFuel(60f);
			expectEqual(helper, "stock litres", 6f, litres(pod));
			if (!ReserveTank.install(pod)) {
				throw helper.assertionException("a reserve tank should install into a pod without one");
			}
			expectEqual(helper, "tank litres with the reserve", TANK + 25f, PodStats.of(pod).tankLitres());
			expectEqual(helper, "litres are constant", 6f, litres(pod));
			expectEqual(helper, "the stored percent was rescaled", 6f / (TANK + 25f) * FULL, pod.fuel());
			if (TUNING.reserveLitres() != 25f) {
				throw helper.assertionException("the reserve adds 25 litres, not %s", TUNING.reserveLitres());
			}
			if (ReserveTank.install(pod)) {
				throw helper.assertionException("a pod takes one reserve tank");
			}
			expectEqual(helper, "tank litres after a second install", TANK + 25f, PodStats.of(pod).tankLitres());
			helper.succeed();
		} finally {
			pod.discard();
		}
	}

	@GameTest
	public void theReserveAddsExactlyTwentyFiveOnTopOfABiggerTank(GameTestHelper helper) {
		CharterId charter = otherCharter(helper);
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		try {
			PodComponents.register(pod, charter);
			PodComponents.install(pod, ComponentItems.mint(helper.getLevel().getServer(), ComponentTrack.FUEL_TANK, 1, charter));
			float tank = PodStats.of(pod).tankLitres();
			pod.setFuel(50f);
			float before = litres(pod);
			ReserveTank.install(pod);
			expectEqual(helper, "tank with a part and the reserve", tank + 25f, PodStats.of(pod).tankLitres());
			expectEqual(helper, "litres are constant", before, litres(pod));
			// A part installed after the reserve keeps the litres too, and the reserve stays 25 on top.
			PodComponents.install(pod, ComponentItems.mint(helper.getLevel().getServer(), ComponentTrack.FUEL_TANK, 2, charter));
			expectEqual(helper, "tank after a better part", UpgradeTuning.DEFAULT.value(ComponentTrack.FUEL_TANK, 2) + 25f, PodStats.of(pod).tankLitres());
			expectEqual(helper, "litres after a better part", before, litres(pod));
			helper.succeed();
		} finally {
			pod.discard();
		}
	}

	@GameTest
	public void aReserveTankClearsStranded(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		try {
			pod.setFuel(0f);
			pod.setStranded(true);
			ReserveTank.install(pod);
			if (pod.stranded()) {
				throw helper.assertionException("the reserve tank should clear Stranded");
			}
			expectEqual(helper, "litres in the reserve", TUNING.reserveLitres(), litres(pod));
			helper.succeed();
		} finally {
			pod.discard();
		}
	}

	@GameTest
	public void theReserveTankItemInstallsOnAPodAndIsUsedUp(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		MockPlayer player = MockPlayers.join(helper, "reserve-user");
		player.player().setGameMode(GameType.SURVIVAL);
		try {
			pod.setFuel(0f);
			pod.setStranded(true);
			player.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(FuelRegistry.RESERVE_TANK, 2));
			InteractionResult result = use(player.player(), pod);
			if (!result.consumesAction() || !ReserveTank.isInstalled(pod) || pod.stranded()) {
				throw helper.assertionException("using the reserve tank should install it and clear Stranded, got %s", result);
			}
			int left = player.player().getItemInHand(InteractionHand.MAIN_HAND).getCount();
			if (left != 1) {
				throw helper.assertionException("one reserve tank should be used up, %s are left", left);
			}
			use(player.player(), pod);
			left = player.player().getItemInHand(InteractionHand.MAIN_HAND).getCount();
			if (left != 1) {
				throw helper.assertionException("a pod that has a reserve must not use up another, %s are left", left);
			}
			helper.succeed();
		} finally {
			player.leave();
			pod.discard();
		}
	}

	@GameTest
	public void anotherCharterCannotFitAReserveTank(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		MockPlayer player = MockPlayers.join(helper, "reserve-outsider");
		player.player().setGameMode(GameType.SURVIVAL);
		try {
			PodComponents.register(pod, otherCharter(helper));
			player.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(FuelRegistry.RESERVE_TANK));
			use(player.player(), pod);
			if (ReserveTank.isInstalled(pod) || player.player().getItemInHand(InteractionHand.MAIN_HAND).getCount() != 1) {
				throw helper.assertionException("a player of another charter must not fit a reserve tank");
			}
			helper.succeed();
		} finally {
			player.leave();
			pod.discard();
		}
	}

	@GameTest
	public void theReserveTankSurvivesSaveAndLoad(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		pod.setFuel(40f);
		ReserveTank.install(pod);
		float before = PodStats.of(pod).tankLitres();
		Entity loaded = reload(helper, pod, null);
		try {
			PodEntity copy = (PodEntity) loaded;
			if (!ReserveTank.isInstalled(copy) || PodStats.of(copy).tankLitres() != before) {
				throw helper.assertionException("the reserve tank did not survive a reload");
			}
			// Version 1 is the first format of this attachment, so there is no earlier format to load. A new version must
			// add that test here, next to the unknown-version test below.
			helper.succeed();
		} finally {
			loaded.discard();
		}
	}

	@GameTest
	public void anUnreadableReserveStateIsKeptAndTheStatsPathSkipsIt(GameTestHelper helper) {
		String key = ReserveTank.STATE.identifier().toString();
		CompoundTag future = new CompoundTag();
		future.putInt("version", 99);
		future.putString("added-in-v99", "kept");
		CompoundTag attachments = new CompoundTag();
		attachments.put(key, future);
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		LogCapture log = LogCapture.start(key);
		Entity loaded = reload(helper, pod, attachments);
		try {
			PodEntity copy = (PodEntity) loaded;
			if (!(copy.getAttached(ReserveTank.STATE) instanceof Versioned.Unreadable<ReserveTank.State>)) {
				throw helper.assertionException("version 99 should load as Unreadable, got %s", copy.getAttached(ReserveTank.STATE));
			}
			// None of these may throw: they run every tick and on the client.
			if (!PodStats.of(copy).equals(PodStats.base()) || ReserveTank.isInstalled(copy)) {
				throw helper.assertionException("a pod with an unreadable reserve should run on stock stats");
			}
			PodStats.of(copy);
			try {
				ReserveTank.install(copy);
				throw helper.assertionException("installing into an unreadable state must fail");
			} catch (IllegalStateException expected) {
				if (!expected.getMessage().contains(key)) {
					throw helper.assertionException("the failure should name the attachment, got: %s", expected.getMessage());
				}
			}
			TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
			copy.saveWithoutId(output);
			CompoundTag saved = output.buildResult().getCompound(ATTACHMENTS_KEY).orElseThrow();
			if (!future.equals(saved.get(key))) {
				throw helper.assertionException("the unreadable data was not written back unchanged: %s", saved.get(key));
			}
			long logged = log.errors().size();
			if (logged != 1) {
				throw helper.assertionException("the unreadable reserve should be logged once, was logged %s times", logged);
			}
			helper.succeed();
		} finally {
			loaded.discard();
		}
	}

	private static InteractionResult use(Player player, PodEntity pod) {
		return UseEntityCallback.EVENT.invoker().interact(player, pod.level(), InteractionHand.MAIN_HAND, pod, null);
	}

	private static CharterId otherCharter(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		UUID founder = UUID.randomUUID();
		if (Charters.found(server, founder, "Other Pump Test " + CHARTERS.incrementAndGet()).isPresent()) {
			throw helper.assertionException("founding the other charter should succeed");
		}
		return Charters.charterOfOrThrow(server, founder).orElseThrow().id();
	}

	/** The result of putting {@code ingredients} into a crafting grid, or empty when no recipe matches. */
	private static ItemStack craft(GameTestHelper helper, List<ItemStack> ingredients) {
		List<ItemStack> grid = new ArrayList<>(ingredients);
		while (grid.size() < 4) {
			grid.add(ItemStack.EMPTY);
		}
		CraftingInput input = CraftingInput.of(2, 2, grid);
		ServerLevel level = helper.getLevel();
		return level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, level)
				.map(holder -> holder.value().assemble(input))
				.orElse(ItemStack.EMPTY);
	}

	private static Entity reload(GameTestHelper helper, PodEntity pod, CompoundTag attachments) {
		ServerLevel level = helper.getLevel();
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		pod.saveWithoutId(output);
		pod.discard();
		CompoundTag tag = output.buildResult();
		if (attachments != null) {
			tag.put(ATTACHMENTS_KEY, attachments);
		}
		return EntityType.create(PodRegistry.POD,
				TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag),
				level, EntitySpawnReason.LOAD).orElseThrow(() -> helper.assertionException("the saved pod did not load"));
	}

	private static void expectEqual(GameTestHelper helper, String what, float expected, float actual) {
		if (Math.abs(expected - actual) > EPSILON) {
			throw helper.assertionException(Component.literal(String.format("%s: expected %s, got %s", what, expected, actual)));
		}
	}

	private static void expectDone(GameTestHelper helper, Optional<TerminalRefusal> refusal, String what) {
		if (refusal.isPresent()) {
			throw helper.assertionException("%s should succeed, was refused: %s", what, refusal.get());
		}
	}

	private static void expectRefused(GameTestHelper helper, TerminalRefusal expected, Optional<TerminalRefusal> actual, String what) {
		if (!actual.equals(Optional.of(expected))) {
			throw helper.assertionException("%s should be refused with %s, got %s", what, expected, actual);
		}
	}
}
