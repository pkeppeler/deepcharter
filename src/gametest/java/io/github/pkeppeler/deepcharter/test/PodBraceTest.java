package io.github.pkeppeler.deepcharter.test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.GameType;

import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodBrace;
import io.github.pkeppeler.deepcharter.pod.PodBraceTuning;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.OddPods;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTuning;

/**
 * Server GameTests for the crust warning and the breach brace (#378). Layer 1 is crust at y 0 to 2 (3 rows of 8 hull each), so a pod with its
 * feet at y 3 stands on top of the crust, and a pod at y 10 is 7 slabs above it. The pods here are not added to the world: the tests read
 * the warning and the brace from their state and run the brace's tick by hand.
 */
public class PodBraceTest {
	private static final AtomicInteger OWNERS = new AtomicInteger();
	private static final float ROW = 8f;
	private static final float FULL_CRUST = 24f;
	private static final int PATCH = PodBraceTuning.DEFAULT.patchTicks();
	/** Slabs the pilot bores, each one a fall into a new hole. */
	private static final int SLABS = 4;
	private static final Input SPRINT = new Input(false, false, false, false, false, false, true);

	private static ServerLevel layer(GameTestHelper helper, int layer) {
		ServerLevel level = helper.getLevel().getServer().getLevel(LayerChain.dimension(layer));
		if (level == null) {
			throw failure(helper, "dimension %s did not load", LayerChain.dimension(layer));
		}
		return level;
	}

	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}

	private static MockPlayer owner(GameTestHelper helper) {
		MockPlayer owner = MockPlayers.join(helper, "Brace owner " + OWNERS.incrementAndGet());
		owner.player().setGameMode(GameType.SURVIVAL);
		return owner;
	}

	/** A Mole with its feet at {@code y}, {@code hull} hull and a brace if {@code braced}; not in the world, so it never ticks. */
	private static PodEntity pod(GameTestHelper helper, ServerLevel level, int y, float hull, boolean braced) {
		PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
		pod.setPos(100, y, 3800);
		if (braced) {
			ScannerPods.fit(helper.getLevel().getServer(), owner(helper).player(), pod, ComponentTrack.BRACE, 1);
		}
		pod.setHull(hull);
		pod.setOnGround(true);
		return pod;
	}

	private static void ore(GameTestHelper helper, PodEntity pod, OreType... ores) {
		for (OreType ore : ores) {
			if (!pod.cargo().tryAdd(pod, OreRegistry.stack(ore))) {
				throw failure(helper, "the bay had no room for %s", ore);
			}
		}
		// The brace reads the bay on its tick and the HUD reads what it found.
		tick(pod, 1);
	}

	private static void expectNone(GameTestHelper helper, PodEntity pod, String when) {
		Optional<PodBrace.Warning> warning = PodBrace.warning(pod);
		if (warning.isPresent()) {
			throw failure(helper, "no crust warning %s, got %s", when, warning.get());
		}
	}

	private static void expectWarning(GameTestHelper helper, PodEntity pod, int slabsAway, int crustSlabs, float cost, String when) {
		PodBrace.Warning warning = PodBrace.warning(pod).orElseThrow(() -> failure(helper, "a crust warning %s", when));
		if (warning.slabsAway() != slabsAway || warning.crustSlabs() != crustSlabs || Math.abs(warning.cost() - cost) > 0.01f) {
			throw failure(helper, "the warning %s should be %d away, %d crust slabs, cost %s; it is %s", when, slabsAway, crustSlabs, cost, warning);
		}
	}

	private static void expectPatching(GameTestHelper helper, PodEntity pod, PodBrace.Patching expected, String when) {
		PodBrace.Patching now = PodBrace.patching(pod, true);
		if (now != expected) {
			throw failure(helper, "the brace should be %s %s, it is %s", expected, when, now);
		}
	}

	private static void tick(PodEntity pod, int tickCount) {
		pod.tickCount = tickCount;
		PodEvents.AFTER_TICK.invoker().afterTick(pod);
	}

	// ---- the part ----

	@GameTest
	public void theBraceLeavesTheDrillAndTheCrustAsTheyAre(GameTestHelper helper) {
		ServerLevel one = layer(helper, 1);
		PodStats stock = PodStats.of(pod(helper, one, 10, 100f, false));
		PodStats braced = PodStats.of(pod(helper, one, 10, 100f, true));
		if (stock.crustHullDamage() != ROW || braced.crustHullDamage() != ROW) {
			throw failure(helper, "the crust takes %s a row from any pod, it takes %s and %s", ROW, stock.crustHullDamage(), braced.crustHullDamage());
		}
		if (stock.ticksPerHardness() != braced.ticksPerHardness()) {
			throw failure(helper, "the brace does not slow the drill: %s against %s ticks a hardness", braced.ticksPerHardness(), stock.ticksPerHardness());
		}
		helper.succeed();
	}

	@GameTest
	public void theBraceHasOneTierAtItsPrice(GameTestHelper helper) {
		long price = UpgradeTuning.DEFAULT.price(ComponentTrack.BRACE, 1);
		if (price != 200 || ComponentTrack.BRACE.maxTier() != 1) {
			throw failure(helper, "the brace is one tier at $200; it is %s tiers at $%s", ComponentTrack.BRACE.maxTier(), price);
		}
		helper.succeed();
	}

	// ---- the warning ----

	@GameTest
	public void aHullTheCrustWillEndIsWarnedOfAboveTheCrust(GameTestHelper helper) {
		ServerLevel one = layer(helper, 1);
		expectWarning(helper, pod(helper, one, 10, 20f, false), 7, 3, FULL_CRUST, "for 20 hull 7 slabs above the crust");
		expectWarning(helper, pod(helper, one, 10, FULL_CRUST, false), 7, 3, FULL_CRUST, "for exactly the crust's price, which leaves no hull");
		expectNone(helper, pod(helper, one, 10, FULL_CRUST + 1, false), "for a hull that outlasts the crust by 1");
		expectNone(helper, pod(helper, one, 10, 100f, false), "for a full hull");
		expectWarning(helper, pod(helper, one, 10, 20f, true), 7, 3, FULL_CRUST, "for a braced pod, whose crust costs the same");
		helper.succeed();
	}

	@GameTest
	public void theWarningStartsWithinItsReachOfTheCrust(GameTestHelper helper) {
		ServerLevel one = layer(helper, 1);
		int reach = PodBraceTuning.DEFAULT.warnSlabs();
		int crustTop = 3;
		expectWarning(helper, pod(helper, one, crustTop + reach, 5f, false), reach, 3, FULL_CRUST, "at the reach of the warning");
		expectNone(helper, pod(helper, one, crustTop + reach + 1, 5f, false), "one slab beyond the reach");
		helper.succeed();
	}

	@GameTest
	public void inTheCrustTheWarningCountsOnlyTheRowsLeft(GameTestHelper helper) {
		ServerLevel one = layer(helper, 1);
		expectWarning(helper, pod(helper, one, 2, 10f, false), 0, 2, 2 * ROW, "2 rows from the bottom");
		expectWarning(helper, pod(helper, one, 1, ROW, false), 0, 1, ROW, "1 row from the bottom, with exactly its price");
		expectNone(helper, pod(helper, one, 1, ROW + 1, false), "1 row from the bottom, with hull to spare");
		expectNone(helper, pod(helper, one, 0, 1f, false), "with no crust left under the pod");
		helper.succeed();
	}

	@GameTest
	public void thereIsNoWarningWhereThereIsNoCrustToCross(GameTestHelper helper) {
		expectNone(helper, pod(helper, layer(helper, 2), 10, 1f, false), "in the last layer, whose crust is not drilled");
		expectNone(helper, pod(helper, helper.getLevel().getServer().overworld(), 70, 1f, false), "on the surface");
		helper.succeed();
	}

	// ---- the brace patches the hull with ore ----

	@GameTest
	public void theBraceWorksOnlyForAPodThatHasOneAndNeedsIt(GameTestHelper helper) {
		ServerLevel one = layer(helper, 1);
		PodEntity none = pod(helper, one, 10, 5f, false);
		ore(helper, none, OreType.IRONIUM);
		expectPatching(helper, none, PodBrace.Patching.IDLE, "for a pod with no brace");
		PodEntity hurt = pod(helper, one, 10, 5f, true);
		ore(helper, hurt, OreType.IRONIUM);
		expectPatching(helper, hurt, PodBrace.Patching.WORKING, "for a braced pod whose hull would not hold the crust");
		float enough = FULL_CRUST + PodBraceTuning.DEFAULT.reserveHull();
		PodEntity patched = pod(helper, one, 10, enough, true);
		ore(helper, patched, OreType.IRONIUM);
		expectPatching(helper, patched, PodBrace.Patching.IDLE, "for a hull the reserve over the crust's price");
		PodEntity short_ = pod(helper, one, 10, enough - 0.5f, true);
		ore(helper, short_, OreType.IRONIUM);
		expectPatching(helper, short_, PodBrace.Patching.WORKING, "for a hull half a point under the reserve");
		PodEntity far = pod(helper, one, 10 + PodBraceTuning.DEFAULT.warnSlabs(), 5f, true);
		ore(helper, far, OreType.IRONIUM);
		expectPatching(helper, far, PodBrace.Patching.IDLE, "beyond the reach of the crust");
		helper.succeed();
	}

	@GameTest
	public void theBraceWaitsForRestAndNeedsOreToBurn(GameTestHelper helper) {
		ServerLevel one = layer(helper, 1);
		PodEntity pod = pod(helper, one, 10, 5f, true);
		expectPatching(helper, pod, PodBrace.Patching.NO_ORE, "for an empty bay");
		ore(helper, pod, OreType.IRONIUM);
		if (PodBrace.patching(pod, false) != PodBrace.Patching.WAITS_FOR_REST) {
			throw failure(helper, "a pod that is not at rest waits, it is %s", PodBrace.patching(pod, false));
		}
		helper.succeed();
	}

	@GameTest
	public void aPatchBurnsTheCheapestOreForHullOnTheBeat(GameTestHelper helper) {
		ServerLevel one = layer(helper, 1);
		PodEntity pod = pod(helper, one, 10, 5f, true);
		ore(helper, pod, OreType.SILVERIUM, OreType.IRONIUM);
		tick(pod, PATCH + 1);
		if (pod.hull() != 5f || pod.cargoUsed() != 2) {
			throw failure(helper, "the brace patches once every %d ticks, not at tick %d: hull %s, %s ore", PATCH, PATCH + 1, pod.hull(), pod.cargoUsed());
		}
		tick(pod, PATCH);
		float hullPerOre = PodBraceTuning.DEFAULT.hullPerOre();
		if (pod.hull() != 5f + hullPerOre || pod.cargoUsed() != 1 || pod.cargo().count(OreType.IRONIUM) != 0 || pod.cargo().count(OreType.SILVERIUM) != 1) {
			throw failure(helper, "a patch burns the ironium, the cheapest ore, for %s hull: hull %s, %s ore, %s ironium, %s silverium", hullPerOre, pod.hull(), pod.cargoUsed(),
					pod.cargo().count(OreType.IRONIUM), pod.cargo().count(OreType.SILVERIUM));
		}
		tick(pod, 2 * PATCH);
		if (pod.hull() != 5f + 2 * hullPerOre || pod.cargoUsed() != 0) {
			throw failure(helper, "the second patch burns the silverium: hull %s, %s ore", pod.hull(), pod.cargoUsed());
		}
		expectPatching(helper, pod, PodBrace.Patching.IDLE, "once the hull outlasts the crust by the reserve");
		helper.succeed();
	}

	@GameTest
	public void aPodInTheAirIsNotPatched(GameTestHelper helper) {
		ServerLevel one = layer(helper, 1);
		PodEntity pod = pod(helper, one, 10, 5f, true);
		ore(helper, pod, OreType.IRONIUM);
		pod.setOnGround(false);
		tick(pod, PATCH);
		tick(pod, 2 * PATCH);
		if (pod.hull() != 5f || pod.cargoUsed() != 1) {
			throw failure(helper, "a pod in the air is not patched: hull %s, %s ore", pod.hull(), pod.cargoUsed());
		}
		helper.succeed();
	}

	@GameTest
	public void aPodThatMovedWithinTheRestTimeIsNotPatchedUntilItHasRested(GameTestHelper helper) {
		ServerLevel one = layer(helper, 1);
		PodEntity pod = pod(helper, one, 10, 5f, true);
		ore(helper, pod, OreType.IRONIUM);
		pod.setOnGround(false);
		tick(pod, 5);
		pod.setOnGround(true);
		tick(pod, PATCH);
		if (pod.hull() != 5f || pod.cargoUsed() != 1) {
			throw failure(helper, "a pod that landed %d ticks ago is not at rest: hull %s, %s ore", PATCH - 5, pod.hull(), pod.cargoUsed());
		}
		tick(pod, 2 * PATCH);
		if (pod.hull() != 5f + PodBraceTuning.DEFAULT.hullPerOre() || pod.cargoUsed() != 0) {
			throw failure(helper, "a pod that has rested is patched: hull %s, %s ore", pod.hull(), pod.cargoUsed());
		}
		helper.succeed();
	}

	@GameTest
	public void theBraceRefusesAnOreThatCostsMoreAHullThanNanobotsDo(GameTestHelper helper) {
		ServerLevel one = layer(helper, 1);
		float cap = PodBrace.oreValueCap();
		if (OreType.GOLDIUM.value() <= cap || OreType.SILVERIUM.value() > cap) {
			throw failure(helper, "the cap of %s lets silverium through and stops goldium", cap);
		}
		PodEntity dear = pod(helper, one, 10, 5f, true);
		ore(helper, dear, OreType.GOLDIUM);
		expectPatching(helper, dear, PodBrace.Patching.NO_CHEAP_ORE, "for a bay of only dear ore");
		tick(dear, PATCH);
		if (dear.hull() != 5f || dear.cargoUsed() != 1) {
			throw failure(helper, "dear ore is not burned: hull %s, %s ore", dear.hull(), dear.cargoUsed());
		}
		PodEntity mixed = pod(helper, one, 10, 5f, true);
		ore(helper, mixed, OreType.GOLDIUM, OreType.IRONIUM);
		tick(mixed, PATCH);
		tick(mixed, 2 * PATCH);
		if (mixed.cargo().count(OreType.GOLDIUM) != 1 || mixed.cargo().count(OreType.IRONIUM) != 0 || mixed.hull() != 5f + PodBraceTuning.DEFAULT.hullPerOre()) {
			throw failure(helper, "the brace burns the ironium and leaves the goldium: hull %s, %s goldium, %s ironium", mixed.hull(),
					mixed.cargo().count(OreType.GOLDIUM), mixed.cargo().count(OreType.IRONIUM));
		}
		helper.succeed();
	}

	/** A pilot who holds the drill and bores slab after slab: the pod falls into each hole, and no ore burns however many ticks pass. */
	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 700)
	public void aPilotWhoHoldsTheDrillIsNeverPatchedAcrossABoredSlab(GameTestHelper helper) {
		int x = 4100;
		int z = 3800;
		ServerLevel one = layer(helper, 1);
		RoomCarver.carve(one, x - 4, x + 4, 0, 2, z - 4, z + 4, LayerBlocks.BREACH_CRUST);
		RoomCarver.carve(one, x - 4, x + 4, 3, 9, z - 4, z + 4, Blocks.STONE);
		RoomCarver.carve(one, x - 4, x + 4, 10, 16, z - 4, z + 4, Blocks.AIR);
		MockPlayer pilot = MockPlayers.join(helper, "brace-holder");
		pilot.teleportTo(one, new Vec3(x, 10, z), 0f, 0f);
		PodEntity[] pod = {null};
		FarChunks.awaitEntityTicking(helper, one, new BlockPos(x, 10, z), () -> {
			PodEntity spawned = PodRegistry.POD.create(one, EntitySpawnReason.COMMAND);
			spawned.setPos(x, 10, z);
			one.addFreshEntity(spawned);
			if (!pilot.player().startRiding(spawned)) {
				throw failure(helper, "the pilot could not mount the pod");
			}
			ScannerPods.fit(helper.getLevel().getServer(), pilot.player(), spawned, ComponentTrack.BRACE, 1);
			spawned.setHull(5f);
			ore(helper, spawned, OreType.IRONIUM, OreType.IRONIUM, OreType.IRONIUM);
			pilot.setInput(SPRINT);
			pod[0] = spawned;
		});
		helper.onEachTick(() -> {
			if (pod[0] == null) {
				return;
			}
			if (pod[0].hull() > 5f || pod[0].cargoUsed() != 3) {
				throw failure(helper, "a pilot holding the drill is never patched: hull %s, %s ore at y %s", pod[0].hull(), pod[0].cargoUsed(), pod[0].getY());
			}
			if (pod[0].getY() < 10 - SLABS + 0.01) {
				pilot.releaseInput();
				pod[0].discard();
				helper.succeed();
			}
		});
	}

	/** The brace is one tier for every chassis, and the warning and the patch read the pod's own footprint and stats, whatever its size. */
	@GameTest
	public void theBraceFitsAndWorksOnEveryChassis(GameTestHelper helper) {
		ServerLevel one = layer(helper, 1);
		for (EntityType<PodEntity> type : List.of(PodRegistry.POD, PodRegistry.PROSPECTOR, OddPods.TYPE, OddPods.TALL_TYPE)) {
			PodEntity pod = type.create(one, EntitySpawnReason.COMMAND);
			pod.setPos(100, 10, 3800);
			ScannerPods.fit(helper.getLevel().getServer(), owner(helper).player(), pod, ComponentTrack.BRACE, 1);
			pod.setHull(5f);
			pod.setOnGround(true);
			if (PodBrace.tier(pod) != 1) {
				throw failure(helper, "the %s works the brace at tier 1, it works it at %s", pod.chassis().id(), PodBrace.tier(pod));
			}
			ore(helper, pod, OreType.IRONIUM);
			if (PodBrace.warning(pod).isEmpty()) {
				throw failure(helper, "the %s with 5 hull above the crust is warned", pod.chassis().id());
			}
			expectPatching(helper, pod, PodBrace.Patching.WORKING, "for a hurt " + pod.chassis().id());
			tick(pod, PATCH);
			if (pod.hull() != 5f + PodBraceTuning.DEFAULT.hullPerOre() || pod.cargoUsed() != 0) {
				throw failure(helper, "the %s is patched: hull %s, %s ore", pod.chassis().id(), pod.hull(), pod.cargoUsed());
			}
		}
		helper.succeed();
	}

	@GameTest
	public void aPodWithNoPowerIsNotPatched(GameTestHelper helper) {
		ServerLevel one = layer(helper, 1);
		PodEntity pod = pod(helper, one, 10, 5f, true);
		ore(helper, pod, OreType.IRONIUM);
		pod.setStranded(true);
		tick(pod, PATCH);
		if (pod.hull() != 5f || pod.cargoUsed() != 1) {
			throw failure(helper, "a stranded pod is not patched: hull %s, %s ore", pod.hull(), pod.cargoUsed());
		}
		helper.succeed();
	}
}
