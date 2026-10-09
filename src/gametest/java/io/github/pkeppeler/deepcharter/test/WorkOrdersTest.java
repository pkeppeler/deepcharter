package io.github.pkeppeler.deepcharter.test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.colony.FounderStatue;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.market.WorkOrder;
import io.github.pkeppeler.deepcharter.market.WorkOrderData;
import io.github.pkeppeler.deepcharter.market.WorkOrders;
import io.github.pkeppeler.deepcharter.market.WorkOrdersView;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalAction;
import io.github.pkeppeler.deepcharter.terminal.TerminalRefusal;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import io.github.pkeppeler.deepcharter.test.support.WorldData;

/**
 * Server GameTests for #80: a charter hands ore in for a work order at the ore processor. Progress is the charter's own and
 * persists; the last delivery completes the order once, pays its reward and puts the Founder statue's hands back; every refusal
 * comes before any ore is taken.
 *
 * <p>The work order data, the repair state and the colony site are one per world, so each test swaps in fresh ones and puts the
 * originals back, and does all its work inside its first tick. The hands are blocks of the shared world: a test that restores them
 * clears them again.
 */
public class WorkOrdersTest {
	private static final String NO_ORE = "deepcharter.market.refusal.no_order_ore";
	private static final String ORDER_DONE = "deepcharter.market.refusal.order_done";
	private static final String UNREADABLE = "deepcharter.market.refusal.work_orders_unreadable";
	private static final String NO_STATUE = "deepcharter.market.refusal.no_statue";

	static void withProcessorOnline(MinecraftServer server, Runnable body) {
		RepairState fresh = new RepairState();
		try {
			WorldData.swap(server).with(RepairState.TYPE, fresh).with(WorkOrderData.TYPE, new WorkOrderData()).run(() -> {
				for (TerminalType type : List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR)) {
					type.parts().forEach(part -> fresh.insert(type, part));
				}
				body.run();
			});
		} finally {
			clearHands(server);
		}
	}

	private static void clearHands(MinecraftServer server) {
		FounderStatue.hands(server).forEach(Entity::discard);
	}

	static MockPlayer player(GameTestHelper helper, String name, boolean onCharter) {
		MockPlayer mock = MockPlayers.join(helper, name);
		mock.player().setGameMode(GameType.SURVIVAL);
		MinecraftServer server = helper.getLevel().getServer();
		if (onCharter && Charters.found(server, mock.player().getUUID(), name + " " + UUID.randomUUID().toString().substring(0, 8)).isPresent()) {
			throw helper.assertionException("founding a charter for %s should succeed", name);
		}
		return mock;
	}

	/** The processor at a fixed place in the test, and the player standing two blocks from it. */
	static BlockPos processorFor(GameTestHelper helper, MockPlayer mock) {
		BlockPos relative = new BlockPos(2, 1, 2);
		helper.setBlock(relative, TerminalTypes.ORE_PROCESSOR.block().defaultBlockState());
		BlockPos pos = helper.absolutePos(relative);
		Vec3 centre = Vec3.atCenterOf(pos);
		mock.teleportTo(helper.getLevel(), new Vec3(centre.x + 2, centre.y - mock.player().getEyeHeight(), centre.z), 0, 0);
		return pos;
	}

	static void carry(ServerPlayer player, OreType ore, int count) {
		for (int i = 0; i < count; i++) {
			player.getInventory().add(OreRegistry.stack(ore));
		}
	}

	static int carried(ServerPlayer player, OreType ore) {
		return player.getInventory().countItem(OreRegistry.item(ore));
	}

	static Charter charter(MinecraftServer server, ServerPlayer player) {
		return Charters.charterOfOrThrow(server, player.getUUID()).orElseThrow();
	}

	static CompoundTag orderArgs(WorkOrder order) {
		CompoundTag args = new CompoundTag();
		args.putString(WorkOrders.ORDER_KEY, order.id().toString());
		return args;
	}

	static TerminalAction.Context context(MinecraftServer server, ServerPlayer player, BlockPos pos) {
		return new TerminalAction.Context(server, player, Charters.charterOfOrThrow(server, player.getUUID()), TerminalTypes.ORE_PROCESSOR, pos, new CompoundTag());
	}

	static void expectDone(GameTestHelper helper, Optional<TerminalRefusal> refusal, String what) {
		if (refusal.isPresent()) {
			throw helper.assertionException("%s should succeed, was refused: %s", what, refusal.get());
		}
	}

	static void expectRefused(GameTestHelper helper, TerminalRefusal expected, Optional<TerminalRefusal> actual, String what) {
		if (!actual.equals(Optional.of(expected))) {
			throw helper.assertionException("%s should be refused with %s, got %s", what, expected, actual);
		}
	}

	static void expectKey(GameTestHelper helper, String key, Optional<Component> refusal, String what) {
		if (refusal.isEmpty() || !(refusal.get().getContents() instanceof TranslatableContents contents) || !contents.getKey().equals(key)) {
			throw helper.assertionException("%s should be refused with %s, got %s", what, key, refusal);
		}
	}

	static boolean handsRestored(MinecraftServer server) {
		return FounderStatue.hands(server).size() == 1;
	}

	private static void expectHands(GameTestHelper helper, MinecraftServer server, boolean restored, String when) {
		if (handsRestored(server) != restored) {
			throw helper.assertionException("%s: the Founder's hands should be %s", when, restored ? "restored" : "missing");
		}
	}

	@GameTest
	public void theColonyIsBuiltWithoutTheFoundersHands(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		if (!FounderStatue.isBuilt(server)) {
			throw helper.assertionException("the colony was not built, so the Host has no hands to lack");
		}
		if (!FounderStatue.hands(server).isEmpty()) {
			throw helper.assertionException("the built colony should leave the Host without hands, found %s", FounderStatue.hands(server));
		}
		helper.succeed();
	}

	@GameTest
	public void aPartialDeliveryTakesOnlyThatOreAndRecordsTheProgress(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		withProcessorOnline(server, () -> {
			MockPlayer mock = player(helper, "Courier", true);
			ServerPlayer player = mock.player();
			BlockPos processor = processorFor(helper, mock);
			carry(player, OreType.BRONZIUM, 4);
			carry(player, OreType.IRONIUM, 2);
			long before = charter(server, player).account();

			expectDone(helper, Terminals.act(player, processor, WorkOrders.DELIVER, orderArgs(WorkOrder.FOUNDERS_HANDS)), "a partial delivery");

			CharterId id = charter(server, player).id();
			if (WorkOrderData.get(server).delivered(id, WorkOrder.FOUNDERS_HANDS) != 4 || carried(player, OreType.BRONZIUM) != 0) {
				throw helper.assertionException("four Bronzium should be handed in: progress %s, still carried %s",
						WorkOrderData.get(server).delivered(id, WorkOrder.FOUNDERS_HANDS), carried(player, OreType.BRONZIUM));
			}
			if (carried(player, OreType.IRONIUM) != 2) {
				throw helper.assertionException("another ore must stay with the player, %s Ironium left", carried(player, OreType.IRONIUM));
			}
			if (charter(server, player).account() != before) {
				throw helper.assertionException("a partial delivery pays nothing");
			}
			expectHands(helper, server, false, "after a partial delivery");
		});
		helper.succeed();
	}

	@GameTest
	public void deliveriesAddUpAndTheLastOneCompletesTheOrderOnce(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		withProcessorOnline(server, () -> {
			MockPlayer mock = player(helper, "Foreman", true);
			ServerPlayer player = mock.player();
			BlockPos processor = processorFor(helper, mock);
			carry(player, OreType.BRONZIUM, 6);
			expectDone(helper, Terminals.act(player, processor, WorkOrders.DELIVER, orderArgs(WorkOrder.FOUNDERS_HANDS)), "the first delivery");
			expectHands(helper, server, false, "with six of ten in");

			carry(player, OreType.BRONZIUM, 7);
			long before = charter(server, player).account();
			expectDone(helper, Terminals.act(player, processor, WorkOrders.DELIVER, orderArgs(WorkOrder.FOUNDERS_HANDS)), "the completing delivery");

			CharterId id = charter(server, player).id();
			if (WorkOrderData.get(server).delivered(id, WorkOrder.FOUNDERS_HANDS) != 10) {
				throw helper.assertionException("the order should stand at 10 of 10, got %s", WorkOrderData.get(server).delivered(id, WorkOrder.FOUNDERS_HANDS));
			}
			if (carried(player, OreType.BRONZIUM) != 3) {
				throw helper.assertionException("only the four still owed should be taken from seven, %s left", carried(player, OreType.BRONZIUM));
			}
			if (charter(server, player).account() != before + 600) {
				throw helper.assertionException("completing should pay $600: balance %s -> %s", before, charter(server, player).account());
			}
			expectHands(helper, server, true, "after the completing delivery");

			expectRefused(helper, TerminalRefusal.ACTION_REFUSED, Terminals.act(player, processor, WorkOrders.DELIVER, orderArgs(WorkOrder.FOUNDERS_HANDS)),
					"a delivery to a finished order");
			expectKey(helper, ORDER_DONE, WorkOrders.deliver(context(server, player, processor), WorkOrder.FOUNDERS_HANDS), "a delivery to a finished order");
			if (carried(player, OreType.BRONZIUM) != 3 || charter(server, player).account() != before + 600) {
				throw helper.assertionException("a finished order takes no more ore and pays no more");
			}
		});
		helper.succeed();
	}

	@GameTest
	public void progressBelongsToTheCharter(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		withProcessorOnline(server, () -> {
			MockPlayer first = player(helper, "Alpha", true);
			MockPlayer second = player(helper, "Beta", true);
			BlockPos processor = processorFor(helper, first);
			carry(first.player(), OreType.BRONZIUM, 10);
			carry(second.player(), OreType.BRONZIUM, 3);
			expectDone(helper, Terminals.act(first.player(), processor, WorkOrders.DELIVER, orderArgs(WorkOrder.FOUNDERS_HANDS)), "the first charter's delivery");

			second.teleportTo(helper.getLevel(), first.player().position(), 0, 0);
			expectDone(helper, Terminals.act(second.player(), processor, WorkOrders.DELIVER, orderArgs(WorkOrder.FOUNDERS_HANDS)), "the second charter's delivery");
			WorkOrderData data = WorkOrderData.get(server);
			if (data.delivered(charter(server, second.player()).id(), WorkOrder.FOUNDERS_HANDS) != 3 || data.delivered(charter(server, second.player()).id(), WorkOrder.FOUNDERS_HANDS) >= WorkOrder.FOUNDERS_HANDS.quantity()
					|| data.delivered(charter(server, first.player()).id(), WorkOrder.FOUNDERS_HANDS) < WorkOrder.FOUNDERS_HANDS.quantity()) {
				throw helper.assertionException("each charter keeps its own progress");
			}
		});
		helper.succeed();
	}

	/** Both names are saved in the world: renaming either one orphans every charter's progress. */
	@GameTest
	public void theSavedNamesNeverChange(GameTestHelper helper) {
		if (!"founders_hands".equals(WorkOrder.FOUNDERS_HANDS.getSerializedName())) {
			throw helper.assertionException("the saved name of the Founder's hands order must stay founders_hands");
		}
		if (!"deepcharter:work_orders".equals(WorkOrderData.ID.toString())) {
			throw helper.assertionException("the saved data id must stay deepcharter:work_orders");
		}
		helper.succeed();
	}

	@GameTest
	public void aRefusedDeliveryTakesNoOreAndChangesNothing(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		withProcessorOnline(server, () -> {
			MockPlayer mock = player(helper, "Hoarder", true);
			ServerPlayer player = mock.player();
			BlockPos processor = processorFor(helper, mock);

			expectKey(helper, NO_ORE, WorkOrders.deliver(context(server, player, processor), WorkOrder.FOUNDERS_HANDS), "a delivery with no Bronzium");
			carry(player, OreType.SILVERIUM, 5);
			expectKey(helper, NO_ORE, WorkOrders.deliver(context(server, player, processor), WorkOrder.FOUNDERS_HANDS), "a delivery of another ore");

			carry(player, OreType.BRONZIUM, 10);
			CharterId id = charter(server, player).id();
			expectRefused(helper, TerminalRefusal.ACTION_REFUSED, Terminals.act(player, processor, WorkOrders.DELIVER, new CompoundTag()), "a delivery naming no order");
			CompoundTag unknown = new CompoundTag();
			unknown.putString(WorkOrders.ORDER_KEY, "deepcharter:no_such_order");
			expectRefused(helper, TerminalRefusal.ACTION_REFUSED, Terminals.act(player, processor, WorkOrders.DELIVER, unknown), "a delivery naming an unknown order");

			// The last delivery pays the reward, and a full account refuses it: the ore must stay.
			Charters.deposit(server, id, Long.MAX_VALUE - 10);
			expectRefused(helper, TerminalRefusal.ACTION_REFUSED, Terminals.act(player, processor, WorkOrders.DELIVER, orderArgs(WorkOrder.FOUNDERS_HANDS)),
					"a completing delivery into a full account");
			if (carried(player, OreType.BRONZIUM) != 10 || WorkOrderData.get(server).delivered(id, WorkOrder.FOUNDERS_HANDS) != 0) {
				throw helper.assertionException("a refused completion must keep the ore and the progress");
			}
			expectHands(helper, server, false, "after a refused completion");
			if (carried(player, OreType.SILVERIUM) != 5) {
				throw helper.assertionException("another ore must not be touched");
			}
		});
		helper.succeed();
	}

	@GameTest
	public void aDeliveryNeedsACharterAndARepairedProcessor(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		withProcessorOnline(server, () -> {
			MockPlayer drifter = player(helper, "Drifter", false);
			BlockPos processor = processorFor(helper, drifter);
			carry(drifter.player(), OreType.BRONZIUM, 10);
			expectRefused(helper, TerminalRefusal.NOT_ON_A_CHARTER, Terminals.act(drifter.player(), processor, WorkOrders.DELIVER, orderArgs(WorkOrder.FOUNDERS_HANDS)),
					"a delivery by a player on no charter");

			MockPlayer member = player(helper, "Member", true);
			member.teleportTo(helper.getLevel(), drifter.player().position(), 0, 0);
			carry(member.player(), OreType.BRONZIUM, 10);
			WorldData.with(server, RepairState.TYPE, new RepairState(), () -> expectRefused(helper, TerminalRefusal.UNREPAIRED,
					Terminals.act(member.player(), processor, WorkOrders.DELIVER, orderArgs(WorkOrder.FOUNDERS_HANDS)), "a delivery at an unrepaired processor"));
			if (carried(drifter.player(), OreType.BRONZIUM) != 10 || carried(member.player(), OreType.BRONZIUM) != 10) {
				throw helper.assertionException("a refused delivery must take no ore");
			}
		});
		helper.succeed();
	}

	@GameTest
	public void aColonyThatCannotBeFoundRefusesTheCompletionBeforeTakingOre(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		withProcessorOnline(server, () -> {
			MockPlayer mock = player(helper, "Surveyor", true);
			ServerPlayer player = mock.player();
			BlockPos processor = processorFor(helper, mock);
			carry(player, OreType.BRONZIUM, 10);
			WorldData.with(server, ColonySite.TYPE, new ColonySite(), () -> expectKey(helper, NO_STATUE,
					WorkOrders.deliver(context(server, player, processor), WorkOrder.FOUNDERS_HANDS), "a completion with no statue to restore"));
			if (carried(player, OreType.BRONZIUM) != 10 || WorkOrderData.get(server).delivered(charter(server, player).id(), WorkOrder.FOUNDERS_HANDS) != 0) {
				throw helper.assertionException("a refused completion must keep the ore and the progress");
			}
		});
		helper.succeed();
	}

	@GameTest
	public void progressSurvivesASaveAndALoad(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		withProcessorOnline(server, () -> {
			MockPlayer mock = player(helper, "Archivist", true);
			ServerPlayer player = mock.player();
			BlockPos processor = processorFor(helper, mock);
			carry(player, OreType.BRONZIUM, 7);
			expectDone(helper, Terminals.act(player, processor, WorkOrders.DELIVER, orderArgs(WorkOrder.FOUNDERS_HANDS)), "a delivery");

			Tag saved = WorkOrderData.CODEC.encodeStart(NbtOps.INSTANCE, WorkOrderData.get(server)).getOrThrow();
			WorkOrderData loaded = WorkOrderData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
			if (!loaded.isReadable() || loaded.delivered(charter(server, player).id(), WorkOrder.FOUNDERS_HANDS) != 7) {
				throw helper.assertionException("seven Bronzium should still be handed in after a reload, got %s", saved);
			}
		});
		helper.succeed();
	}

	@GameTest
	public void dataOfAnotherVersionIsKeptRefusedAndNeverThrownOn(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		withProcessorOnline(server, () -> {
			MockPlayer mock = player(helper, "Reader", true);
			ServerPlayer player = mock.player();
			BlockPos processor = processorFor(helper, mock);
			carry(player, OreType.BRONZIUM, 10);
			WorkOrderData unreadable = WorkOrderData.CODEC.parse(NbtOps.INSTANCE, UnreadableChecks.futureData()).getOrThrow();
			if (unreadable.isReadable() || !UnreadableChecks.futureData().equals(WorkOrderData.CODEC.encodeStart(NbtOps.INSTANCE, unreadable).getOrThrow())) {
				throw helper.assertionException("version 99 should load as unreadable and be written back unchanged");
			}
			Optional<Charter> charter = Charters.charterOfOrThrow(server, player.getUUID());
			Map<String, Runnable> paths = new LinkedHashMap<>();
			paths.put("delivery", () -> expectKey(helper, UNREADABLE, WorkOrders.deliver(context(server, player, processor), WorkOrder.FOUNDERS_HANDS),
					"a delivery on unreadable data"));
			paths.put("delivery through the terminal", () -> expectRefused(helper, TerminalRefusal.ACTION_REFUSED,
					Terminals.act(player, processor, WorkOrders.DELIVER, orderArgs(WorkOrder.FOUNDERS_HANDS)), "a delivery on unreadable data through the terminal"));
			paths.put("view", () -> {
				WorkOrdersView view = WorkOrders.view(server, player, charter, processor);
				if (view.readable() || !view.orders().isEmpty()) {
					throw helper.assertionException("the view of unreadable data lists no order, got %s", view);
				}
			});
			UnreadableChecks.assertSavedDataNoThrow(helper, "work orders", server, WorkOrderData.TYPE, paths);
			if (carried(player, OreType.BRONZIUM) != 10) {
				throw helper.assertionException("a refused delivery must keep the ore");
			}
			// A body of this version that does not parse (here: more handed in than the order asks) is kept unread, not dropped.
			CompoundTag entry = new CompoundTag();
			entry.putString("charter", UUID.randomUUID().toString());
			entry.putString("order", "founders_hands");
			entry.putInt("delivered", 99);
			entry.putInt("rounds", 0);
			ListTag progress = new ListTag();
			progress.add(entry);
			CompoundTag overfull = new CompoundTag();
			overfull.putInt("version", WorkOrderData.VERSION);
			overfull.put("progress", progress);
			if (WorkOrderData.CODEC.parse(NbtOps.INSTANCE, overfull).getOrThrow().isReadable()) {
				throw helper.assertionException("progress past the order's quantity should load as unreadable");
			}
		});
		helper.succeed();
	}

	@GameTest
	public void theViewListsEachOrderWithTheCharterProgress(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		withProcessorOnline(server, () -> {
			MockPlayer mock = player(helper, "Clerk", true);
			ServerPlayer player = mock.player();
			BlockPos processor = processorFor(helper, mock);
			Optional<Charter> charter = Charters.charterOfOrThrow(server, player.getUUID());
			WorkOrdersView fresh = WorkOrders.view(server, player, charter, processor);
			if (!fresh.equals(new WorkOrdersView(true, List.of(new WorkOrdersView.Entry(WorkOrder.FOUNDERS_HANDS, 0, 0))))) {
				throw helper.assertionException("a new charter has handed in nothing, got %s", fresh);
			}
			carry(player, OreType.BRONZIUM, 4);
			expectDone(helper, Terminals.act(player, processor, WorkOrders.DELIVER, orderArgs(WorkOrder.FOUNDERS_HANDS)), "a delivery");
			WorkOrdersView after = WorkOrders.view(server, player, charter, processor);
			if (!after.equals(new WorkOrdersView(true, List.of(new WorkOrdersView.Entry(WorkOrder.FOUNDERS_HANDS, 4, 0))))) {
				throw helper.assertionException("the view should show four in, got %s", after);
			}
		});
		helper.succeed();
	}
}
