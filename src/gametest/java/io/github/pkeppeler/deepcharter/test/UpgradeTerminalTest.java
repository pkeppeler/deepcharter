package io.github.pkeppeler.deepcharter.test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.pod.Serials;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalRefusal;
import io.github.pkeppeler.deepcharter.terminal.TerminalTuning;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import io.github.pkeppeler.deepcharter.test.support.WorldData;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.PartLabel;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeRefusal;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTerminal;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTuning;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeView;

/**
 * Server GameTests for #73: a purchase debits the exact price, installs the part and drops the one it replaces; a new hull
 * refills the hull and a new tank the fuel; a part above the Mole's cap installs but is shown and applied as capped; a refused
 * purchase changes nothing.
 *
 * <p>Every purchase goes through {@link Terminals#act}, so the checks of the terminal framework apply. The world's repair
 * state is swapped for one with the upgrade terminal repaired for the length of a test, which does all its work in its first tick.
 */
public class UpgradeTerminalTest {
	private static final AtomicInteger CHARTERS = new AtomicInteger();
	private static final float EPSILON = 0.001f;
	private static final BlockPos TERMINAL = new BlockPos(0, 1, 0);

	/** A founded charter with a mock player standing at the terminal and a pod of the charter parked beside it. */
	private record Scene(MockPlayer mock, ServerPlayer player, CharterId charter, BlockPos terminal, PodEntity pod) {
	}

	@GameTest
	public void buyingDebitsTheExactPriceInstallsThePartAndDropsTheReplacedOne(GameTestHelper helper) {
		withRepairedTerminal(helper, () -> {
			Scene scene = scene(helper, 10_000);
			try {
				expectDone(helper, buy(scene, ComponentTrack.HULL, 2), "buying a tier 2 hull");
				expectEqual(helper, "account after the 500 hull", 9_500, balance(helper, scene));
				PartLabel first = PodComponents.partOf(scene.pod(), ComponentTrack.HULL).orElseThrow();
				if (first.tier() != 2 || !first.charter().equals(scene.charter())) {
					throw failure(helper, "the hull should be a tier 2 part of the charter, got %s", first);
				}
				if (!drops(helper, scene).isEmpty()) {
					throw failure(helper, "the first hull replaced nothing, but %s dropped", drops(helper, scene));
				}

				expectDone(helper, buy(scene, ComponentTrack.HULL, 3), "buying a tier 3 hull");
				expectEqual(helper, "account after the 1250 hull", 8_250, balance(helper, scene));
				PartLabel second = PodComponents.partOf(scene.pod(), ComponentTrack.HULL).orElseThrow();
				if (second.tier() != 3 || second.serial().equals(first.serial())) {
					throw failure(helper, "the hull should be a new tier 3 part, got %s after %s", second, first);
				}
				List<PartLabel> dropped = drops(helper, scene);
				if (!dropped.equals(List.of(first))) {
					throw failure(helper, "the replaced tier 2 hull should drop as it was, got %s", dropped);
				}
				helper.succeed();
			} finally {
				clean(helper, scene);
			}
		});
	}

	@GameTest
	public void aNewHullRefillsTheHullAndANewTankRefillsTheFuel(GameTestHelper helper) {
		withRepairedTerminal(helper, () -> {
			Scene scene = scene(helper, 10_000);
			PodEntity pod = scene.pod();
			try {
				// The shared install keeps litres and damage: only a purchase refills.
				pod.setHull(40f);
				pod.setFuel(30f);
				PodComponents.install(pod, ComponentItems.mint(helper.getLevel().getServer(), ComponentTrack.HULL, 1, scene.charter()));
				expectEqual(helper, "a plain install keeps the damage", 40f + 70f, pod.hull());
				expectEqual(helper, "a plain install keeps the fuel", 30f, pod.fuel());

				pod.setHull(40f);
				expectDone(helper, buy(scene, ComponentTrack.HULL, 2), "buying a hull");
				expectEqual(helper, "hull refilled to the new maximum", 300f, pod.hull());
				expectEqual(helper, "a hull does not refill the tank", 30f, pod.fuel());

				pod.setHull(50f);
				expectDone(helper, buy(scene, ComponentTrack.FUEL_TANK, 2), "buying a tank");
				expectEqual(helper, "tank refilled", 100f, pod.fuel());
				expectEqual(helper, "a tank does not repair the hull", 50f, pod.hull());
				helper.succeed();
			} finally {
				clean(helper, scene);
			}
		});
	}

	@GameTest
	public void aNewHullDoesNotRepairAWreck(GameTestHelper helper) {
		withRepairedTerminal(helper, () -> {
			Scene scene = scene(helper, 10_000);
			try {
				scene.pod().setHull(0f);
				expectDone(helper, buy(scene, ComponentTrack.HULL, 2), "buying a hull for a wreck");
				expectEqual(helper, "a wreck stays a wreck", 0f, scene.pod().hull());
				helper.succeed();
			} finally {
				clean(helper, scene);
			}
		});
	}

	@GameTest
	public void aPartAboveTheCapInstallsButIsShownAndAppliedAsCapped(GameTestHelper helper) {
		withRepairedTerminal(helper, () -> {
			Scene scene = scene(helper, 50_000);
			try {
				long price = UpgradeTuning.DEFAULT.price(ComponentTrack.HULL, 4);
				expectEqual(helper, "the price of a tier 4 hull", 5_000, price);
				expectDone(helper, buy(scene, ComponentTrack.HULL, 4), "buying a tier 4 hull for a Mole");
				expectEqual(helper, "the full price is charged", 45_000, balance(helper, scene));
				expectEqual(helper, "the part keeps its tier", 4, PodComponents.partOf(scene.pod(), ComponentTrack.HULL).orElseThrow().tier());
				expectEqual(helper, "the pod runs it as the cap", 2, PodComponents.effectiveTier(scene.pod(), ComponentTrack.HULL));
				expectEqual(helper, "maximum hull of a capped part", 300f, PodStats.of(scene.pod()).maxHull());

				UpgradeView.Pod shown = UpgradeTerminal.view(helper.getLevel().getServer(), scene.player(), Charters.charterOfOrThrow(helper.getLevel().getServer(), scene.player().getUUID()), scene.terminal()).pod().orElseThrow();
				expectEqual(helper, "the view shows the cap", 2, shown.cap());
				UpgradeView.Slot hull = shown.slots().stream().filter(slot -> slot.track() == ComponentTrack.HULL).findFirst().orElseThrow();
				if (hull.installed() != 4 || hull.effective() != 2) {
					throw failure(helper, "the view should show a tier 4 hull working as tier 2, got %s", hull);
				}
				helper.succeed();
			} finally {
				clean(helper, scene);
			}
		});
	}

	@GameTest
	public void anInsufficientAccountRefusesAndChangesNothing(GameTestHelper helper) {
		withRepairedTerminal(helper, () -> {
			Scene scene = scene(helper, 499);
			PodEntity pod = scene.pod();
			try {
				pod.setHull(40f);
				pod.setFuel(30f);
				expectRefused(helper, TerminalRefusal.ACTION_REFUSED, buy(scene, ComponentTrack.HULL, 2), "a 500 hull for 499");
				expectEqual(helper, "the account", 499, balance(helper, scene));
				expectEqual(helper, "hull", 40f, pod.hull());
				expectEqual(helper, "fuel", 30f, pod.fuel());
				if (PodComponents.partOf(pod, ComponentTrack.HULL).isPresent() || !drops(helper, scene).isEmpty()) {
					throw failure(helper, "a refused purchase must install and drop nothing");
				}
				expectSame(helper, UpgradeTerminal.buy(helper.getLevel().getServer(), scene.player(), scene.terminal(), ComponentTrack.HULL, 2),
						Optional.of(Charters.spend(helper.getLevel().getServer(), scene.charter(), 750).orElseThrow().message()), "the reason");
				helper.succeed();
			} finally {
				clean(helper, scene);
			}
		});
	}

	@GameTest
	public void aPodOfAnotherCharterNoPodAndAFarPodAreRefused(GameTestHelper helper) {
		withRepairedTerminal(helper, () -> {
			Scene scene = scene(helper, 10_000);
			PodEntity foreign = null;
			try {
				MinecraftServer server = helper.getLevel().getServer();
				foreign = helper.spawn(PodRegistry.POD, 1, 1, 1);
				PodComponents.register(foreign, otherCharter(helper));
				// Two pods are parked: the player's own is used, the other charter's is left alone.
				expectDone(helper, buy(scene, ComponentTrack.DRILL, 2), "buying with both pods parked");
				if (PodComponents.partOf(foreign, ComponentTrack.DRILL).isPresent()
						|| PodComponents.partOf(scene.pod(), ComponentTrack.DRILL).isEmpty()) {
					throw failure(helper, "the part belongs in the player's own pod");
				}

				// Only the other charter's pod is parked.
				scene.pod().setPos(scene.pod().position().add(20, 0, 0));
				long before = balance(helper, scene);
				expectRefused(helper, TerminalRefusal.ACTION_REFUSED, buy(scene, ComponentTrack.ENGINE, 2), "a purchase for a pod of another charter");
				expectSame(helper, UpgradeTerminal.buy(server, scene.player(), scene.terminal(), ComponentTrack.ENGINE, 2),
						Optional.of(UpgradeRefusal.NOT_YOUR_POD.message()), "the reason");
				expectEqual(helper, "the account", before, balance(helper, scene));
				if (PodComponents.partOf(foreign, ComponentTrack.ENGINE).isPresent()) {
					throw failure(helper, "a pod of another charter must not get the part");
				}
				UpgradeView view = UpgradeTerminal.view(server, scene.player(), Charters.charterOfOrThrow(server, scene.player().getUUID()), scene.terminal());
				if (view.pod().isPresent() || !view.foreignPod()) {
					throw failure(helper, "the view should say another charter's pod is parked, got %s", view);
				}

				// Nothing parked at all.
				foreign.setPos(foreign.position().add(20, 0, 0));
				expectSame(helper, UpgradeTerminal.buy(server, scene.player(), scene.terminal(), ComponentTrack.ENGINE, 2),
						Optional.of(UpgradeRefusal.NO_POD.message()), "the reason with no pod near");
				expectEqual(helper, "the account", before, balance(helper, scene));
				helper.succeed();
			} finally {
				clean(helper, scene);
				if (foreign != null) {
					foreign.discard();
				}
			}
		});
	}

	@GameTest
	public void badArgumentsAreRefusedAndChangeNothing(GameTestHelper helper) {
		withRepairedTerminal(helper, () -> {
			Scene scene = scene(helper, 10_000);
			try {
				expectRefused(helper, TerminalRefusal.ACTION_REFUSED, act(scene, new CompoundTag()), "no arguments");
				for (String track : List.of("", "warp_drive", "HULL")) {
					expectRefused(helper, TerminalRefusal.ACTION_REFUSED, act(scene, args(track, 1)), "the track '" + track + "'");
				}
				for (int tier : List.of(-1, 0, 7, Integer.MAX_VALUE)) {
					expectRefused(helper, TerminalRefusal.ACTION_REFUSED, act(scene, args("hull", tier)), "hull tier " + tier);
				}
				expectRefused(helper, TerminalRefusal.ACTION_REFUSED, act(scene, args("radiator", 6)), "radiator tier 6, whose best is 5");
				CompoundTag text = new CompoundTag();
				text.putString("track", "hull");
				text.putString("tier", "1");
				expectRefused(helper, TerminalRefusal.ACTION_REFUSED, act(scene, text), "a tier sent as text");
				expectEqual(helper, "the account", 10_000, balance(helper, scene));

				expectDone(helper, buy(scene, ComponentTrack.HULL, 3), "buying a tier 3 hull");
				expectRefused(helper, TerminalRefusal.ACTION_REFUSED, buy(scene, ComponentTrack.HULL, 3), "the same tier again");
				expectRefused(helper, TerminalRefusal.ACTION_REFUSED, buy(scene, ComponentTrack.HULL, 2), "a lower tier");
				expectEqual(helper, "only the one purchase was charged", 10_000 - 1_250, balance(helper, scene));
				helper.succeed();
			} finally {
				clean(helper, scene);
			}
		});
	}

	/** #483, #536: tier 1 of every track is fitted from a crafted unit, so the terminal does not sell it, whatever the account holds. */
	@GameTest
	public void theTierOneOfEveryTrackIsCraftedAndNotSoldAtTheTerminal(GameTestHelper helper) {
		withRepairedTerminal(helper, () -> {
			Scene scene = scene(helper, 10_000);
			try {
				for (ComponentTrack track : ComponentTrack.values()) {
					expectEqual(helper, track + ": the price table has no price for the crafted tier", 0, UpgradeTuning.DEFAULT.price(track, 1));
					expectRefused(helper, TerminalRefusal.ACTION_REFUSED, buy(scene, track, 1), "a tier 1 " + track);
					expectSame(helper, UpgradeTerminal.buy(helper.getLevel().getServer(), scene.player(), scene.terminal(), track, 1),
							Optional.of(UpgradeRefusal.CRAFTED.message()), "the reason for " + track);
					expectEqual(helper, "the account", 10_000, balance(helper, scene));
					if (PodComponents.partOf(scene.pod(), track).isPresent()) {
						throw failure(helper, "a refused %s must not be installed", track);
					}
					if (track.needsFirstTier() != (track == ComponentTrack.SCANNER)) {
						throw failure(helper, "only the scanner needs its tier 1 fitted before tier 2 is sold, %s says %s", track, track.needsFirstTier());
					}
				}
				helper.succeed();
			} finally {
				clean(helper, scene);
			}
		});
	}

	/**
	 * #536: tier 2 of every track but the scanner is sold to a pod that never fitted tier 1 (over the stock part, or over nothing), at the price it always had.
	 * Only the scanner, whose crafted tier is the first map a Mole has, needs it first.
	 */
	@GameTest
	public void tierTwoIsSoldToAPodThatNeverFittedTierOne(GameTestHelper helper) {
		withRepairedTerminal(helper, () -> {
			Scene scene = scene(helper, 100_000);
			try {
				long spent = 0;
				int tracks = 0;
				for (ComponentTrack track : ComponentTrack.values()) {
					if (track.needsFirstTier() || track.maxTier() < 2) {
						continue;
					}
					tracks++;
					expectDone(helper, buy(scene, track, 2), "buying tier 2 " + track + " with no tier 1 fitted");
					spent += UpgradeTuning.DEFAULT.price(track, 2);
					expectEqual(helper, track + " at its price", 100_000 - spent, balance(helper, scene));
					expectEqual(helper, track + " works as tier 2", 2, PodComponents.effectiveTier(scene.pod(), track));
				}
				expectEqual(helper, "the tracks with a tier 2 that needs no tier 1", 10, tracks);
				helper.succeed();
			} finally {
				clean(helper, scene);
			}
		});
	}

	/** #483: the tiers above the crafted one are purchases, and need the crafted one fitted to the pod (and working for the charter) first. */
	@GameTest
	public void aHigherScannerTierIsBoughtAfterTheCraftedOneIsFitted(GameTestHelper helper) {
		withRepairedTerminal(helper, () -> {
			Scene scene = scene(helper, 10_000);
			try {
				MinecraftServer server = helper.getLevel().getServer();
				for (int tier = 2; tier <= ComponentTrack.SCANNER.maxTier(); tier++) {
					expectSame(helper, UpgradeTerminal.buy(server, scene.player(), scene.terminal(), ComponentTrack.SCANNER, tier),
							Optional.of(UpgradeRefusal.NEEDS_FIRST_TIER.message()), "the reason for tier " + tier + " with no scanner");
				}
				expectRefused(helper, TerminalRefusal.ACTION_REFUSED, buy(scene, ComponentTrack.SCANNER, 2), "tier 2 with no scanner");
				expectEqual(helper, "nothing was charged", 10_000, balance(helper, scene));

				// A void scanner of another charter is not the first tier.
				PodComponents.install(scene.pod(), ComponentItems.mint(server, ComponentTrack.SCANNER, 1, otherCharter(helper)));
				expectRefused(helper, TerminalRefusal.ACTION_REFUSED, buy(scene, ComponentTrack.SCANNER, 2), "tier 2 over a void scanner");
				expectEqual(helper, "nothing was charged for the void scanner", 10_000, balance(helper, scene));

				PodComponents.install(scene.pod(), ComponentItems.mint(server, ComponentTrack.SCANNER, 1, scene.charter()));
				PartLabel crafted = PodComponents.partOf(scene.pod(), ComponentTrack.SCANNER).orElseThrow();
				expectDone(helper, buy(scene, ComponentTrack.SCANNER, 2), "buying tier 2 over the fitted tier 1");
				expectEqual(helper, "tier 2 is charged at its price", 10_000 - UpgradeTuning.DEFAULT.price(ComponentTrack.SCANNER, 2), balance(helper, scene));
				expectEqual(helper, "the pod scans at tier 2", 2, PodComponents.effectiveTier(scene.pod(), ComponentTrack.SCANNER));
				if (!drops(helper, scene).contains(crafted)) {
					throw failure(helper, "the tier 1 scanner it replaced should drop, got %s", drops(helper, scene));
				}
				helper.succeed();
			} finally {
				clean(helper, scene);
			}
		});
	}

	@GameTest
	public void aVoidPartOfAnotherCharterCanBeReplacedByTheSameTier(GameTestHelper helper) {
		withRepairedTerminal(helper, () -> {
			Scene scene = scene(helper, 10_000);
			try {
				MinecraftServer server = helper.getLevel().getServer();
				PodComponents.install(scene.pod(), ComponentItems.mint(server, ComponentTrack.ENGINE, 2, otherCharter(helper)));
				expectEqual(helper, "a void part does nothing", 0, PodComponents.effectiveTier(scene.pod(), ComponentTrack.ENGINE));
				expectDone(helper, buy(scene, ComponentTrack.ENGINE, 2), "buying the tier of the void part");
				expectEqual(helper, "the void part is replaced by a part that works", 2, PodComponents.effectiveTier(scene.pod(), ComponentTrack.ENGINE));
				expectEqual(helper, "the account", 9_500, balance(helper, scene));
				helper.succeed();
			} finally {
				clean(helper, scene);
			}
		});
	}

	/** An unowned pod is anyone's to park (the canMount rule) but takes no purchase: its parts would be void and a refill a repair. */
	@GameTest
	public void anUnregisteredPodIsRefusedAndTheViewHasNoSerial(GameTestHelper helper) {
		withRepairedTerminal(helper, () -> {
			Scene scene = scene(helper, 10_000);
			try {
				scene.pod().discard();
				PodEntity unowned = helper.spawn(PodRegistry.POD, 2, 1, 2);
				Scene bare = new Scene(scene.mock(), scene.player(), scene.charter(), scene.terminal(), unowned);
				MinecraftServer server = helper.getLevel().getServer();
				UpgradeView.Pod shown = UpgradeTerminal.view(server, scene.player(), Charters.charterOfOrThrow(server, scene.player().getUUID()), scene.terminal()).pod().orElseThrow();
				if (!shown.serial().isEmpty()) {
					throw failure(helper, "an unowned pod has no serial, got %s", shown.serial());
				}
				unowned.setHull(40f);
				expectRefused(helper, TerminalRefusal.ACTION_REFUSED, buy(bare, ComponentTrack.HULL, 2), "a purchase for an unregistered pod");
				expectSame(helper, UpgradeTerminal.buy(server, scene.player(), scene.terminal(), ComponentTrack.HULL, 2),
						Optional.of(UpgradeRefusal.NOT_REGISTERED.message()), "the reason");
				expectEqual(helper, "the account", 10_000, balance(helper, bare));
				expectEqual(helper, "a refused hull is no repair", 40f, unowned.hull());
				helper.succeed();
			} finally {
				clean(helper, scene);
			}
		});
	}

	@GameTest
	public void aPodOfADormantOrMissingOwnerIsAnyones(GameTestHelper helper) {
		withRepairedTerminal(helper, () -> {
			Scene scene = scene(helper, 10_000);
			PodEntity dormantPod = null;
			try {
				MinecraftServer server = helper.getLevel().getServer();
				scene.pod().discard();
				// Missing: the owner id belongs to no charter.
				PodEntity missing = helper.spawn(PodRegistry.POD, 2, 1, 2);
				PodComponents.register(missing, CharterId.random());
				Scene toMissing = new Scene(scene.mock(), scene.player(), scene.charter(), scene.terminal(), missing);
				expectDone(helper, buy(toMissing, ComponentTrack.DRILL, 2), "buying for a pod whose owner charter is gone");
				if (PodComponents.partOf(missing, ComponentTrack.DRILL).isEmpty()) {
					throw failure(helper, "the part should be in the pod of the missing owner");
				}
				missing.discard();

				// Dormant: the only member left.
				UUID founder = UUID.randomUUID();
				if (Charters.found(server, founder, "Dormant " + UUID.randomUUID().toString().substring(0, 8)).isPresent()) {
					throw failure(helper, "founding should succeed");
				}
				CharterId dormant = Charters.charterOfOrThrow(server, founder).orElseThrow().id();
				Charters.leave(server, founder);
				if (!Charters.findOrThrow(server, dormant).orElseThrow().dormant()) {
					throw failure(helper, "the charter should be dormant");
				}
				dormantPod = helper.spawn(PodRegistry.POD, 2, 1, 2);
				PodComponents.register(dormantPod, dormant);
				Scene toDormant = new Scene(scene.mock(), scene.player(), scene.charter(), scene.terminal(), dormantPod);
				expectDone(helper, buy(toDormant, ComponentTrack.ENGINE, 2), "buying for a pod whose owner charter is dormant");
				if (PodComponents.partOf(dormantPod, ComponentTrack.ENGINE).isEmpty()) {
					throw failure(helper, "the part should be in the pod of the dormant owner");
				}
				helper.succeed();
			} finally {
				clean(helper, scene);
				if (dormantPod != null) {
					dormantPod.discard();
				}
			}
		});
	}

	@GameTest
	public void theNearerOfTwoOwnPodsGetsThePart(GameTestHelper helper) {
		withRepairedTerminal(helper, () -> {
			Scene scene = scene(helper, 10_000);
			PodEntity farther = null;
			try {
				farther = helper.spawn(PodRegistry.POD, 6, 1, 2);
				PodComponents.register(farther, scene.charter());
				expectDone(helper, buy(scene, ComponentTrack.CARGO_BAY, 2), "buying with two own pods parked");
				if (PodComponents.partOf(scene.pod(), ComponentTrack.CARGO_BAY).isEmpty() || PodComponents.partOf(farther, ComponentTrack.CARGO_BAY).isPresent()) {
					throw failure(helper, "the nearer pod should get the part, and only it");
				}
				helper.succeed();
			} finally {
				clean(helper, scene);
				if (farther != null) {
					farther.discard();
				}
			}
		});
	}

	@GameTest
	public void theParkedRadiusIsHonouredAtItsEdge(GameTestHelper helper) {
		withRepairedTerminal(helper, () -> {
			Scene scene = scene(helper, 10_000);
			try {
				double radius = TerminalTuning.DEFAULT.parkedRadius();
				Vec3 centre = Vec3.atCenterOf(scene.terminal());
				scene.pod().setPos(centre.x + radius + 0.1, centre.y, centre.z);
				expectParked(helper, scene, false, "just outside");
				scene.pod().setPos(centre.x + radius - 0.1, centre.y, centre.z);
				expectParked(helper, scene, true, "just inside");
				helper.succeed();
			} finally {
				clean(helper, scene);
			}
		});
	}

	/** Checks that the scene's pod, where it is now, is or is not parked at the terminal; failures name the distance, the radius and the other pods. */
	private static void expectParked(GameTestHelper helper, Scene scene, boolean inside, String where) {
		double radius = TerminalTuning.DEFAULT.parkedRadius();
		Vec3 centre = Vec3.atCenterOf(scene.terminal());
		double distance = scene.pod().position().distanceTo(centre);
		List<PodEntity> parked = Terminals.parkedPods(helper.getLevel(), scene.terminal());
		List<String> others = helper.getLevel().getEntitiesOfClass(PodEntity.class, new AABB(scene.terminal()).inflate(radius + 1),
				pod -> pod != scene.pod()).stream().map(pod -> pod.position() + " " + pod.getUUID()).toList();
		String context = "(distance " + distance + ", radius " + radius + ", other pods within " + (radius + 1) + ": " + others + ")";
		if (parked.contains(scene.pod()) != inside) {
			throw failure(helper, "a pod %s the radius should %sbe parked, parked: %s %s", where, inside ? "" : "not ", parked, context);
		}
		if (inside) {
			expectDone(helper, buy(scene, ComponentTrack.DRILL, 2), "a pod " + where + " the radius " + context);
			return;
		}
		// Outside the radius the terminal has no pod of ours; an unowned stray inside it would be offered instead, and refused as unregistered.
		Optional<Component> refusal = UpgradeTerminal.buy(helper.getLevel().getServer(), scene.player(), scene.terminal(), ComponentTrack.DRILL, 2);
		if (refusal.isEmpty() || PodComponents.partOf(scene.pod(), ComponentTrack.DRILL).isPresent()) {
			throw failure(helper, "a pod %s the radius should be refused and get nothing, got %s %s", where, refusal, context);
		}
		if (others.isEmpty()) {
			expectSame(helper, refusal, Optional.of(UpgradeRefusal.NO_POD.message()), "a pod " + where + " the radius " + context);
		}
	}

	@GameTest
	public void theOwnPodWinsOverANearerUnownedOne(GameTestHelper helper) {
		withRepairedTerminal(helper, () -> {
			Scene scene = scene(helper, 10_000);
			PodEntity stray = helper.spawn(PodRegistry.POD, 1, 1, 0);
			try {
				Vec3 centre = Vec3.atCenterOf(scene.terminal());
				if (stray.position().distanceTo(centre) >= scene.pod().position().distanceTo(centre)) {
					throw failure(helper, "the unowned pod should be nearer: %s against %s", stray.position(), scene.pod().position());
				}
				UpgradeView view = UpgradeTerminal.view(helper.getLevel().getServer(), scene.player(),
						Charters.charterOfOrThrow(helper.getLevel().getServer(), scene.player().getUUID()), scene.terminal());
				String serial = PodComponents.registration(scene.pod()).orElseThrow().serial();
				if (!view.pod().map(UpgradeView.Pod::serial).equals(Optional.of(serial))) {
					throw failure(helper, "the view should show the own pod %s, got %s", serial, view);
				}
				expectDone(helper, buy(scene, ComponentTrack.DRILL, 2), "buying with an unowned pod parked nearer");
				if (PodComponents.partOf(scene.pod(), ComponentTrack.DRILL).isEmpty() || PodComponents.partOf(stray, ComponentTrack.DRILL).isPresent()) {
					throw failure(helper, "the part should go in the own pod only: own %s, unowned %s",
							PodComponents.partOf(scene.pod(), ComponentTrack.DRILL), PodComponents.partOf(stray, ComponentTrack.DRILL));
				}
				helper.succeed();
			} finally {
				stray.discard();
				clean(helper, scene);
			}
		});
	}

	@GameTest
	public void unreadableSerialsRefuseBeforeTheSpend(GameTestHelper helper) {
		withRepairedTerminal(helper, () -> {
			MinecraftServer server = helper.getLevel().getServer();
			Scene scene = scene(helper, 10_000);
			try {
				int carriedBefore = carried(scene.player());
				scene.pod().setHull(40f);
				Map<String, Runnable> paths = new LinkedHashMap<>();
				paths.put("purchase", () -> expectRefused(helper, TerminalRefusal.ACTION_REFUSED, buy(scene, ComponentTrack.HULL, 2),
						"a purchase with unreadable serials"));
				paths.put("purchase reason", () -> expectSame(helper, UpgradeTerminal.buy(server, scene.player(), scene.terminal(), ComponentTrack.HULL, 2),
						Optional.of(UpgradeRefusal.SERIALS_UNREADABLE.message()), "the reason"));
				UnreadableChecks.assertSavedDataNoThrow(helper, "serials", server, Serials.TYPE, paths);
				expectEqual(helper, "the account", 10_000, balance(helper, scene));
				expectEqual(helper, "the hull", 40f, scene.pod().hull());
				if (PodComponents.partOf(scene.pod(), ComponentTrack.HULL).isPresent() || !drops(helper, scene).isEmpty()
						|| carried(scene.player()) != carriedBefore) {
					throw failure(helper, "a refused purchase must install, drop and give nothing: part %s, drops %s, carried %s of %s",
							PodComponents.partOf(scene.pod(), ComponentTrack.HULL), drops(helper, scene), carried(scene.player()), carriedBefore);
				}
				helper.succeed();
			} finally {
				clean(helper, scene);
			}
		});
	}

	@GameTest
	public void anUnreadablePodIsRefusedAndNotThrownOn(GameTestHelper helper) {
		withRepairedTerminal(helper, () -> {
			Scene scene = scene(helper, 10_000);
			try {
				scene.pod().setAttached(PodComponents.STATE, new Versioned.Unreadable<>(new CompoundTag()));
				expectRefused(helper, TerminalRefusal.ACTION_REFUSED, buy(scene, ComponentTrack.HULL, 2), "a purchase for a pod with unreadable parts");
				expectEqual(helper, "the account", 10_000, balance(helper, scene));
				// The view is built on a request path: it must not throw either.
				UpgradeTerminal.view(helper.getLevel().getServer(), scene.player(), Charters.charterOfOrThrow(helper.getLevel().getServer(), scene.player().getUUID()), scene.terminal());
				helper.succeed();
			} finally {
				clean(helper, scene);
			}
		});
	}

	@GameTest
	public void aTerminalThatIsNotRepairedSellsNothing(GameTestHelper helper) {
		WorldData.with(helper.getLevel().getServer(), RepairState.TYPE, new RepairState(), () -> {
			Scene scene = scene(helper, 10_000);
			try {
				expectRefused(helper, TerminalRefusal.UNREPAIRED, buy(scene, ComponentTrack.HULL, 2), "a purchase at an offline terminal");
				expectEqual(helper, "the account", 10_000, balance(helper, scene));
				helper.succeed();
			} finally {
				clean(helper, scene);
			}
		});
	}

	@GameTest
	public void theViewSurvivesTheWire(GameTestHelper helper) {
		UpgradeView view = new UpgradeView(Optional.of(new UpgradeView.Pod("MOLE-0001", 2,
				List.of(new UpgradeView.Slot(ComponentTrack.HULL, 4, 2), new UpgradeView.Slot(ComponentTrack.LIGHTS, 0, 0)))), false);
		ByteBuf buffer = Unpooled.buffer();
		UpgradeView.STREAM_CODEC.encode(buffer, view);
		UpgradeView decoded = UpgradeView.STREAM_CODEC.decode(buffer);
		if (!decoded.equals(view) || buffer.isReadable()) {
			throw failure(helper, "the view should round-trip, got %s from %s", decoded, view);
		}
		helper.succeed();
	}

	private static void withRepairedTerminal(GameTestHelper helper, Runnable body) {
		MinecraftServer server = helper.getLevel().getServer();
		RepairState fresh = new RepairState();
		for (TerminalType type : List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR, TerminalTypes.UPGRADE_TERMINAL)) {
			type.parts().forEach(part -> fresh.insert(type, part).ifPresent(refusal -> {
				throw new IllegalStateException("repairing " + type.id() + ": " + refusal);
			}));
		}
		WorldData.with(server, RepairState.TYPE, fresh, body);
	}

	private static Scene scene(GameTestHelper helper, long account) {
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayer mock = MockPlayers.join(helper, "Upgrader" + CHARTERS.incrementAndGet());
		ServerPlayer player = mock.player();
		player.setGameMode(GameType.SURVIVAL);
		if (Charters.found(server, player.getUUID(), "Upgrade " + UUID.randomUUID().toString().substring(0, 8)).isPresent()) {
			throw helper.assertionException("founding a charter should succeed");
		}
		CharterId charter = Charters.charterOfOrThrow(server, player.getUUID()).orElseThrow().id();
		if (Charters.deposit(server, charter, account).isPresent()) {
			throw helper.assertionException("funding the account should succeed");
		}
		helper.setBlock(TERMINAL, TerminalTypes.UPGRADE_TERMINAL.block().defaultBlockState());
		BlockPos terminal = helper.absolutePos(TERMINAL);
		Vec3 centre = Vec3.atCenterOf(terminal);
		mock.teleportTo(helper.getLevel(), new Vec3(centre.x + 2, centre.y - player.getEyeHeight(), centre.z), 0, 0);
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 1, 2);
		PodComponents.register(pod, charter);
		return new Scene(mock, player, charter, terminal, pod);
	}

	private static CharterId otherCharter(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		UUID founder = UUID.randomUUID();
		if (Charters.found(server, founder, "Other " + UUID.randomUUID().toString().substring(0, 8)).isPresent()) {
			throw helper.assertionException("founding the other charter should succeed");
		}
		return Charters.charterOfOrThrow(server, founder).orElseThrow().id();
	}

	private static void clean(GameTestHelper helper, Scene scene) {
		scene.pod().discard();
		helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(Vec3.atCenterOf(scene.terminal()), Vec3.atCenterOf(scene.terminal())).inflate(60))
				.forEach(ItemEntity::discard);
		scene.mock().leave();
	}

	private static Optional<TerminalRefusal> buy(Scene scene, ComponentTrack track, int tier) {
		return act(scene, args(track.id(), tier));
	}

	private static Optional<TerminalRefusal> act(Scene scene, CompoundTag args) {
		return Terminals.act(scene.player(), scene.terminal(), UpgradeTerminal.BUY, args);
	}

	private static CompoundTag args(String track, int tier) {
		CompoundTag args = new CompoundTag();
		args.putString(UpgradeTerminal.TRACK_KEY, track);
		args.putInt(UpgradeTerminal.TIER_KEY, tier);
		return args;
	}

	/** How many items the player carries. */
	private static int carried(ServerPlayer player) {
		int total = 0;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			total += player.getInventory().getItem(slot).getCount();
		}
		return total;
	}

	private static long balance(GameTestHelper helper, Scene scene) {
		return Charters.findOrThrow(helper.getLevel().getServer(), scene.charter()).orElseThrow().account();
	}

	/** The labels of the part items lying around the terminal. */
	private static List<PartLabel> drops(GameTestHelper helper, Scene scene) {
		return helper.getLevel().getEntitiesOfClass(ItemEntity.class,
				new AABB(Vec3.atCenterOf(scene.terminal()), Vec3.atCenterOf(scene.terminal())).inflate(60))
				.stream().flatMap(item -> ComponentItems.labelOf(item.getItem()).stream()).toList();
	}

	private static void expectDone(GameTestHelper helper, Optional<TerminalRefusal> refusal, String what) {
		if (refusal.isPresent()) {
			throw failure(helper, "%s should succeed, was refused: %s", what, refusal.get());
		}
	}

	private static void expectRefused(GameTestHelper helper, TerminalRefusal expected, Optional<TerminalRefusal> actual, String what) {
		if (!actual.equals(Optional.of(expected))) {
			throw failure(helper, "%s should be refused with %s, got %s", what, expected, actual);
		}
	}

	private static void expectEqual(GameTestHelper helper, String what, long expected, long actual) {
		if (expected != actual) {
			throw failure(helper, "%s: expected %s, got %s", what, expected, actual);
		}
	}

	private static void expectEqual(GameTestHelper helper, String what, float expected, float actual) {
		if (Math.abs(expected - actual) > EPSILON) {
			throw failure(helper, "%s: expected %s, got %s", what, expected, actual);
		}
	}

	private static void expectSame(GameTestHelper helper, Object actual, Object expected, String what) {
		if (!expected.equals(actual)) {
			throw failure(helper, "%s: expected %s, got %s", what, expected, actual);
		}
	}

	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(format, args);
	}
}
