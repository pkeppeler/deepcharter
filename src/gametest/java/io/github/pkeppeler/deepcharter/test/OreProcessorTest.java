package io.github.pkeppeler.deepcharter.test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.CharterRefusal;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.market.MarketTuning;
import io.github.pkeppeler.deepcharter.market.OreProcessor;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalAction;
import io.github.pkeppeler.deepcharter.terminal.TerminalRefusal;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
import io.github.pkeppeler.deepcharter.test.support.LogCapture;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;

/**
 * Server GameTests for #68: "Sell All" at the ore processor credits exactly the {@link OreType} values, from the cargo of a pod
 * parked at it and from the player's inventory; a player on no charter, a full account and unreadable cargo are refused and
 * nothing is consumed.
 *
 * <p>The repair state is one per world, so each test swaps in a fresh one with the processor repaired, and does all its work
 * inside its first tick, as the terminal framework tests do.
 */
public class OreProcessorTest {
	private static final String UNREADABLE_CARGO = "deepcharter.market.refusal.unreadable_cargo";
	private static final String NO_POD = "deepcharter.market.refusal.no_pod";
	private static final String NOTHING_TO_SELL = "deepcharter.market.refusal.nothing_to_sell";

	private static void withProcessorOnline(MinecraftServer server, Runnable body) {
		RepairState original = RepairState.get(server);
		RepairState fresh = new RepairState();
		server.getDataStorage().set(RepairState.TYPE, fresh);
		try {
			for (TerminalType type : List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR)) {
				type.parts().forEach(part -> fresh.insert(type, part));
			}
			body.run();
		} finally {
			server.getDataStorage().set(RepairState.TYPE, original);
		}
	}

	private static MockPlayer player(GameTestHelper helper, String name, boolean onCharter) {
		MockPlayer mock = MockPlayers.join(helper, name);
		mock.player().setGameMode(GameType.SURVIVAL);
		MinecraftServer server = helper.getLevel().getServer();
		if (onCharter && Charters.found(server, mock.player().getUUID(), name + " " + UUID.randomUUID().toString().substring(0, 8)).isPresent()) {
			throw helper.assertionException("founding a charter for %s should succeed", name);
		}
		return mock;
	}

	/** The processor at a fixed place in the test, and the player standing two blocks from it. */
	private static BlockPos processorFor(GameTestHelper helper, MockPlayer mock) {
		BlockPos relative = new BlockPos(2, 1, 2);
		helper.setBlock(relative, TerminalTypes.ORE_PROCESSOR.block().defaultBlockState());
		BlockPos pos = helper.absolutePos(relative);
		Vec3 centre = Vec3.atCenterOf(pos);
		mock.teleportTo(helper.getLevel(), new Vec3(centre.x + 2, centre.y - mock.player().getEyeHeight(), centre.z), 0, 0);
		return pos;
	}

	private static PodEntity podAt(GameTestHelper helper, Vec3 at, OreType... ores) {
		ServerLevel level = helper.getLevel();
		PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
		pod.setPos(at);
		level.addFreshEntity(pod);
		for (OreType ore : ores) {
			pod.cargo().tryAdd(pod, OreRegistry.stack(ore));
		}
		return pod;
	}

	private static Vec3 beside(BlockPos processor, double distance) {
		return Vec3.atCenterOf(processor).add(0, 0, distance);
	}

	private static void carry(ServerPlayer player, OreType... ores) {
		for (OreType ore : ores) {
			player.getInventory().add(OreRegistry.stack(ore));
		}
	}

	private static long balance(MinecraftServer server, ServerPlayer player) {
		return Charters.charterOf(server, player.getUUID()).orElseThrow().account();
	}

	private static int ores(ServerPlayer player) {
		int count = 0;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			if (OreRegistry.typeOf(player.getInventory().getItem(slot)).isPresent()) {
				count += player.getInventory().getItem(slot).getCount();
			}
		}
		return count;
	}

	private static long worth(OreType... ores) {
		long total = 0;
		for (OreType ore : ores) {
			total += ore.value();
		}
		return total;
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

	private static void expectKey(GameTestHelper helper, String key, Optional<Component> refusal, String what) {
		if (refusal.isEmpty() || !(refusal.get().getContents() instanceof TranslatableContents contents) || !contents.getKey().equals(key)) {
			throw helper.assertionException("%s should be refused with %s, got %s", what, key, refusal);
		}
	}

	private static TerminalAction.Context context(MinecraftServer server, ServerPlayer player, BlockPos pos) {
		return new TerminalAction.Context(server, player, Charters.charterOf(server, player.getUUID()), TerminalTypes.ORE_PROCESSOR, pos, new CompoundTag());
	}

	@GameTest
	public void sellAllCreditsTheExactValueFromCargoAndInventory(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		withProcessorOnline(server, () -> {
			MockPlayer mock = player(helper, "Seller", true);
			ServerPlayer player = mock.player();
			BlockPos processor = processorFor(helper, mock);
			PodEntity near = podAt(helper, beside(processor, 4), OreType.IRONIUM, OreType.GOLDIUM, OreType.EINSTEINIUM);
			PodEntity far = podAt(helper, beside(processor, MarketTuning.DEFAULT.processorRadius() + 3), OreType.PLATINIUM);
			carry(player, OreType.BRONZIUM, OreType.CICATRIUM, OreType.SILVERIUM);
			try {
				long before = balance(server, player);

				expectDone(helper, Terminals.act(player, processor, OreProcessor.SELL_CARGO, new CompoundTag()), "selling the cargo");
				long cargo = worth(OreType.IRONIUM, OreType.GOLDIUM, OreType.EINSTEINIUM);
				if (balance(server, player) != before + cargo || near.cargoUsed() != 0 || !near.cargo().entries().isEmpty()) {
					throw helper.assertionException("selling the cargo should credit %s and empty the bay: balance %s -> %s, bay %s",
							cargo, before, balance(server, player), near.cargo().entries());
				}
				if (far.cargoUsed() != 1 || ores(player) != 3) {
					throw helper.assertionException("a pod out of the radius and the inventory must be left alone by a cargo sale");
				}

				expectDone(helper, Terminals.act(player, processor, OreProcessor.SELL_INVENTORY, new CompoundTag()), "selling the inventory");
				long inventory = worth(OreType.BRONZIUM, OreType.CICATRIUM, OreType.SILVERIUM);
				if (balance(server, player) != before + cargo + inventory || ores(player) != 0) {
					throw helper.assertionException("selling the inventory should credit %s and take every ore: balance %s -> %s, %s ore left",
							inventory, before + cargo, balance(server, player), ores(player));
				}
				if (far.cargoUsed() != 1) {
					throw helper.assertionException("a pod out of the radius must keep its cargo");
				}
			} finally {
				near.discard();
				far.discard();
			}
		});
		helper.succeed();
	}

	@GameTest
	public void everyOreSellsForItsOriginalDollarValue(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		withProcessorOnline(server, () -> {
			MockPlayer mock = player(helper, "Appraiser", true);
			ServerPlayer player = mock.player();
			BlockPos processor = processorFor(helper, mock);
			for (OreType ore : OreType.values()) {
				carry(player, ore);
				long before = balance(server, player);
				expectDone(helper, Terminals.act(player, processor, OreProcessor.SELL_INVENTORY, new CompoundTag()), "selling " + ore);
				if (balance(server, player) != before + ore.value()) {
					throw helper.assertionException("%s should sell for %s, balance went %s -> %s", ore, ore.value(), before, balance(server, player));
				}
			}
		});
		helper.succeed();
	}

	@GameTest
	public void aPlayerOnNoCharterIsRefusedAndKeepsTheOre(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		withProcessorOnline(server, () -> {
			MockPlayer mock = player(helper, "Drifter", false);
			ServerPlayer player = mock.player();
			BlockPos processor = processorFor(helper, mock);
			PodEntity pod = podAt(helper, beside(processor, 4), OreType.GOLDIUM);
			carry(player, OreType.IRONIUM);
			try {
				expectRefused(helper, TerminalRefusal.NOT_ON_A_CHARTER, Terminals.act(player, processor, OreProcessor.SELL_CARGO, new CompoundTag()),
						"a cargo sale by a player on no charter");
				expectRefused(helper, TerminalRefusal.NOT_ON_A_CHARTER, Terminals.act(player, processor, OreProcessor.SELL_INVENTORY, new CompoundTag()),
						"an inventory sale by a player on no charter");
				if (pod.cargoUsed() != 1 || ores(player) != 1) {
					throw helper.assertionException("a refused sale must consume nothing");
				}
			} finally {
				pod.discard();
			}
		});
		helper.succeed();
	}

	@GameTest
	public void aFullAccountRefusesTheSaleAndConsumesNothing(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		withProcessorOnline(server, () -> {
			MockPlayer mock = player(helper, "Hoarder", true);
			ServerPlayer player = mock.player();
			BlockPos processor = processorFor(helper, mock);
			PodEntity pod = podAt(helper, beside(processor, 4), OreType.GOLDIUM);
			carry(player, OreType.IRONIUM);
			try {
				Charters.deposit(server, Charters.charterOf(server, player.getUUID()).orElseThrow().id(), Long.MAX_VALUE - 10);
				long before = balance(server, player);
				expectRefused(helper, TerminalRefusal.ACTION_REFUSED, Terminals.act(player, processor, OreProcessor.SELL_CARGO, new CompoundTag()),
						"a cargo sale into a full account");
				expectRefused(helper, TerminalRefusal.ACTION_REFUSED, Terminals.act(player, processor, OreProcessor.SELL_INVENTORY, new CompoundTag()),
						"an inventory sale into a full account");
				if (balance(server, player) != before || pod.cargoUsed() != 1 || ores(player) != 1) {
					throw helper.assertionException("a full account must keep the ore and the balance: balance %s -> %s, cargo %s, carried %s",
							before, balance(server, player), pod.cargoUsed(), ores(player));
				}
			} finally {
				pod.discard();
			}
		});
		helper.succeed();
	}

	@GameTest
	public void unreadableCargoRefusesWithoutThrowing(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel level = helper.getLevel();
		withProcessorOnline(server, () -> {
			MockPlayer mock = player(helper, "Reader", true);
			ServerPlayer player = mock.player();
			BlockPos processor = processorFor(helper, mock);
			PodEntity pod = podAt(helper, beside(processor, 4), OreType.GOLDIUM);
			try {
				TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
				pod.saveWithoutId(output);
				CompoundTag tag = output.buildResult();
				tag.putInt("cargo_version", 99);
				pod.cargo().load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag), pod);
				if (pod.cargo().isReadable()) {
					throw helper.assertionException("the test needs unreadable cargo");
				}
				long before = balance(server, player);
				expectKey(helper, UNREADABLE_CARGO, OreProcessor.sellCargo(context(server, player, processor)), "selling unreadable cargo");
				expectRefused(helper, TerminalRefusal.ACTION_REFUSED, Terminals.act(player, processor, OreProcessor.SELL_CARGO, new CompoundTag()),
						"selling unreadable cargo through the terminal");
				if (balance(server, player) != before) {
					throw helper.assertionException("unreadable cargo must credit nothing");
				}
			} finally {
				pod.discard();
			}
		});
		helper.succeed();
	}

	@GameTest
	public void nothingToSellAndNoPodAreRefused(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		withProcessorOnline(server, () -> {
			MockPlayer mock = player(helper, "Empty", true);
			ServerPlayer player = mock.player();
			BlockPos processor = processorFor(helper, mock);
			expectKey(helper, NO_POD, OreProcessor.sellCargo(context(server, player, processor)), "selling cargo with no pod parked");
			expectKey(helper, NOTHING_TO_SELL, OreProcessor.sellInventory(context(server, player, processor)), "selling an empty inventory");
			PodEntity pod = podAt(helper, beside(processor, 4));
			try {
				expectKey(helper, NOTHING_TO_SELL, OreProcessor.sellCargo(context(server, player, processor)), "selling an empty bay");
			} finally {
				pod.discard();
			}
			if (balance(server, player) != 0) {
				throw helper.assertionException("nothing sold must credit nothing");
			}
		});
		helper.succeed();
	}

	/** Registers {@code pod} to the charter of {@code owner}. */
	private static void register(MinecraftServer server, PodEntity pod, ServerPlayer owner) {
		PodComponents.register(pod, Charters.charterOf(server, owner.getUUID()).orElseThrow().id());
	}

	@GameTest
	public void anotherChartersPodIsNeitherSoldNorDumped(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		withProcessorOnline(server, () -> {
			MockPlayer mock = player(helper, "Thief", true);
			MockPlayer rival = player(helper, "Rival", true);
			ServerPlayer player = mock.player();
			BlockPos processor = processorFor(helper, mock);
			PodEntity theirs = podAt(helper, beside(processor, 4), OreType.EINSTEINIUM);
			register(server, theirs, rival.player());
			try {
				long before = balance(server, player);
				expectKey(helper, NO_POD, OreProcessor.sellCargo(context(server, player, processor)), "selling the cargo of another charter's pod");
				PodEntity mine = podAt(helper, beside(processor, -4), OreType.IRONIUM);
				register(server, mine, player);
				try {
					expectDone(helper, Terminals.act(player, processor, OreProcessor.SELL_CARGO, new CompoundTag()), "selling beside a rival's pod");
					if (balance(server, player) != before + OreType.IRONIUM.value() || mine.cargoUsed() != 0
							|| theirs.cargoUsed() != 1 || theirs.cargo().entries().size() != 1) {
						throw helper.assertionException("only the own pod may be sold and dumped: balance %s -> %s, rival cargo %s",
								before, balance(server, player), theirs.cargo().entries());
					}
				} finally {
					mine.discard();
				}
			} finally {
				theirs.discard();
			}
		});
		helper.succeed();
	}

	@GameTest
	public void anUnownedPodAndADormantOwnersPodAreSold(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		withProcessorOnline(server, () -> {
			MockPlayer mock = player(helper, "Salvager", true);
			MockPlayer lapsed = player(helper, "Lapsed", true);
			ServerPlayer player = mock.player();
			BlockPos processor = processorFor(helper, mock);
			PodEntity unowned = podAt(helper, beside(processor, 4), OreType.IRONIUM);
			PodEntity dormant = podAt(helper, beside(processor, -4), OreType.GOLDIUM);
			register(server, dormant, lapsed.player());
			try {
				Optional<CharterRefusal> left = Charters.leave(server, lapsed.player().getUUID());
				if (left.isPresent()) {
					throw helper.assertionException("leaving should succeed, was refused: %s", left.get());
				}
				long before = balance(server, player);
				expectDone(helper, Terminals.act(player, processor, OreProcessor.SELL_CARGO, new CompoundTag()), "selling unowned and dormant pods");
				if (balance(server, player) != before + worth(OreType.IRONIUM, OreType.GOLDIUM) || unowned.cargoUsed() != 0 || dormant.cargoUsed() != 0) {
					throw helper.assertionException("an unowned pod and a dormant owner's pod are anyone's: balance %s -> %s", before, balance(server, player));
				}
			} finally {
				unowned.discard();
				dormant.discard();
			}
		});
		helper.succeed();
	}

	@GameTest
	public void aPodWithUnreadableComponentsIsNotSold(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		withProcessorOnline(server, () -> {
			MockPlayer mock = player(helper, "Doubter", true);
			ServerPlayer player = mock.player();
			BlockPos processor = processorFor(helper, mock);
			PodEntity pod = podAt(helper, beside(processor, 4), OreType.GOLDIUM);
			pod.setAttached(PodComponents.STATE, new Versioned.Unreadable<PodComponents.State>(new CompoundTag()));
			try {
				expectKey(helper, NO_POD, OreProcessor.sellCargo(context(server, player, processor)), "selling a pod whose owner is unknown");
				if (pod.cargoUsed() != 1 || balance(server, player) != 0) {
					throw helper.assertionException("a pod with unreadable components must keep its cargo");
				}
			} finally {
				pod.discard();
			}
		});
		helper.succeed();
	}

	@GameTest
	public void anUnreadableCargoIsSkippedAndTheOtherPodsAreSold(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel level = helper.getLevel();
		withProcessorOnline(server, () -> {
			MockPlayer mock = player(helper, "Sorter", true);
			ServerPlayer player = mock.player();
			BlockPos processor = processorFor(helper, mock);
			PodEntity good = podAt(helper, beside(processor, 4), OreType.SILVERIUM);
			PodEntity bad = podAt(helper, beside(processor, -4), OreType.GOLDIUM);
			LogCapture log = LogCapture.start(bad.getUUID().toString());
			try {
				TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
				bad.saveWithoutId(output);
				CompoundTag tag = output.buildResult();
				tag.putInt("cargo_version", 99);
				bad.cargo().load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag), bad);
				long before = balance(server, player);
				expectDone(helper, Terminals.act(player, processor, OreProcessor.SELL_CARGO, new CompoundTag()), "selling beside an unreadable pod");
				expectKey(helper, NOTHING_TO_SELL, OreProcessor.sellCargo(context(server, player, processor)), "selling again");
				String once = "Pod " + bad.getUUID() + ": cargo unreadable, not sold";
				long logged = log.errors().stream().filter(once::equals).count();
				if (balance(server, player) != before + OreType.SILVERIUM.value() || good.cargoUsed() != 0 || bad.cargo().isReadable()) {
					throw helper.assertionException("the readable pod is sold and the unreadable one is left: balance %s -> %s", before, balance(server, player));
				}
				if (logged != 1) {
					throw helper.assertionException("the skipped pod is logged once, \"%s\" appeared %s times in %s", once, logged, log.errors());
				}
			} finally {
				good.discard();
				bad.discard();
			}
		});
		helper.succeed();
	}
}
