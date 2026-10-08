package io.github.pkeppeler.deepcharter.test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.colony.FounderStatue;
import io.github.pkeppeler.deepcharter.fuel.FuelPump;
import io.github.pkeppeler.deepcharter.hangar.HangarData;
import io.github.pkeppeler.deepcharter.hangar.HangarTerminal;
import io.github.pkeppeler.deepcharter.handbook.HandbookTriggers;
import io.github.pkeppeler.deepcharter.handbook.HandbookTuning;
import io.github.pkeppeler.deepcharter.layer.BreachEvents;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.market.OreProcessor;
import io.github.pkeppeler.deepcharter.market.WorkOrder;
import io.github.pkeppeler.deepcharter.market.WorkOrderData;
import io.github.pkeppeler.deepcharter.market.WorkOrders;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTowing;
import io.github.pkeppeler.deepcharter.pod.Serials;
import io.github.pkeppeler.deepcharter.repair.RepairStation;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalRefusal;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.M2SliceEndState;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTerminal;

/**
 * Server GameTest for #85: the scripted run of the evidence scenario {@code m2-slice}, without a client. Two players on one charter
 * do what the nine chapters ask, in the order the scenario does them, through the real terminals, pod events and crossings, and the
 * run ends in {@link M2SliceEndState}, which the scenario asks for too. Where the scenario drives a pod with keys, this test sets
 * the pod's state and runs the same trigger poll the game runs, as the tests of each chapter do. A chapter, directive or work order
 * added to the game fails this test until the slice covers it.
 */
public class M2SliceTest {
	private static final double X = 9300.5;
	private static final double Z = 9300.5;
	private static final BlockPos TERMINAL = new BlockPos(0, 1, 0);
	private static final long FUNDS = 50_000;

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 400)
	public void theScriptedSliceReachesItsEndState(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel one = layer(helper, server, 1);
		ServerLevel two = layer(helper, server, 2);
		AtomicInteger ready = new AtomicInteger();
		Runnable arrived = () -> {
			if (ready.incrementAndGet() == 3) {
				runTheSlice(helper, server, one, two);
			}
		};
		FarChunks.awaitEntityTicking(helper, one, BlockPos.containing(X, zoneY(one, 2), Z), arrived);
		FarChunks.awaitEntityTicking(helper, two, BlockPos.containing(X, zoneY(two, 2), Z), arrived);
		// The colony's chunk too: a pod there is found by its UUID, which a cable needs, only once the chunk ticks entities.
		FarChunks.awaitEntityTicking(helper, server.overworld(), Colony.placed(server).orElseThrow().center(), arrived);
	}

	private static ServerLevel layer(GameTestHelper helper, MinecraftServer server, int layer) {
		ServerLevel level = server.getLevel(LayerChain.dimension(layer));
		if (level == null) {
			throw helper.assertionException("layer %s did not load", layer);
		}
		return level;
	}

	private static double zoneY(ServerLevel level, int index) {
		Zones.Span span = Zones.span(level.getMinY(), level.getHeight(), index);
		return span.low() + span.size() / 2.0;
	}

	private static void expect(GameTestHelper helper, boolean condition, String format, Object... args) {
		if (!condition) {
			throw helper.assertionException(net.minecraft.network.chat.Component.literal(String.format(format, args)));
		}
	}

	private static void expectDone(GameTestHelper helper, java.util.Optional<TerminalRefusal> refusal, String what) {
		expect(helper, refusal.isEmpty(), "%s should succeed, was refused: %s", what, refusal);
	}

	private static void poll(PodEntity pod) {
		pod.tickCount = 0;
		PodEvents.AFTER_TICK.invoker().afterTick(pod);
	}

	private static void repair(GameTestHelper helper, ServerPlayer player, BlockPos terminal, TerminalType type) {
		helper.getLevel().setBlock(terminal, type.block().defaultBlockState(), 3);
		for (var part : type.parts()) {
			player.getInventory().add(new net.minecraft.world.item.ItemStack(part));
			expectDone(helper, Terminals.insertPart(player, terminal, part), "putting " + part + " into " + type.id());
		}
	}

	private static void runTheSlice(GameTestHelper helper, MinecraftServer server, ServerLevel one, ServerLevel two) {
		RepairState repairs = RepairState.get(server);
		HangarData hangar = HangarData.get(server);
		Serials serials = Serials.get(server);
		WorkOrderData orders = WorkOrderData.get(server);
		server.getDataStorage().set(RepairState.TYPE, new RepairState());
		server.getDataStorage().set(HangarData.TYPE, new HangarData());
		server.getDataStorage().set(Serials.TYPE, new Serials());
		server.getDataStorage().set(WorkOrderData.TYPE, new WorkOrderData());
		List<PodEntity> pods = new ArrayList<>();
		MockPlayer director = MockPlayers.join(helper, "Director");
		MockPlayer crew = MockPlayers.join(helper, "Crew");
		try {
			ServerPlayer first = director.player();
			ServerPlayer second = crew.player();
			first.setGameMode(GameType.SURVIVAL);
			second.setGameMode(GameType.SURVIVAL);
			BlockPos terminal = helper.absolutePos(TERMINAL);
			HandbookChaptersOneToFiveTest.stand(helper, director, terminal);
			HandbookChaptersOneToFiveTest.stand(helper, crew, terminal);

			// The charter: the director founds it, the crewmate joins, and it has money.
			expect(helper, Charters.found(server, first.getUUID(), HandbookChaptersOneToFiveTest.uniqueName()).isEmpty(), "founding should succeed");
			CharterId charter = Charters.charterOf(server, first.getUUID()).orElseThrow().id();
			expect(helper, Charters.apply(server, second.getUUID(), charter).isEmpty() && Charters.approve(server, first.getUUID(), second.getUUID()).isEmpty()
					&& Charters.deposit(server, charter, FUNDS).isEmpty(), "the crewmate should join the charter, which is then funded");

			// Chapter 1.
			for (var item : List.of(Items.OAK_LOG, Items.CRAFTING_TABLE, Items.STONE_PICKAXE, Items.RAW_IRON, Items.IRON_INGOT)) {
				HandbookChaptersOneToFiveTest.give(first, item);
			}

			// Chapter 2, and the repair station, which the hull repair of chapter 7 needs.
			repair(helper, first, terminal, TerminalTypes.FUEL_PUMP);
			repair(helper, first, terminal, TerminalTypes.ORE_PROCESSOR);
			repair(helper, first, terminal, TerminalTypes.UPGRADE_TERMINAL);
			repair(helper, first, terminal, TerminalTypes.REPAIR_STATION);

			// Chapter 3: the hangar console, then boarding the Mole.
			repair(helper, second, terminal, HangarTerminal.TYPE);
			PodEntity mole = helper.spawn(PodRegistry.POD, 2, 1, 2);
			pods.add(mole);
			PodComponents.register(mole, charter);
			expect(helper, first.startRiding(mole, true, false), "the player should board the Mole");
			poll(mole);

			// Chapter 4: the pump, flight, the bore and home.
			first.stopRiding();
			HandbookChaptersOneToFiveTest.stand(helper, director, terminal);
			helper.getLevel().setBlock(terminal, TerminalTypes.FUEL_PUMP.block().defaultBlockState(), 3);
			mole.setPos(Vec3.atBottomCenterOf(terminal).add(2, 0, 2));
			mole.setFuel(0f);
			expectDone(helper, Terminals.act(first, terminal, FuelPump.FILL, new CompoundTag()), "filling the tank");
			first.startRiding(mole, true, false);
			mole.setFlying(true);
			poll(mole);
			ColonySite.Placed colony = Colony.placed(server).orElseThrow();
			mole.setFlying(false);
			mole.setDrilling(true);
			mole.setDrillDirection(Direction.DOWN);
			mole.setPos(mole.getX(), colony.groundY() - HandbookTuning.DEFAULT.drillDownBlocks(), mole.getZ());
			poll(mole);
			mole.setDrilling(false);
			mole.setPos(Vec3.atBottomCenterOf(colony.center()));
			poll(mole);

			// Chapter 5: sell ore, buy a part.
			first.stopRiding();
			HandbookChaptersOneToFiveTest.stand(helper, director, terminal);
			mole.setPos(Vec3.atBottomCenterOf(terminal).add(2, 0, 2));
			helper.getLevel().setBlock(terminal, TerminalTypes.ORE_PROCESSOR.block().defaultBlockState(), 3);
			first.getInventory().add(OreRegistry.stack(OreType.IRONIUM));
			expectDone(helper, Terminals.act(first, terminal, OreProcessor.SELL_INVENTORY, new CompoundTag()), "selling the ore");

			// Chapter 6: the scanner, then ore in view from the pilot's seat.
			helper.getLevel().setBlock(terminal, TerminalTypes.UPGRADE_TERMINAL.block().defaultBlockState(), 3);
			CompoundTag args = new CompoundTag();
			args.putString(UpgradeTerminal.TRACK_KEY, ComponentTrack.SCANNER.id());
			args.putInt(UpgradeTerminal.TIER_KEY, 1);
			expectDone(helper, Terminals.act(first, terminal, UpgradeTerminal.BUY, args), "buying a scanner");
			BlockPos ore = mole.blockPosition().above(2);
			helper.getLevel().setBlock(ore, net.minecraft.world.level.block.Blocks.GOLD_ORE.defaultBlockState(), 3);
			expect(helper, first.startRiding(mole, true, false), "the player should board the Mole");
			poll(mole);
			helper.getLevel().setBlock(ore, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);

			// Chapter 7: the hull is repaired, and the crew reaches the Deep Claim.
			first.stopRiding();
			HandbookChaptersOneToFiveTest.stand(helper, director, terminal);
			HandbookChaptersOneToFiveTest.stand(helper, director, terminal);
			helper.getLevel().setBlock(terminal, TerminalTypes.REPAIR_STATION.block().defaultBlockState(), 3);
			mole.damageHull(mole.maxHull() / 2);
			expectDone(helper, Terminals.act(first, terminal, RepairStation.REPAIR_TOTAL, new CompoundTag()), "repairing the hull");
			director.teleportTo(one, new Vec3(X, zoneY(one, 2), Z), 0, 0);
			HandbookTriggers.pollPlayer(server, first);

			// Chapter 8: the first descent into the Old Workings.
			BreachEvents.CROSSED.invoker().onCrossed(first, one, two, 1, 2);

			// Chapter 9: find the wreck, tow it into the colony, restore it at the hangar, and fly it to the floor.
			director.teleportTo(two, new Vec3(X, zoneY(two, 2), Z), 0, 0);
			PodEntity wreck = pod(helper, two, PodRegistry.PROSPECTOR, new Vec3(X + HandbookTuning.DEFAULT.findProspectorBlocks() / 2.0, zoneY(two, 2), Z));
			pods.add(wreck);
			wreck.damageHull(wreck.maxHull());
			HandbookTriggers.pollPlayer(server, first);

			// The wreck found in layer 2 and the one towed into the colony are made apart: a crossing makes a pod anew, and nothing here follows it.
			wreck = helper.spawn(PodRegistry.PROSPECTOR, 6, 1, 6);
			pods.add(wreck);
			wreck.damageHull(wreck.maxHull());
			director.teleportTo(server.overworld(), Vec3.atBottomCenterOf(colony.center()), 0, 0);
			PodEntity tower = helper.spawn(PodRegistry.POD, 4, 1, 4);
			pods.add(tower);
			PodComponents.register(tower, charter);
			tower.setPos(Vec3.atBottomCenterOf(colony.center()));
			wreck.setPos(Vec3.atBottomCenterOf(colony.center()).add(2, 0, 0));
			PodTowing.attach(tower, wreck);
			expect(helper, first.startRiding(tower, true, false), "the player should board the tower");
			poll(wreck);
			first.stopRiding();
			HandbookChaptersOneToFiveTest.stand(helper, director, terminal);

			// The hangar console restores it, for money and the catalyst.
			helper.getLevel().setBlock(terminal, HangarTerminal.TYPE.block().defaultBlockState(), 3);
			wreck.setPos(Vec3.atBottomCenterOf(terminal).add(3, 0, 3));
			HandbookChaptersOneToFiveTest.stand(helper, director, terminal);
			for (int i = 0; i < 3; i++) {
				first.getInventory().add(OreRegistry.stack(OreType.CICATRIUM));
			}
			expectDone(helper, Terminals.act(first, terminal, HangarTerminal.RESTORE_WRECK, new CompoundTag()), "restoring the wreck");

			PodEntity restored = (PodEntity) wreck.teleport(new net.minecraft.world.level.portal.TeleportTransition(two, new Vec3(X, zoneY(two, 2), Z), Vec3.ZERO, 0f, 0f,
					net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING));
			director.teleportTo(two, new Vec3(X, zoneY(two, 2), Z), 0, 0);
			expect(helper, first.startRiding(restored, true, false), "the player should board the restored Prospector");
			HandbookTriggers.pollPlayer(server, first);
			expect(helper, second.startRiding(restored, true, false), "the crewmate should take the navigator's seat");
			first.stopRiding();
			HandbookChaptersOneToFiveTest.stand(helper, director, terminal);
			second.stopRiding();

			// The Founder's hands: the player hands in four, the crewmate six.
			helper.getLevel().setBlock(terminal, TerminalTypes.ORE_PROCESSOR.block().defaultBlockState(), 3);
			HandbookChaptersOneToFiveTest.stand(helper, director, terminal);
			HandbookChaptersOneToFiveTest.stand(helper, crew, terminal);
			for (int i = 0; i < 4; i++) {
				first.getInventory().add(OreRegistry.stack(OreType.BRONZIUM));
			}
			for (int i = 0; i < 6; i++) {
				second.getInventory().add(OreRegistry.stack(OreType.BRONZIUM));
			}
			CompoundTag order = new CompoundTag();
			order.putString(WorkOrders.ORDER_KEY, WorkOrder.FOUNDERS_HANDS.id().toString());
			expectDone(helper, Terminals.act(first, terminal, WorkOrders.DELIVER, order), "the player's delivery");
			expectDone(helper, Terminals.act(second, terminal, WorkOrders.DELIVER, order), "the crewmate's delivery");

			try {
				M2SliceEndState.require(server, charter, restored);
			} catch (AssertionError open) {
				throw helper.assertionException(net.minecraft.network.chat.Component.literal(open.getMessage()));
			}
			pods.add(restored);
			helper.succeed();
		} finally {
			pods.forEach(PodEntity::discard);
			director.leave();
			crew.leave();
			FounderStatue.handPositions(server).ifPresent(positions -> positions.forEach(pos ->
					server.overworld().setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3)));
			server.getDataStorage().set(RepairState.TYPE, repairs);
			server.getDataStorage().set(HangarData.TYPE, hangar);
			server.getDataStorage().set(Serials.TYPE, serials);
			server.getDataStorage().set(WorkOrderData.TYPE, orders);
		}
	}

	private static PodEntity pod(GameTestHelper helper, ServerLevel level, net.minecraft.world.entity.EntityType<PodEntity> type, Vec3 at) {
		PodEntity pod = type.create(level, EntitySpawnReason.TRIGGERED);
		expect(helper, pod != null, "the pod should be made");
		pod.setPos(at);
		expect(helper, level.addFreshEntity(pod), "the world should accept the pod at %s", at);
		return pod;
	}
}
