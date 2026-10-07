package io.github.pkeppeler.deepcharter.test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.mojang.serialization.DataResult;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.entity.EntityTypeTest;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.CharterRefusal;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.handbook.Directives;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapters;
import io.github.pkeppeler.deepcharter.handbook.HandbookItems;
import io.github.pkeppeler.deepcharter.handbook.HandbookProgress;
import io.github.pkeppeler.deepcharter.handbook.HandbookProgressData;
import io.github.pkeppeler.deepcharter.handbook.ReadMarks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;

/**
 * Server GameTests for #61: a vanilla trigger and a custom trigger each complete a directive for the whole charter, new crew
 * inherit progress, read marks belong to one player, the handbook item is bound, and the saved formats are versioned.
 *
 * <p>The directives used here are the ones of the sample chapter {@code data/deepcharter/deepcharter/handbook_chapter/sample.json}:
 * one is completed by the vanilla "slept in bed" trigger, the other by the custom {@code deepcharter:directive} trigger.
 */
public class HandbookCoreTest {
	private static final Identifier VANILLA_DIRECTIVE = directive("sample/sleep");
	private static final Identifier CUSTOM_DIRECTIVE = directive("sample/custom");
	private static final Identifier NOTE = directive("sample/note");
	private static final int POLL_BUDGET_TICKS = 200;
	private static final int HOTBAR_SIZE = 9;

	private static Identifier directive(String path) {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "handbook/" + path);
	}

	private static String uniqueName() {
		return "Handbook " + UUID.randomUUID().toString().substring(0, 8);
	}

	private static void expectDone(GameTestHelper helper, Optional<CharterRefusal> refusal, String what) {
		if (refusal.isPresent()) {
			throw helper.assertionException("%s should succeed, was refused: %s", what, refusal.get());
		}
	}

	/** A charter with {@code director} as Director and {@code crew} as its crew. */
	private static CharterId found(GameTestHelper helper, MinecraftServer server, ServerPlayer director, ServerPlayer... crew) {
		expectDone(helper, Charters.found(server, director.getUUID(), uniqueName()), "founding");
		Charter charter = Charters.charterOf(server, director.getUUID()).orElseThrow();
		for (ServerPlayer member : crew) {
			join(helper, server, charter.id(), director, member);
		}
		return charter.id();
	}

	private static void join(GameTestHelper helper, MinecraftServer server, CharterId id, ServerPlayer director, ServerPlayer member) {
		expectDone(helper, Charters.apply(server, member.getUUID(), id), "applying");
		expectDone(helper, Charters.approve(server, director.getUUID(), member.getUUID()), "approving");
	}

	private static void expectComplete(GameTestHelper helper, MinecraftServer server, ServerPlayer player, Identifier directive, boolean expected) {
		boolean actual = HandbookProgress.completedFor(server, player.getUUID()).contains(directive);
		if (actual != expected) {
			throw helper.assertionException("%s should %sbe complete for %s", directive, expected ? "" : "not ", player.getGameProfile().name());
		}
	}

	@GameTest(maxTicks = POLL_BUDGET_TICKS)
	public void aVanillaTriggerCompletesTheDirectiveForTheWholeCharter(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerPlayer director = MockPlayers.join(helper, "Director").player();
		ServerPlayer crew = MockPlayers.join(helper, "Crew").player();
		ServerPlayer outsider = MockPlayers.join(helper, "Outsider").player();
		found(helper, server, director, crew);
		found(helper, server, outsider);

		CriteriaTriggers.SLEPT_IN_BED.trigger(crew);

		helper.succeedWhen(() -> {
			expectComplete(helper, server, crew, VANILLA_DIRECTIVE, true);
			expectComplete(helper, server, director, VANILLA_DIRECTIVE, true);
			expectComplete(helper, server, outsider, VANILLA_DIRECTIVE, false);
		});
	}

	@GameTest
	public void aCustomTriggerCompletesTheDirectiveForTheWholeCharter(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerPlayer director = MockPlayers.join(helper, "Director").player();
		ServerPlayer crew = MockPlayers.join(helper, "Crew").player();
		ServerPlayer outsider = MockPlayers.join(helper, "Outsider").player();
		found(helper, server, director, crew);
		found(helper, server, outsider);

		Directives.fire(director, CUSTOM_DIRECTIVE);
		Directives.fire(director, CUSTOM_DIRECTIVE);

		expectComplete(helper, server, director, CUSTOM_DIRECTIVE, true);
		expectComplete(helper, server, crew, CUSTOM_DIRECTIVE, true);
		expectComplete(helper, server, outsider, CUSTOM_DIRECTIVE, false);
		expectComplete(helper, server, director, VANILLA_DIRECTIVE, false);
		helper.succeed();
	}

	@GameTest
	public void aDirectiveNobodyDefinedChangesNothing(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerPlayer director = MockPlayers.join(helper, "Director").player();
		found(helper, server, director);

		Directives.fire(director, directive("sample/no_such_directive"));

		if (!HandbookProgress.completedFor(server, director.getUUID()).isEmpty()) {
			throw helper.assertionException("an unknown directive must complete nothing");
		}
		helper.succeed();
	}

	@GameTest
	public void aPlayerOnNoCharterCompletesNothing(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerPlayer loner = MockPlayers.join(helper, "Loner").player();

		Directives.fire(loner, CUSTOM_DIRECTIVE);

		expectComplete(helper, server, loner, CUSTOM_DIRECTIVE, false);
		helper.succeed();
	}

	@GameTest
	public void newCrewInheritProgress(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerPlayer director = MockPlayers.join(helper, "Director").player();
		ServerPlayer newcomer = MockPlayers.join(helper, "Newcomer").player();
		CharterId id = found(helper, server, director);
		Directives.fire(director, CUSTOM_DIRECTIVE);
		expectComplete(helper, server, newcomer, CUSTOM_DIRECTIVE, false);

		join(helper, server, id, director, newcomer);

		expectComplete(helper, server, newcomer, CUSTOM_DIRECTIVE, true);

		expectDone(helper, Charters.leave(server, newcomer.getUUID()), "leaving");
		expectComplete(helper, server, newcomer, CUSTOM_DIRECTIVE, false);
		if (!HandbookProgress.completed(server, id).contains(CUSTOM_DIRECTIVE)) {
			throw helper.assertionException("the charter keeps its progress when someone leaves");
		}
		helper.succeed();
	}

	@GameTest
	public void progressSurvivesADormantCharter(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerPlayer director = MockPlayers.join(helper, "Director").player();
		CharterId id = found(helper, server, director);
		Directives.fire(director, CUSTOM_DIRECTIVE);

		expectDone(helper, Charters.leave(server, director.getUUID()), "the last person leaving");

		if (!HandbookProgress.completed(server, id).contains(CUSTOM_DIRECTIVE)) {
			throw helper.assertionException("a dormant charter keeps its progress");
		}
		helper.succeed();
	}

	@GameTest
	public void readMarksArePerPlayer(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerPlayer director = MockPlayers.join(helper, "Director").player();
		ServerPlayer crew = MockPlayers.join(helper, "Crew").player();
		found(helper, server, director, crew);

		ReadMarks.mark(director, NOTE);

		if (!ReadMarks.isRead(director, NOTE)) {
			throw helper.assertionException("the player who read it has it marked read");
		}
		if (ReadMarks.isRead(crew, NOTE)) {
			throw helper.assertionException("a crewmate of the same charter has not read it");
		}
		ReadMarks.unmark(director, NOTE);
		if (ReadMarks.isRead(director, NOTE)) {
			throw helper.assertionException("unmark clears the mark");
		}
		helper.succeed();
	}

	@GameTest
	public void everyChapterDirectiveHasAHiddenAdvancement(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		Set<Identifier> directives = HandbookChapters.directives(server);
		if (!directives.containsAll(List.of(VANILLA_DIRECTIVE, CUSTOM_DIRECTIVE))) {
			throw helper.assertionException("the sample chapter should define both sample directives, found %s", directives);
		}
		for (Identifier directive : directives) {
			var advancement = server.getAdvancements().get(directive);
			if (advancement == null) {
				throw helper.assertionException("directive %s has no advancement of the same id", directive);
			}
			if (advancement.value().display().isPresent()) {
				throw helper.assertionException("the advancement of %s must be hidden: no display", directive);
			}
		}
		helper.succeed();
	}

	@GameTest(maxTicks = POLL_BUDGET_TICKS)
	public void theHandbookIsIssuedOnJoin(GameTestHelper helper) {
		ServerPlayer player = MockPlayers.join(helper, "Newhire").player();

		helper.succeedWhen(() -> {
			if (handbooksHeld(player) != 1) {
				throw helper.assertionException("a joined player holds exactly one handbook, holds %s", handbooksHeld(player));
			}
		});
	}

	@GameTest(maxTicks = POLL_BUDGET_TICKS)
	public void theHandbookCannotBeDropped(GameTestHelper helper) {
		ServerPlayer player = MockPlayers.join(helper, "Dropper").player();
		ServerLevel level = helper.getLevel();
		boolean[] dropped = {false};
		helper.succeedWhen(() -> {
			if (!dropped[0]) {
				int slot = slotHolding(player);
				if (slot < 0 || slot >= HOTBAR_SIZE) {
					throw helper.assertionException("waiting for the handbook to be issued into the hotbar");
				}
				player.getInventory().setSelectedSlot(slot);
				player.drop(true);
				dropped[0] = true;
			}
			if (handbookItemsOnGround(level) != 0) {
				throw helper.assertionException("a dropped handbook must not stay in the world");
			}
			if (handbooksHeld(player) != 1) {
				throw helper.assertionException("the player keeps exactly one handbook after dropping it, holds %s", handbooksHeld(player));
			}
		});
	}

	@GameTest(maxTicks = POLL_BUDGET_TICKS)
	public void theHandbookCannotBePutInAContainer(GameTestHelper helper) {
		ServerPlayer player = MockPlayers.join(helper, "Packer").player();
		Container chest = new SimpleContainer(27);
		boolean[] tried = {false};
		helper.succeedWhen(() -> {
			if (!tried[0]) {
				if (handbooksHeld(player) != 1) {
					throw helper.assertionException("waiting for the handbook to be issued");
				}
				player.openMenu(new SimpleMenuProvider((id, inventory, ignored) -> ChestMenu.threeRows(id, inventory, chest), Component.literal("chest")));
				AbstractContainerMenu menu = player.containerMenu;
				for (Slot slot : menu.slots) {
					if (slot.container == player.getInventory() && HandbookItems.isHandbook(slot.getItem())) {
						menu.clicked(slot.index, 0, ContainerInput.QUICK_MOVE, player);
						break;
					}
				}
				tried[0] = true;
				throw helper.assertionException("tried to move the handbook into the chest; waiting to see where it ends up");
			}
			for (int index = 0; index < chest.getContainerSize(); index++) {
				if (HandbookItems.isHandbook(chest.getItem(index))) {
					throw helper.assertionException("the chest holds a handbook");
				}
			}
			if (handbooksHeld(player) != 1) {
				throw helper.assertionException("the player keeps exactly one handbook, holds %s", handbooksHeld(player));
			}
		});
	}

	@GameTest(maxTicks = POLL_BUDGET_TICKS)
	public void theHandbookSurvivesDeath(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel level = helper.getLevel();
		ServerPlayer player = MockPlayers.join(helper, "Mortal").player();
		UUID id = player.getUUID();
		boolean[] died = {false};
		helper.succeedWhen(() -> {
			if (!died[0]) {
				if (handbooksHeld(player) != 1) {
					throw helper.assertionException("waiting for the handbook to be issued");
				}
				player.kill(level);
				server.getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
				died[0] = true;
			}
			ServerPlayer reborn = server.getPlayerList().getPlayer(id);
			if (reborn == null || reborn == player) {
				throw helper.assertionException("the player should have respawned");
			}
			if (handbookItemsOnGround(level) != 0) {
				throw helper.assertionException("the handbook must not drop on death");
			}
			if (handbooksHeld(reborn) != 1) {
				throw helper.assertionException("the respawned player holds exactly one handbook, holds %s", handbooksHeld(reborn));
			}
			// The mock only knows the dead body, so it cannot remove the respawned player itself.
			server.getPlayerList().remove(reborn);
		});
	}

	@GameTest(maxTicks = POLL_BUDGET_TICKS)
	public void aMissingHandbookIsReissued(GameTestHelper helper) {
		ServerPlayer player = MockPlayers.join(helper, "Forgetful").player();
		boolean[] removed = {false};
		helper.succeedWhen(() -> {
			if (!removed[0]) {
				if (handbooksHeld(player) != 1) {
					throw helper.assertionException("waiting for the handbook to be issued");
				}
				player.getInventory().setItem(slotHolding(player), ItemStack.EMPTY);
				removed[0] = true;
				throw helper.assertionException("removed the handbook; waiting for it to come back");
			}
			if (handbooksHeld(player) != 1) {
				throw helper.assertionException("the handbook should be re-issued, holds %s", handbooksHeld(player));
			}
		});
	}

	@GameTest
	public void everyPersistedFormatIsVersionedAndKeepsAnUnknownVersion(GameTestHelper helper) {
		CompoundTag future = new CompoundTag();
		future.putInt("version", 99);
		future.putString("shape", "from a later build");

		DataResult<HandbookProgressData> progress = HandbookProgressData.CODEC.parse(NbtOps.INSTANCE, future);
		HandbookProgressData loadedProgress = progress.getOrThrow();
		Tag savedProgress = HandbookProgressData.CODEC.encodeStart(NbtOps.INSTANCE, loadedProgress).getOrThrow();
		if (!future.equals(savedProgress)) {
			throw helper.assertionException("progress of an unknown version must be written back unchanged, got %s", savedProgress);
		}
		expectUseThrows(helper, () -> loadedProgress.completed(CharterId.random()), "progress of an unknown version");

		Versioned<ReadMarks> marks = ReadMarks.CODEC.parse(NbtOps.INSTANCE, future).getOrThrow();
		if (!(marks instanceof Versioned.Unreadable<ReadMarks>)) {
			throw helper.assertionException("read marks of an unknown version must decode as Unreadable, got %s", marks);
		}
		Tag savedMarks = ReadMarks.CODEC.encodeStart(NbtOps.INSTANCE, marks).getOrThrow();
		if (!future.equals(savedMarks)) {
			throw helper.assertionException("read marks of an unknown version must be written back unchanged, got %s", savedMarks);
		}

		Tag current = ReadMarks.CODEC.encodeStart(NbtOps.INSTANCE, Versioned.of(new ReadMarks(Set.of(NOTE)))).getOrThrow();
		if (!(current instanceof CompoundTag compound) || compound.getIntOr("version", -1) != ReadMarks.VERSION) {
			throw helper.assertionException("current read marks carry version %s, got %s", ReadMarks.VERSION, current);
		}
		Versioned<ReadMarks> reloaded = ReadMarks.CODEC.parse(NbtOps.INSTANCE, current).getOrThrow();
		if (!reloaded.equals(Versioned.of(new ReadMarks(Set.of(NOTE))))) {
			throw helper.assertionException("current read marks must load as they were saved, got %s", reloaded);
		}
		helper.succeed();
	}

	private static void expectUseThrows(GameTestHelper helper, Runnable use, String what) {
		try {
			use.run();
		} catch (IllegalStateException expected) {
			return;
		}
		throw helper.assertionException("using %s must fail loud", what);
	}

	private static int handbooksHeld(ServerPlayer player) {
		int held = HandbookItems.isHandbook(player.containerMenu.getCarried()) ? 1 : 0;
		for (int index = 0; index < player.getInventory().getContainerSize(); index++) {
			if (HandbookItems.isHandbook(player.getInventory().getItem(index))) {
				held++;
			}
		}
		return held;
	}

	private static int slotHolding(ServerPlayer player) {
		for (int index = 0; index < player.getInventory().getContainerSize(); index++) {
			if (HandbookItems.isHandbook(player.getInventory().getItem(index))) {
				return index;
			}
		}
		return -1;
	}

	private static int handbookItemsOnGround(ServerLevel level) {
		return (int) level.getEntities(EntityTypeTest.forClass(ItemEntity.class), entity -> HandbookItems.isHandbook(entity.getItem())).size();
	}
}
