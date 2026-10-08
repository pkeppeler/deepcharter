package io.github.pkeppeler.deepcharter.test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.CharterRefusal;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.market.WorkOrder;
import io.github.pkeppeler.deepcharter.market.WorkOrderData;
import io.github.pkeppeler.deepcharter.market.WorkOrders;
import io.github.pkeppeler.deepcharter.market.WorkOrdersView;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.terminal.TerminalRefusal;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;

/**
 * Server GameTests for #188: repeatable work orders (the Morale Initiative), delivery from the cargo hold of a parked pod as well as the
 * inventory, and the second version of the saved progress. Helpers and the same-tick rule for swapped world data are
 * {@link WorkOrdersTest}'s.
 */
public class WorkOrdersRepeatTest {
	private static final WorkOrder MORALE = WorkOrder.MORALE_INITIATIVE;
	private static final String LOCKED = "deepcharter.market.refusal.order_locked";
	private static final String NO_ORE = "deepcharter.market.refusal.no_order_ore";
	private static final String ORDER_DONE = "deepcharter.market.refusal.order_done";
	private static final String UNREADABLE_CARGO = "deepcharter.market.refusal.unreadable_cargo";

	/** Lets the player's charter see the act 2 order: it has reached the top of layer 3. */
	private static void reachLayerThree(MinecraftServer server, ServerPlayer player) {
		Charters.recordDeepestPoint(server, WorkOrdersTest.charter(server, player).id(), LayerChain.topDepth(server.registryAccess(), 3));
	}

	private static PodEntity podAt(GameTestHelper helper, Vec3 at, OreType... ores) {
		PodEntity pod = PodRegistry.POD.create(helper.getLevel(), EntitySpawnReason.COMMAND);
		pod.setPos(at);
		helper.getLevel().addFreshEntity(pod);
		for (OreType ore : ores) {
			if (!pod.cargo().tryAdd(pod, OreRegistry.stack(ore))) {
				throw helper.assertionException("the test pod should have a free slot for %s", ore);
			}
		}
		return pod;
	}

	private static PodEntity podBeside(GameTestHelper helper, BlockPos processor, double distance, OreType... ores) {
		return podAt(helper, Vec3.atCenterOf(processor).add(0, 0, distance), ores);
	}

	private static OreType[] ores(OreType type, int count) {
		OreType[] ores = new OreType[count];
		Arrays.fill(ores, type);
		return ores;
	}

	private static void expectProgress(GameTestHelper helper, MinecraftServer server, ServerPlayer player, WorkOrder order, int delivered, int rounds, String when) {
		WorkOrderData data = WorkOrderData.get(server);
		CharterId id = WorkOrdersTest.charter(server, player).id();
		if (data.delivered(id, order) != delivered || data.rounds(id, order) != rounds) {
			throw helper.assertionException("%s: %s should stand at %s delivered with %s rounds done, got %s and %s", when, order, delivered, rounds,
					data.delivered(id, order), data.rounds(id, order));
		}
	}

	private static long balance(MinecraftServer server, ServerPlayer player) {
		return WorkOrdersTest.charter(server, player).account();
	}

	private static WorkOrdersView view(MinecraftServer server, ServerPlayer player, BlockPos processor) {
		return WorkOrders.view(server, player, Charters.charterOfOrThrow(server, player.getUUID()), processor);
	}

	/** Refresh on completion: every finished round pays, and the next round is open at once, with no wait. */
	@GameTest
	public void aRepeatableOrderPaysEachRoundAndOpensTheNextAtOnce(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		WorkOrdersTest.withProcessorOnline(server, () -> {
			MockPlayer mock = WorkOrdersTest.player(helper, "Regular", true);
			ServerPlayer player = mock.player();
			BlockPos processor = WorkOrdersTest.processorFor(helper, mock);
			reachLayerThree(server, player);
			long before = balance(server, player);

			WorkOrdersTest.carry(player, OreType.SILVERIUM, 10);
			WorkOrdersTest.expectDone(helper, Terminals.act(player, processor, WorkOrders.DELIVER, WorkOrdersTest.orderArgs(MORALE)), "the first round");
			if (balance(server, player) != before + MORALE.reward()) {
				throw helper.assertionException("round one should pay $%s: balance %s -> %s", MORALE.reward(), before, balance(server, player));
			}
			expectProgress(helper, server, player, MORALE, 0, 1, "after round one");

			WorkOrdersTest.carry(player, OreType.SILVERIUM, 4);
			WorkOrdersTest.expectDone(helper, Terminals.act(player, processor, WorkOrders.DELIVER, WorkOrdersTest.orderArgs(MORALE)), "a partial second round");
			expectProgress(helper, server, player, MORALE, 4, 1, "four into round two");
			if (balance(server, player) != before + MORALE.reward()) {
				throw helper.assertionException("a partial round pays nothing");
			}

			WorkOrdersTest.carry(player, OreType.SILVERIUM, 20);
			WorkOrdersTest.expectDone(helper, Terminals.act(player, processor, WorkOrders.DELIVER, WorkOrdersTest.orderArgs(MORALE)), "the completing second round");
			expectProgress(helper, server, player, MORALE, 0, 2, "after round two");
			if (balance(server, player) != before + 2 * MORALE.reward() || WorkOrdersTest.carried(player, OreType.SILVERIUM) != 14) {
				throw helper.assertionException("round two should take the six still owed and pay again: balance %s -> %s, %s Silverium left",
						before, balance(server, player), WorkOrdersTest.carried(player, OreType.SILVERIUM));
			}
			WorkOrdersView view = view(server, player, processor);
			if (!view.orders().contains(new WorkOrdersView.Entry(MORALE, 0, 2)) || view.orders().stream().anyMatch(entry -> entry.order() == MORALE && entry.done())) {
				throw helper.assertionException("the view should show two rounds done and a repeatable order never finished, got %s", view);
			}
		});
		helper.succeed();
	}

	/** One delivery completes at most one round: ore beyond the round stays with the player. */
	@GameTest
	public void aDeliveryNeverReachesPastTheRoundItCompletes(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		WorkOrdersTest.withProcessorOnline(server, () -> {
			MockPlayer mock = WorkOrdersTest.player(helper, "Overstock", true);
			ServerPlayer player = mock.player();
			BlockPos processor = WorkOrdersTest.processorFor(helper, mock);
			reachLayerThree(server, player);
			WorkOrdersTest.carry(player, OreType.SILVERIUM, 25);
			WorkOrdersTest.expectDone(helper, Terminals.act(player, processor, WorkOrders.DELIVER, WorkOrdersTest.orderArgs(MORALE)), "a delivery of 25");
			expectProgress(helper, server, player, MORALE, 0, 1, "after one delivery of 25");
			if (WorkOrdersTest.carried(player, OreType.SILVERIUM) != 15) {
				throw helper.assertionException("one delivery takes one round (10), %s of 25 left", WorkOrdersTest.carried(player, OreType.SILVERIUM));
			}
		});
		helper.succeed();
	}

	@GameTest
	public void repeatProgressBelongsToTheCharter(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		WorkOrdersTest.withProcessorOnline(server, () -> {
			MockPlayer first = WorkOrdersTest.player(helper, "Gamma", true);
			MockPlayer second = WorkOrdersTest.player(helper, "Delta", true);
			BlockPos processor = WorkOrdersTest.processorFor(helper, first);
			reachLayerThree(server, first.player());
			reachLayerThree(server, second.player());
			second.teleportTo(helper.getLevel(), first.player().position(), 0, 0);
			WorkOrdersTest.carry(first.player(), OreType.SILVERIUM, 10);
			WorkOrdersTest.carry(second.player(), OreType.SILVERIUM, 3);
			WorkOrdersTest.expectDone(helper, Terminals.act(first.player(), processor, WorkOrders.DELIVER, WorkOrdersTest.orderArgs(MORALE)), "the first charter's round");
			WorkOrdersTest.expectDone(helper, Terminals.act(second.player(), processor, WorkOrders.DELIVER, WorkOrdersTest.orderArgs(MORALE)), "the second charter's delivery");
			expectProgress(helper, server, first.player(), MORALE, 0, 1, "the first charter");
			expectProgress(helper, server, second.player(), MORALE, 3, 0, "the second charter");
		});
		helper.succeed();
	}

	@GameTest
	public void theFoundersHandsStaysOneShot(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		WorkOrdersTest.withProcessorOnline(server, () -> {
			MockPlayer mock = WorkOrdersTest.player(helper, "Mason", true);
			ServerPlayer player = mock.player();
			BlockPos processor = WorkOrdersTest.processorFor(helper, mock);
			if (WorkOrder.FOUNDERS_HANDS.repeatable() || !MORALE.repeatable()) {
				throw helper.assertionException("only the Morale Initiative repeats");
			}
			WorkOrdersTest.carry(player, OreType.BRONZIUM, 30);
			WorkOrdersTest.expectDone(helper, Terminals.act(player, processor, WorkOrders.DELIVER, WorkOrdersTest.orderArgs(WorkOrder.FOUNDERS_HANDS)), "the Founder's hands");
			long paid = balance(server, player);
			WorkOrdersTest.expectKey(helper, ORDER_DONE, WorkOrders.deliver(WorkOrdersTest.context(server, player, processor), WorkOrder.FOUNDERS_HANDS),
					"a second round of the Founder's hands");
			expectProgress(helper, server, player, WorkOrder.FOUNDERS_HANDS, 10, 0, "after the order is done");
			if (WorkOrdersTest.carried(player, OreType.BRONZIUM) != 20 || balance(server, player) != paid) {
				throw helper.assertionException("a finished one-shot order takes no more ore and pays no more");
			}
			if (!view(server, player, processor).orders().getFirst().done()) {
				throw helper.assertionException("the view should show the Founder's hands done");
			}
		});
		helper.succeed();
	}

	/** The act 2 order is offered once the charter has reached layer 3, and refuses (taking nothing) before. */
	@GameTest
	public void theMoraleInitiativeOpensAtLayerThree(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		WorkOrdersTest.withProcessorOnline(server, () -> {
			MockPlayer mock = WorkOrdersTest.player(helper, "Junior", true);
			ServerPlayer player = mock.player();
			BlockPos processor = WorkOrdersTest.processorFor(helper, mock);
			WorkOrdersTest.carry(player, OreType.SILVERIUM, 10);
			if (view(server, player, processor).orders().stream().anyMatch(entry -> entry.order() == MORALE)) {
				throw helper.assertionException("a charter above layer 3 is not offered the Morale Initiative");
			}
			WorkOrdersTest.expectKey(helper, LOCKED, WorkOrders.deliver(WorkOrdersTest.context(server, player, processor), MORALE), "a delivery before layer 3");
			WorkOrdersTest.expectRefused(helper, TerminalRefusal.ACTION_REFUSED,
					Terminals.act(player, processor, WorkOrders.DELIVER, WorkOrdersTest.orderArgs(MORALE)), "a delivery before layer 3");
			if (WorkOrdersTest.carried(player, OreType.SILVERIUM) != 10) {
				throw helper.assertionException("a locked order takes no ore");
			}
			reachLayerThree(server, player);
			if (view(server, player, processor).orders().stream().noneMatch(entry -> entry.order() == MORALE)) {
				throw helper.assertionException("a charter that reached layer 3 is offered the Morale Initiative");
			}
		});
		helper.succeed();
	}

	@GameTest
	public void deliveryTakesFromTheInventoryFirstThenTheParkedPodsCargo(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		WorkOrdersTest.withProcessorOnline(server, () -> {
			MockPlayer mock = WorkOrdersTest.player(helper, "Hauler", true);
			ServerPlayer player = mock.player();
			BlockPos processor = WorkOrdersTest.processorFor(helper, mock);
			reachLayerThree(server, player);
			PodEntity pod = podBeside(helper, processor, 4, ores(OreType.SILVERIUM, 7));
			try {
				WorkOrdersTest.carry(player, OreType.SILVERIUM, 6);
				long before = balance(server, player);
				WorkOrdersTest.expectDone(helper, Terminals.act(player, processor, WorkOrders.DELIVER, WorkOrdersTest.orderArgs(MORALE)),
						"a delivery from the inventory and the cargo");
				if (WorkOrdersTest.carried(player, OreType.SILVERIUM) != 0 || pod.cargoUsed() != 3 || pod.cargo().entries().size() != 3) {
					throw helper.assertionException("six from the inventory and four of the seven in the hold: %s carried, %s left in the hold",
							WorkOrdersTest.carried(player, OreType.SILVERIUM), pod.cargoUsed());
				}
				float mass = (float) pod.cargo().entries().stream().mapToDouble(entry -> entry.mass()).sum();
				if (Math.abs(pod.cargoMass() - mass) > 1e-4f) {
					throw helper.assertionException("the pod's synced cargo mass must follow the removal, got %s for %s", pod.cargoMass(), mass);
				}
				expectProgress(helper, server, player, MORALE, 0, 1, "after the mixed delivery");
				if (balance(server, player) != before + MORALE.reward()) {
					throw helper.assertionException("the completed round should pay");
				}
				// The hold alone is enough, and another ore in a nearer hold is left alone.
				PodEntity mixed = podBeside(helper, processor, 3, ores(OreType.GOLDIUM, 2));
				try {
					WorkOrdersTest.expectDone(helper, Terminals.act(player, processor, WorkOrders.DELIVER, WorkOrdersTest.orderArgs(MORALE)), "a delivery from the cargo alone");
					expectProgress(helper, server, player, MORALE, 3, 1, "three from the hold alone");
					if (pod.cargoUsed() != 0 || mixed.cargoUsed() != 2) {
						throw helper.assertionException("the hold is emptied of Silverium and the Goldium stays: %s, %s", pod.cargoUsed(), mixed.cargoUsed());
					}
				} finally {
					mixed.discard();
				}
			} finally {
				pod.discard();
			}
		});
		helper.succeed();
	}

	@GameTest
	public void aPodOfAnotherCharterOrFarFromTheProcessorIsNotDeliveredFrom(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		WorkOrdersTest.withProcessorOnline(server, () -> {
			MockPlayer mock = WorkOrdersTest.player(helper, "Driver", true);
			MockPlayer rival = WorkOrdersTest.player(helper, "Rival", true);
			ServerPlayer player = mock.player();
			BlockPos processor = WorkOrdersTest.processorFor(helper, mock);
			reachLayerThree(server, player);
			PodEntity theirs = podBeside(helper, processor, 3, ores(OreType.SILVERIUM, 7));
			PodEntity far = podBeside(helper, processor, 40, ores(OreType.SILVERIUM, 7));
			try {
				PodComponents.register(theirs, WorkOrdersTest.charter(server, rival.player()).id());
				PodComponents.register(far, WorkOrdersTest.charter(server, player).id());
				WorkOrdersTest.expectKey(helper, NO_ORE, WorkOrders.deliver(WorkOrdersTest.context(server, player, processor), MORALE),
						"a delivery with only a rival's pod and a far pod");
				if (theirs.cargoUsed() != 7 || far.cargoUsed() != 7) {
					throw helper.assertionException("neither pod may be touched: %s, %s", theirs.cargoUsed(), far.cargoUsed());
				}
				expectProgress(helper, server, player, MORALE, 0, 0, "after the refused delivery");
			} finally {
				theirs.discard();
				far.discard();
			}
		});
		helper.succeed();
	}

	@GameTest
	public void unreadableCargoIsSkippedOrRefusedAndNeverThrownOn(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel level = helper.getLevel();
		WorkOrdersTest.withProcessorOnline(server, () -> {
			MockPlayer mock = WorkOrdersTest.player(helper, "Reader", true);
			ServerPlayer player = mock.player();
			BlockPos processor = WorkOrdersTest.processorFor(helper, mock);
			reachLayerThree(server, player);
			PodEntity pod = podBeside(helper, processor, 4, ores(OreType.SILVERIUM, 7));
			try {
				TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
				pod.saveWithoutId(output);
				CompoundTag tag = output.buildResult();
				tag.putInt("cargo_version", 99);
				pod.cargo().load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag), pod);
				if (pod.cargo().isReadable()) {
					throw helper.assertionException("the test needs unreadable cargo");
				}
				Map<String, Runnable> paths = new LinkedHashMap<>();
				paths.put("delivery with only unreadable cargo", () -> WorkOrdersTest.expectKey(helper, UNREADABLE_CARGO,
						WorkOrders.deliver(WorkOrdersTest.context(server, player, processor), MORALE), "a delivery from unreadable cargo"));
				paths.put("delivery through the terminal", () -> WorkOrdersTest.expectRefused(helper, TerminalRefusal.ACTION_REFUSED,
						Terminals.act(player, processor, WorkOrders.DELIVER, WorkOrdersTest.orderArgs(MORALE)), "a delivery from unreadable cargo through the terminal"));
				paths.put("view", () -> view(server, player, processor));
				UnreadableChecks.assertNoThrow(helper, "work orders with unreadable pod cargo", paths);
				expectProgress(helper, server, player, MORALE, 0, 0, "after the refused deliveries");
				// What the inventory holds still goes in: the unreadable hold is skipped, not fatal.
				WorkOrdersTest.carry(player, OreType.SILVERIUM, 3);
				WorkOrdersTest.expectDone(helper, Terminals.act(player, processor, WorkOrders.DELIVER, WorkOrdersTest.orderArgs(MORALE)),
						"a delivery from the inventory beside unreadable cargo");
				expectProgress(helper, server, player, MORALE, 3, 0, "three from the inventory");
				if (pod.cargo().isReadable()) {
					throw helper.assertionException("the unreadable cargo must stay unreadable");
				}
				try {
					pod.cargo().take(pod, OreType.SILVERIUM, 1);
					throw helper.assertionException("taking from unreadable cargo should throw: it is an explicit call");
				} catch (IllegalStateException expected) {
					// refused, and the cargo is kept as it was read
				}
			} finally {
				pod.discard();
			}
		});
		helper.succeed();
	}

	/** One delivery carries what is still owed from the inventory into the holds, nearest first, across more than one hold. */
	@GameTest
	public void oneDeliveryDrawsOnTheInventoryAndSeveralHoldsNearestFirst(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		WorkOrdersTest.withProcessorOnline(server, () -> {
			MockPlayer mock = WorkOrdersTest.player(helper, "Convoy", true);
			ServerPlayer player = mock.player();
			BlockPos processor = WorkOrdersTest.processorFor(helper, mock);
			reachLayerThree(server, player);
			PodEntity far = podBeside(helper, processor, 5, ores(OreType.SILVERIUM, 5));
			PodEntity near = podBeside(helper, processor, 3, ores(OreType.SILVERIUM, 4));
			try {
				WorkOrdersTest.carry(player, OreType.SILVERIUM, 3);
				long before = balance(server, player);
				WorkOrdersTest.expectDone(helper, Terminals.act(player, processor, WorkOrders.DELIVER, WorkOrdersTest.orderArgs(MORALE)),
						"a delivery across the inventory and two holds");
				if (WorkOrdersTest.carried(player, OreType.SILVERIUM) != 0 || near.cargoUsed() != 0 || far.cargoUsed() != 2) {
					throw helper.assertionException("3 from the inventory, 4 from the nearer hold and 3 of the 5 in the farther: %s carried, %s near, %s far",
							WorkOrdersTest.carried(player, OreType.SILVERIUM), near.cargoUsed(), far.cargoUsed());
				}
				for (PodEntity pod : new PodEntity[] {near, far}) {
					float mass = (float) pod.cargo().entries().stream().mapToDouble(entry -> entry.mass()).sum();
					if (pod.cargoUsed() != pod.cargo().entries().size() || Math.abs(pod.cargoMass() - mass) > 1e-4f) {
						throw helper.assertionException("a pod's synced cargo must follow the removal: used %s for %s entries, mass %s for %s",
								pod.cargoUsed(), pod.cargo().entries().size(), pod.cargoMass(), mass);
					}
				}
				expectProgress(helper, server, player, MORALE, 0, 1, "after the delivery across holds");
				if (balance(server, player) != before + MORALE.reward()) {
					throw helper.assertionException("exactly one round should be paid");
				}
			} finally {
				near.discard();
				far.discard();
			}
		});
		helper.succeed();
	}

	/** Spend last: a refusal that comes after the ore is counted still leaves the holds and the progress as they were. */
	@GameTest
	public void aRefusedDeliveryLeavesTheHoldsUntouched(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		WorkOrdersTest.withProcessorOnline(server, () -> {
			MockPlayer mock = WorkOrdersTest.player(helper, "Refused", true);
			ServerPlayer player = mock.player();
			BlockPos processor = WorkOrdersTest.processorFor(helper, mock);
			// A pod has seven slots, so ten Silverium take two holds.
			PodEntity pod = podBeside(helper, processor, 3, ores(OreType.SILVERIUM, 7));
			PodEntity second = podBeside(helper, processor, 4, ores(OreType.SILVERIUM, 3));
			try {
				WorkOrdersTest.expectKey(helper, LOCKED, WorkOrders.deliver(WorkOrdersTest.context(server, player, processor), MORALE),
						"a delivery of a locked order from a hold");
				expectHoldsUntouched(helper, pod, second, server, player, "the locked order");
				reachLayerThree(server, player);
				long before = balance(server, player);
				Charters.deposit(server, WorkOrdersTest.charter(server, player).id(), Long.MAX_VALUE - 10 - before);
				if (WorkOrders.deliver(WorkOrdersTest.context(server, player, processor), MORALE).filter(CharterRefusal.ACCOUNT_FULL.message()::equals).isEmpty()) {
					throw helper.assertionException("a reward that does not fit the account should refuse the completing delivery");
				}
				expectHoldsUntouched(helper, pod, second, server, player, "the full account");
			} finally {
				pod.discard();
				second.discard();
			}
		});
		helper.succeed();
	}

	private static void expectHoldsUntouched(GameTestHelper helper, PodEntity pod, PodEntity second, MinecraftServer server, ServerPlayer player, String when) {
		if (pod.cargoUsed() != 7 || pod.cargo().entries().size() != 7 || pod.cargo().count(OreType.SILVERIUM) != 7
				|| second.cargoUsed() != 3 || second.cargo().entries().size() != 3 || second.cargo().count(OreType.SILVERIUM) != 3) {
			throw helper.assertionException("%s: the holds must keep their 10 Silverium, used %s and %s", when, pod.cargoUsed(), second.cargoUsed());
		}
		expectProgress(helper, server, player, MORALE, 0, 0, when);
	}

	@GameTest
	public void takingFromTheCargoRemovesWholeOreAndKeepsTheRest(GameTestHelper helper) {
		PodEntity pod = podAt(helper, helper.absoluteVec(new Vec3(1, 1, 1)),
				OreType.IRONIUM, OreType.SILVERIUM, OreType.IRONIUM, OreType.SILVERIUM, OreType.IRONIUM);
		try {
			if (pod.cargo().take(pod, OreType.IRONIUM, 2) != 2 || pod.cargoUsed() != 3 || pod.cargo().entries().size() != 3) {
				throw helper.assertionException("two Ironium should leave and three ore stay: %s", pod.cargo().entries());
			}
			if (pod.cargo().take(pod, OreType.IRONIUM, 5) != 1 || pod.cargoUsed() != 2) {
				throw helper.assertionException("asking for more than the hold has takes what is there, and reports it");
			}
			if (pod.cargo().take(pod, OreType.GOLDIUM, 1) != 0 || pod.cargoUsed() != 2) {
				throw helper.assertionException("an ore the hold does not have takes nothing");
			}
			if (pod.cargo().entries().stream().anyMatch(entry -> OreRegistry.typeOf(entry.stack()).orElseThrow() != OreType.SILVERIUM)) {
				throw helper.assertionException("only Silverium should be left: %s", pod.cargo().entries());
			}
			float expected = 2 * OreType.SILVERIUM.mass();
			if (Math.abs(pod.cargoMass() - expected) > 1e-4f) {
				throw helper.assertionException("the cargo mass should be %s, got %s", expected, pod.cargoMass());
			}
			boolean threw = false;
			try {
				pod.cargo().take(pod, OreType.SILVERIUM, 0);
			} catch (IllegalArgumentException refused) {
				threw = true;
			}
			if (!threw) {
				throw helper.assertionException("taking none is a bug and should throw");
			}
		} finally {
			pod.discard();
		}
		helper.succeed();
	}

	private static CompoundTag progressEntry(CharterId charter, String order, int delivered, Integer rounds) {
		CompoundTag entry = new CompoundTag();
		entry.put("charter", CharterId.CODEC.encodeStart(NbtOps.INSTANCE, charter).getOrThrow());
		entry.putString("order", order);
		entry.putInt("delivered", delivered);
		if (rounds != null) {
			entry.putInt("rounds", rounds);
		}
		return entry;
	}

	private static CompoundTag saved(int version, CompoundTag... entries) {
		ListTag progress = new ListTag();
		progress.addAll(Arrays.asList(entries));
		CompoundTag data = new CompoundTag();
		data.putInt("version", version);
		data.put("progress", progress);
		return data;
	}

	/** Version 1 had no rounds. It still reads after the bump, and is written as version 2 from then on. */
	@GameTest
	public void progressSavedAsVersionOneStillReads(GameTestHelper helper) {
		CharterId partial = new CharterId(UUID.randomUUID());
		CharterId finished = new CharterId(UUID.randomUUID());
		CompoundTag v1 = saved(1, progressEntry(partial, "founders_hands", 7, null), progressEntry(finished, "founders_hands", 10, null));
		WorkOrderData loaded = WorkOrderData.CODEC.parse(NbtOps.INSTANCE, v1).getOrThrow();
		if (WorkOrderData.VERSION != 2 || !loaded.isReadable()) {
			throw helper.assertionException("version 1 must stay readable after the bump to %s", WorkOrderData.VERSION);
		}
		if (loaded.delivered(partial, WorkOrder.FOUNDERS_HANDS) != 7 || loaded.delivered(finished, WorkOrder.FOUNDERS_HANDS) != 10
				|| loaded.rounds(partial, WorkOrder.FOUNDERS_HANDS) != 0) {
			throw helper.assertionException("version 1 progress should read as it was saved");
		}
		Tag rewritten = WorkOrderData.CODEC.encodeStart(NbtOps.INSTANCE, loaded).getOrThrow();
		if (!(rewritten instanceof CompoundTag compound) || compound.getInt("version").orElse(0) != 2) {
			throw helper.assertionException("version 1 data is written back as version 2, got %s", rewritten);
		}
		WorkOrderData again = WorkOrderData.CODEC.parse(NbtOps.INSTANCE, rewritten).getOrThrow();
		if (!again.isReadable() || again.delivered(partial, WorkOrder.FOUNDERS_HANDS) != 7) {
			throw helper.assertionException("the rewritten data should read back, got %s", rewritten);
		}
		// Version 1 that does not parse is kept unread and written back as it was, version and all.
		CompoundTag broken = saved(1, progressEntry(partial, "founders_hands", 99, null));
		WorkOrderData unreadable = WorkOrderData.CODEC.parse(NbtOps.INSTANCE, broken).getOrThrow();
		if (unreadable.isReadable() || !broken.equals(WorkOrderData.CODEC.encodeStart(NbtOps.INSTANCE, unreadable).getOrThrow())) {
			throw helper.assertionException("bad version 1 data should be kept unread and written back unchanged");
		}
		helper.succeed();
	}

	@GameTest
	public void roundsSurviveASaveAndALoad(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		WorkOrdersTest.withProcessorOnline(server, () -> {
			MockPlayer mock = WorkOrdersTest.player(helper, "Archivist", true);
			ServerPlayer player = mock.player();
			BlockPos processor = WorkOrdersTest.processorFor(helper, mock);
			reachLayerThree(server, player);
			WorkOrdersTest.carry(player, OreType.SILVERIUM, 15);
			WorkOrdersTest.expectDone(helper, Terminals.act(player, processor, WorkOrders.DELIVER, WorkOrdersTest.orderArgs(MORALE)), "a round");
			WorkOrdersTest.expectDone(helper, Terminals.act(player, processor, WorkOrders.DELIVER, WorkOrdersTest.orderArgs(MORALE)), "a part of the next round");
			Tag saved = WorkOrderData.CODEC.encodeStart(NbtOps.INSTANCE, WorkOrderData.get(server)).getOrThrow();
			WorkOrderData loaded = WorkOrderData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
			CharterId id = WorkOrdersTest.charter(server, player).id();
			if (!loaded.isReadable() || loaded.delivered(id, MORALE) != 5 || loaded.rounds(id, MORALE) != 1) {
				throw helper.assertionException("5 delivered and 1 round done should survive a reload, got %s", saved);
			}
		});
		helper.succeed();
	}

	@GameTest
	public void progressThatBreaksAnOrdersRulesIsKeptUnread(GameTestHelper helper) {
		CharterId id = new CharterId(UUID.randomUUID());
		CompoundTag[] bad = {
				// a repeatable order never rests at a full round: it rolled over
				saved(2, progressEntry(id, "morale_initiative", 10, 1)),
				// a one-shot order has no rounds
				saved(2, progressEntry(id, "founders_hands", 10, 1)),
				// version 2 has rounds
				saved(2, progressEntry(id, "morale_initiative", 3, null)),
				saved(2, progressEntry(id, "morale_initiative", 3, -1))};
		for (CompoundTag tag : bad) {
			WorkOrderData parsed = WorkOrderData.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
			if (parsed.isReadable() || !tag.equals(WorkOrderData.CODEC.encodeStart(NbtOps.INSTANCE, parsed).getOrThrow())) {
				throw helper.assertionException("%s should be kept unread and written back unchanged", tag);
			}
		}
		helper.succeed();
	}

	/** The order table is saved by name: pinned with literals. */
	@GameTest
	public void theMoraleInitiativeNameAndNumbersArePinned(GameTestHelper helper) {
		if (!"morale_initiative".equals(MORALE.getSerializedName()) || MORALE.quantity() != 10 || MORALE.ore() != OreType.SILVERIUM
				|| MORALE.reward() != 1250 || MORALE.reward() != Math.round(MORALE.quantity() * MORALE.ore().value() * 1.25) || MORALE.unlockLayer() != 3 || WorkOrder.FOUNDERS_HANDS.unlockLayer() != 0) {
			throw helper.assertionException("the Morale Initiative is saved as morale_initiative: 10 Silverium for $1250 from layer 3");
		}
		if (WorkOrder.values().length != 2) {
			throw helper.assertionException("work orders are saved by name, so a new one needs its own pin: %s", Arrays.toString(WorkOrder.values()));
		}
		helper.succeed();
	}
}
