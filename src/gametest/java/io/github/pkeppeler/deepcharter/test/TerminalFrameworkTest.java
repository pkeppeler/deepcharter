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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalActions;
import io.github.pkeppeler.deepcharter.terminal.TerminalBlockEntity;
import io.github.pkeppeler.deepcharter.terminal.TerminalEvents;
import io.github.pkeppeler.deepcharter.terminal.TerminalRefusal;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;

/**
 * Server GameTests for #59: the repair order, a repair seen by a second charter, validated actions, saved state, and the rest
 * of the terminal rules (parts, recipes, unbreakable blocks, registration).
 *
 * <p>The repair state is one per world, so these tests share it. Each one does all its work inside its first tick and
 * resets the state before and after, so no two of them overlap.
 */
public class TerminalFrameworkTest {
	private static final Identifier PING = Identifier.fromNamespaceAndPath("deepcharter_test", "ping");
	private static final Identifier DENY = Identifier.fromNamespaceAndPath("deepcharter_test", "deny");
	private static final Identifier UNKNOWN = Identifier.fromNamespaceAndPath("deepcharter_test", "unknown");
	private static final Set<String> RECIPE_INGREDIENTS = Set.of("minecraft:iron_ingot", "minecraft:copper_ingot", "minecraft:redstone");

	private static final List<Identifier> REPAIRED_LOG = new ArrayList<>();
	private static int pings;
	private static Optional<Charter> lastPinger = Optional.empty();

	static {
		for (TerminalType type : List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR)) {
			TerminalActions.register(type, PING, context -> {
				pings++;
				lastPinger = Optional.of(context.charter());
				return Optional.empty();
			});
		}
		TerminalActions.register(TerminalTypes.FUEL_PUMP, DENY, context -> Optional.of(Component.literal("denied for the test")));
		TerminalEvents.REPAIRED.register((server, type, charter, player) -> REPAIRED_LOG.add(type.id()));
	}

	private static BlockPos place(GameTestHelper helper, TerminalType type, int x) {
		BlockPos relative = new BlockPos(x, 1, 0);
		helper.setBlock(relative, type.block().defaultBlockState());
		return helper.absolutePos(relative);
	}

	/** A mock player, in survival, founding a charter of their own. */
	private static MockPlayer charterMember(GameTestHelper helper, String name) {
		MockPlayer mock = MockPlayers.join(helper, name);
		mock.player().setGameMode(GameType.SURVIVAL);
		MinecraftServer server = helper.getLevel().getServer();
		if (Charters.found(server, mock.player().getUUID(), name + " " + UUID.randomUUID().toString().substring(0, 8)).isPresent()) {
			throw helper.assertionException("founding a charter for %s should succeed", name);
		}
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
		TerminalTypes.all().forEach(type -> type.parts().forEach(part -> give(player, part, 1)));
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

	/** Repairs {@code type} for the world straight in the saved data, leaving no events. */
	private static void repairDirectly(GameTestHelper helper, TerminalType type) {
		RepairState state = RepairState.get(helper.getLevel().getServer());
		type.parts().forEach(part -> expectDone(helper, state.insert(type, part), "inserting " + part));
	}

	private static String id(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}

	@GameTest
	public void theRepairOrderHolds(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		RepairState state = RepairState.get(server);
		state.reset();
		REPAIRED_LOG.clear();
		try {
			MockPlayer mock = charterMember(helper, "Repairer");
			ServerPlayer player = mock.player();
			giveAllParts(player);
			int x = 0;
			List<BlockPos> positions = new ArrayList<>();
			for (TerminalType type : TerminalTypes.all()) {
				positions.add(place(helper, type, x++));
			}
			stand(helper, mock, positions.getFirst(), 2);

			for (int step = 1; step < TerminalTypes.all().size(); step++) {
				TerminalType early = TerminalTypes.all().get(step);
				Item part = early.parts().getFirst();
				int held = count(player, part);
				expectRefused(helper, TerminalRefusal.PREREQUISITE_UNREPAIRED, Terminals.insertPart(player, positions.get(step), part),
						"a part of " + early.id() + " before its predecessor is repaired");
				if (!state.inserted(early).isEmpty() || count(player, part) != held) {
					throw helper.assertionException("a refused insert must keep the part and change no state for %s", early.id());
				}
			}

			for (int step = 0; step < TerminalTypes.all().size(); step++) {
				TerminalType type = TerminalTypes.all().get(step);
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
				if (step + 2 < TerminalTypes.all().size()) {
					TerminalType skipped = TerminalTypes.all().get(step + 2);
					expectRefused(helper, TerminalRefusal.PREREQUISITE_UNREPAIRED,
							Terminals.insertPart(player, positions.get(step + 2), skipped.parts().getFirst()),
							"a part of " + skipped.id() + " while " + TerminalTypes.all().get(step + 1).id() + " is not repaired");
				}
			}
			if (REPAIRED_LOG.size() != 4) {
				throw helper.assertionException("REPAIRED fires once per terminal, log is %s", REPAIRED_LOG);
			}
			expectRefused(helper, TerminalRefusal.ALREADY_REPAIRED, Terminals.insertPart(player, positions.getFirst(), TerminalTypes.FUEL_PUMP.parts().getFirst()),
					"a part into a repaired terminal");
		} finally {
			state.reset();
		}
		helper.succeed();
	}

	@GameTest
	public void onlyTheRightPartsGoInOnce(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		RepairState state = RepairState.get(server);
		state.reset();
		try {
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
		} finally {
			state.reset();
		}
		helper.succeed();
	}

	@GameTest
	public void aRepairIsVisibleToASecondCharter(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		RepairState state = RepairState.get(server);
		state.reset();
		try {
			MockPlayer first = charterMember(helper, "FirstCharter");
			MockPlayer second = charterMember(helper, "SecondCharter");
			Charter firstCharter = Charters.charterOf(server, first.player().getUUID()).orElseThrow();
			Charter secondCharter = Charters.charterOf(server, second.player().getUUID()).orElseThrow();
			if (firstCharter.id().equals(secondCharter.id())) {
				throw helper.assertionException("the test needs two charters");
			}
			BlockPos pump = place(helper, TerminalTypes.FUEL_PUMP, 0);
			stand(helper, first, pump, 2);
			stand(helper, second, pump, 3);
			giveAllParts(first.player());

			int before = pings;
			expectRefused(helper, TerminalRefusal.UNREPAIRED, Terminals.act(second.player(), pump, PING, new CompoundTag()),
					"the second charter's action before any repair");

			TerminalTypes.FUEL_PUMP.parts().forEach(part -> expectDone(helper, Terminals.insertPart(first.player(), pump, part), "the first charter inserting"));

			expectDone(helper, Terminals.act(second.player(), pump, PING, new CompoundTag()), "the second charter's action after the first repaired");
			if (pings != before + 1 || lastPinger.map(Charter::id).filter(secondCharter.id()::equals).isEmpty()) {
				throw helper.assertionException("the action should run once, for the second charter: pings %s -> %s, charter %s", before, pings, lastPinger);
			}
			expectRefused(helper, TerminalRefusal.UNREPAIRED, Terminals.act(second.player(), place(helper, TerminalTypes.ORE_PROCESSOR, 1), PING, new CompoundTag()),
					"an action at a terminal nobody has repaired");
		} finally {
			state.reset();
		}
		helper.succeed();
	}

	@GameTest
	public void actionsAreValidated(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		RepairState state = RepairState.get(server);
		state.reset();
		try {
			MockPlayer member = charterMember(helper, "Member");
			MockPlayer outsider = MockPlayers.join(helper, "Outsider");
			outsider.player().setGameMode(GameType.SURVIVAL);
			BlockPos pump = place(helper, TerminalTypes.FUEL_PUMP, 0);
			BlockPos processor = place(helper, TerminalTypes.ORE_PROCESSOR, 1);
			repairDirectly(helper, TerminalTypes.FUEL_PUMP);
			CompoundTag none = new CompoundTag();
			int before = pings;

			stand(helper, member, pump, 5.5);
			expectDone(helper, Terminals.act(member.player(), pump, PING, none), "an action from inside the range");
			expectDone(helper, Terminals.open(member.player(), pump), "opening from inside the range");
			if (pings != before + 1) {
				throw helper.assertionException("the action should have run once");
			}

			stand(helper, member, pump, 6.5);
			expectRefused(helper, TerminalRefusal.TOO_FAR, Terminals.act(member.player(), pump, PING, none), "an action from too far");
			expectRefused(helper, TerminalRefusal.TOO_FAR, Terminals.open(member.player(), pump), "opening from too far");

			stand(helper, outsider, pump, 2);
			expectRefused(helper, TerminalRefusal.NOT_ON_A_CHARTER, Terminals.act(outsider.player(), pump, PING, none), "an action from a player on no charter");
			expectRefused(helper, TerminalRefusal.NOT_ON_A_CHARTER, Terminals.open(outsider.player(), pump), "opening by a player on no charter");

			stand(helper, member, processor, 2);
			expectRefused(helper, TerminalRefusal.UNREPAIRED, Terminals.act(member.player(), processor, PING, none), "an action at an unrepaired terminal");

			stand(helper, member, pump, 2);
			expectRefused(helper, TerminalRefusal.NO_SUCH_ACTION, Terminals.act(member.player(), pump, UNKNOWN, none), "an action nobody registered");
			expectRefused(helper, TerminalRefusal.ACTION_REFUSED, Terminals.act(member.player(), pump, DENY, none), "an action whose handler refuses");
			expectRefused(helper, TerminalRefusal.NO_SUCH_TERMINAL, Terminals.act(member.player(), pump.above(3), PING, none), "an action at a position with no terminal");
			expectRefused(helper, TerminalRefusal.NO_SUCH_TERMINAL, Terminals.open(member.player(), pump.above(3)), "opening a position with no terminal");

			if (pings != before + 1) {
				throw helper.assertionException("no refused action may have run its handler, pings went from %s to %s", before, pings);
			}
		} finally {
			state.reset();
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
	public void everyTerminalNeedsTwoOrThreePartsMadeOfIronCopperAndRedstone(GameTestHelper helper) {
		Set<String> used = new HashSet<>();
		Set<Item> allParts = new HashSet<>();
		for (TerminalType type : TerminalTypes.all()) {
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
		List<TerminalType> order = List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR, TerminalTypes.UPGRADE_TERMINAL, TerminalTypes.REPAIR_STATION);
		if (!TerminalTypes.all().equals(order)) {
			throw helper.assertionException("the chain is pump, processor, upgrade, repair: %s", TerminalTypes.all());
		}
		if (TerminalTypes.FUEL_PUMP.prerequisite().isPresent()) {
			throw helper.assertionException("the pump is first and has no prerequisite");
		}
		for (int i = 1; i < order.size(); i++) {
			if (!order.get(i).prerequisite().equals(Optional.of(order.get(i - 1)))) {
				throw helper.assertionException("%s should need %s first", order.get(i).id(), order.get(i - 1).id());
			}
		}
		for (TerminalType type : order) {
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
		List<Runnable> bad = List.of(
				() -> TerminalTypes.register(TerminalTypes.FUEL_PUMP.id(), List.of(free)),
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
		if (TerminalTypes.get(fresh).isPresent() || TerminalTypes.all().size() != 4) {
			throw helper.assertionException("a refused registration must leave nothing behind");
		}
		helper.succeed();
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
