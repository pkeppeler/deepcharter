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
				expectDone(helper, buy(scene, ComponentTrack.HULL, 1), "buying a tier 1 hull");
				expectEqual(helper, "account after the 750 hull", 9_250, balance(helper, scene));
				PartLabel first = PodComponents.partOf(scene.pod(), ComponentTrack.HULL).orElseThrow();
				if (first.tier() != 1 || !first.charter().equals(scene.charter())) {
					throw failure(helper, "the hull should be a tier 1 part of the charter, got %s", first);
				}
				if (!drops(helper, scene).isEmpty()) {
					throw failure(helper, "the first hull replaced nothing, but %s dropped", drops(helper, scene));
				}

				expectDone(helper, buy(scene, ComponentTrack.HULL, 2), "buying a tier 2 hull");
				expectEqual(helper, "account after the 2000 hull", 7_250, balance(helper, scene));
				PartLabel second = PodComponents.partOf(scene.pod(), ComponentTrack.HULL).orElseThrow();
				if (second.tier() != 2 || second.serial().equals(first.serial())) {
					throw failure(helper, "the hull should be a new tier 2 part, got %s after %s", second, first);
				}
				List<PartLabel> dropped = drops(helper, scene);
				if (!dropped.equals(List.of(first))) {
					throw failure(helper, "the replaced tier 1 hull should drop as it was, got %s", dropped);
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
				pod.setHull(40f);
				pod.setFuel(30f);
				expectDone(helper, buy(scene, ComponentTrack.HULL, 1), "buying a hull");
				expectEqual(helper, "hull refilled to the new maximum", 170f, pod.hull());
				expectEqual(helper, "a hull does not refill the tank", 30f, pod.fuel());

				pod.setHull(50f);
				expectDone(helper, buy(scene, ComponentTrack.FUEL_TANK, 1), "buying a tank");
				expectEqual(helper, "tank refilled", 100f, pod.fuel());
				expectEqual(helper, "a tank does not repair the hull", 50f, pod.hull());

				// The shared install keeps litres and damage: only a purchase refills.
				pod.setHull(60f);
				pod.setFuel(30f);
				PodComponents.install(pod, ComponentItems.mint(helper.getLevel().getServer(), ComponentTrack.HULL, 2, scene.charter()));
				expectEqual(helper, "a plain install keeps the damage", 60f + 130f, pod.hull());
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
				expectDone(helper, buy(scene, ComponentTrack.HULL, 1), "buying a hull for a wreck");
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
				expectEqual(helper, "the original price of a tier 4 hull", 20_000, price);
				expectDone(helper, buy(scene, ComponentTrack.HULL, 4), "buying a tier 4 hull for a Mole");
				expectEqual(helper, "the full price is charged", 30_000, balance(helper, scene));
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
			Scene scene = scene(helper, 749);
			PodEntity pod = scene.pod();
			try {
				pod.setHull(40f);
				pod.setFuel(30f);
				expectRefused(helper, TerminalRefusal.ACTION_REFUSED, buy(scene, ComponentTrack.HULL, 1), "a 750 hull for 749");
				expectEqual(helper, "the account", 749, balance(helper, scene));
				expectEqual(helper, "hull", 40f, pod.hull());
				expectEqual(helper, "fuel", 30f, pod.fuel());
				if (PodComponents.partOf(pod, ComponentTrack.HULL).isPresent() || !drops(helper, scene).isEmpty()) {
					throw failure(helper, "a refused purchase must install and drop nothing");
				}
				expectSame(helper, UpgradeTerminal.buy(helper.getLevel().getServer(), scene.player(), scene.terminal(), ComponentTrack.HULL, 1),
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
				expectDone(helper, buy(scene, ComponentTrack.DRILL, 1), "buying with both pods parked");
				if (PodComponents.partOf(foreign, ComponentTrack.DRILL).isPresent()
						|| PodComponents.partOf(scene.pod(), ComponentTrack.DRILL).isEmpty()) {
					throw failure(helper, "the part belongs in the player's own pod");
				}

				// Only the other charter's pod is parked.
				scene.pod().setPos(scene.pod().position().add(20, 0, 0));
				long before = balance(helper, scene);
				expectRefused(helper, TerminalRefusal.ACTION_REFUSED, buy(scene, ComponentTrack.ENGINE, 1), "a purchase for a pod of another charter");
				expectSame(helper, UpgradeTerminal.buy(server, scene.player(), scene.terminal(), ComponentTrack.ENGINE, 1),
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
				expectSame(helper, UpgradeTerminal.buy(server, scene.player(), scene.terminal(), ComponentTrack.ENGINE, 1),
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
				expectEqual(helper, "only the one purchase was charged", 10_000 - 5_000, balance(helper, scene));
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
				expectEqual(helper, "the account", 8_000, balance(helper, scene));
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
				expectRefused(helper, TerminalRefusal.ACTION_REFUSED, buy(bare, ComponentTrack.HULL, 1), "a purchase for an unregistered pod");
				expectSame(helper, UpgradeTerminal.buy(server, scene.player(), scene.terminal(), ComponentTrack.HULL, 1),
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
				expectDone(helper, buy(toMissing, ComponentTrack.DRILL, 1), "buying for a pod whose owner charter is gone");
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
				expectDone(helper, buy(toDormant, ComponentTrack.ENGINE, 1), "buying for a pod whose owner charter is dormant");
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
				expectDone(helper, buy(scene, ComponentTrack.CARGO_BAY, 1), "buying with two own pods parked");
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
				expectSame(helper, UpgradeTerminal.buy(helper.getLevel().getServer(), scene.player(), scene.terminal(), ComponentTrack.DRILL, 1),
						Optional.of(UpgradeRefusal.NO_POD.message()), "a pod just outside the radius");
				scene.pod().setPos(centre.x + radius - 0.1, centre.y, centre.z);
				expectDone(helper, buy(scene, ComponentTrack.DRILL, 1), "a pod just inside the radius");
				helper.succeed();
			} finally {
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
				paths.put("purchase", () -> expectRefused(helper, TerminalRefusal.ACTION_REFUSED, buy(scene, ComponentTrack.HULL, 1),
						"a purchase with unreadable serials"));
				paths.put("purchase reason", () -> expectSame(helper, UpgradeTerminal.buy(server, scene.player(), scene.terminal(), ComponentTrack.HULL, 1),
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
				expectRefused(helper, TerminalRefusal.ACTION_REFUSED, buy(scene, ComponentTrack.HULL, 1), "a purchase for a pod with unreadable parts");
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
		RepairState original = RepairState.get(helper.getLevel().getServer());
		helper.getLevel().getServer().getDataStorage().set(RepairState.TYPE, new RepairState());
		try {
			Scene scene = scene(helper, 10_000);
			try {
				expectRefused(helper, TerminalRefusal.UNREPAIRED, buy(scene, ComponentTrack.HULL, 1), "a purchase at an offline terminal");
				expectEqual(helper, "the account", 10_000, balance(helper, scene));
				helper.succeed();
			} finally {
				clean(helper, scene);
			}
		} finally {
			helper.getLevel().getServer().getDataStorage().set(RepairState.TYPE, original);
		}
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
		RepairState original = RepairState.get(server);
		RepairState fresh = new RepairState();
		for (TerminalType type : List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR, TerminalTypes.UPGRADE_TERMINAL)) {
			type.parts().forEach(part -> fresh.insert(type, part).ifPresent(refusal -> {
				throw new IllegalStateException("repairing " + type.id() + ": " + refusal);
			}));
		}
		server.getDataStorage().set(RepairState.TYPE, fresh);
		try {
			body.run();
		} finally {
			server.getDataStorage().set(RepairState.TYPE, original);
		}
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
