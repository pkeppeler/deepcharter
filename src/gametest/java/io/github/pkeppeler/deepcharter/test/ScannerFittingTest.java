package io.github.pkeppeler.deepcharter.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.scanner.ScanSlice;
import io.github.pkeppeler.deepcharter.scanner.ScannerRegistry;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.PartLabel;

/**
 * Server GameTests for #483: the tier 1 scanner is a unit the player crafts from the bootstrap's metal and fits by using it on the pod of
 * their own charter. It is stamped for the charter like a bought part, and a refused use changes nothing. The terminal's side of the rule
 * (tier 1 is not sold, tier 2 needs it) is in {@link UpgradeTerminalTest}, and the cost of the recipe in {@link ScannerCostTest}.
 */
public class ScannerFittingTest {
	private static final AtomicInteger PLAYERS = new AtomicInteger();

	/** The recipe's grid, three rows of three: I iron, C copper, R redstone. */
	private static final String[] GRID = {"RCR", "ICI", "I I"};

	/** A player who may be on a charter, with the pods and the unit they hold. */
	private record Scene(MockPlayer mock, ServerPlayer player, List<PodEntity> pods) {
		void clean() {
			pods.forEach(Entity::discard);
			mock.leave();
		}
	}

	private static Scene scene(GameTestHelper helper, boolean onCharter) {
		MockPlayer mock = MockPlayers.join(helper, "Fitter" + PLAYERS.incrementAndGet());
		ServerPlayer player = mock.player();
		player.setGameMode(GameType.SURVIVAL);
		if (onCharter && Charters.found(helper.getLevel().getServer(), player.getUUID(), "Fit " + UUID.randomUUID().toString().substring(0, 8)).isPresent()) {
			throw helper.assertionException("founding a charter should succeed");
		}
		return new Scene(mock, player, new ArrayList<>());
	}

	private static CharterId charterOf(GameTestHelper helper, ServerPlayer player) {
		return Charters.charterOfOrThrow(helper.getLevel().getServer(), player.getUUID()).orElseThrow().id();
	}

	private static PodEntity pod(GameTestHelper helper, Scene scene, Optional<CharterId> owner) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 1, 2);
		scene.pods().add(pod);
		owner.ifPresent(charter -> PodComponents.register(pod, charter));
		return pod;
	}

	private static CharterId otherCharter(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		UUID founder = UUID.randomUUID();
		if (Charters.found(server, founder, "Other " + UUID.randomUUID().toString().substring(0, 8)).isPresent()) {
			throw helper.assertionException("founding the other charter should succeed");
		}
		return Charters.charterOfOrThrow(server, founder).orElseThrow().id();
	}

	/** Holds one unit in the main hand and uses it on the pod through the game's own entity-use event, as a click does. */
	private static InteractionResult useUnit(Scene scene, PodEntity pod) {
		scene.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ScannerRegistry.SCANNER_UNIT));
		return UseEntityCallback.EVENT.invoker().interact(scene.player(), pod.level(), InteractionHand.MAIN_HAND, pod, null);
	}

	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(format, args);
	}

	/** The recipe is the real one, in the real recipe manager, and asks for the bootstrap's iron, copper and redstone and nothing else. */
	@GameTest
	public void theUnitIsCraftedFromIronCopperAndRedstoneAlone(GameTestHelper helper) {
		List<ItemStack> grid = new ArrayList<>();
		for (String row : GRID) {
			for (char cell : row.toCharArray()) {
				grid.add(cell == 'I' ? new ItemStack(Items.IRON_INGOT) : cell == 'C' ? new ItemStack(Items.COPPER_INGOT)
						: cell == 'R' ? new ItemStack(Items.REDSTONE) : ItemStack.EMPTY);
			}
		}
		CraftingInput input = CraftingInput.of(3, 3, grid);
		ItemStack made = helper.getLevel().recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, helper.getLevel())
				.map(holder -> holder.value().assemble(input)).orElseThrow(() -> failure(helper, "no crafting recipe takes the scanner unit's grid"));
		if (made.getItem() != ScannerRegistry.SCANNER_UNIT || made.getCount() != 1) {
			throw failure(helper, "the grid should make one scanner unit, made %s", made);
		}
		List<ItemStack> lacking = new ArrayList<>(grid);
		lacking.set(0, ItemStack.EMPTY);
		CraftingInput short1 = CraftingInput.of(3, 3, lacking);
		if (helper.getLevel().recipeAccess().getRecipeFor(RecipeType.CRAFTING, short1, helper.getLevel()).isPresent()) {
			throw failure(helper, "a grid with a redstone missing should make nothing");
		}
		helper.succeed();
	}

	@GameTest
	public void usingAUnitOnYourOwnPodFitsATierOneScannerStampedForTheCharter(GameTestHelper helper) {
		Scene scene = scene(helper, true);
		try {
			CharterId charter = charterOf(helper, scene.player());
			PodEntity pod = pod(helper, scene, Optional.of(charter));
			if (ScanSlice.scan(helper.getLevel(), pod).isPresent()) {
				throw failure(helper, "a Mole shows no scanner until one is fitted");
			}
			InteractionResult result = useUnit(scene, pod);
			if (!result.consumesAction()) {
				throw failure(helper, "using the unit on the pod should be consumed, got %s", result);
			}
			PartLabel part = PodComponents.partOf(pod, ComponentTrack.SCANNER).orElseThrow(() -> failure(helper, "the pod should hold a scanner now"));
			if (part.tier() != 1 || !part.charter().equals(charter) || !part.serial().startsWith("SCANNER")) {
				throw failure(helper, "the scanner should be a tier 1 part of the charter with a scanner serial, got %s", part);
			}
			if (PodComponents.effectiveTier(pod, ComponentTrack.SCANNER) != 1 || ScanSlice.scan(helper.getLevel(), pod).isEmpty()) {
				throw failure(helper, "the fitted scanner should work and scan");
			}
			if (!scene.player().getMainHandItem().isEmpty()) {
				throw failure(helper, "the unit should be used up, the hand holds %s", scene.player().getMainHandItem());
			}
			helper.succeed();
		} finally {
			scene.clean();
		}
	}

	@GameTest
	public void aCreativePlayerKeepsTheUnit(GameTestHelper helper) {
		Scene scene = scene(helper, true);
		try {
			PodEntity pod = pod(helper, scene, Optional.of(charterOf(helper, scene.player())));
			scene.player().setGameMode(GameType.CREATIVE);
			useUnit(scene, pod);
			if (PodComponents.effectiveTier(pod, ComponentTrack.SCANNER) != 1 || !scene.player().getMainHandItem().is(ScannerRegistry.SCANNER_UNIT)) {
				throw failure(helper, "a creative player fits the scanner and keeps the unit, hand holds %s", scene.player().getMainHandItem());
			}
			helper.succeed();
		} finally {
			scene.clean();
		}
	}

	/** Each refusal leaves the pod without a new part and the unit in the hand. */
	@GameTest
	public void aRefusedUseChangesNothingAndKeepsTheUnit(GameTestHelper helper) {
		Scene scene = scene(helper, true);
		Scene loner = scene(helper, false);
		try {
			CharterId charter = charterOf(helper, scene.player());
			PodEntity unregistered = pod(helper, scene, Optional.empty());
			PodEntity foreign = pod(helper, scene, Optional.of(otherCharter(helper)));
			expectKept(helper, scene, unregistered, "a pod nobody owns");
			expectKept(helper, scene, foreign, "a pod of another charter");
			PodEntity ours = pod(helper, loner, Optional.of(charter));
			expectKept(helper, loner, ours, "a player on no charter");

			PodEntity fitted = pod(helper, scene, Optional.of(charter));
			PodComponents.install(fitted, ComponentItems.mint(helper.getLevel().getServer(), ComponentTrack.SCANNER, 2, charter));
			PartLabel before = PodComponents.partOf(fitted, ComponentTrack.SCANNER).orElseThrow();
			expectKept(helper, scene, fitted, "a pod that has a scanner");
			if (!PodComponents.partOf(fitted, ComponentTrack.SCANNER).equals(Optional.of(before))) {
				throw failure(helper, "a tier 2 scanner must not be replaced by the tier 1 unit");
			}
			helper.succeed();
		} finally {
			scene.clean();
			loner.clean();
		}
	}

	private static void expectKept(GameTestHelper helper, Scene scene, PodEntity pod, String what) {
		Optional<PartLabel> before = PodComponents.partOf(pod, ComponentTrack.SCANNER);
		InteractionResult result = useUnit(scene, pod);
		if (result != InteractionResult.FAIL) {
			throw failure(helper, "%s should refuse the unit, got %s", what, result);
		}
		if (!scene.player().getMainHandItem().is(ScannerRegistry.SCANNER_UNIT)) {
			throw failure(helper, "%s: the refused unit should stay in the hand", what);
		}
		if (!PodComponents.partOf(pod, ComponentTrack.SCANNER).equals(before)) {
			throw failure(helper, "%s: the pod's scanner changed to %s", what, PodComponents.partOf(pod, ComponentTrack.SCANNER));
		}
	}

	/** A scanner the fit replaces (a void one of another charter, higher than tier 1) drops as an item, as a purchase's replaced part does. */
	@GameTest
	public void aUnitFittedOverAForeignScannerDropsTheOldPart(GameTestHelper helper) {
		Scene scene = scene(helper, true);
		try {
			CharterId charter = charterOf(helper, scene.player());
			PodEntity pod = pod(helper, scene, Optional.of(charter));
			PodComponents.install(pod, ComponentItems.mint(helper.getLevel().getServer(), ComponentTrack.SCANNER, 3, otherCharter(helper)));
			PartLabel foreign = PodComponents.partOf(pod, ComponentTrack.SCANNER).orElseThrow();
			if (!useUnit(scene, pod).consumesAction()) {
				throw failure(helper, "a void scanner does not stop a fit");
			}
			PartLabel now = PodComponents.partOf(pod, ComponentTrack.SCANNER).orElseThrow();
			if (now.tier() != 1 || !now.charter().equals(charter)) {
				throw failure(helper, "the pod should hold the new tier 1 part, got %s", now);
			}
			// entity-query: the pod stands in the test's own box and its drop falls beside it
			List<PartLabel> dropped = helper.getLevel().getEntitiesOfClass(ItemEntity.class, pod.getBoundingBox().inflate(4)).stream()
					.flatMap(item -> ComponentItems.labelOf(item.getItem()).stream()).toList();
			if (!dropped.equals(List.of(foreign))) {
				throw failure(helper, "the replaced foreign scanner should drop as it was, got %s", dropped);
			}
			helper.getLevel().getEntitiesOfClass(ItemEntity.class, pod.getBoundingBox().inflate(4)).forEach(ItemEntity::discard);
			helper.succeed();
		} finally {
			scene.clean();
		}
	}

	/** A world's inventories and saved handbook progress name these ids, so a rename is a decision (ADR 0048): each is pinned by its literal. */
	@GameTest
	public void theIdsThatPacksAndProgressNameArePinned(GameTestHelper helper) {
		Identifier unit = BuiltInRegistries.ITEM.getKey(ScannerRegistry.SCANNER_UNIT);
		if (!unit.toString().equals("deepcharter:scanner_unit")) {
			throw failure(helper, "the scanner unit's id is permanent, got %s", unit);
		}
		Identifier directive = Identifier.parse("deepcharter:handbook/welcome/craft_scanner");
		if (helper.getLevel().getServer().getAdvancements().get(directive) == null) {
			throw failure(helper, "the craft_scanner directive needs its advancement %s", directive);
		}
		helper.succeed();
	}

	/** Anything else held on a pod is left to the other features (fuel, towing); only the unit fits a scanner. */
	@GameTest
	public void onlyTheUnitFitsAScanner(GameTestHelper helper) {
		Scene scene = scene(helper, true);
		try {
			PodEntity pod = pod(helper, scene, Optional.of(charterOf(helper, scene.player())));
			scene.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_INGOT));
			UseEntityCallback.EVENT.invoker().interact(scene.player(), pod.level(), InteractionHand.MAIN_HAND, pod, null);
			Item held = scene.player().getMainHandItem().getItem();
			if (held != Items.IRON_INGOT || PodComponents.partOf(pod, ComponentTrack.SCANNER).isPresent()) {
				throw failure(helper, "an iron ingot fits nothing, hand holds %s", held);
			}
			helper.succeed();
		} finally {
			scene.clean();
		}
	}
}
