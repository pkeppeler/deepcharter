package io.github.pkeppeler.deepcharter.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterData;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.handbook.HandbookTriggers;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalActionPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalBlockEntity;
import io.github.pkeppeler.deepcharter.terminal.TerminalEvents;
import io.github.pkeppeler.deepcharter.terminal.TerminalRefusal;
import io.github.pkeppeler.deepcharter.terminal.TerminalFeature;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TerminalTestTypes;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;

/**
 * Server GameTests for #59: the repair order, a repair seen by a second charter, validated actions, saved state, and the rest
 * of the terminal rules (access, unreadable data, parts, recipes, unbreakable blocks, registration).
 *
 * <p>The repair state is one per world. A test that repairs things swaps in a fresh {@link RepairState} for its own duration
 * ({@link #withState}) and puts the world's back at the end. Each such test does all its work inside its first tick, so no two
 * of them overlap. Test handlers live on the test-only types of {@link TerminalTestTypes}, never on a colony terminal.
 */
public class TerminalFrameworkTest {
	private static final List<TerminalType> COLONY = List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR,
			TerminalTypes.UPGRADE_TERMINAL, TerminalTypes.REPAIR_STATION);
	private static final Identifier UNKNOWN = Identifier.fromNamespaceAndPath("deepcharter_test", "unknown");
	private static final Set<String> RECIPE_INGREDIENTS = Set.of("minecraft:iron_ingot", "minecraft:copper_ingot", "minecraft:redstone");
	private static final TagKey<Block> TERMINALS = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("deepcharter", "terminals"));

	private static final List<Identifier> REPAIRED_LOG = new ArrayList<>();
	private static final List<String> OPENED_LOG = new ArrayList<>();

	static {
		TerminalEvents.REPAIRED.register((server, type, charter, player) -> REPAIRED_LOG.add(type.id()));
		TerminalEvents.OPENED.register((server, type, player) -> OPENED_LOG.add(type.id() + "/" + player.getGameProfile().name()));
	}

	/** Runs {@code body} with {@code state} as the world's repair state, and puts the world's own back after. */
	private static void withState(MinecraftServer server, RepairState state, Runnable body) {
		RepairState original = RepairState.get(server);
		server.getDataStorage().set(RepairState.TYPE, state);
		try {
			body.run();
		} finally {
			server.getDataStorage().set(RepairState.TYPE, original);
		}
	}

	private static void withFreshState(MinecraftServer server, Consumer<RepairState> body) {
		RepairState fresh = new RepairState();
		withState(server, fresh, () -> body.accept(fresh));
	}

	private static BlockPos place(GameTestHelper helper, TerminalType type, int x) {
		BlockPos relative = new BlockPos(x, 1, 0);
		helper.setBlock(relative, type.block().defaultBlockState());
		return helper.absolutePos(relative);
	}

	/** A mock player, in survival, founding a charter of their own. */
	private static MockPlayer charterMember(GameTestHelper helper, String name) {
		MockPlayer mock = outsider(helper, name);
		MinecraftServer server = helper.getLevel().getServer();
		if (Charters.found(server, mock.player().getUUID(), name + " " + UUID.randomUUID().toString().substring(0, 8)).isPresent()) {
			throw helper.assertionException("founding a charter for %s should succeed", name);
		}
		return mock;
	}

	/** A mock player, in survival, on no charter. */
	private static MockPlayer outsider(GameTestHelper helper, String name) {
		MockPlayer mock = MockPlayers.join(helper, name);
		mock.player().setGameMode(GameType.SURVIVAL);
		return mock;
	}

	/** Puts the player {@code distance} blocks from the middle of {@code pos}, with their eyes level with it. */
	private static void stand(GameTestHelper helper, MockPlayer mock, BlockPos pos, double distance) {
		Vec3 centre = Vec3.atCenterOf(pos);
		mock.teleportTo(helper.getLevel(), new Vec3(centre.x + distance, centre.y - mock.player().getEyeHeight(), centre.z), 0, 0);
	}

	private static void give(ServerPlayer player, Item item, int count) {
		for (int i = 0; i < count; i++) {
			player.getInventory().add(new ItemStack(item));
		}
	}

	private static void giveAllParts(ServerPlayer player) {
		COLONY.forEach(type -> type.parts().forEach(part -> give(player, part, 1)));
	}

	private static int count(ServerPlayer player, Item item) {
		Inventory inventory = player.getInventory();
		int total = 0;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			if (inventory.getItem(slot).is(item)) {
				total += inventory.getItem(slot).getCount();
			}
		}
		return total;
	}

	private static void expectRefused(GameTestHelper helper, TerminalRefusal expected, Optional<TerminalRefusal> actual, String what) {
		if (!actual.equals(Optional.of(expected))) {
			throw helper.assertionException("%s should be refused with %s, got %s", what, expected, actual);
		}
	}

	private static void expectDone(GameTestHelper helper, Optional<TerminalRefusal> refusal, String what) {
		if (refusal.isPresent()) {
			throw helper.assertionException("%s should succeed, was refused: %s", what, refusal.get());
		}
	}

	/** Repairs {@code type} straight in {@code state}, leaving no events. */
	private static void repairDirectly(GameTestHelper helper, RepairState state, TerminalType type) {
		type.parts().forEach(part -> expectDone(helper, state.insert(type, part), "inserting " + part));
	}

	private static String id(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}

	@GameTest
	public void theRepairOrderHolds(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		withFreshState(server, state -> {
			REPAIRED_LOG.clear();
			MockPlayer mock = charterMember(helper, "Repairer");
			ServerPlayer player = mock.player();
			giveAllParts(player);
			List<BlockPos> positions = new ArrayList<>();
			for (int x = 0; x < COLONY.size(); x++) {
				positions.add(place(helper, COLONY.get(x), x));
			}
			stand(helper, mock, positions.getFirst(), 2);

			for (int step = 1; step < COLONY.size(); step++) {
				TerminalType early = COLONY.get(step);
				Item part = early.parts().getFirst();
				int held = count(player, part);
				expectRefused(helper, TerminalRefusal.PREREQUISITE_UNREPAIRED, Terminals.insertPart(player, positions.get(step), part),
						"a part of " + early.id() + " before its predecessor is repaired");
				if (!state.inserted(early).isEmpty() || count(player, part) != held) {
					throw helper.assertionException("a refused insert must keep the part and change no state for %s", early.id());
				}
			}

			for (int step = 0; step < COLONY.size(); step++) {
				TerminalType type = COLONY.get(step);
				for (int i = 0; i < type.parts().size(); i++) {
					if (state.repaired(type)) {
						throw helper.assertionException("%s is repaired before its last part: %s of %s", type.id(), i, type.parts().size());
					}
					expectDone(helper, Terminals.insertPart(player, positions.get(step), type.parts().get(i)), "inserting part " + i + " of " + type.id());
				}
				if (!state.repaired(type)) {
					throw helper.assertionException("%s should be repaired with all its parts in", type.id());
				}
				if (REPAIRED_LOG.size() != step + 1 || !REPAIRED_LOG.getLast().equals(type.id())) {
					throw helper.assertionException("REPAIRED should fire once for %s, log is %s", type.id(), REPAIRED_LOG);
				}
				if (step + 2 < COLONY.size()) {
					TerminalType skipped = COLONY.get(step + 2);
					expectRefused(helper, TerminalRefusal.PREREQUISITE_UNREPAIRED,
							Terminals.insertPart(player, positions.get(step + 2), skipped.parts().getFirst()),
							"a part of " + skipped.id() + " while " + COLONY.get(step + 1).id() + " is not repaired");
				}
			}
			if (REPAIRED_LOG.size() != COLONY.size()) {
				throw helper.assertionException("REPAIRED fires once per terminal, log is %s", REPAIRED_LOG);
			}
			expectRefused(helper, TerminalRefusal.ALREADY_REPAIRED, Terminals.insertPart(player, positions.getFirst(), TerminalTypes.FUEL_PUMP.parts().getFirst()),
					"a part into a repaired terminal");
		});
		helper.succeed();
	}

	@GameTest
	public void onlyTheRightPartsGoInOnce(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		withFreshState(server, state -> {
			MockPlayer mock = charterMember(helper, "Fitter");
			ServerPlayer player = mock.player();
			BlockPos pump = place(helper, TerminalTypes.FUEL_PUMP, 0);
			stand(helper, mock, pump, 2);
			Item own = TerminalTypes.FUEL_PUMP.parts().getFirst();
			Item foreign = TerminalTypes.ORE_PROCESSOR.parts().getFirst();

			give(player, foreign, 1);
			expectRefused(helper, TerminalRefusal.NOT_A_PART, Terminals.insertPart(player, pump, foreign), "another terminal's part");
			expectRefused(helper, TerminalRefusal.MISSING_PART, Terminals.insertPart(player, pump, own), "a part the player does not hold");
			if (count(player, foreign) != 1 || !state.inserted(TerminalTypes.FUEL_PUMP).isEmpty()) {
				throw helper.assertionException("refused inserts change nothing");
			}

			give(player, own, 2);
			expectDone(helper, Terminals.insertPart(player, pump, own), "inserting a part");
			if (count(player, own) != 1 || !state.inserted(TerminalTypes.FUEL_PUMP).equals(List.of(own))) {
				throw helper.assertionException("an insert takes one part from the player and records it");
			}
			expectRefused(helper, TerminalRefusal.ALREADY_INSERTED, Terminals.insertPart(player, pump, own), "the same part twice");
			if (count(player, own) != 1) {
				throw helper.assertionException("the refused second insert must keep the part");
			}
		});
		helper.succeed();
	}

	@GameTest
	public void aRepairIsVisibleToASecondCharter(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		TerminalType type = TerminalTestTypes.REPAIRABLE;
		withFreshState(server, state -> {
			MockPlayer first = charterMember(helper, "FirstCharter");
			MockPlayer second = charterMember(helper, "SecondCharter");
			Charter firstCharter = Charters.charterOf(server, first.player().getUUID()).orElseThrow();
			Charter secondCharter = Charters.charterOf(server, second.player().getUUID()).orElseThrow();
			if (firstCharter.id().equals(secondCharter.id())) {
				throw helper.assertionException("the test needs two charters");
			}
			BlockPos terminal = place(helper, type, 0);
			stand(helper, first, terminal, 2);
			stand(helper, second, terminal, 3);
			give(first.player(), Items.FLINT, 1);

			int before = TerminalTestTypes.pings();
			expectRefused(helper, TerminalRefusal.UNREPAIRED, Terminals.act(second.player(), terminal, TerminalTestTypes.PING, new CompoundTag()),
					"the second charter's action before any repair");

			expectDone(helper, Terminals.insertPart(first.player(), terminal, Items.FLINT), "the first charter inserting");

			expectDone(helper, Terminals.act(second.player(), terminal, TerminalTestTypes.PING, new CompoundTag()), "the second charter's action after the first repaired");
			if (TerminalTestTypes.pings() != before + 1
					|| !TerminalTestTypes.lastPinger().equals(Optional.of(Optional.of(secondCharter)))) {
				throw helper.assertionException("the action should run once, for the second charter: pings %s -> %s, charter %s",
						before, TerminalTestTypes.pings(), TerminalTestTypes.lastPinger());
			}
		});
		helper.succeed();
	}

	@GameTest
	public void actionsAreValidated(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		TerminalType type = TerminalTestTypes.REPAIRABLE;
		withFreshState(server, state -> {
			MockPlayer member = charterMember(helper, "Member");
			MockPlayer outsider = outsider(helper, "Outsider");
			BlockPos terminal = place(helper, type, 0);
			CompoundTag none = new CompoundTag();
			int before = TerminalTestTypes.pings();

			stand(helper, member, terminal, 2);
			expectRefused(helper, TerminalRefusal.UNREPAIRED, Terminals.act(member.player(), terminal, TerminalTestTypes.PING, none), "an action at an unrepaired terminal");
			repairDirectly(helper, state, type);

			stand(helper, member, terminal, 5.5);
			expectDone(helper, Terminals.act(member.player(), terminal, TerminalTestTypes.PING, none), "an action from inside the range");
			expectDone(helper, Terminals.open(member.player(), terminal), "opening from inside the range");
			if (TerminalTestTypes.pings() != before + 1) {
				throw helper.assertionException("the action should have run once");
			}

			stand(helper, member, terminal, 6.5);
			expectRefused(helper, TerminalRefusal.TOO_FAR, Terminals.act(member.player(), terminal, TerminalTestTypes.PING, none), "an action from too far");
			expectRefused(helper, TerminalRefusal.TOO_FAR, Terminals.open(member.player(), terminal), "opening from too far");

			stand(helper, outsider, terminal, 2);
			expectRefused(helper, TerminalRefusal.NOT_ON_A_CHARTER, Terminals.act(outsider.player(), terminal, TerminalTestTypes.PING, none), "an action from a player on no charter");
			expectRefused(helper, TerminalRefusal.NOT_ON_A_CHARTER, Terminals.open(outsider.player(), terminal), "opening by a player on no charter");

			stand(helper, member, terminal, 2);
			expectRefused(helper, TerminalRefusal.NO_SUCH_ACTION, Terminals.act(member.player(), terminal, UNKNOWN, none), "an action nobody registered");
			expectRefused(helper, TerminalRefusal.ACTION_REFUSED, Terminals.act(member.player(), terminal, TerminalTestTypes.DENY, none), "an action whose handler refuses");
			expectRefused(helper, TerminalRefusal.NO_SUCH_TERMINAL, Terminals.act(member.player(), terminal.above(3), TerminalTestTypes.PING, none), "an action at a position with no terminal");
			expectRefused(helper, TerminalRefusal.NO_SUCH_TERMINAL, Terminals.open(member.player(), terminal.above(3)), "opening a position with no terminal");

			if (TerminalTestTypes.pings() != before + 1) {
				throw helper.assertionException("no refused action may have run its handler, pings went from %s to %s", before, TerminalTestTypes.pings());
			}
		});
		helper.succeed();
	}

	@GameTest
	public void aSpoofedPositionOnAnotherBlockIsNoTerminal(GameTestHelper helper) {
		MockPlayer member = charterMember(helper, "Spoofer");
		BlockPos chest = helper.absolutePos(new BlockPos(0, 1, 0));
		helper.setBlock(new BlockPos(0, 1, 0), Blocks.CHEST.defaultBlockState());
		stand(helper, member, chest, 2);
		expectRefused(helper, TerminalRefusal.NO_SUCH_TERMINAL, Terminals.open(member.player(), chest), "opening a chest as a terminal");
		expectRefused(helper, TerminalRefusal.NO_SUCH_TERMINAL, Terminals.act(member.player(), chest, TerminalTestTypes.PING, new CompoundTag()),
				"an action at a chest");
		expectRefused(helper, TerminalRefusal.NO_SUCH_TERMINAL, Terminals.insertPart(member.player(), chest, Items.FLINT), "a part into a chest");
		helper.succeed();
	}

	@GameTest
	public void aTerminalWithNoRepairWorksForAnyoneFromTheStart(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		TerminalType type = TerminalTestTypes.OPEN;
		withFreshState(server, state -> {
			MockPlayer outsider = outsider(helper, "Newcomer");
			BlockPos terminal = place(helper, type, 0);
			stand(helper, outsider, terminal, 2);
			int before = TerminalTestTypes.pings();
			OPENED_LOG.clear();

			if (type.needsRepair() || !type.parts().isEmpty() || type.access() != TerminalType.Access.ANYONE || !RepairState.get(server).repaired(type)) {
				throw helper.assertionException("the open terminal needs no repair, and is online for anyone");
			}
			expectDone(helper, Terminals.open(outsider.player(), terminal), "a player on no charter opening it");
			expectDone(helper, Terminals.act(outsider.player(), terminal, TerminalTestTypes.PING, new CompoundTag()), "a player on no charter acting at it");
			if (TerminalTestTypes.pings() != before + 1 || !TerminalTestTypes.lastPinger().equals(Optional.of(Optional.empty()))
					|| !OPENED_LOG.equals(List.of(type.id() + "/Newcomer"))) {
				throw helper.assertionException("the action runs with no charter and the open is announced once: pings %s -> %s, charter %s, opened %s",
						before, TerminalTestTypes.pings(), TerminalTestTypes.lastPinger(), OPENED_LOG);
			}
			expectRefused(helper, TerminalRefusal.ALREADY_REPAIRED, Terminals.insertPart(outsider.player(), terminal, Items.FLINT), "a part into a terminal that needs none");

			stand(helper, outsider, terminal, 6.5);
			expectRefused(helper, TerminalRefusal.TOO_FAR, Terminals.open(outsider.player(), terminal), "opening it from too far");
			if (!state.inserted(type).isEmpty()) {
				throw helper.assertionException("a terminal with no repair has no parts in the state");
			}
		});
		helper.succeed();
	}

	@GameTest
	public void blockUseOpensTheTerminalOnTheServer(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		withFreshState(server, state -> {
			MockPlayer member = charterMember(helper, "User");
			BlockPos pump = place(helper, TerminalTypes.FUEL_PUMP, 0);
			stand(helper, member, pump, 2);
			OPENED_LOG.clear();

			BlockState blockState = helper.getLevel().getBlockState(pump);
			blockState.useWithoutItem(helper.getLevel(), member.player(), new BlockHitResult(Vec3.atCenterOf(pump), Direction.UP, pump, false));
			if (!OPENED_LOG.equals(List.of(TerminalTypes.FUEL_PUMP.id() + "/User"))) {
				throw helper.assertionException("using the block should open the terminal for the player, opened: %s", OPENED_LOG);
			}
			stand(helper, member, pump, 6.5);
			OPENED_LOG.clear();
			blockState.useWithoutItem(helper.getLevel(), member.player(), new BlockHitResult(Vec3.atCenterOf(pump), Direction.UP, pump, false));
			if (!OPENED_LOG.isEmpty()) {
				throw helper.assertionException("using the block from too far must not open it: %s", OPENED_LOG);
			}
		});
		helper.succeed();
	}

	@GameTest
	public void unreadableRepairDataRefusesInsteadOfThrowing(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayer member = charterMember(helper, "Unlucky");
		MockPlayer outsider = outsider(helper, "Newcomer");
		BlockPos pump = place(helper, TerminalTypes.FUEL_PUMP, 0);
		BlockPos open = place(helper, TerminalTestTypes.OPEN, 1);
		stand(helper, member, pump, 2);
		stand(helper, outsider, open, 2);
		give(member.player(), TerminalTypes.FUEL_PUMP.parts().getFirst(), 1);
		OPENED_LOG.clear();

		Map<String, Runnable> paths = new LinkedHashMap<>();
		paths.put("open", () -> expectRefused(helper, TerminalRefusal.STATE_UNREADABLE, Terminals.open(member.player(), pump),
				"opening with unreadable repair data"));
		paths.put("insert", () -> expectRefused(helper, TerminalRefusal.STATE_UNREADABLE,
				Terminals.insertPart(member.player(), pump, TerminalTypes.FUEL_PUMP.parts().getFirst()), "inserting with unreadable repair data"));
		paths.put("use", () -> helper.getLevel().getBlockState(pump).useWithoutItem(helper.getLevel(), member.player(),
				new BlockHitResult(Vec3.atCenterOf(pump), Direction.UP, pump, false)));
		paths.put("handbook repair credit", () -> HandbookTriggers.creditRepairs(server, member.player()));
		paths.put("terminal that needs no repair", () -> {
			int opened = OPENED_LOG.size();
			expectDone(helper, Terminals.open(outsider.player(), open), "a terminal that needs no repair state still opens");
			if (OPENED_LOG.size() != opened + 1) {
				throw helper.assertionException("the terminal that needs no repair state opens once");
			}
		});
		UnreadableChecks.assertSavedDataNoThrow(helper, "repair state", server, RepairState.TYPE, paths);
		if (count(member.player(), TerminalTypes.FUEL_PUMP.parts().getFirst()) != 1 || OPENED_LOG.stream().anyMatch(entry -> entry.startsWith(TerminalTypes.FUEL_PUMP.id().toString()))) {
			throw helper.assertionException("a refused request keeps the part and opens nothing");
		}
		helper.succeed();
	}

	@GameTest
	public void unreadableCharterDataRefusesInsteadOfThrowing(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayer member = charterMember(helper, "Stranded");
		BlockPos pump = place(helper, TerminalTypes.FUEL_PUMP, 0);
		BlockPos open = place(helper, TerminalTestTypes.OPEN, 1);
		stand(helper, member, pump, 2);
		give(member.player(), TerminalTypes.FUEL_PUMP.parts().getFirst(), 1);
		Map<String, Runnable> paths = new LinkedHashMap<>();
		paths.put("open", () -> expectRefused(helper, TerminalRefusal.STATE_UNREADABLE, Terminals.open(member.player(), pump),
				"opening with unreadable charter data"));
		paths.put("insert", () -> expectRefused(helper, TerminalRefusal.STATE_UNREADABLE,
				Terminals.insertPart(member.player(), pump, TerminalTypes.FUEL_PUMP.parts().getFirst()), "inserting with unreadable charter data"));
		paths.put("action at a terminal for anyone", () -> expectRefused(helper, TerminalRefusal.STATE_UNREADABLE,
				Terminals.act(member.player(), open, TerminalTestTypes.PING, new CompoundTag()), "an action at a terminal for anyone, which still needs the charter lookup"));
		paths.put("use", () -> helper.getLevel().getBlockState(pump).useWithoutItem(helper.getLevel(), member.player(),
				new BlockHitResult(Vec3.atCenterOf(pump), Direction.UP, pump, false)));
		UnreadableChecks.assertSavedDataNoThrow(helper, "terminals on unreadable charters", server, CharterData.TYPE, paths);
		if (count(member.player(), TerminalTypes.FUEL_PUMP.parts().getFirst()) != 1) {
			throw helper.assertionException("a refused request keeps the part");
		}
		helper.succeed();
	}

	@GameTest
	public void terminalsHaveABlockEntityOfTheirType(GameTestHelper helper) {
		for (TerminalType type : TerminalTypes.all()) {
			BlockPos pos = place(helper, type, 0);
			if (!(helper.getLevel().getBlockEntity(pos) instanceof TerminalBlockEntity entity) || entity.type() != type) {
				throw helper.assertionException("%s should have a terminal block entity of its type, got %s", type.id(), helper.getLevel().getBlockEntity(pos));
			}
		}
		helper.succeed();
	}

	@GameTest
	public void terminalsCannotBeBrokenInSurvival(GameTestHelper helper) {
		MockPlayer mock = MockPlayers.join(helper, "Breaker");
		ServerPlayer player = mock.player();
		player.setGameMode(GameType.SURVIVAL);
		for (TerminalType type : TerminalTypes.all()) {
			BlockPos pos = place(helper, type, 0);
			ServerLevel level = helper.getLevel();
			BlockState state = level.getBlockState(pos);
			if (state.getDestroySpeed(level, pos) >= 0 || state.getDestroyProgress(player, level, pos) != 0F) {
				throw helper.assertionException("%s must be unbreakable in survival: speed %s, progress %s", type.id(),
						state.getDestroySpeed(level, pos), state.getDestroyProgress(player, level, pos));
			}
			if (state.getBlock().getExplosionResistance() < 3_600_000F) {
				throw helper.assertionException("%s must survive explosions, resistance %s", type.id(), state.getBlock().getExplosionResistance());
			}
		}
		helper.succeed();
	}

	@GameTest
	public void theWitherAndTheDragonCannotBreakTerminals(GameTestHelper helper) {
		for (TerminalType type : TerminalTypes.all()) {
			BlockState state = type.block().defaultBlockState();
			if (type.id().getNamespace().equals("deepcharter")
					&& (!state.is(TERMINALS) || !state.is(BlockTags.WITHER_IMMUNE) || !state.is(BlockTags.DRAGON_IMMUNE))) {
				throw helper.assertionException("%s must be in deepcharter:terminals, wither_immune and dragon_immune", type.id());
			}
		}
		helper.succeed();
	}

	@GameTest
	public void everyTerminalNeedsTwoOrThreePartsMadeOfIronCopperAndRedstone(GameTestHelper helper) {
		Set<String> used = new HashSet<>();
		Set<Item> allParts = new HashSet<>();
		for (TerminalType type : COLONY) {
			if (type.parts().size() < 2 || type.parts().size() > 3) {
				throw helper.assertionException("%s needs 2 or 3 parts, has %s", type.id(), type.parts().size());
			}
			for (Item part : type.parts()) {
				if (!allParts.add(part)) {
					throw helper.assertionException("%s is a part of two terminals", id(part));
				}
				used.addAll(recipeIngredients(helper, part));
			}
		}
		if (!used.equals(RECIPE_INGREDIENTS)) {
			throw helper.assertionException("the part recipes should use exactly %s, they use %s", RECIPE_INGREDIENTS, used);
		}
		helper.succeed();
	}

	/** The ingredient ids of the part's recipe file, which must make exactly one of the part and use only iron, copper and redstone. */
	private static Set<String> recipeIngredients(GameTestHelper helper, Item part) {
		String path = "/data/deepcharter/recipe/" + BuiltInRegistries.ITEM.getKey(part).getPath() + ".json";
		try (InputStream stream = TerminalFrameworkTest.class.getResourceAsStream(path)) {
			if (stream == null) {
				throw helper.assertionException("%s has no recipe at %s", id(part), path);
			}
			try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
				JsonObject recipe = JsonParser.parseReader(reader).getAsJsonObject();
				JsonObject result = recipe.getAsJsonObject("result");
				if (!result.get("id").getAsString().equals(id(part)) || result.get("count").getAsInt() != 1) {
					throw helper.assertionException("the recipe at %s should make one %s", path, id(part));
				}
				Set<String> ingredients = new HashSet<>();
				for (JsonElement ingredient : recipe.getAsJsonObject("key").asMap().values()) {
					ingredients.add(ingredient.getAsString());
				}
				if (!RECIPE_INGREDIENTS.containsAll(ingredients)) {
					throw helper.assertionException("the recipe for %s uses %s, only %s are allowed", id(part), ingredients, RECIPE_INGREDIENTS);
				}
				return ingredients;
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@GameTest
	public void theColonyTerminalsFormOneChain(GameTestHelper helper) {
		if (TerminalTypes.FUEL_PUMP.prerequisite().isPresent()) {
			throw helper.assertionException("the pump is first and has no prerequisite");
		}
		for (int i = 1; i < COLONY.size(); i++) {
			if (!COLONY.get(i).prerequisite().equals(Optional.of(COLONY.get(i - 1)))) {
				throw helper.assertionException("%s should need %s first", COLONY.get(i).id(), COLONY.get(i - 1).id());
			}
		}
		if (!TerminalTypes.all().subList(0, COLONY.size()).equals(COLONY)) {
			throw helper.assertionException("the colony terminals are registered first, in order: %s", TerminalTypes.all());
		}
		for (TerminalType type : TerminalTypes.all()) {
			if (TerminalTypes.get(type.id()).orElse(null) != type || TerminalTypes.of(type.block()).orElse(null) != type) {
				throw helper.assertionException("%s should be found by id and by block", type.id());
			}
		}
		helper.succeed();
	}

	@GameTest
	public void aBadRegistrationThrowsAndRegistersNothing(GameTestHelper helper) {
		Item aPart = TerminalTypes.FUEL_PUMP.parts().getFirst();
		Item free = Items.STICK;
		Identifier fresh = Identifier.fromNamespaceAndPath("deepcharter_test", "never_registered");
		int before = TerminalTypes.all().size();
		List<Runnable> bad = List.of(
				() -> TerminalTypes.register(TerminalTypes.FUEL_PUMP.id(), List.of(free)),
				() -> TerminalTypes.registerAlwaysOnline(TerminalTypes.FUEL_PUMP.id(), TerminalType.Access.ANYONE),
				() -> TerminalTypes.register(fresh, List.of()),
				() -> TerminalTypes.register(fresh, List.of(free, free)),
				() -> TerminalTypes.register(fresh, List.of(aPart)));
		for (int i = 0; i < bad.size(); i++) {
			boolean threw = false;
			try {
				bad.get(i).run();
			} catch (IllegalArgumentException expected) {
				threw = true;
			}
			if (!threw) {
				throw helper.assertionException("bad registration %s should throw IllegalArgumentException", i);
			}
		}
		if (TerminalTypes.get(fresh).isPresent() || TerminalTypes.all().size() != before) {
			throw helper.assertionException("a refused registration must leave nothing behind");
		}
		helper.succeed();
	}

	@GameTest
	public void anOversizedActionPayloadDoesNotDecode(GameTestHelper helper) {
		BlockPos pos = helper.absolutePos(BlockPos.ZERO);
		CompoundTag small = new CompoundTag();
		small.putString(Terminals.PART_KEY, "deepcharter:pump_motor");
		CompoundTag big = new CompoundTag();
		big.putString("junk", "x".repeat(TerminalActionPayload.MAX_ARGS_BYTES * 4));
		if (!decodes(helper, pos, small)) {
			throw helper.assertionException("a small args compound should decode");
		}
		if (decodes(helper, pos, big)) {
			throw helper.assertionException("args of more than %s bytes should not decode", TerminalActionPayload.MAX_ARGS_BYTES);
		}
		helper.succeed();
	}

	private static boolean decodes(GameTestHelper helper, BlockPos pos, CompoundTag args) {
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
		BlockPos.STREAM_CODEC.encode(buf, pos);
		Identifier.STREAM_CODEC.encode(buf, Terminals.INSERT_PART);
		ByteBufCodecs.COMPOUND_TAG.encode(buf, args);
		try {
			TerminalActionPayload payload = TerminalActionPayload.CODEC.decode(buf);
			return payload.args().equals(args);
		} catch (RuntimeException tooBig) {
			return false;
		}
	}

	@GameTest
	public void theRepairStateSurvivesARestart(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		Path dir = tempDir();
		try {
			try (SavedDataStorage first = storage(server, dir)) {
				RepairState state = first.computeIfAbsent(RepairState.TYPE);
				TerminalTypes.FUEL_PUMP.parts().forEach(part -> expectDone(helper, state.insert(TerminalTypes.FUEL_PUMP, part), "inserting"));
				expectDone(helper, state.insert(TerminalTypes.ORE_PROCESSOR, TerminalTypes.ORE_PROCESSOR.parts().getFirst()), "inserting");
				first.saveAndJoin();
			}
			try (SavedDataStorage second = storage(server, dir)) {
				RepairState loaded = second.computeIfAbsent(RepairState.TYPE);
				if (!loaded.repaired(TerminalTypes.FUEL_PUMP)
						|| loaded.repaired(TerminalTypes.ORE_PROCESSOR)
						|| !loaded.inserted(TerminalTypes.ORE_PROCESSOR).equals(List.of(TerminalTypes.ORE_PROCESSOR.parts().getFirst()))
						|| !loaded.inserted(TerminalTypes.UPGRADE_TERMINAL).isEmpty()) {
					throw helper.assertionException("the repairs and the part in the processor should load as saved");
				}
			}
		} finally {
			deleteTree(dir);
		}
		helper.succeed();
	}

	/** Guards the datafixer type of {@link RepairState#TYPE}, as {@code CharterCoreTest} does for the charters (ADR 0007). */
	@GameTest
	public void aFileFromAnOlderMinecraftLoadsUnchanged(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		int olderDataVersion = 4000;
		Path dir = tempDir();
		try {
			try (SavedDataStorage first = storage(server, dir)) {
				RepairState state = first.computeIfAbsent(RepairState.TYPE);
				TerminalTypes.FUEL_PUMP.parts().forEach(part -> expectDone(helper, state.insert(TerminalTypes.FUEL_PUMP, part), "inserting"));
				first.saveAndJoin();
			}
			Path file = savedFile(dir);
			CompoundTag stamped = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
			if (NbtUtils.getDataVersion(stamped) <= olderDataVersion) {
				throw helper.assertionException("the test needs a saved DataVersion above %s, got %s", olderDataVersion, NbtUtils.getDataVersion(stamped));
			}
			Tag savedBody = stamped.get("data");
			NbtIo.writeCompressed(NbtUtils.addDataVersion(stamped, olderDataVersion), file);

			try (SavedDataStorage second = storage(server, dir)) {
				RepairState loaded = second.computeIfAbsent(RepairState.TYPE);
				Tag reloaded = RepairState.CODEC.encodeStart(NbtOps.INSTANCE, loaded).getOrThrow();
				if (!reloaded.equals(savedBody) || !loaded.repaired(TerminalTypes.FUEL_PUMP)) {
					throw helper.assertionException("the fixer changed the repair state: saved %s, loaded %s", savedBody, reloaded);
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} finally {
			deleteTree(dir);
		}
		helper.succeed();
	}

	@GameTest
	public void dataOfAnotherVersionIsKeptAndFailsLoud(GameTestHelper helper) {
		CompoundTag future = new CompoundTag();
		future.putInt("version", RepairState.VERSION + 1);
		future.putString("shape", "from a newer build");

		RepairState data = RepairState.CODEC.parse(NbtOps.INSTANCE, future).getOrThrow();
		if (data.isReadable() || !data.unreadableVersion().equals(Optional.of(Integer.toString(RepairState.VERSION + 1)))) {
			throw helper.assertionException("data of another version is unreadable and names its version, got %s", data.unreadableVersion());
		}
		boolean threw = false;
		try {
			data.repaired(TerminalTypes.FUEL_PUMP);
		} catch (IllegalStateException e) {
			threw = e.getMessage().contains(Integer.toString(RepairState.VERSION + 1));
		}
		if (!threw) {
			throw helper.assertionException("using unreadable repair state should throw and name its version");
		}
		Tag written = RepairState.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
		if (!future.equals(written)) {
			throw helper.assertionException("unreadable data must be written back unchanged, got %s", written);
		}
		helper.succeed();
	}

	@GameTest
	public void aPodIsParkedWithinEightBlocksOfTheMiddleOfTheTerminalAndIsServedNearestFirst(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayer member = charterMember(helper, "Parker");
		MockPlayer other = charterMember(helper, "Stranger");
		Charter ours = Charters.charterOf(server, member.player().getUUID()).orElseThrow();
		BlockPos terminal = helper.absolutePos(new BlockPos(2, 1, 2));
		Vec3 centre = Vec3.atCenterOf(terminal);
		List<PodEntity> pods = new ArrayList<>();
		try {
			PodEntity far = pod(helper, centre.add(5, 0, 0), ours);
			PodEntity near = pod(helper, centre.add(0, 0, 3), ours);
			PodEntity edge = pod(helper, centre.add(0, 0, -7.9), ours);
			PodEntity outside = pod(helper, centre.add(-8.1, 0, 0), ours);
			PodEntity foreign = pod(helper, centre.add(1, 0, 0), Charters.charterOf(server, other.player().getUUID()).orElseThrow());
			pods.addAll(List.of(far, near, edge, outside, foreign));

			List<PodEntity> parked = Terminals.parkedPods(helper.getLevel(), terminal);
			if (!parked.equals(List.of(foreign, near, far, edge))) {
				throw helper.assertionException("pods parked at a terminal are those within 8 blocks, nearest first: expected the foreign pod, the near, the far and the edge pod, got %s",
						parked.stream().map(pod -> pod.position().subtract(centre)).toList());
			}
			List<PodEntity> ownedByTheMember = Terminals.parkedPods(helper.getLevel(), terminal, Optional.of(ours));
			if (!ownedByTheMember.equals(List.of(near, far, edge))) {
				throw helper.assertionException("a charter is served only the pods PodComponents.mayAccess allows it, nearest first, got %s",
						ownedByTheMember.stream().map(pod -> pod.position().subtract(centre)).toList());
			}
			if (!Terminals.parkedPods(helper.getLevel(), terminal, Optional.empty()).equals(List.of())) {
				throw helper.assertionException("a player on no charter may use no pod that a charter owns");
			}
			helper.succeed();
		} finally {
			pods.forEach(PodEntity::discard);
		}
	}

	@GameTest
	public void aTerminalViewCarriesItsTypesFeatureOnTheWire(GameTestHelper helper) {
		BlockPos pos = new BlockPos(1, 2, 3);
		TerminalView withFeature = new TerminalView(pos, TerminalTestTypes.OPEN.id(), true, true, List.of(), Optional.of(new TerminalTestTypes.Note("hello")));
		TerminalView decoded = roundTrip(withFeature);
		if (!decoded.equals(withFeature) || !decoded.feature(TerminalTestTypes.Note.class).equals(Optional.of(new TerminalTestTypes.Note("hello")))) {
			throw helper.assertionException("a view should keep its feature across the wire, got %s from %s", decoded, withFeature);
		}
		if (decoded.feature(TerminalFeature.class).isEmpty() || decoded.feature(TerminalTestTypes.Note.class).isEmpty()) {
			throw helper.assertionException("the feature should be readable by its class");
		}
		TerminalView without = new TerminalView(pos, TerminalTypes.FUEL_PUMP.id(), false, true, List.of(), Optional.empty());
		if (!roundTrip(without).equals(without)) {
			throw helper.assertionException("a view of a type with no feature should round-trip with none, got %s", roundTrip(without));
		}
		ByteBuf bytes = Unpooled.buffer();
		TerminalView.STREAM_CODEC.encode(bytes, without);
		TerminalView.STREAM_CODEC.encode(bytes, withFeature);
		if (!TerminalView.STREAM_CODEC.decode(bytes).equals(without) || !TerminalView.STREAM_CODEC.decode(bytes).equals(withFeature) || bytes.isReadable()) {
			throw helper.assertionException("a view of a type with no feature should leave the next view in the buffer readable");
		}
		boolean refused = false;
		try {
			roundTrip(new TerminalView(pos, TerminalTypes.FUEL_PUMP.id(), true, true, List.of(), Optional.of(new TerminalTestTypes.Note("x"))));
		} catch (IllegalStateException unregistered) {
			refused = true;
		}
		if (!refused) {
			throw helper.assertionException("a feature on a type that registered none must fail loudly, not be dropped");
		}
		helper.succeed();
	}

	private static TerminalView roundTrip(TerminalView view) {
		ByteBuf buffer = Unpooled.buffer();
		TerminalView.STREAM_CODEC.encode(buffer, view);
		TerminalView decoded = TerminalView.STREAM_CODEC.decode(buffer);
		if (buffer.isReadable()) {
			throw new IllegalStateException("the view left bytes unread");
		}
		return decoded;
	}

	private static PodEntity pod(GameTestHelper helper, Vec3 at, Charter owner) {
		ServerLevel level = helper.getLevel();
		PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
		pod.setPos(at);
		level.addFreshEntity(pod);
		PodComponents.register(pod, owner.id());
		return pod;
	}

	private static SavedDataStorage storage(MinecraftServer server, Path dir) {
		return new SavedDataStorage(dir, server.getFixerUpper(), server.registryAccess());
	}

	private static Path tempDir() {
		try {
			return Files.createTempDirectory("terminal-saved-data");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static Path savedFile(Path dir) throws IOException {
		try (Stream<Path> files = Files.walk(dir)) {
			return files.filter(path -> path.toString().endsWith(".dat")).findFirst().orElseThrow();
		}
	}

	private static void deleteTree(Path dir) {
		try (Stream<Path> files = Files.walk(dir)) {
			for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(path);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
